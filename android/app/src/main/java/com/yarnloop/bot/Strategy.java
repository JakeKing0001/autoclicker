package com.yarnloop.bot;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sceglie quale rocchetto toccare.
 * <ul>
 * <li>si puo' toccare solo il primo rocchetto di ogni colonna;</li>
 * <li>due rocchetti collegati da un'asta partono solo se sono entrambi in prima fila;</li>
 * <li>il distributore (blocco grigio) sforna rocchetti nella casella davanti: la sua colonna ha la
 * precedenza e cio' che sta dietro non si raggiunge;</li>
 * <li>se un rocchetto toccato non parte, per un po' viene lasciato stare.</li>
 * </ul>
 */
public final class Strategy {

    public static final class Decision {
        public final Vision.Spool spool;
        public final String reason;
        public final boolean fast;

        Decision(Vision.Spool spool, String reason, boolean fast) {
            this.spool = spool;
            this.reason = reason;
            this.fast = fast;
        }
    }

    private static final int INF = 99;

    int maxOnBelt = 5;
    int yarnPerSpool = 8;
    long recentWindowMs = 2500;
    long tapCheckMs = 450;
    long blockMs = 6000;
    int reserveSlots = 1;

    private static final class Tap {
        final String key;
        final int[] colors;
        final long t;
        final boolean refills;

        Tap(String key, int[] colors, long t, boolean refills) {
            this.key = key;
            this.colors = colors;
            this.t = t;
            this.refills = refills;
        }
    }

    private final List<long[]> sent = new ArrayList<>();      // {tempo, colore}
    private final List<Tap> pending = new ArrayList<>();
    private final Map<String, Long> blocked = new HashMap<>();
    private Tap lastChoice;

    // ------------------------------------------------------------------ memoria
    public void remember(Vision.Spool s) {
        if (lastChoice == null) return;
        long now = SystemClock.uptimeMillis();
        pending.add(new Tap(lastChoice.key, lastChoice.colors, now, lastChoice.refills));
        for (int c : lastChoice.colors) if (c >= 0) sent.add(new long[]{now, c});
    }

    private int[] recent(long now) {
        sent.removeIf(e -> now - e[0] >= recentWindowMs);
        int[] r = new int[Vision.N];
        for (long[] e : sent) r[(int) e[1]]++;
        return r;
    }

    private void checkPending(java.util.Set<String> present, long now) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            Tap p = pending.get(i);
            if (now - p.t < tapCheckMs) continue;
            pending.remove(i);
            if (present.contains(p.key) && !p.refills) {
                blocked.put(p.key, now + blockMs);
                for (int c : p.colors)
                    for (int k = sent.size() - 1; k >= 0; k--)
                        if (sent.get(k)[1] == c) {
                            sent.remove(k);
                            break;
                        }
            }
        }
    }

    private boolean usable(String key, long now) {
        for (Tap p : pending) if (p.key.equals(key)) return false;
        Long until = blocked.get(key);
        return until == null || until < now;
    }

    // ------------------------------------------------------------------ griglia
    private static int enc(int col, int row) {
        return col * 100 + row;
    }

    private static String slotKey(Vision.Spool s) {
        return "s" + s.index + ":" + s.color;
    }

    private static String gridKey(Vision.State st, int col, int row) {
        StringBuilder sb = new StringBuilder("g").append(col);
        for (int r = row; r < st.visibleRows; r++) sb.append(':').append(st.cell(col, r));
        return sb.toString();
    }

    private static boolean isFront(Vision.State st, int col, int row) {
        int v = st.cell(col, row);
        return v >= 0 && v != Vision.GEN && st.front(col) == row;
    }

    /** Celle che partono toccando (col,row), oppure null se ora non si puo'. */
    private static int[] group(Vision.State st, int col, int row) {
        if (!isFront(st, col, row)) return null;
        Integer o = st.links.get(enc(col, row));
        if (o == null) return new int[]{enc(col, row)};
        if (isFront(st, o / 100, o % 100)) return new int[]{enc(col, row), o};
        return null;
    }

    private static int depth(Vision.State st, int col, int row) {
        int f = st.front(col);
        if (f < 0) return INF;
        for (int r = f; r < Math.min(row, st.visibleRows); r++) if (st.cell(col, r) == Vision.GEN) return INF;
        return Math.max(0, row - f);
    }

    private static boolean beforeGenerator(Vision.State st, int[] group) {
        for (int g : group) if (st.cell(g / 100, g % 100 + 1) == Vision.GEN) return true;
        return false;
    }

    private static int[] colorsOf(Vision.State st, int[] group) {
        int[] c = new int[group.length];
        for (int i = 0; i < group.length; i++) c[i] = st.cell(group[i] / 100, group[i] % 100);
        return c;
    }

    private static Vision.Spool spool(Vision.State st, int col, int row) {
        int pitch = st.rowY.length > 1 ? st.rowY[1] - st.rowY[0] : 0;
        return new Vision.Spool(st.cell(col, row), st.colX[col], st.rowY[row] + pitch / 20, false, col);
    }

    private static String names(int[] colors) {
        StringBuilder sb = new StringBuilder();
        for (int c : colors) if (c >= 0) sb.append(sb.length() > 0 ? "+" : "").append(Vision.NAMES[c]);
        return sb.toString();
    }

    // ------------------------------------------------------------------ decisione
    public Decision decide(Vision.State st) {
        long now = SystemClock.uptimeMillis();
        lastChoice = null;

        java.util.Set<String> present = new java.util.HashSet<>();
        for (Vision.Spool s : st.slots) if (s != null) present.add(slotKey(s));
        List<int[]> fronts = new ArrayList<>();
        for (int c = 0; c < st.cells.length; c++) {
            int f = st.front(c);
            if (f >= 0 && isFront(st, c, f)) {
                fronts.add(new int[]{c, f});
                present.add(gridKey(st, c, f));
            }
        }
        checkPending(present, now);

        int[] rec = recent(now);
        int[] need = new int[Vision.N];
        int onBelt = 0;
        for (int c = 0; c < Vision.N; c++) {
            int b = Math.max(st.belt[c], rec[c]);
            onBelt += b;
            need[c] = st.exposed[c] - b * yarnPerSpool;
        }

        if (!st.levelVisible()) return new Decision(null, "nessun livello visibile", false);
        if (onBelt >= maxOnBelt) return new Decision(null, "nastro pieno (" + onBelt + ")", false);

        // 1) rimetti in gioco i rocchetti negli slot
        boolean urgent = st.freeSlots() <= 1;
        Vision.Spool bestSlot = null;
        int[] bestSlotColors = null;
        int bestGain = -1, bestExp = -1;
        for (Vision.Spool s : st.slots) {
            if (s == null || !usable(slotKey(s), now)) continue;
            Integer j = st.slotLinks.get(s.index);
            int[] colors = (j != null && st.slots.get(j) != null)
                    ? new int[]{s.color, st.slots.get(j).color} : new int[]{s.color};
            int gain = 0, exp = 0;
            for (int c : colors) {
                gain += Math.max(0, need[c]);
                exp += st.exposed[c];
            }
            if (exp <= 0 || (gain <= 0 && !urgent)) continue;
            if (gain > bestGain || (gain == bestGain && exp > bestExp)) {
                bestSlot = s;
                bestSlotColors = colors;
                bestGain = gain;
                bestExp = exp;
            }
        }
        if (bestSlot != null) {
            lastChoice = new Tap(slotKey(bestSlot), bestSlotColors, now, false);
            return new Decision(bestSlot, "slot: " + names(bestSlotColors), false);
        }

        // 2) prima fila con un colore che serve (precedenza al distributore)
        int[] best = null, bestGroup = null;
        long bestScore = Long.MIN_VALUE;
        for (int[] f : fronts) {
            int[] g = group(st, f[0], f[1]);
            if (g == null || !usable(gridKey(st, f[0], f[1]), now)) continue;
            int gain = 0;
            for (int c : colorsOf(st, g)) gain += Math.max(0, need[c]);
            if (gain <= 0) continue;
            int behind = 0;
            for (int r = 1; r <= 2; r++) {
                int v = st.cell(f[0], f[1] + r);
                if (v >= 0 && st.exposed[v] > 0) behind++;
            }
            long score = (beforeGenerator(st, g) ? 1_000_000L : 0) + gain * 10L + behind;
            if (score > bestScore) {
                bestScore = score;
                best = f;
                bestGroup = g;
            }
        }
        if (best != null) {
            boolean gen = beforeGenerator(st, bestGroup);
            lastChoice = new Tap(gridKey(st, best[0], best[1]), colorsOf(st, bestGroup), now, gen);
            return new Decision(spool(st, best[0], best[1]),
                    "griglia: " + names(colorsOf(st, bestGroup)) + (gen ? " (distributore)" : ""), false);
        }

        // 3) scavo: togli subito i rocchetti che coprono un colore utile
        int[] move = null;
        int moveCost = INF, moveTarget = -1;
        for (int c = 0; c < st.cells.length; c++)
            for (int r = 0; r < st.visibleRows; r++) {
                int color = st.cell(c, r);
                if (color < 0 || color == Vision.GEN || st.exposed[color] <= 0) continue;
                if (group(st, c, r) != null) continue;
                List<int[]> parts = new ArrayList<>();
                parts.add(new int[]{c, r});
                Integer o = st.links.get(enc(c, r));
                if (o != null) parts.add(new int[]{o / 100, o % 100});
                int cost = 0;
                for (int[] p : parts) cost += depth(st, p[0], p[1]);
                if (cost >= INF) continue;
                int[] m = null;
                for (int[] p : parts) {
                    if (depth(st, p[0], p[1]) <= 0) continue;
                    int fr = st.front(p[0]);
                    if (group(st, p[0], fr) != null && usable(gridKey(st, p[0], fr), now)) {
                        m = new int[]{p[0], fr};
                        break;
                    }
                }
                if (m == null) continue;
                if (cost < moveCost || (cost == moveCost && need[color] > need[moveTarget])) {
                    move = m;
                    moveCost = cost;
                    moveTarget = color;
                }
            }
        if (move != null && moveCost <= st.freeSlots() - reserveSlots) {
            int[] g = group(st, move[0], move[1]);
            lastChoice = new Tap(gridKey(st, move[0], move[1]), colorsOf(st, g), now, beforeGenerator(st, g));
            return new Decision(spool(st, move[0], move[1]),
                    "scavo: tolgo " + names(colorsOf(st, g)) + " per " + Vision.NAMES[moveTarget], true);
        }

        if (onBelt > 0) return new Decision(null, "aspetto che il nastro lavori", false);

        // 4) ultimo tentativo: distributore, poi il colore con piu' lana rimasta
        if (st.freeSlots() >= reserveSlots + 2) {
            best = null;
            bestScore = 0;
            for (int[] f : fronts) {
                int[] g = group(st, f[0], f[1]);
                if (g == null || !usable(gridKey(st, f[0], f[1]), now)) continue;
                int rem = 0;
                for (int c : colorsOf(st, g)) rem += st.remaining[c];
                long score = (beforeGenerator(st, g) ? 1_000_000L : 0) + rem;
                if (score > bestScore) {
                    bestScore = score;
                    best = f;
                    bestGroup = g;
                }
            }
            if (best != null) {
                lastChoice = new Tap(gridKey(st, best[0], best[1]), colorsOf(st, bestGroup), now,
                        beforeGenerator(st, bestGroup));
                return new Decision(spool(st, best[0], best[1]), "tentativo: " + names(colorsOf(st, bestGroup)), true);
            }
        }
        return new Decision(null, "nessuna mossa sicura", false);
    }
}
