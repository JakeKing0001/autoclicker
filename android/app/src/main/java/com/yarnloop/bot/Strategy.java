package com.yarnloop.bot;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sceglie quali rocchetti toccare (come yarnloop_bot/strategy.py).
 * <p>
 * Tattica: tenere il nastro sempre pieno. Ad ogni screenshot si toccano subito tanti rocchetti
 * quanti sono i posti liberi sul nastro (contatore "N/5"): prima gli slot (prima chi puo' finire),
 * poi la prima fila con colori esposti (prima il distributore), poi lo scavo (uno per colonna),
 * tenendo sempre almeno uno slot libero.
 */
public final class Strategy {

    public static final class Move {
        public final Vision.Spool spool;
        final String key;
        final int[] colors;
        final boolean refills;
        final int seats;

        Move(Vision.Spool spool, String key, int[] colors, boolean refills) {
            this.spool = spool;
            this.key = key;
            this.colors = colors;
            this.refills = refills;
            this.seats = colors.length;
        }
    }

    public static final class Decision {
        public final List<Move> moves;
        public final String reason;

        Decision(List<Move> moves, String reason) {
            this.moves = moves;
            this.reason = reason;
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

    private final List<long[]> sent = new ArrayList<>();      // {tempo, colore, lana}
    private final List<Tap> pending = new ArrayList<>();
    private final Map<String, Long> blocked = new HashMap<>();

    // ------------------------------------------------------------------ memoria
    public synchronized void remember(Move m) {
        long now = SystemClock.uptimeMillis();
        pending.add(new Tap(m.key, m.colors, now, m.refills));
        int n = m.spool.count >= 0 ? m.spool.count : yarnPerSpool;
        for (int c : m.colors) if (c >= 0) sent.add(new long[]{now, c, n});
    }

    private void checkPending(Set<String> present, long now) {
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

    private Move gridMove(Vision.State st, int col, int row) {
        int[] g = group(st, col, row);
        int[] colors = new int[g.length];
        boolean refills = false;
        for (int i = 0; i < g.length; i++) {
            colors[i] = st.cell(g[i] / 100, g[i] % 100);
            if (st.cell(g[i] / 100, g[i] % 100 + 1) == Vision.GEN) refills = true;
        }
        int pitch = st.rowY.length > 1 ? st.rowY[1] - st.rowY[0] : 0;
        Vision.Spool sp = new Vision.Spool(st.cell(col, row), st.colX[col], st.rowY[row] + pitch / 20, false, col);
        Integer n = st.counts.get(enc(col, row));
        if (n != null) sp.count = n;
        return new Move(sp, gridKey(st, col, row), colors, refills);
    }

    private static boolean anyExposed(Vision.State st, int[] colors) {
        for (int c : colors) if (c >= 0 && st.exposed[c] > 0) return true;
        return false;
    }

    // ------------------------------------------------------------------ decisione
    public synchronized Decision decide(Vision.State st) {
        long now = SystemClock.uptimeMillis();
        List<Move> moves = new ArrayList<>();

        Set<String> present = new HashSet<>();
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

        if (!st.levelVisible()) return new Decision(moves, "nessun livello visibile");

        sent.removeIf(e -> now - e[0] >= recentWindowMs);
        int[] recent = new int[Vision.N];
        int[] taken = new int[Vision.N];
        for (long[] e : sent) {
            recent[(int) e[1]]++;
            taken[(int) e[1]] += (int) e[2];
        }
        int seats;
        if (st.beltFree >= 0) {
            seats = st.beltFree;
        } else {
            int on = 0;
            for (int c = 0; c < Vision.N; c++) on += Math.max(st.belt[c], recent[c]);
            seats = maxOnBelt - on;
        }
        if (seats <= 0) return new Decision(moves, "nastro pieno");

        int[] avail = new int[Vision.N];
        for (int c = 0; c < Vision.N; c++) avail[c] = st.exposed[c] - taken[c];
        int spare = st.freeSlots() - reserveSlots;
        Set<Integer> doneSlots = new HashSet<>(), doneCols = new HashSet<>();

        // 1) slot: rimetti in giro tutto quello che ha lana esposta, prima chi puo' finire
        List<Object[]> cands = new ArrayList<>(); // {chiave ordinamento long, Spool, colori}
        for (Vision.Spool s : st.slots) {
            if (s == null || !usable(slotKey(s), now)) continue;
            Integer j = st.slotLinks.get(s.index);
            Vision.Spool partner = (j != null) ? st.slots.get(j) : null;
            int[] colors = partner != null ? new int[]{s.color, partner.color} : new int[]{s.color};
            if (!anyExposed(st, colors)) continue;
            int n = s.count >= 0 ? s.count : yarnPerSpool;
            boolean finishes = n <= avail[s.color];
            long score = finishes ? 1_000_000L - n : st.exposed[s.color];
            cands.add(new Object[]{score, s, colors, partner});
        }
        cands.sort((a, b) -> Long.compare((long) b[0], (long) a[0]));
        for (Object[] k : cands) {
            Vision.Spool s = (Vision.Spool) k[1];
            if (doneSlots.contains(s.index)) continue;
            int[] colors = (int[]) k[2];
            if (colors.length > seats) continue;
            Move m = new Move(s, slotKey(s), colors, false);
            moves.add(m);
            seats -= m.seats;
            for (int c : colors) avail[c] -= s.count >= 0 ? s.count : yarnPerSpool;
            doneSlots.add(s.index);
            if (k[3] != null) doneSlots.add(((Vision.Spool) k[3]).index);
        }

        // 2) prima fila con colore esposto (prima il distributore, poi chi serve di piu')
        cands.clear();
        for (int[] f : fronts) {
            int[] g = group(st, f[0], f[1]);
            if (g == null || !usable(gridKey(st, f[0], f[1]), now)) continue;
            Move m = gridMove(st, f[0], f[1]);
            if (!anyExposed(st, m.colors)) continue;
            int gain = 0;
            for (int c : m.colors) gain += Math.max(0, avail[c]);
            cands.add(new Object[]{(m.refills ? 1_000_000L : 0) + gain, m, g});
        }
        cands.sort((a, b) -> Long.compare((long) b[0], (long) a[0]));
        for (Object[] k : cands) {
            Move m = (Move) k[1];
            int[] g = (int[]) k[2];
            boolean clash = false;
            for (int e : g) if (doneCols.contains(e / 100)) clash = true;
            if (clash || m.seats > seats) continue;
            moves.add(m);
            seats -= m.seats;
            for (int c : m.colors) avail[c] -= m.spool.count >= 0 ? m.spool.count : yarnPerSpool;
            for (int e : g) doneCols.add(e / 100);
        }

        // 3) scavo: togli i rocchetti che coprono un colore utile (uno per colonna)
        List<int[]> plans = new ArrayList<>(); // {costo, -avail, colonna, riga, colore}
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
                for (int[] p : parts) {
                    if (depth(st, p[0], p[1]) <= 0) continue;
                    int fr = st.front(p[0]);
                    if (group(st, p[0], fr) != null && usable(gridKey(st, p[0], fr), now)) {
                        plans.add(new int[]{cost, -avail[color], p[0], fr, color});
                        break;
                    }
                }
            }
        plans.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        for (int[] p : plans) {
            if (doneCols.contains(p[2]) || p[0] > spare) continue;
            Move m = gridMove(st, p[2], p[3]);
            boolean useless = !anyExposed(st, m.colors);
            if (useless && spare <= 0) continue;
            if (m.seats > seats) continue;
            moves.add(m);
            seats -= m.seats;
            for (int e : group(st, p[2], p[3])) doneCols.add(e / 100);
            if (useless) spare--;
        }

        // 4) niente da fare e nastro vuoto: distributore o il colore che c'e' di piu'
        int onBelt = 0;
        for (int c = 0; c < Vision.N; c++) onBelt += recent[c] + st.belt[c];
        if (moves.isEmpty() && onBelt == 0 && st.freeSlots() >= reserveSlots + 2) {
            Move best = null;
            long bestScore = 0;
            for (int[] f : fronts) {
                if (group(st, f[0], f[1]) == null || !usable(gridKey(st, f[0], f[1]), now)) continue;
                Move m = gridMove(st, f[0], f[1]);
                long score = m.refills ? 1_000_000L : 0;
                for (int c : m.colors) score += st.remaining[c];
                if (score > bestScore) {
                    bestScore = score;
                    best = m;
                }
            }
            if (best != null && best.seats <= seats) moves.add(best);
        }

        if (moves.isEmpty())
            return new Decision(moves, onBelt > 0 ? "aspetto che il nastro lavori" : "nessuna mossa sicura");
        StringBuilder sb = new StringBuilder().append(moves.size()).append(" tocchi: ");
        for (int i = 0; i < moves.size(); i++) {
            Move m = moves.get(i);
            if (i > 0) sb.append(", ");
            for (int k = 0; k < m.colors.length; k++)
                sb.append(k > 0 ? "+" : "").append(m.colors[k] >= 0 ? Vision.NAMES[m.colors[k]] : "?");
            if (m.spool.count >= 0) sb.append(' ').append(m.spool.count);
            if (m.spool.slot) sb.append(" (slot)");
        }
        return new Decision(moves, sb.toString());
    }
}
