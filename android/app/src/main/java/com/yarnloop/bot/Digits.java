package com.yarnloop.bot;

import java.util.ArrayList;
import java.util.List;

/** Lettura dei numeri del gioco (cifre bianche col bordo nero), come yarnloop_bot/digits.py. */
final class Digits {
    static final int GW = 10, GH = 14;
    private static float[][] templates;

    private static synchronized float[][] templates() {
        if (templates == null) {
            templates = new float[DigitTemplates.DATA.length][];
            for (int i = 0; i < templates.length; i++) {
                String s = DigitTemplates.DATA[i];
                float[] v = new float[GW * GH];
                for (int k = 0; k < v.length; k++) v[k] = (s.charAt(k) - '0') / 9f;
                templates[i] = v;
            }
        }
        return templates;
    }

    private static boolean white(int argb) {
        int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
        int mn = Math.min(r, Math.min(g, b)), mx = Math.max(r, Math.max(g, b));
        return mn >= 200 && mx - mn < 45;
    }

    /** Legge i caratteri bianchi alti circa digitH pixel nel rettangolo, da sinistra a destra. */
    static String read(int[] px, int w, int h, int x0, int y0, int x1, int y1, float digitH) {
        List<float[]> found = scan(px, w, h, x0, y0, x1, y1, digitH, 0.22f, null);
        if (found.isEmpty()) return "";
        found.sort((a, b) -> Float.compare(a[0], b[0]));
        float[] ys = new float[found.size()];
        for (int i = 0; i < ys.length; i++) ys[i] = found.get(i)[2];
        java.util.Arrays.sort(ys);
        float ymid = ys.length % 2 == 1 ? ys[ys.length / 2] : (ys[ys.length / 2 - 1] + ys[ys.length / 2]) / 2f;
        StringBuilder sb = new StringBuilder();
        for (float[] f : found)
            if (Math.abs(f[2] - ymid) < 0.5f * digitH) sb.append(DigitTemplates.CHARS[(int) f[3]]);
        return sb.toString();
    }

    /**
     * Tutti i numeri bianchi nel rettangolo (cifre vicine sulla stessa riga unite):
     * lista di {x centro, y centro, valore}. skip = rettangolo da ignorare {x0,y0,x1,y1} o null.
     */
    static List<int[]> find(int[] px, int w, int h, int x0, int y0, int x1, int y1, float digitH, int[][] skip) {
        List<float[]> found = scan(px, w, h, x0, y0, x1, y1, digitH, 0.2f, skip);
        found.sort((a, b) -> Float.compare(a[0], b[0]));
        List<float[]> groups = new ArrayList<>(); // {x0, x1, y, valore, cifre}
        for (float[] f : found) {
            String ch = DigitTemplates.CHARS[(int) f[3]];
            if (!Character.isDigit(ch.charAt(0))) continue;
            int d = ch.charAt(0) - '0';
            boolean joined = false;
            for (float[] g : groups)
                if (Math.abs(f[2] - g[2]) < 0.4f * digitH && f[0] - g[1] >= -0.1f * digitH
                        && f[0] - g[1] < 0.5f * digitH && g[4] < 2) {
                    g[1] = f[1];
                    g[3] = g[3] * 10 + d;
                    g[4]++;
                    joined = true;
                    break;
                }
            if (!joined) groups.add(new float[]{f[0], f[1], f[2], d, 1});
        }
        List<int[]> out = new ArrayList<>();
        for (float[] g : groups) out.add(new int[]{(int) ((g[0] + g[1]) / 2), (int) g[2], (int) g[3]});
        return out;
    }

    /** Componenti bianche della dimensione di una cifra, riconosciute: {x0, x1, y centro, indice modello}. */
    private static List<float[]> scan(int[] px, int w, int h, int x0, int y0, int x1, int y1, float digitH,
                                      float maxDist, int[][] skip) {
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(w, x1);
        y1 = Math.min(h, y1);
        int W = x1 - x0, H = y1 - y0;
        if (W <= 0 || H <= 0) return new ArrayList<>();
        boolean[] m = new boolean[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                boolean skipped = false;
                if (skip != null)
                    for (int[] k : skip)
                        if (x0 + x >= k[0] && x0 + x < k[2] && y0 + y >= k[1] && y0 + y < k[3]) skipped = true;
                m[y * W + x] = !skipped && white(px[(y0 + y) * w + x0 + x]);
            }
        boolean[] seen = new boolean[W * H];
        int[] queue = new int[W * H];
        int minPx = (int) (digitH * digitH * 0.08);
        List<float[]> found = new ArrayList<>(); // {x0, x1, ycentro, indice modello}
        for (int i = 0; i < m.length; i++) {
            if (!m[i] || seen[i]) continue;
            int qh = 0, qt = 0;
            queue[qt++] = i;
            seen[i] = true;
            int bx0 = W, by0 = H, bx1 = -1, by1 = -1;
            while (qh < qt) {
                int k = queue[qh++];
                int ky = k / W, kx = k % W;
                bx0 = Math.min(bx0, kx);
                bx1 = Math.max(bx1, kx);
                by0 = Math.min(by0, ky);
                by1 = Math.max(by1, ky);
                if (ky > 0 && m[k - W] && !seen[k - W]) { seen[k - W] = true; queue[qt++] = k - W; }
                if (ky < H - 1 && m[k + W] && !seen[k + W]) { seen[k + W] = true; queue[qt++] = k + W; }
                if (kx > 0 && m[k - 1] && !seen[k - 1]) { seen[k - 1] = true; queue[qt++] = k - 1; }
                if (kx < W - 1 && m[k + 1] && !seen[k + 1]) { seen[k + 1] = true; queue[qt++] = k + 1; }
            }
            if (qt < minPx) continue;
            int cw = bx1 - bx0 + 1, ch = by1 - by0 + 1;
            if (ch < 0.75f * digitH || ch > 1.3f * digitH || cw > 1.4f * ch) continue;
            // normalizza su griglia GHxGW
            float[] v = new float[GW * GH];
            for (int gy = 0; gy < GH; gy++)
                for (int gx = 0; gx < GW; gx++) {
                    int ya = gy * ch / GH, yb = Math.max(ya + 1, (gy + 1) * ch / GH);
                    int xa = gx * cw / GW, xb = Math.max(xa + 1, (gx + 1) * cw / GW);
                    int n = 0, f = 0;
                    for (int y = ya; y < yb; y++)
                        for (int x = xa; x < xb; x++) {
                            n++;
                            if (m[(by0 + y) * W + bx0 + x]) f++;
                        }
                    v[gy * GW + gx] = f / (float) n;
                }
            float aspect = cw / (float) ch;
            int best = -1;
            float bd = 9f;
            float[][] t = templates();
            for (int j = 0; j < t.length; j++) {
                float d = 0;
                for (int k = 0; k < v.length; k++) d += Math.abs(v[k] - t[j][k]);
                d = d / v.length + 0.5f * Math.abs(aspect - DigitTemplates.ASPECT[j]);
                if (d < bd) {
                    bd = d;
                    best = j;
                }
            }
            if (bd <= maxDist) found.add(new float[]{x0 + bx0, x0 + bx1 + 1, y0 + (by0 + by1) / 2f, best});
        }
        return found;
    }

    /** Numero intero (prima di un'eventuale "/"), oppure -1. */
    static int readInt(int[] px, int w, int h, int x0, int y0, int x1, int y1, float digitH) {
        String s = read(px, w, h, x0, y0, x1, y1, digitH);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (s.isEmpty() || s.length() > 3) return -1;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return -1;
        return Integer.parseInt(s);
    }
}
