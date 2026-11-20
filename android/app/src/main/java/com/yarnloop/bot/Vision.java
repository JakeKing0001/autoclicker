package com.yarnloop.bot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Analisi di uno screenshot di Yarn Loop (stessa logica della versione Python). */
public final class Vision {

    // ------------------------------------------------------------------ colori
    public static final String[] NAMES = {
            "nero", "bianco", "sfondo", "rosso", "rosa", "pelle", "arancio", "marrone",
            "giallo", "lime", "verde", "ciano", "azzurro", "blu", "viola", "magenta"};
    public static final int BLACK = 0, WHITE = 1, BG = 2, RED = 3, PINK = 4, SKIN = 5, ORANGE = 6,
            BROWN = 7, YELLOW = 8, LIME = 9, GREEN = 10, CYAN = 11, LIGHTBLUE = 12, BLUE = 13,
            PURPLE = 14, MAGENTA = 15;
    public static final int N = NAMES.length;

    static boolean ignored(int c) {
        return c == BG || c == WHITE;
    }

    static int classify(int argb) {
        int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        float v = mx / 255f;
        int d = mx - mn;
        float s = mx > 0 ? d / (float) mx : 0f;
        float h = 0f;
        if (d > 0) {
            if (mx == r) {
                h = (g - b) / (float) d;
                if (h < 0) h += 6f;
            } else if (mx == g) {
                h = (b - r) / (float) d + 2f;
            } else {
                h = (r - g) / (float) d + 4f;
            }
            h *= 60f;
        }
        if (v < 0.20f || (v < 0.30f && s < 0.07f)) return BLACK;
        if (s < 0.22f && v > 0.85f) return WHITE;
        if (s < 0.33f && v > 0.70f) return WHITE; // crema delle cornici
        if (s < 0.22f) return BG;
        if ((h < 12 || h >= 345) && s >= 0.75f) return RED;
        if ((h < 20 || h >= 330) && s < 0.75f) return PINK;
        if (h >= 12 && h < 42) {
            if (v < 0.60f) return BROWN;
            if (s >= 0.80f) return ORANGE;
            return SKIN;
        }
        if (h < 70) return YELLOW;
        if (h < 95) return LIME;
        if (h < 165) return GREEN;
        if (h < 195) return CYAN;
        if (h < 250) return s < 0.55f ? LIGHTBLUE : BLUE;
        if (h < 290) return PURPLE;
        if (h < 345) return MAGENTA;
        return BG;
    }

    // ------------------------------------------------------------------ dati
    public static final class Spool {
        public final int color, x, y;
        public final boolean slot;
        public final List<Integer> behind = new ArrayList<>();

        Spool(int color, int x, int y, boolean slot) {
            this.color = color;
            this.x = x;
            this.y = y;
            this.slot = slot;
        }
    }

    public static final class State {
        public final int[] exposed = new int[N];
        public final int[] remaining = new int[N];
        public final int[] belt = new int[N];
        public final List<Spool> slots = new ArrayList<>(); // null = slot vuoto
        public final List<Spool> grid = new ArrayList<>();

        public int freeSlots() {
            int n = 0;
            for (Spool s : slots) if (s == null) n++;
            return n;
        }

        public boolean levelVisible() {
            int tot = 0;
            for (int v : remaining) tot += v;
            if (tot > 0 || !grid.isEmpty()) return true;
            for (Spool s : slots) if (s != null) return true;
            return false;
        }
    }

    // ------------------------------------------------------------------ layout
    /** Zone dello schermo in frazioni (misurate su 1080x2340). */
    static final float[] PICTURE = r(170, 385, 910, 1250);
    static final float CELL = 32f / 1080f;
    static final float[] SLOTS = r(110, 1505, 975, 1650);
    static final float[] SLOT_X = {190f / 1080, 365f / 1080, 540f / 1080, 715f / 1080, 890f / 1080};
    static final float[] GRID = r(100, 1680, 980, 2110);
    static final float SPOOL_W = 125f / 1080f;
    static final float SPOOL_PITCH = 190f / 2340f;
    static final float[] BELT = r(105, 290, 975, 1330);
    static final float[][] BELT_IGNORE = {r(30, 1140, 185, 1395), r(185, 1250, 290, 1400)};

    private static float[] r(int x0, int y0, int x1, int y1) {
        return new float[]{x0 / 1080f, y0 / 2340f, x1 / 1080f, y1 / 2340f};
    }

    // ------------------------------------------------------------------ immagine
    private final int w, h;
    private final int[] px;
    private final byte[] cls;

    public Vision(int[] pixels, int width, int height) {
        this.px = pixels;
        this.w = width;
        this.h = height;
        this.cls = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) cls[i] = (byte) classify(pixels[i]);
    }

    private int c(int x, int y) {
        return cls[y * w + x];
    }

    private boolean fg(int x, int y) {
        return !ignored(cls[y * w + x]);
    }

    private int[] rect(float[] f) {
        return new int[]{(int) (f[0] * w), (int) (f[1] * h), (int) (f[2] * w), (int) (f[3] * h)};
    }

    /** Colore dominante nel rettangolo [x0,x1) x [y0,y1), o -1. */
    private int dominant(int x0, int y0, int x1, int y1, float minFrac, boolean ignoreBlack) {
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(w, x1);
        y1 = Math.min(h, y1);
        int[] cnt = new int[N];
        int total = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                cnt[cls[y * w + x]]++;
                total++;
            }
        cnt[BG] = 0;
        cnt[WHITE] = 0;
        if (ignoreBlack) cnt[BLACK] = 0;
        int best = 0;
        for (int i = 1; i < N; i++) if (cnt[i] > cnt[best]) best = i;
        if (total == 0 || cnt[best] < minFrac * total) return -1;
        return best;
    }

    public State analyze() {
        State st = new State();
        picture(st);
        slots(st);
        grid(st);
        belt(st);
        return st;
    }

    // ------------------------------------------------------------------ disegno
    private void picture(State st) {
        int[] R = rect(PICTURE);
        final int x0 = R[0], y0 = R[1], W = R[2] - R[0], H = R[3] - R[1];
        final int cell = Math.max(4, (int) (CELL * w));
        final int probe = Math.max(2, (int) (cell * 0.45));
        final int step = Math.max(2, cell / 4);

        // colori ancora presenti
        Map<Long, int[]> cells = new HashMap<>();
        for (int yy = 0; yy < H; yy += step)
            for (int xx = 0; xx < W; xx += step) {
                int v = c(x0 + xx, y0 + yy);
                if (ignored(v)) continue;
                if (v == BLACK && !isBlackBlock(x0, y0, W, H, xx, yy, probe)) continue;
                long key = ((long) (yy / cell) << 32) | (xx / cell);
                int[] cnt = cells.get(key);
                if (cnt == null) cells.put(key, cnt = new int[N]);
                cnt[v]++;
            }
        for (int[] cnt : cells.values()) st.remaining[argmax(cnt)]++;

        // blocchi esposti (il primo da ogni lato)
        Map<Long, int[]> exp = new HashMap<>();
        for (int yy = 0; yy < H; yy += step) {
            int first = -1, last = -1;
            for (int xx = 0; xx < W; xx++)
                if (fg(x0 + xx, y0 + yy)) {
                    if (first < 0) first = xx;
                    last = xx;
                }
            if (first < 0) continue;
            if (first > 0) sample(exp, x0, y0, W, H, cell, yy, first + probe);
            if (last < W - 1) sample(exp, x0, y0, W, H, cell, yy, last - probe);
        }
        for (int xx = 0; xx < W; xx += step) {
            int first = -1, last = -1;
            for (int yy = 0; yy < H; yy++)
                if (fg(x0 + xx, y0 + yy)) {
                    if (first < 0) first = yy;
                    last = yy;
                }
            if (first < 0) continue;
            if (first > 0) sample(exp, x0, y0, W, H, cell, first + probe, xx);
            if (last < H - 1) sample(exp, x0, y0, W, H, cell, last - probe, xx);
        }
        for (int[] cnt : exp.values()) st.exposed[argmax(cnt)]++;
    }

    private boolean isBlackBlock(int x0, int y0, int W, int H, int xx, int yy, int probe) {
        int a = probe / 2, n = 0, b = 0;
        for (int y = Math.max(0, yy - a); y < Math.min(H, yy + a + 1); y++)
            for (int x = Math.max(0, xx - a); x < Math.min(W, xx + a + 1); x++) {
                n++;
                if (c(x0 + x, y0 + y) == BLACK) b++;
            }
        return n > 0 && b > 0.7 * n;
    }

    private void sample(Map<Long, int[]> exp, int x0, int y0, int W, int H, int cell, int py, int px) {
        if (py < 0 || py >= H || px < 0 || px >= W) return;
        // scarta le cose sottili (il filo che va dal blocco al rocchetto)
        int q = Math.max(2, (int) (cell * 0.4)), n = 0, f = 0;
        for (int y = Math.max(0, py - q); y < Math.min(H, py + q + 1); y++)
            for (int x = Math.max(0, px - q); x < Math.min(W, px + q + 1); x++) {
                n++;
                if (fg(x0 + x, y0 + y)) f++;
            }
        if (f < 0.85 * n) return;
        int r = Math.max(2, cell / 5);
        int name = dominant(x0 + px - r, y0 + py - r, x0 + px + r + 1, y0 + py + r + 1, 0.3f, false);
        if (name < 0) return;
        long key = ((long) (py / cell) << 32) | (px / cell);
        int[] cnt = exp.get(key);
        if (cnt == null) exp.put(key, cnt = new int[N]);
        cnt[name]++;
    }

    private static int argmax(int[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[best]) best = i;
        return best;
    }

    // ------------------------------------------------------------------ slot
    private void slots(State st) {
        int[] R = rect(SLOTS);
        int half = (int) (SPOOL_W * w / 2 * 0.85);
        for (float fx : SLOT_X) {
            int cx = (int) (fx * w);
            int col = dominant(cx - half, R[1], cx + half, R[3], 0.25f, false);
            st.slots.add(col < 0 ? null : new Spool(col, cx, (R[1] + R[3]) / 2, true));
        }
    }

    // ------------------------------------------------------------------ griglia
    private void grid(State st) {
        int[] R = rect(GRID);
        int x0 = R[0], y0 = R[1], W = R[2] - R[0], H = R[3] - R[1];
        int sw = (int) (SPOOL_W * w);
        int pitch = (int) (SPOOL_PITCH * h);

        boolean[] colMask = new boolean[W];
        for (int xx = 0; xx < W; xx++) {
            int f = 0;
            for (int yy = 0; yy < H; yy++) if (fg(x0 + xx, y0 + yy)) f++;
            colMask[xx] = f > 0.12 * H;
        }
        for (int[] run : runs(colMask, sw / 2)) {
            int cx = (run[0] + run[1]) / 2;
            int sx0 = Math.max(0, cx - sw / 4), sx1 = Math.min(W, cx + sw / 4);
            int top = -1;
            for (int yy = 0; yy < H && top < 0; yy++) {
                int f = 0;
                for (int xx = sx0; xx < sx1; xx++) if (fg(x0 + xx, y0 + yy)) f++;
                if (f > 0.5 * (sx1 - sx0)) top = yy;
            }
            if (top < 0) continue;
            List<Integer> stack = new ArrayList<>();
            for (int y = top; y + pitch * 0.5 < H; y += pitch) {
                int py0 = y0 + y, py1 = y0 + Math.min(H, y + (int) (pitch * 0.85));
                int col = dominant(x0 + cx - sw / 2 + 8, py0, x0 + cx + sw / 2 - 8, py1, 0.2f, false);
                if (col < 0) break;
                stack.add(col);
            }
            if (stack.isEmpty()) continue;
            Spool s = new Spool(stack.get(0), x0 + cx, y0 + top + (int) (pitch * 0.35), false);
            s.behind.addAll(stack.subList(1, stack.size()));
            st.grid.add(s);
        }
    }

    private static List<int[]> runs(boolean[] m, int minLen) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= m.length; i++) {
            boolean v = i < m.length && m[i];
            if (v && start < 0) start = i;
            else if (!v && start >= 0) {
                if (i - start >= minLen) out.add(new int[]{start, i});
                start = -1;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ nastro
    private void belt(State st) {
        int[] R = rect(BELT);
        int x0 = R[0], y0 = R[1], x1 = R[2], y1 = R[3];
        int[][] ign = new int[BELT_IGNORE.length][];
        for (int i = 0; i < ign.length; i++) ign[i] = rect(BELT_IGNORE[i]);
        int r = Math.max(3, (int) (0.012 * w));
        int step = Math.max(3, r);
        int cc = (int) (0.08 * w);
        int[][] poly = {{x0 + cc, y0}, {x1 - cc, y0}, {x1, y0 + cc}, {x1, y1 - cc},
                {x1 - cc, y1}, {x0 + cc, y1}, {x0, y1 - cc}, {x0, y0 + cc}, {x0 + cc, y0}};
        List<Integer> seq = new ArrayList<>();
        for (int i = 0; i + 1 < poly.length; i++) {
            int ax = poly[i][0], ay = poly[i][1], bx = poly[i + 1][0], by = poly[i + 1][1];
            int n = Math.max(1, (int) (Math.hypot(bx - ax, by - ay) / step));
            for (int k = 0; k < n; k++) {
                int x = ax + (bx - ax) * k / n, y = ay + (by - ay) * k / n;
                boolean skip = false;
                for (int[] g : ign) if (g[0] <= x && x <= g[2] && g[1] <= y && y <= g[3]) skip = true;
                seq.add(skip ? -1 : dominant(x - r, y - r, x + r + 1, y + r + 1, 0.35f, true));
            }
        }
        // non iniziare a meta' di un rocchetto
        if (!seq.isEmpty() && seq.get(0) >= 0) {
            int k = seq.indexOf(-1);
            if (k > 0) {
                List<Integer> rot = new ArrayList<>(seq.subList(k, seq.size()));
                rot.addAll(seq.subList(0, k));
                seq = rot;
            }
        }
        int minLen = Math.max(3, (int) (0.06 * w) / step);
        int maxGap = Math.max(1, (int) (0.04 * w) / step);
        for (int i = 0; i <= maxGap; i++) seq.add(-1);
        int cur = -1, n = 0, gap = 0;
        for (int s : seq) {
            if (s >= 0 && s == cur) {
                n++;
                gap = 0;
                continue;
            }
            if (cur >= 0 && gap < maxGap && s < 0) {
                gap++;
                continue;
            }
            if (cur >= 0 && n >= minLen) st.belt[cur]++;
            cur = s;
            n = s >= 0 ? 1 : 0;
            gap = 0;
        }
    }
}
