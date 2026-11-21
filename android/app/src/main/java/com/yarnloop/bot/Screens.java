package com.yarnloop.bot;

/**
 * Schermate fuori dal livello (come yarnloop_bot/screens.py). Tocca solo pulsanti sicuri:
 * <ul>
 * <li>pannello chiaro con un pulsante verde in basso e nessuna offerta (Riprova, Continua);</li>
 * <li>X rossa di chiusura in alto a destra (rinuncia a "Vuoi davvero procedere?", chiude le offerte);</li>
 * <li>Home: pulsante verde "Livello N".</li>
 * </ul>
 * Mai offerte a pagamento, "Continua a giocare" con le monete, pubblicita', Google Play.
 */
final class Screens {

    static final class Result {
        final String name;
        final int x, y;

        Result(String name, int x, int y) {
            this.name = name;
            this.x = x;
            this.y = y;
        }
    }

    private static float[] r(int x0, int y0, int x1, int y1) {
        return new float[]{x0 / 1080f, y0 / 2340f, x1 / 1080f, y1 / 2340f};
    }

    private static final float[] RESULT_BUTTON = r(380, 1340, 700, 1420);
    private static final float[] RESULT_PANEL = r(150, 650, 930, 1120);
    private static final float[] OFFER_BOX = r(100, 1550, 980, 2050);
    private static final float[] CLOSE_AREA = r(900, 400, 1070, 620);
    private static final float[] HOME_BUTTON = r(350, 1900, 730, 2000);
    private static final float[] HOME_NAV = r(0, 2230, 1080, 2330);

    private static float frac(int[] px, int w, int h, float[] rc, int... classes) {
        int x0 = (int) (rc[0] * w), y0 = (int) (rc[1] * h), x1 = (int) (rc[2] * w), y1 = (int) (rc[3] * h);
        int n = 0, f = 0;
        for (int y = y0; y < y1; y += 2)
            for (int x = x0; x < x1; x += 2) {
                n++;
                int c = Vision.classify(px[y * w + x]);
                for (int k : classes)
                    if (c == k) {
                        f++;
                        break;
                    }
            }
        return n == 0 ? 0 : f / (float) n;
    }

    private static boolean white(int argb) {
        int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
        int mn = Math.min(r, Math.min(g, b)), mx = Math.max(r, Math.max(g, b));
        return mn >= 200 && mx - mn < 45;
    }

    /** Una X: bianco lungo le due diagonali e niente bianco sugli assi (a differenza di "%" o scritte). */
    private static boolean isX(int[] px, int w, int h, int mx, int my, int bw) {
        int d1 = 0, d2 = 0, ax = 0;
        for (int k = -2; k <= 2; k++) {
            float t = k / 10f;
            if (whiteAt(px, w, h, mx + t * bw, my + t * bw)) d1++;
            if (whiteAt(px, w, h, mx + t * bw, my - t * bw)) d2++;
        }
        float o = 0.22f * bw;
        if (whiteAt(px, w, h, mx + o, my)) ax++;
        if (whiteAt(px, w, h, mx - o, my)) ax++;
        if (whiteAt(px, w, h, mx, my + o)) ax++;
        if (whiteAt(px, w, h, mx, my - o)) ax++;
        return d1 / 5f >= 0.6f && d2 / 5f >= 0.6f && ax / 4f <= 0.25f;
    }

    private static boolean whiteAt(int[] px, int w, int h, float x, float y) {
        int xi = (int) x, yi = (int) y;
        return xi >= 0 && yi >= 0 && xi < w && yi < h && white(px[yi * w + xi]);
    }

    static Result classify(int[] px, int w, int h) {
        float green = frac(px, w, h, RESULT_BUTTON, Vision.GREEN, Vision.LIME);
        float panel = frac(px, w, h, RESULT_PANEL, Vision.CREAM, Vision.WHITE);
        float offer = frac(px, w, h, OFFER_BOX, Vision.ORANGE, Vision.YELLOW);
        if (green >= 0.5f && panel >= 0.6f && offer < 0.05f)
            return new Result("pulsante Riprova/Continua", (int) (540f / 1080 * w), (int) (1380f / 2340 * h));

        // Home prima di tutto: le sue icone rosse in alto a destra (es. "%") sono offerte, non X
        if (frac(px, w, h, HOME_BUTTON, Vision.GREEN, Vision.LIME) >= 0.6f
                && frac(px, w, h, HOME_NAV, Vision.BLUE, Vision.LIGHTBLUE, Vision.PURPLE) >= 0.5f)
            return new Result("Home: avvio il livello", (int) (540f / 1080 * w), (int) (1950f / 2340 * h));

        // X rossa: cerchio rosso compatto con una X bianca in mezzo
        int x0 = (int) (CLOSE_AREA[0] * w), y0 = (int) (CLOSE_AREA[1] * h);
        int W = (int) (CLOSE_AREA[2] * w) - x0, H = (int) (CLOSE_AREA[3] * h) - y0;
        boolean[] red = new boolean[W * H], seen = new boolean[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) red[y * W + x] = Vision.classify(px[(y0 + y) * w + x0 + x]) == Vision.RED;
        int[] q = new int[W * H];
        int minPx = (int) Math.pow(0.02 * w, 2);
        for (int i = 0; i < red.length; i++) {
            if (!red[i] || seen[i]) continue;
            int qh = 0, qt = 0, bx0 = W, by0 = H, bx1 = -1, by1 = -1;
            q[qt++] = i;
            seen[i] = true;
            while (qh < qt) {
                int k = q[qh++], ky = k / W, kx = k % W;
                bx0 = Math.min(bx0, kx);
                bx1 = Math.max(bx1, kx);
                by0 = Math.min(by0, ky);
                by1 = Math.max(by1, ky);
                if (ky > 0 && red[k - W] && !seen[k - W]) { seen[k - W] = true; q[qt++] = k - W; }
                if (ky < H - 1 && red[k + W] && !seen[k + W]) { seen[k + W] = true; q[qt++] = k + W; }
                if (kx > 0 && red[k - 1] && !seen[k - 1]) { seen[k - 1] = true; q[qt++] = k - 1; }
                if (kx < W - 1 && red[k + 1] && !seen[k + 1]) { seen[k + 1] = true; q[qt++] = k + 1; }
            }
            if (qt < minPx) continue;
            int bw = bx1 - bx0 + 1, bh = by1 - by0 + 1;
            if (bw < 0.05f * w || bw > 0.12f * w || bw / (float) bh < 0.75f || bw / (float) bh > 1.33f) continue;
            int mx = (bx0 + bx1 + 1) / 2, my = (by0 + by1 + 1) / 2;
            if (isX(px, w, h, x0 + mx, y0 + my, bw)) return new Result("chiudo (X)", x0 + mx, y0 + my);
        }

        return null;
    }

    private static final float[] LEVEL_LABEL = r(0, 110, 320, 260);      // "Livello N" in alto, nel livello
    private static final float[] LEVEL_HOME = r(330, 1880, 760, 2030);   // pulsante "Livello N" della Home
    private static final float[] LEVEL_PANEL = r(200, 420, 880, 570);    // titolo del pannello Hai perso

    /** Numero del livello, letto solo dove e' affidabile (Home, pannello Hai perso), o -1. */
    static int levelNumber(int[] px, int w, int h, Result scr, boolean inLevel) {
        float[] rc = inLevel ? LEVEL_LABEL : scr == null ? null
                : scr.name.startsWith("Home") ? LEVEL_HOME : scr.name.startsWith("pulsante") ? LEVEL_PANEL : null;
        if (rc == null) return -1;
        return Digits.readLevel(px, w, h, (int) (rc[0] * w), (int) (rc[1] * h), (int) (rc[2] * w), (int) (rc[3] * h));
    }
}
