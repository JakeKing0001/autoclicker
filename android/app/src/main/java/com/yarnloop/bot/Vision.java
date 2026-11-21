package com.yarnloop.bot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Analisi di uno screenshot di Yarn Loop (stessa logica della versione Python). */
public final class Vision {

    // ------------------------------------------------------------------ colori
    public static final String[] NAMES = {
            "nero", "bianco", "sfondo", "crema", "distributore", "rosso", "rosa", "pelle", "arancio",
            "marrone", "giallo", "lime", "verde", "ciano", "azzurro", "blu", "viola", "magenta", "misterioso"};
    public static final int BLACK = 0, WHITE = 1, BG = 2, CREAM = 3, GEN = 4, RED = 5, PINK = 6, SKIN = 7,
            ORANGE = 8, BROWN = 9, YELLOW = 10, LIME = 11, GREEN = 12, CYAN = 13, LIGHTBLUE = 14,
            BLUE = 15, PURPLE = 16, MAGENTA = 17,
            MYSTERY = 18; // rocchetto col "?" (colore sconosciuto): non e' un colore di pixel
    public static final int N = NAMES.length;

    static boolean ignored(int c) {
        return c == BG || c == CREAM;
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
        if (s < 0.12f && v >= 0.55f) return WHITE;   // lana bianca, anche in ombra
        if (s < 0.33f && v > 0.70f) return CREAM;    // cornici e riflessi
        if (s < 0.22f) return BG;
        if (h >= 200 && h < 260 && s >= 0.22f && s < 0.5f && v < 0.78f) return GEN;
        if ((h < 12 || h >= 345) && s >= 0.75f) return RED;
        if ((h < 20 || h >= 345) && s < 0.75f) return PINK;
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
        public final int index; // posizione nello slot, oppure colonna
        public int count = -1;  // numero scritto sul rocchetto (lana che manca), -1 = non letto

        Spool(int color, int x, int y, boolean slot, int index) {
            this.color = color;
            this.x = x;
            this.y = y;
            this.slot = slot;
            this.index = index;
        }
    }

    public static final class State {
        public final int[] exposed = new int[N];
        public final int[] remaining = new int[N];
        public final int[] belt = new int[N];
        public final List<Spool> slots = new ArrayList<>(); // null = slot vuoto
        public final Map<Integer, Integer> slotLinks = new HashMap<>();
        /** Griglia: cells[colonna][riga] = colore, -1 = vuoto (o riga nascosta). */
        public int[][] cells = new int[0][0];
        public int[] colX = new int[0];
        public int[] rowY = new int[0];     // comprese due righe nascoste sotto la barra
        public int visibleRows;
        /** Collegamenti: chiave colonna*100+riga -> compagno. */
        public final Map<Integer, Integer> links = new HashMap<>();
        /** Numeri letti in prima fila e sui distributori: colonna*100+riga -> numero. */
        public final Map<Integer, Integer> counts = new HashMap<>();
        /** Posti liberi sul nastro (contatore "N/5"), -1 = non letto = non siamo nel livello. */
        public int beltFree = -1;
        public int beltCap = -1;
        /** Rocchetti sul nastro: {colore, lana che manca} (numeri letti accanto a loro). */
        public final List<int[]> beltSpools = new ArrayList<>();

        public int freeSlots() {
            int n = 0;
            for (Spool s : slots) if (s == null) n++;
            return n;
        }

        public int cell(int col, int row) {
            if (col < 0 || col >= cells.length || row < 0 || row >= visibleRows) return -1;
            return cells[col][row];
        }

        public int front(int col) {
            for (int r = 0; r < visibleRows; r++) if (cells[col][r] >= 0) return r;
            return -1;
        }

        public boolean levelVisible() {
            int tot = 0;
            for (int v : remaining) tot += v;
            if (tot > 0) return true;
            for (int c = 0; c < cells.length; c++) if (front(c) >= 0) return true;
            for (Spool s : slots) if (s != null) return true;
            return false;
        }
    }

    // ------------------------------------------------------------------ layout
    /** Zone dello schermo in frazioni (misurate su 1080x2340). */
    static final float[] PICTURE = r(170, 385, 910, 1250);
    static final float CELL = 32f / 1080f;
    static final int MIN_PART_CELLS = 5;
    static final float[] SLOTS = r(110, 1505, 975, 1650);
    static final float[] SLOT_X = {190f / 1080, 365f / 1080, 540f / 1080, 715f / 1080, 890f / 1080};
    static final float[] GRID = r(100, 1680, 980, 2110);
    static final float GRID_CENTER_X = 0.5f;
    static final float COL_PITCH = 175f / 1080f;
    static final float FIRST_CAP_Y = 1735f / 2340f;
    static final float SPOOL_W = 125f / 1080f;
    static final float SPOOL_PITCH = 187f / 2340f;
    static final float[] BELT = r(105, 290, 975, 1330);
    static final float[] COUNTER = r(40, 1270, 185, 1390);
    static final float DIGIT_H = 0.034f;
    static final float[][] BELT_IGNORE = {r(30, 1140, 185, 1395), r(185, 1250, 290, 1400)};

    private static float[] r(int x0, int y0, int x1, int y1) {
        return new float[]{x0 / 1080f, y0 / 2340f, x1 / 1080f, y1 / 2340f};
    }

    // ------------------------------------------------------------------ immagine
    private final int w, h;
    private final byte[] cls;
    private final int[] px;

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
        cnt[CREAM] = 0;
        if (ignoreBlack) cnt[BLACK] = 0;
        int best = 0;
        for (int i = 1; i < N; i++) if (cnt[i] > cnt[best]) best = i;
        if (total == 0 || cnt[best] < minFrac * total) return -1;
        return best;
    }

    /** Frazione di pixel colorati (non sfondo, crema o nero). */
    private float colored(int cx, int cy, int r) {
        int n = 0, f = 0;
        for (int y = Math.max(0, cy - r); y < Math.min(h, cy + r + 1); y++)
            for (int x = Math.max(0, cx - r); x < Math.min(w, cx + r + 1); x++) {
                n++;
                int v = c(x, y);
                if (!ignored(v) && v != BLACK) f++;
            }
        return n == 0 ? 0 : f / (float) n;
    }

    public State analyze() {
        State st = new State();
        picture(st);
        slots(st);
        grid(st);
        links(st);
        belt(st);
        numbers(st);
        return st;
    }

    // ------------------------------------------------------------------ numeri
    private void numbers(State st) {
        float dh = DIGIT_H * w;
        int[] C = rect(COUNTER);
        String txt = Digits.read(px, w, h, C[0], C[1], C[2], C[3], dh);
        String[] parts = txt.split("/", -1);
        st.beltFree = parts[0].matches("\\d{1,2}") ? Integer.parseInt(parts[0]) : -1;
        st.beltCap = parts.length > 1 && parts[1].matches("\\d{1,2}") ? Integer.parseInt(parts[1]) : -1;
        if (st.beltFree >= 0) beltLabels(st);
        int[] S = rect(SLOTS);
        int hw = (int) (SPOOL_W * w / 2);
        for (Spool s : st.slots)
            if (s != null)
                s.count = Digits.readInt(px, w, h, s.x - hw, (int) (S[1] - 0.017f * h), s.x + hw, (int) (S[1] + 0.034f * h), dh);
        int halfH = (int) (0.27f * SPOOL_PITCH * h);
        for (int c = 0; c < st.cells.length; c++)
            for (int r = 0; r < st.visibleRows; r++) {
                int v = st.cells[c][r];
                if (v < 0 || (v != GEN && st.front(c) != r)) continue;
                int n = Digits.readInt(px, w, h, st.colX[c] - hw, st.rowY[r] - halfH, st.colX[c] + hw, st.rowY[r] + halfH, dh);
                if (n > 0 || (n == 0 && v == GEN)) st.counts.put(c * 100 + r, n); // in griglia mai 0
            }
    }

    // ------------------------------------------------------------------ disegno
    private void picture(State st) {
        int[] R = rect(PICTURE);
        final int x0 = R[0], y0 = R[1], W = R[2] - R[0], H = R[3] - R[1];
        final int cell = Math.max(4, (int) (CELL * w));
        final int probe = Math.max(2, (int) (cell * 0.45));
        final int step = Math.max(2, cell / 4);

        boolean[] fg = new boolean[W * H];
        for (int yy = 0; yy < H; yy++)
            for (int xx = 0; xx < W; xx++) fg[yy * W + xx] = !ignored(c(x0 + xx, y0 + yy));
        dropSmallParts(fg, W, H, cell);

        // colori ancora presenti
        Map<Long, int[]> cells = new HashMap<>();
        for (int yy = 0; yy < H; yy += step)
            for (int xx = 0; xx < W; xx += step) {
                if (!fg[yy * W + xx]) continue;
                int v = c(x0 + xx, y0 + yy);
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
                if (fg[yy * W + xx]) {
                    if (first < 0) first = xx;
                    last = xx;
                }
            if (first < 0) continue;
            if (first > 0) sample(exp, fg, x0, y0, W, H, cell, yy, first + probe);
            if (last < W - 1) sample(exp, fg, x0, y0, W, H, cell, yy, last - probe);
        }
        for (int xx = 0; xx < W; xx += step) {
            int first = -1, last = -1;
            for (int yy = 0; yy < H; yy++)
                if (fg[yy * W + xx]) {
                    if (first < 0) first = yy;
                    last = yy;
                }
            if (first < 0) continue;
            if (first > 0) sample(exp, fg, x0, y0, W, H, cell, first + probe, xx);
            if (last < H - 1) sample(exp, fg, x0, y0, W, H, cell, last - probe, xx);
        }
        for (int[] cnt : exp.values()) st.exposed[argmax(cnt)]++;
    }

    /** Toglie i pezzetti staccati dal disegno (es. i numeri bianchi dei rocchetti sul nastro). */
    private static void dropSmallParts(boolean[] fg, int W, int H, int cell) {
        int gw = (W + cell - 1) / cell, gh = (H + cell - 1) / cell;
        boolean[] occ = new boolean[gw * gh];
        for (int gy = 0; gy < gh; gy++)
            for (int gx = 0; gx < gw; gx++) {
                int n = 0;
                for (int y = gy * cell; y < Math.min(H, (gy + 1) * cell); y++)
                    for (int x = gx * cell; x < Math.min(W, (gx + 1) * cell); x++) if (fg[y * W + x]) n++;
                occ[gy * gw + gx] = n > 0.3 * cell * cell;
            }
        boolean[] keep = new boolean[gw * gh], seen = new boolean[gw * gh];
        int[] stack = new int[gw * gh];
        List<Integer> comp = new ArrayList<>();
        for (int i = 0; i < gw * gh; i++) {
            if (!occ[i] || seen[i]) continue;
            comp.clear();
            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;
            while (sp > 0) {
                int k = stack[--sp];
                comp.add(k);
                int ky = k / gw, kx = k % gw;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int ny = ky + dy, nx = kx + dx;
                        if (ny < 0 || ny >= gh || nx < 0 || nx >= gw) continue;
                        int j = ny * gw + nx;
                        if (occ[j] && !seen[j]) {
                            seen[j] = true;
                            stack[sp++] = j;
                        }
                    }
            }
            if (comp.size() >= MIN_PART_CELLS) for (int k : comp) keep[k] = true;
        }
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                if (!keep[(y / cell) * gw + x / cell]) fg[y * W + x] = false;
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

    private void sample(Map<Long, int[]> exp, boolean[] fg, int x0, int y0, int W, int H, int cell, int py, int px) {
        if (py < 0 || py >= H || px < 0 || px >= W) return;
        // scarta le cose sottili (il filo che va dal blocco al rocchetto)
        int q = Math.max(2, (int) (cell * 0.4)), n = 0, f = 0;
        for (int y = Math.max(0, py - q); y < Math.min(H, py + q + 1); y++)
            for (int x = Math.max(0, px - q); x < Math.min(W, px + q + 1); x++) {
                n++;
                if (fg[y * W + x]) f++;
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
            st.slots.add(col < 0 ? null : new Spool(col, cx, (R[1] + R[3]) / 2, true, st.slots.size()));
        }
        // sbarretta tra due slot vicini = rocchetti collegati
        int yb = R[1] + (R[3] - R[1]) / 2, need = Math.max(3, (int) (0.006 * h));
        for (int i = 0; i + 1 < st.slots.size(); i++) {
            Spool a = st.slots.get(i), b = st.slots.get(i + 1);
            if (a == null || b == null) continue;
            int xm = (a.x + b.x) / 2, full = 0;
            for (int y = R[1]; y < yb; y++) {
                int f = 0;
                for (int x = xm - 4; x < xm + 5; x++) if (!ignored(c(x, y))) f++;
                if (f > 0.8 * 9) full++;
            }
            if (full >= need) {
                st.slotLinks.put(i, i + 1);
                st.slotLinks.put(i + 1, i);
            }
        }
    }

    // ------------------------------------------------------------------ griglia
    /** Griglia su una maglia fissa: colonne centrate, sfalsate di mezzo passo se sono pari. */
    private void grid(State st) {
        int[] G = rect(GRID);
        int gy0 = G[1], gy1 = G[3];
        float pitchX = COL_PITCH * w, pitchY = SPOOL_PITCH * h, cap0 = FIRST_CAP_Y * h;
        int half = (int) (SPOOL_W * w * 0.33);
        int vis = 0;
        while (vis < 8 && cap0 + vis * pitchY - 0.25 * pitchY < gy1) vis++;

        int bestFilled = -1;
        int[] bestX = null;
        int[][] bestCells = null;
        for (float offset : new float[]{0f, 0.5f}) {
            List<Integer> xs = new ArrayList<>();
            for (int k = -4; k <= 4; k++) {
                float x = GRID_CENTER_X * w + (k + offset) * pitchX;
                if (x > pitchX * 0.4 && x < w - pitchX * 0.4) xs.add((int) x);
            }
            int[][] cells = new int[xs.size()][vis];
            int filled = 0;
            for (int ci = 0; ci < xs.size(); ci++)
                for (int ri = 0; ri < vis; ri++) {
                    float y = cap0 + ri * pitchY;
                    int ya, yb;
                    if (y + 0.5 * pitchY < gy1) {       // corpo del rocchetto (niente numeri)
                        ya = (int) (y + 0.25 * pitchY);
                        yb = (int) (y + 0.55 * pitchY);
                    } else {                             // ultima riga: cima del tappo
                        ya = (int) (y - 0.24 * pitchY);
                        yb = (int) (y - 0.12 * pitchY);
                    }
                    ya = Math.max(gy0, ya);
                    yb = Math.min(gy1, yb);
                    int x = xs.get(ci);
                    cells[ci][ri] = yb - ya < 4 ? -1 : dominant(x - half, ya, x + half, yb, 0.3f, false);
                    if (cells[ci][ri] == BLACK && isMystery(x, y, half, pitchY, gy0, gy1)) cells[ci][ri] = MYSTERY;
                    if (ri < 2 && cells[ci][ri] >= 0) filled++;
                }
            if (filled > bestFilled) {
                bestFilled = filled;
                bestX = new int[xs.size()];
                for (int i = 0; i < bestX.length; i++) bestX[i] = xs.get(i);
                bestCells = cells;
            }
        }
        st.colX = bestX;
        st.visibleRows = vis;
        st.rowY = new int[vis + 2];
        for (int i = 0; i < st.rowY.length; i++) st.rowY[i] = (int) (cap0 + i * pitchY);
        // tieni solo la parte continua di ogni colonna (dal primo rocchetto in giu')
        for (int[] col : bestCells) {
            boolean started = false, ended = false;
            for (int r = 0; r < col.length; r++) {
                if (ended) col[r] = -1;
                else if (col[r] >= 0) started = true;
                else if (started) ended = true;
            }
        }
        st.cells = bestCells;
    }

    /** Rocchetto misterioso: nero con un "?" colorato sul tappo (i neri veri hanno numeri bianchi). */
    private boolean isMystery(int x, float y, int half, float pitchY, int gy0, int gy1) {
        int ya = Math.max(gy0, (int) (y - 0.2f * pitchY)), yb = Math.min(gy1, (int) (y + 0.15f * pitchY));
        if (yb - ya < 4) return false;
        int n = 0, f = 0;
        for (int yy = ya; yy < yb; yy++)
            for (int xx = Math.max(0, x - half); xx < Math.min(w, x + half); xx++) {
                n++;
                int v = c(xx, yy);
                if (v != BLACK && v != BG && v != CREAM && v != WHITE && v != GEN) f++;
            }
        return n > 0 && f > 0.03f * n;
    }

    /** Aste che collegano due rocchetti di colonne vicine (anche con uno nascosto sotto la barra). */
    private void links(State st) {
        float pitchY = SPOOL_PITCH * h;
        int r = Math.max(3, (int) (0.005 * w));
        int gy1 = rect(GRID)[3];
        int nRows = st.rowY.length;
        List<float[]> cand = new ArrayList<>(); // {visibili, punti, minimo, a, b, x1,y1,x2,y2,x3,y3}
        for (int c = 0; c + 1 < st.colX.length; c++)
            for (int ra = 0; ra < nRows; ra++)
                for (int rb = Math.max(0, ra - 3); rb < Math.min(nRows, ra + 4); rb++) {
                    boolean va = st.cell(c, ra) >= 0, vb = st.cell(c + 1, rb) >= 0;
                    if (!va && !vb) continue;
                    if ((!va && ra < st.visibleRows) || (!vb && rb < st.visibleRows)) continue;
                    float ax = st.colX[c], ay = st.rowY[ra] + 0.3f * pitchY;
                    float bx = st.colX[c + 1], by = st.rowY[rb] + 0.3f * pitchY;
                    float min = 1f;
                    int n = 0;
                    float[] pts = new float[6];
                    for (float t : new float[]{0.4f, 0.5f, 0.6f}) {
                        int x = (int) (ax + (bx - ax) * t), y = (int) (ay + (by - ay) * t);
                        if (y + r >= gy1) continue;
                        min = Math.min(min, colored(x, y, r));
                        pts[2 * n] = x;
                        pts[2 * n + 1] = y;
                        n++;
                    }
                    if (n >= 2 && min > 0.75f)
                        cand.add(new float[]{(va ? 1 : 0) + (vb ? 1 : 0), n, min, c * 100 + ra, (c + 1) * 100 + rb,
                                n, pts[0], pts[1], pts[2], pts[3], pts[4], pts[5]});
                }
        cand.sort((p, q) -> {
            for (int i = 0; i < 3; i++) if (p[i] != q[i]) return Float.compare(q[i], p[i]);
            return 0;
        });
        List<float[]> used = new ArrayList<>();
        float near = 0.025f * w;
        for (float[] k : cand) {
            int a = (int) k[3], b = (int) k[4], n = (int) k[5];
            if (st.links.containsKey(a) || st.links.containsKey(b)) continue;
            boolean overlap = false;
            for (int i = 0; i < n && !overlap; i++)
                for (float[] u : used)
                    if (Math.abs(k[6 + 2 * i] - u[0]) < near && Math.abs(k[7 + 2 * i] - u[1]) < near) {
                        overlap = true;
                        break;
                    }
            if (overlap) continue;
            st.links.put(a, b);
            st.links.put(b, a);
            for (int i = 0; i < n; i++) used.add(new float[]{k[6 + 2 * i], k[7 + 2 * i]});
        }
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
        List<int[]> pts = new ArrayList<>();
        for (int i = 0; i + 1 < poly.length; i++) {
            int ax = poly[i][0], ay = poly[i][1], bx = poly[i + 1][0], by = poly[i + 1][1];
            int n = Math.max(1, (int) (Math.hypot(bx - ax, by - ay) / step));
            for (int k = 0; k < n; k++) {
                int x = ax + (bx - ax) * k / n, y = ay + (by - ay) * k / n;
                pts.add(new int[]{x, y});
                boolean skip = false;
                for (int[] g : ign) if (g[0] <= x && x <= g[2] && g[1] <= y && y <= g[3]) skip = true;
                seq.add(skip ? -1 : dominant(x - r, y - r, x + r + 1, y + r + 1, 0.35f, true));
            }
        }
        int rotK = 0;
        if (!seq.isEmpty() && seq.get(0) >= 0) {
            int k = seq.indexOf(-1);
            if (k > 0) {
                List<Integer> rot = new ArrayList<>(seq.subList(k, seq.size()));
                rot.addAll(seq.subList(0, k));
                seq = rot;
                rotK = k;
            }
        }
        int nPts = pts.size();
        int minLen = Math.max(3, (int) (0.06 * w) / step);
        int maxGap = Math.max(1, (int) (0.04 * w) / step);
        for (int i = 0; i <= maxGap; i++) seq.add(-1);
        int cur = -1, n = 0, gap = 0, start = 0;
        for (int i = 0; i < seq.size(); i++) {
            int s = seq.get(i);
            if (s >= 0 && s == cur) {
                n++;
                gap = 0;
                continue;
            }
            if (cur >= 0 && gap < maxGap && s < 0) {
                gap++;
                continue;
            }
            if (cur >= 0 && n >= minLen) {
                st.belt[cur]++;
                int mid = (start + i - gap) / 2;
                int[] p = pts.get((mid + rotK) % nPts);
                beltPos.add(new int[]{cur, p[0], p[1]});
            }
            cur = s;
            n = s >= 0 ? 1 : 0;
            gap = 0;
            start = i;
        }
    }

    private final List<int[]> beltPos = new ArrayList<>(); // {colore, x, y} dei rocchetti visti sul nastro

    /** Numeri accanto ai rocchetti sul nastro; quelli senza rocchetto colorato vicino sono neri. */
    private void beltLabels(State st) {
        int[] B = rect(BELT);
        int pad = (int) (0.12f * w), inner = (int) (0.13f * w);
        int X0 = Math.max(0, B[0] - pad), Y0 = Math.max(0, B[1] - (int) (0.03f * w));
        int X1 = Math.min(w, B[2] + pad), Y1 = Math.min(h, B[3] + pad);
        int[][] skip = {{B[0] + inner, B[1] + inner, B[2] - inner, B[3] - inner}, rect(COUNTER)};
        List<int[]> labels = Digits.find(px, w, h, X0, Y0, X1, Y1, DIGIT_H * w, skip);
        List<float[]> pairs = new ArrayList<>();
        for (int li = 0; li < labels.size(); li++)
            for (int si = 0; si < beltPos.size(); si++) {
                float d = (float) Math.hypot(labels.get(li)[0] - beltPos.get(si)[1], labels.get(li)[1] - beltPos.get(si)[2]);
                if (d <= 0.17f * w) pairs.add(new float[]{d, li, si});
            }
        pairs.sort((a, b) -> Float.compare(a[0], b[0]));
        boolean[] usedL = new boolean[labels.size()], usedS = new boolean[beltPos.size()];
        for (float[] p : pairs) {
            int li = (int) p[1], si = (int) p[2];
            if (usedL[li] || usedS[si]) continue;
            usedL[li] = usedS[si] = true;
            st.beltSpools.add(new int[]{beltPos.get(si)[0], labels.get(li)[2]});
        }
        for (int li = 0; li < labels.size(); li++)
            if (!usedL[li]) st.beltSpools.add(new int[]{BLACK, labels.get(li)[2]});
    }
}
