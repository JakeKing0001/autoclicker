package com.yarnloop.bot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Servizio di accessibilita': fa gli screenshot, decide la mossa e tocca lo schermo. */
public class BotService extends AccessibilityService {

    static volatile BotService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private WindowManager wm;
    private View overlay;
    private WindowManager.LayoutParams lp;
    private TextView button, status;

    private volatile boolean running;
    private Strategy strategy;
    private String gamePackage;
    private long afterBurst = 500, tapGap = 120, idleDelay = 400;
    private boolean dryRun, autoButtons = true;
    private String lastScreen;
    private long lastScreenTap;
    private int levelStreak;
    private final Guide.Tracker tracker = new Guide.Tracker();
    private boolean followGuide = true;
    private final java.util.Map<Integer, int[]> guides = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Integer> loading = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile String guideStatus = "";
    private final java.util.Map<Integer, Long> guideTried = new java.util.concurrent.ConcurrentHashMap<>();

    /** Priorita' dei colori dalla guida del livello; la pagina si scarica in un thread a parte. */
    private int[] guideFor(int level) {
        if (!followGuide) return new int[Vision.N];
        if (level <= 0) {
            guideStatus = "livello sconosciuto";
            return new int[Vision.N];
        }
        int[] g = guides.get(level);
        if (g != null) return g;
        long now = android.os.SystemClock.uptimeMillis();
        Long last = guideTried.get(level);
        if (loading.add(level)) {
            if (last != null && now - last < 60000) {
                loading.remove(level); // dopo un errore si riprova tra un minuto
            } else {
                guideTried.put(level, now);
                guideStatus = "scarico la guida...";
                SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
                new Thread(() -> {
                    Guide.Result r = Guide.load(p, level, true);
                    if (r.found()) guides.put(level, r.p);
                    guideStatus = r.status;
                    loading.remove(level);
                }).start();
            }
        }
        return new int[Vision.N];
    }

    // ------------------------------------------------------------------ ciclo di vita
    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        stop();
    }

    @Override
    public void onDestroy() {
        stop();
        if (overlay != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignored) {
            }
            overlay = null;
        }
        instance = null;
        worker.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ pulsante galleggiante
    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void createOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC000000);
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);
        box.setPadding(dp(4), dp(2), dp(8), dp(2));

        TextView handle = new TextView(this);
        handle.setText("✥");
        handle.setTextColor(Color.LTGRAY);
        handle.setTextSize(20);
        handle.setPadding(dp(6), dp(4), dp(6), dp(4));
        box.addView(handle);

        button = new TextView(this);
        button.setText("▶");
        button.setTextColor(0xFF66FF66);
        button.setTextSize(26);
        button.setPadding(dp(8), 0, dp(10), 0);
        button.setOnClickListener(v -> toggle());
        button.setOnLongClickListener(v -> {
            saveScreenshot();
            return true;
        });
        box.addView(button);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(10);
        status.setMaxLines(4);
        status.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.55));
        status.setText("Tocca ▶ per avviare\nTieni premuto ▶ = salva screenshot");
        box.addView(status);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // a destra della scritta "Livello N" (serve leggerla per sapere il livello)
        lp.x = (int) (getResources().getDisplayMetrics().widthPixels * 0.29);
        lp.y = 0;

        handle.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        ox = lp.x;
                        oy = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = ox + (int) (e.getRawX() - sx);
                        lp.y = oy + (int) (e.getRawY() - sy);
                        wm.updateViewLayout(overlay, lp);
                        return true;
                }
                return false;
            }
        });

        overlay = box;
        wm.addView(overlay, lp);
    }

    private void setStatus(String s) {
        main.post(() -> {
            if (status != null) status.setText(s);
        });
    }

    // ------------------------------------------------------------------ avvio / stop
    private void toggle() {
        if (running) stop();
        else start();
    }

    private void start() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        strategy = new Strategy();
        strategy.maxOnBelt = p.getInt("max_belt", 5);
        afterBurst = p.getInt("after_burst_ms", 500);
        dryRun = p.getBoolean("dry_run", false);
        autoButtons = p.getBoolean("auto_buttons", true);
        followGuide = p.getBoolean("follow_guide", true);
        int startLevel = p.getInt("level", 0);
        if (startLevel > 0) tracker.level = startLevel;
        gamePackage = currentPackage();
        frame = 0;
        taps = 0;
        running = true;
        button.setText("■");
        button.setTextColor(0xFFFF5555);
        setStatus(dryRun ? "Avviato (solo prova, non tocca)" : "Avviato");
        main.post(loop);
    }

    private void stop() {
        running = false;
        main.removeCallbacks(loop);
        if (button != null) {
            button.setText("▶");
            button.setTextColor(0xFF66FF66);
        }
        if (frame > 0) setStatus("Fermato. Tocchi fatti: " + taps);
    }

    private String currentPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) return root.getPackageName().toString();
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ un passo del bot
    private void step() {
        if (!running) return;
        String pkg = currentPackage();
        if (gamePackage != null && pkg != null && !pkg.equals(gamePackage) && !pkg.equals(getPackageName())) {
            setStatus("In pausa: il gioco non è in primo piano");
            main.postDelayed(loop, 1000);
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                try {
                    analyzeAndAct(toBitmap(result));
                } catch (Throwable t) {
                    setStatus("Errore: " + t);
                    if (running) main.postDelayed(loop, 1000);
                }
            }

            @Override
            public void onFailure(int errorCode) {
                if (running) main.postDelayed(loop, 400);
            }
        });
    }

    private static Bitmap toBitmap(ScreenshotResult result) {
        HardwareBuffer hb = result.getHardwareBuffer();
        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle();
        hb.close();
        return sw;
    }

    private void analyzeAndAct(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        bmp.recycle();

        Vision.State st = new Vision(px, w, h).analyze();
        // fuori dal livello: che schermata e'? (serve anche a leggere il numero del livello)
        Screens.Result scr = st.beltFree < 0 ? Screens.classify(px, w, h) : null;
        boolean fresh = st.beltFree >= 0 && st.beltFree == (st.beltCap > 0 ? st.beltCap : 5)
                && st.freeSlots() == st.slots.size();
        tracker.update(st.beltFree >= 0, fresh, scr != null ? scr.name : null,
                Screens.levelNumber(px, w, h, scr, st.beltFree >= 0));
        strategy.guide = guideFor(tracker.level);
        Strategy.Decision d0 = strategy.decide(st);
        frame++;
        // il livello deve vedersi in due screenshot di fila e non subito dopo un pulsante
        // (dopo Riprova si vede per un attimo la tavola vecchia, poi parte la pubblicita')
        levelStreak = st.beltFree >= 0 ? levelStreak + 1 : 0;
        boolean settling = levelStreak < 2 || android.os.SystemClock.uptimeMillis() - lastScreenTap < 4000;
        Strategy.Decision d = (!d0.moves.isEmpty() && settling)
                ? new Strategy.Decision(new java.util.ArrayList<>(), "aspetto che il livello sia pronto") : d0;
        String guideTxt = Guide.describe(strategy.guide);
        String text = frame + ": " + d.reason
                + "\nliv. " + (tracker.level > 0 ? tracker.level : "?")
                + (followGuide ? " · guida: " + (guideTxt.equals("-") ? guideStatus : guideTxt) : "")
                + "\nesposti: " + fmt(st.exposed)
                + "\nposti sul nastro " + (st.beltFree >= 0 ? st.beltFree : "?") + " · slot liberi "
                + st.freeSlots() + " · tocchi " + taps;
        // Riprova / Continua / X / "Livello N": solo se attivato e se la schermata e' stabile
        final Screens.Result scrTap = autoButtons ? scr : null;
        main.post(() -> {
            status.setText(scrTap != null ? frame + ": " + scrTap.name : text);
            if (!running) return;
            if (d.moves.isEmpty()) {
                long t = android.os.SystemClock.uptimeMillis();
                if (scrTap != null && scrTap.name.equals(lastScreen) && t - lastScreenTap > 2500) {
                    if (!dryRun) tap(scrTap.x, scrTap.y);
                    lastScreenTap = t;
                    lastScreen = null;
                } else {
                    lastScreen = scrTap != null ? scrTap.name : null;
                }
                main.postDelayed(loop, idleDelay);
                return;
            }
            // raffica: tutti i tocchi uno dopo l'altro, poi un nuovo screenshot
            for (int i = 0; i < d.moves.size(); i++) {
                Strategy.Move m = d.moves.get(i);
                main.postDelayed(() -> {
                    if (!running || dryRun) return;
                    tap(m.spool.x, m.spool.y);
                    strategy.remember(m);
                    taps++;
                }, i * tapGap);
            }
            main.postDelayed(loop, d.moves.size() * tapGap + afterBurst);
        });
    }

    private static String fmt(int[] counts) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < counts.length; c++)
            if (counts[c] > 0) sb.append(sb.length() > 0 ? ", " : "").append(Vision.NAMES[c]).append('=').append(counts[c]);
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private void tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        dispatchGesture(g, null, null);
    }

    // ------------------------------------------------------------------ screenshot per il debug
    private void saveScreenshot() {
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                Bitmap bmp = toBitmap(result);
                String msg;
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, "yarnloop_" + System.currentTimeMillis() + ".png");
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YarnLoopBot");
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    msg = "Screenshot salvato in Immagini/YarnLoopBot";
                } catch (Exception e) {
                    msg = "Impossibile salvare: " + e.getMessage();
                }
                bmp.recycle();
                String m = msg;
                main.post(() -> Toast.makeText(BotService.this, m, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onFailure(int errorCode) {
                main.post(() -> Toast.makeText(BotService.this, "Screenshot non riuscito, riprova", Toast.LENGTH_SHORT).show());
            }
        });
    }
}
