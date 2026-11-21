package com.yarnloop.bot;

import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guida di yarnlooplevel.com (come yarnloop_bot/guide.py): dalla sezione "Full Solution" della
 * pagina del livello si ricava una priorita' per colore (i colori dei primi passi valgono di piu').
 * Le pagine scaricate restano in memoria nelle impostazioni, cosi' si scaricano una volta sola.
 */
final class Guide {
    private static final String URL_FMT = "https://yarnlooplevel.com/level/%d/";
    private static final int[] STEP_WEIGHTS = {6, 4, 2, 1};
    private static final Object[][] WORDS = {
            {"white", new int[]{Vision.WHITE}}, {"cream", new int[]{Vision.WHITE}},
            {"black", new int[]{Vision.BLACK}}, {"brown", new int[]{Vision.BROWN}},
            {"blue", new int[]{Vision.BLUE, Vision.LIGHTBLUE}}, {"navy", new int[]{Vision.BLUE}},
            {"cyan", new int[]{Vision.CYAN}}, {"teal", new int[]{Vision.CYAN}}, {"turquoise", new int[]{Vision.CYAN}},
            {"yellow", new int[]{Vision.YELLOW}}, {"gold", new int[]{Vision.YELLOW}}, {"orange", new int[]{Vision.ORANGE}},
            {"tan", new int[]{Vision.SKIN}}, {"beige", new int[]{Vision.SKIN}}, {"peach", new int[]{Vision.SKIN}},
            {"skin", new int[]{Vision.SKIN}}, {"pink", new int[]{Vision.PINK, Vision.MAGENTA}},
            {"magenta", new int[]{Vision.MAGENTA}}, {"red", new int[]{Vision.RED}},
            {"green", new int[]{Vision.GREEN, Vision.LIME}}, {"lime", new int[]{Vision.LIME}},
            {"purple", new int[]{Vision.PURPLE}}, {"violet", new int[]{Vision.PURPLE}},
    };

    static String htmlToText(String page) {
        page = page.replaceAll("(?is)<(script|style).*?</\\1>", "");
        page = page.replaceAll("(?i)<br\\s*/?>|</p>|</h\\d>|</li>|</div>", "\n");
        page = page.replaceAll("(?s)<[^>]+>", "");
        page = page.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        return page.replaceAll("\\n\\s*\\n+", "\n");
    }

    /** Colore -> peso (0 = nessuna indicazione). */
    static int[] priorities(String page) {
        int[] out = new int[Vision.N];
        String text = htmlToText(page);
        Matcher m = Pattern.compile("Full Solution(.*?)(Common Mistakes|Frequently Asked|$)", Pattern.DOTALL).matcher(text);
        if (!m.find()) return out;
        int i = 0;
        for (String line : m.group(1).split("\n")) {
            String step = line.trim().toLowerCase(Locale.ROOT);
            if (step.length() <= 15) continue;
            int w = i < STEP_WEIGHTS.length ? STEP_WEIGHTS[i] : 0;
            i++;
            if (w == 0 || Pattern.compile("\\b(later|last|save)\\b").matcher(step).find()) continue;
            for (Object[] e : WORDS)
                if (Pattern.compile("\\b" + e[0] + "\\b").matcher(step).find())
                    for (int c : (int[]) e[1]) out[c] = Math.max(out[c], w);
        }
        return out;
    }

    static String encode(int[] p) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < p.length; c++) if (p[c] > 0) sb.append(c).append(':').append(p[c]).append(',');
        return sb.length() == 0 ? "-" : sb.toString();
    }

    static int[] decode(String s) {
        int[] p = new int[Vision.N];
        if (s == null || s.equals("-")) return p;
        for (String kv : s.split(",")) {
            if (kv.isEmpty()) continue;
            String[] a = kv.split(":");
            p[Integer.parseInt(a[0])] = Integer.parseInt(a[1]);
        }
        return p;
    }

    /** Priorita' del livello: dalla memoria, altrimenti scaricate (da chiamare fuori dal thread principale). */
    static int[] load(SharedPreferences prefs, int level) {
        String key = "guide_" + level;
        String cached = prefs.getString(key, null);
        if (cached != null) return decode(cached);
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(String.format(Locale.ROOT, URL_FMT, level)).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");
            if (c.getResponseCode() != 200) return new int[Vision.N];
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && bos.size() < 3_000_000) bos.write(buf, 0, n);
            }
            int[] p = priorities(bos.toString("UTF-8"));
            prefs.edit().putString(key, encode(p)).apply();
            return p;
        } catch (Exception e) {
            return new int[Vision.N]; // niente rete: si gioca senza guida (si riprova al prossimo avvio)
        }
    }

    static String describe(int[] p) {
        StringBuilder sb = new StringBuilder();
        for (int w = 6; w >= 1; w--)
            for (int c = 0; c < p.length; c++)
                if (p[c] == w && c != Vision.LIGHTBLUE && c != Vision.LIME)
                    sb.append(sb.length() > 0 ? ", " : "").append(Vision.NAMES[c]);
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** Livello in corso (come LevelTracker in guide.py). */
    static final class Tracker {
        int level;
        boolean outOfLevel, lost, fromScreen;

        void update(boolean inLevel, boolean fresh, String kind, int number) {
            if (number > 0) {
                level = number;
                fromScreen = true;
                if (kind != null && kind.startsWith("pulsante")) lost = true;
            }
            if (!inLevel) {
                outOfLevel = true;
                return;
            }
            if (outOfLevel && fresh) {
                if (level > 0 && !lost && !fromScreen) level++; // vinto: si passa al successivo
                lost = fromScreen = false;
            }
            outOfLevel = false;
        }
    }

    private Guide() {
    }
}
