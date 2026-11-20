package com.yarnloop.bot;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/** Sceglie quale rocchetto toccare. */
public final class Strategy {

    public static final class Decision {
        public final Vision.Spool spool;
        public final String reason;

        Decision(Vision.Spool spool, String reason) {
            this.spool = spool;
            this.reason = reason;
        }
    }

    int maxOnBelt = 5;
    int yarnPerSpool = 8;
    long recentWindowMs = 2500;
    int minFreeSlotsForUnblock = 2;

    private final List<long[]> sent = new ArrayList<>(); // {tempo, colore}

    public void remember(Vision.Spool s) {
        sent.add(new long[]{SystemClock.uptimeMillis(), s.color});
    }

    private int[] recent() {
        long now = SystemClock.uptimeMillis();
        sent.removeIf(e -> now - e[0] >= recentWindowMs);
        int[] r = new int[Vision.N];
        for (long[] e : sent) r[(int) e[1]]++;
        return r;
    }

    private static String n(int c) {
        return Vision.NAMES[c];
    }

    public Decision decide(Vision.State st) {
        int[] rec = recent();
        int[] belt = new int[Vision.N];
        int[] need = new int[Vision.N];
        int onBelt = 0;
        for (int c = 0; c < Vision.N; c++) {
            belt[c] = Math.max(st.belt[c], rec[c]);
            onBelt += belt[c];
            need[c] = st.exposed[c] - belt[c] * yarnPerSpool;
        }

        if (!st.levelVisible()) return new Decision(null, "nessun livello visibile");
        if (onBelt >= maxOnBelt) return new Decision(null, "nastro pieno (" + onBelt + ")");

        // 1) rimetti in gioco i rocchetti negli slot
        boolean urgent = st.freeSlots() <= 1;
        Vision.Spool best = null;
        for (Vision.Spool s : st.slots) {
            if (s == null || st.exposed[s.color] <= 0) continue;
            if (need[s.color] <= 0 && !urgent) continue;
            if (best == null || need[s.color] > need[best.color]
                    || (need[s.color] == need[best.color] && st.exposed[s.color] > st.exposed[best.color]))
                best = s;
        }
        if (best != null)
            return new Decision(best, "slot: " + n(best.color) + " (esposti " + st.exposed[best.color] + ")");

        // 2) prima fila della griglia con un colore che serve
        int bestBonus = -1;
        for (Vision.Spool s : st.grid) {
            if (need[s.color] <= 0) continue;
            int bonus = 0;
            for (int i = 0; i < Math.min(2, s.behind.size()); i++)
                if (st.exposed[s.behind.get(i)] > 0) bonus++;
            if (best == null || need[s.color] > need[best.color]
                    || (need[s.color] == need[best.color] && bonus > bestBonus)) {
                best = s;
                bestBonus = bonus;
            }
        }
        if (best != null)
            return new Decision(best, "griglia: " + n(best.color) + " (esposti " + st.exposed[best.color] + ")");

        if (onBelt > 0) return new Decision(null, "aspetto che il nastro lavori");

        // 3) sblocco: togli un rocchetto davanti per arrivare a un colore utile
        if (st.freeSlots() >= minFreeSlotsForUnblock) {
            int depth = 99;
            for (Vision.Spool s : st.grid)
                for (int d = 0; d < s.behind.size(); d++)
                    if (st.exposed[s.behind.get(d)] > 0) {
                        if (d < depth) {
                            best = s;
                            depth = d;
                        }
                        break;
                    }
            if (best != null)
                return new Decision(best, "sblocco: tolgo " + n(best.color) + " per " + n(best.behind.get(depth)));
            // 4) ultimo tentativo: il colore con piu' lana rimasta
            if (!st.grid.isEmpty() && st.freeSlots() >= minFreeSlotsForUnblock + 1) {
                for (Vision.Spool s : st.grid)
                    if (best == null || st.remaining[s.color] > st.remaining[best.color]) best = s;
                if (st.remaining[best.color] > 0)
                    return new Decision(best, "tentativo: " + n(best.color));
            }
        }
        return new Decision(null, "nessuna mossa sicura");
    }
}
