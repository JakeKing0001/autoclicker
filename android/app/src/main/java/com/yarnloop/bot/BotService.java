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
    private long tapDelay = 700, idleDelay = 600;
    private boolean dryRun;
    private int frame, taps;

    private final Runnable loop = this::step;

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
        status.setMaxLines(3);
        status.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.62));
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
        lp.x = 0;
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
        tapDelay = p.getInt("tap_delay", 700);
        dryRun = p.getBoolean("dry_run", false);
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
        Strategy.Decision d = strategy.decide(st);
        frame++;
        String text = frame + ": " + d.reason
                + "\nesposti: " + fmt(st.exposed)
                + "\nnastro: " + fmt(st.belt) + " · slot liberi " + st.freeSlots() + " · tocchi " + taps;
        main.post(() -> {
            status.setText(text);
            if (!running) return;
            if (d.spool != null) {
                if (!dryRun) {
                    tap(d.spool.x, d.spool.y);
                    strategy.remember(d.spool);
                    taps++;
                }
                main.postDelayed(loop, tapDelay);
            } else {
                main.postDelayed(loop, idleDelay);
            }
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
