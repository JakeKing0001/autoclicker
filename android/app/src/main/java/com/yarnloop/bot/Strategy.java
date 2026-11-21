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
        int returns; // quanti probabilmente torneranno negli slot (non finiscono)

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
    long loopMs = 8000;   // quanto resta in giro un rocchetto (circa un giro di nastro)
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

    private final List<long[]> sent = new ArrayList<>();      // {tempo, colore, lana, torna negli slot 0/1}
    private final List<Tap> pending = new ArrayList<>();
    private final Map<String, Long> blocked = new HashMap<>();

    // ------------------------------------------------------------------ memoria
    public synchronized void remember(Move m) {
        long now = SystemClock.uptimeMillis();
        pending.add(new Tap(m.key, m.colors, now, m.refills));
        int n = m.spool.count >= 0 ? m.spool.count : yarnPerSpool;
        for (int i = 0; i < m.colors.length; i++)
            if (m.colors[i] >= 0) sent.add(new long[]{now, m.colors[i], n, i < m.returns ? 1 : 0});
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
    private static final class Cand {
        final Move m;
        final boolean slot;
        final int[] counts;
        final int[] cols;

        Cand(Move m, boolean slot, int[] counts, int[] cols) {
            this.m = m;
            this.slot = slot;
            this.counts = counts;
            this.cols = cols;
        }
    }

    /**
     * Riempie i posti liberi del nastro con i rocchetti che raccolgono piu' lana
     * (valore = min(numero, lana esposta ancora libera)). Chi non riesce a finire torna negli slot:
     * se ne mandano solo finche' c'e' posto (contando anche quelli gia' in giro che torneranno).
     */
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

        // il contatore del nastro non si vede: popup, pubblicita', fine livello -> non toccare la griglia
        if (st.beltFree < 0) return new Decision(moves, "non e' la schermata del livello");
        int seats = st.beltFree;
        if (seats <= 0) return new Decision(moves, "nastro pieno");

        sent.removeIf(e -> now - e[0] >= Math.max(recentWindowMs, loopMs));
        int[] avail = new int[Vision.N];
        int returning = 0;
        for (int c = 0; c < Vision.N; c++) avail[c] = st.exposed[c];
        for (long[] e : sent)
            if (now - e[0] < loopMs) {
                avail[(int) e[1]] -= (int) e[2];
                returning += (int) e[3];
            }
        for (int c = 0; c < Vision.N; c++) avail[c] = Math.max(0, avail[c]);
        int room = st.freeSlots() - returning - reserveSlots;

        // --- candidati
        List<Cand> cands = new ArrayList<>();
        for (Vision.Spool s : st.slots) {
            if (s == null || !usable(slotKey(s), now)) continue;
            Integer j = st.slotLinks.get(s.index);
            Vision.Spool partner = j != null ? st.slots.get(j) : null;
            if (partner != null && partner.index < s.index) continue; // la coppia una volta sola
            int[] colors = partner != null ? new int[]{s.color, partner.color} : new int[]{s.color};
            int[] counts = partner != null ? new int[]{nOf(s), nOf(partner)} : new int[]{nOf(s)};
            cands.add(new Cand(new Move(s, slotKey(s), colors, false), true, counts, new int[0]));
        }
        for (int[] f : fronts) {
            int[] g = group(st, f[0], f[1]);
            if (g == null || !usable(gridKey(st, f[0], f[1]), now)) continue;
            Move m = gridMove(st, f[0], f[1]);
            int[] counts = new int[g.length], cols = new int[g.length];
            for (int i = 0; i < g.length; i++) {
                Integer n = st.counts.get(g[i]);
                counts[i] = n != null ? n : yarnPerSpool;
                cols[i] = g[i] / 100;
            }
            cands.add(new Cand(m, false, counts, cols));
        }
        // scavo: primo rocchetto di una colonna che copre un colore utile -> costo minimo
        Map<Integer, Integer> digCost = new HashMap<>();
        for (int c = 0; c < st.cells.length; c++)
            for (int r = 0; r < st.visibleRows; r++) {
                int color = st.cell(c, r);
                if (color < 0 || color == Vision.GEN || color == Vision.MYSTERY || st.exposed[color] <= 0) continue;
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
                    if (group(st, p[0], fr) != null) {
                        int k = enc(p[0], fr);
                        digCost.put(k, Math.min(digCost.getOrDefault(k, INF), cost));
                    }
                    break;
                }
            }

        Set<Integer> usedCols = new HashSet<>(), usedSlots = new HashSet<>();
        while (seats > 0) {
            Cand best = null;
            int bestScore = Integer.MIN_VALUE, bestReturns = 0;
            for (Cand cd : cands) {
                Move m = cd.m;
                if (m.seats > seats) continue;
                if (cd.slot && usedSlots.contains(m.spool.index)) continue;
                boolean clash = false;
                for (int col : cd.cols) if (usedCols.contains(col)) clash = true;
                if (clash) continue;
                int gain = 0;
                boolean finishes = true;
                for (int i = 0; i < m.colors.length; i++) {
                    int c = m.colors[i];
                    if (c < 0) continue;
                    gain += Math.min(cd.counts[i], avail[c]);
                    if (cd.counts[i] > avail[c]) finishes = false;
                }
                Integer dc = cd.slot ? null : digCost.get(enc(m.spool.index, rowOf(st, m)));
                if (gain <= 0 && dc == null) continue;
                int returns = finishes ? 0 : m.colors.length;
                if (!cd.slot && returns > room) continue;
                int score = gain + (finishes ? 5 : 0) + (m.refills ? 4 : 0);
                if (cd.slot) score += 3 * (st.slots.size() - st.freeSlots());
                if (dc != null) score += Math.max(1, 6 - 2 * dc);
                if (score > bestScore) {
                    bestScore = score;
                    best = cd;
                    bestReturns = returns;
                }
            }
            if (best == null) break;
            Move m = best.m;
            m.returns = bestReturns;
            moves.add(m);
            seats -= m.seats;
            for (int col : best.cols) usedCols.add(col);
            if (best.slot) {
                usedSlots.add(m.spool.index);
                Integer j = st.slotLinks.get(m.spool.index);
                if (j != null) usedSlots.add(j);
                room += m.colors.length;
            }
            room -= bestReturns;
            for (int i = 0; i < m.colors.length; i++)
                if (m.colors[i] >= 0) avail[m.colors[i]] = Math.max(0, avail[m.colors[i]] - best.counts[i]);
        }

        int onBelt = (st.beltCap > 0 ? st.beltCap : maxOnBelt) - st.beltFree;
        if (moves.isEmpty() && onBelt == 0 && room >= 1) {
            // nastro vuoto e niente di utile: distributore o colore con piu' lana nel disegno
            Move best = null;
            long bestScore = 0;
            for (Cand cd : cands) {
                if (cd.slot) continue;
                long score = cd.m.refills ? 1_000_000L : 0;
                for (int c : cd.m.colors) if (c >= 0) score += st.remaining[c];
                if (score > bestScore) {
                    bestScore = score;
                    best = cd.m;
                }
            }
            if (best != null) {
                best.returns = best.colors.length;
                moves.add(best);
            }
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

    private int nOf(Vision.Spool s) {
        return s.count >= 0 ? s.count : yarnPerSpool;
    }

    private static int rowOf(Vision.State st, Move m) {
        return st.front(m.spool.index);
    }
}
