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

        public Decision(List<Move> moves, String reason) {
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
    long arriveMs = 1500; // dopo il tocco, tempo per arrivare sul nastro
    float reach = 1.3f;   // mentre gira scopre altra lana: raggiungibile = esposta x 1.3
    int minGain = 3;      // non mandare chi prenderebbe meno di cosi' (se non finisce)
    int maxParked = 2;    // slot al massimo occupati da rocchetti con colori non esposti
    int[] guide = new int[Vision.N]; // priorita' dei colori dalla guida del livello (yarnlooplevel.com)
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

        // lana che stanno per prendere i rocchetti gia' sul nastro (numeri letti accanto a loro)
        // e quelli appena toccati che non sono ancora arrivati; chi non ce la fa tornera' negli slot
        sent.removeIf(e -> now - e[0] >= Math.max(recentWindowMs, arriveMs));
        int[] reachable = new int[Vision.N], taken = new int[Vision.N], avail = new int[Vision.N];
        for (int c = 0; c < Vision.N; c++) reachable[c] = (int) (st.exposed[c] * reach);
        List<int[]> inFlight = new ArrayList<>(st.beltSpools);
        for (long[] e : sent) if (now - e[0] < arriveMs) inFlight.add(new int[]{(int) e[1], (int) e[2]});
        inFlight.sort((a, b) -> Integer.compare(a[1], b[1]));
        int returning = 0;
        for (int[] e : inFlight) {
            if (taken[e[0]] + e[1] > reachable[e[0]]) returning++;
            taken[e[0]] += e[1];
        }
        for (int c = 0; c < Vision.N; c++) avail[c] = Math.max(0, reachable[c] - taken[c]);
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
                    } else {
                        // il primo e' collegato a uno non in prima fila: si libera prima quella colonna
                        Integer mate = st.links.get(enc(p[0], fr));
                        if (mate != null) {
                            int md = depth(st, mate / 100, mate % 100);
                            int fr2 = st.front(mate / 100);
                            if (md > 0 && md < INF && fr2 >= 0 && group(st, mate / 100, fr2) != null) {
                                int k = enc(mate / 100, fr2);
                                digCost.put(k, Math.min(digCost.getOrDefault(k, INF), cost + md));
                            }
                        }
                    }
                    break;
                }
            }

        Set<Integer> usedCols = new HashSet<>(), usedSlots = new HashSet<>();
        // rocchetti "parcheggiati": fermi negli slot con un colore che ora non e' esposto
        int parked = 0;
        for (Vision.Spool sp : st.slots) if (sp != null && st.exposed[sp.color] <= 0) parked++;
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
                int row = cd.slot ? -1 : rowOf(st, m);
                Integer dc = cd.slot ? null : digCost.get(enc(m.spool.index, row));
                // sblocco: svuotare il distributore (sforna i rocchetti che servono) e scoprire i "?"
                boolean explore = !cd.slot && (m.refills || st.cell(m.spool.index, row + 1) == Vision.MYSTERY);
                boolean small = gain <= 0 || (gain < minGain && !finishes);
                if (small && dc == null && !explore) continue; // posto sprecato
                int returns = finishes ? 0 : m.colors.length;
                int limit = room + (explore && small ? reserveSlots : 0);
                if (!cd.slot && returns > 0 && returns > limit) continue;
                boolean parks = !cd.slot && !finishes, gone = false;
                for (int c : m.colors) {
                    if (c >= 0 && st.exposed[c] > 0) parks = false;
                    if (c >= 0 && st.remaining[c] == 0) gone = true;
                }
                if (parks && !m.refills && parked >= maxParked) continue;      // troppi slot fermi
                if (!cd.slot && !finishes && !m.refills && gone) continue;      // colore sparito: per sempre in slot
                int score = gain + (finishes ? 5 : 0) + (m.refills ? 4 : 0);
                if (cd.slot) score += 3 * (st.slots.size() - st.freeSlots());
                if (dc != null) score += Math.max(1, 6 - 2 * dc);
                if (explore && small) score += 1;
                // la guida del livello consiglia di cominciare da certi colori (peso 6: +50% e +3)
                int gw = 0;
                for (int i = 0; i < m.colors.length; i++) {
                    int c = m.colors[i];
                    if (c >= 0 && Math.min(cd.counts[i], avail[c]) > 0) gw = Math.max(gw, guide[c]);
                }
                score += gain * gw / 12 + gw / 2;
                if (score > bestScore) {
                    bestScore = score;
                    best = cd;
                    bestReturns = returns;
                }
            }
            if (best == null) break;
            Move m = best.m;
            m.returns = bestReturns;
            if (!best.slot && bestReturns > 0) {
                boolean allHidden = true;
                for (int c : m.colors) if (c >= 0 && st.exposed[c] > 0) allHidden = false;
                if (allHidden) parked++;
            }
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
