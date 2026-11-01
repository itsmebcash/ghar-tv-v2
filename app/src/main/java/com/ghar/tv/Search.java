package com.ghar.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Forgiving channel search, for typing with a TV remote.
 *
 * Matches the way people spell Nepali and Hindi names in English: "kisan" finds "DD Kisan" and
 * "DD Kishan", "kantipur" finds "Kantipur TV HD", "avenues" finds "Avenues TV". Spellings are
 * folded first (sh/s, aa/a, ee/i, oo/u, w/v, ph/f, bh/b, double letters...), then each word you
 * type has to match the start of a word in the name, with a typo or two allowed in longer words.
 * Category and country words also match, ranked lower.
 */
final class Search {

    private static final int MAX_RESULTS = 120;

    private Search() {
    }

    /** Results, best first. */
    static List<Channel> find(List<Channel> channels, String query) {
        String raw = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Channel> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        String[] words = fold(raw).split(" ");
        List<Scored> scored = new ArrayList<>();
        for (Channel c : channels) {
            int s = score(c, raw, words);
            if (s > 0) scored.add(new Scored(c, s));
        }
        Collections.sort(scored, (a, b) -> a.score != b.score ? b.score - a.score
                : a.channel.name.length() - b.channel.name.length());
        for (int i = 0; i < scored.size() && i < MAX_RESULTS; i++) out.add(scored.get(i).channel);
        return out;
    }

    private static final class Scored {
        final Channel channel;
        final int score;

        Scored(Channel channel, int score) {
            this.channel = channel;
            this.score = score;
        }
    }

    private static int score(Channel c, String raw, String[] words) {
        String name = c.name.toLowerCase(Locale.ROOT);
        if (name.startsWith(raw)) return 1000;
        if (name.contains(raw)) return 900;
        String folded = fold(name);
        String[] nameWords = folded.split(" ");
        int total = 0;
        for (String w : words) {
            if (w.isEmpty()) continue;
            int best = 0;
            for (String nw : nameWords) best = Math.max(best, wordScore(w, nw));
            if (best == 0) {
                // a category or country word ("news", "np", "sports")
                StringBuilder sb = new StringBuilder(c.country == null ? "" : c.country);
                for (String cat : c.categories) sb.append(' ').append(cat);
                String extra = fold(sb.toString().toLowerCase(Locale.ROOT));
                for (String ew : extra.split(" ")) best = Math.max(best, wordScore(w, ew) / 3);
            }
            if (best == 0 && "tv".equals(w)) {
                for (String nw : nameWords) if (nw.startsWith("tele")) best = 200; // "tv" = television
            }
            if (best == 0 && w.length() <= 2) continue; // short extras ("tv", "hd") are optional
            if (best == 0) return 0; // every other word you typed has to match something
            total += best;
        }
        return total;  // 0 if nothing matched
    }

    /** How well one typed word matches one word of the name (0 = not at all). */
    private static int wordScore(String typed, String word) {
        if (word.isEmpty()) return 0;
        if (word.equals(typed)) return 300;
        if (word.startsWith(typed)) return 240;
        if (typed.length() >= 3 && word.contains(typed)) return 150;
        // allow typos: 1 in words of 4+ letters, 2 in words of 7+
        int allowed = typed.length() >= 7 ? 2 : typed.length() >= 4 ? 1 : 0;
        if (allowed == 0) return 0;
        String head = word.length() > typed.length() + 1 ? word.substring(0, typed.length() + 1) : word;
        int d = Math.min(distance(typed, word), distance(typed, head));
        return d <= allowed ? 200 - 50 * d : 0;
    }

    /** Folds common transliteration differences so different spellings compare equal. */
    static String fold(String s) {
        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            clean.append(Character.isLetterOrDigit(ch) ? ch : ' ');
        }
        String t = " " + clean.toString().replaceAll("\\s+", " ").trim() + " ";
        t = t.replace("sh", "s").replace("ph", "f").replace("bh", "b").replace("dh", "d")
                .replace("th", "t").replace("gh", "g").replace("kh", "k").replace("ch", "c")
                .replace("jh", "j").replace("w", "v").replace("z", "j").replace("q", "k")
                .replace("ee", "i").replace("oo", "u").replace("aa", "a").replace("ii", "i")
                .replace("ou", "u").replace("ck", "k");
        // collapse doubled letters ("Kantippur" ~ "Kantipur")
        StringBuilder out = new StringBuilder(t.length());
        char prev = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch != prev || ch == ' ' || Character.isDigit(ch)) out.append(ch);
            prev = ch;
        }
        return out.toString().trim();
    }

    /** Edit distance (insert, delete, replace). */
    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
