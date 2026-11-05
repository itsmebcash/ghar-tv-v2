package com.ghar.tv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "For You": the curated channels that are working right now, in the fixed order of
 * assets/curated.m3u (Nepal first, then India, then the world). They keep their channel
 * numbers 1, 2, 3… across restarts and rescans.
 *
 * The name list below is only a fallback for the very first scan, before the curated
 * entries have finished checking, and for a build with no curated file.
 */
final class Popular {

    static final String[] NAMES = {
            // Nepal
            "Kantipur TV", "Kantipur Max", "Capital TV", "Divya Darshan TV", "Mithila Nepal TV", "TV Today Janakpur",
            "Ray TV", "Public 4K TV", "Nepal Television", "NTV", "NTV News", "NTV Plus", "Image Channel", "AP1",
            "Himalaya TV", "Avenues TV", "News24 Nepal", "Sagarmatha TV", "Nepal 1", "Indigenous Television",
            // India: news
            "Aaj Tak", "Zee News", "ABP News", "India TV", "NDTV India", "NDTV 24x7", "Republic Bharat",
            "Republic TV", "Times Now", "Mirror Now", "News18 India", "DD News", "India Today", "WION",
            "Zee Business", "CNBC TV18", "NDTV Profit", "News Nation", "TV9 Bharatvarsh", "DD India",
            "ABP Ananda", "Republic Bangla", "Zee 24 Taas", "Asianet News", "Manorama News",
            // India: entertainment, movies, music
            "Zee Cinema", "Zee TV", "Sony Max", "Sony SAB", "Colors", "Star Gold", "Star Movies", "Star Bharat",
            "DD National", "DD Bharati", "DD Kisan", "B4U Movies", "B4U Music", "Dangal", "Goldmines",
            "Goldmines Movies", "Shemaroo", "Shemaroo Umang", "9XM", "9X Jalwa", "Zoom", "Mastiii", "Dhoom Music",
            "MTV", "Aastha", "Sanskar", "Zee Marathi", "Star Pravah", "Colors Marathi", "Zee Bangla",
            "Colors Bangla", "Star Jalsha", "Zee Telugu", "Star Maa", "Sun TV", "PTC Punjabi",
            // International
            "Discovery Channel", "Discovery Science", "Discovery Turbo", "Animal Planet", "National Geographic",
            "Nat Geo Wild", "History", "BBC Earth", "Love Nature", "TLC", "Food Network", "Travelxp", "HBO",
            "HBO Family", "Cinemax", "AXN", "Comedy Central", "CNN", "CNN International", "BBC News",
            "Al Jazeera English", "Fox News", "ABC News", "NBC News", "CNBC", "Bloomberg", "Sky News",
            "DW English", "France 24 English", "Euronews", "TRT World", "CGTN", "NHK World-Japan", "Arirang",
            "Cartoon Network", "Pogo", "Nick Jr.", "Nickelodeon", "Disney Channel", "Discovery Kids",
            "Peppa Pig", "DD Sports", "ESPN", "Eurosport", "Fox Sports", "Willow", "Red Bull TV",
    };

    /** Words a channel's name may add after the popular name ("Zee News HD", "HBO Asia"). */
    private static final Set<String> EXTRA = new HashSet<>(java.util.Arrays.asList(
            "hd", "sd", "4k", "uhd", "india", "asia", "south", "southasia", "english", "hindi", "international",
            "intl", "nepal", "tv", "channel", "live", "1080p", "720p", "576p", "480p", "360p", "geo", "blocked",
            "not", "24", "7", "24x7", "us", "usa", "uk", "europe", "global", "world", "middle", "east", "me"));

    private static final java.util.Map<String, String> NORM = new java.util.HashMap<>();

    private Popular() {
    }

    /** The working curated channels, in list order (For You, numbered 1, 2, 3...). */
    static List<Channel> channels(List<Channel> working) {
        List<Channel> out = new ArrayList<>();
        synchronized (working) {
            for (Channel c : working) if (c.rank > 0) out.add(c);
        }
        if (!out.isEmpty()) {
            java.util.Collections.sort(out, (a, b) -> Integer.compare(a.rank, b.rank));
            return out;
        }
        return byName(working); // first scan still running: show whatever matches the names
    }

    /** Curated channels among all channels, to check them first in a channel check. */
    static Set<String> urls(List<Channel> all) {
        Set<String> out = new HashSet<>();
        for (Channel c : all) if (c.rank > 0) out.add(c.url);
        if (out.isEmpty()) for (String name : NAMES) {
            Channel ch = find(all, name);
            if (ch != null) out.add(ch.url);
        }
        return out;
    }

    /**
     * Identifies this build's curated list. When it changes (a new app version), the saved
     * check results for those channels are thrown away and they are tested again — a curated
     * channel that failed months ago must not stay hidden forever.
     */
    static String signature(List<Channel> all) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Channel c : all) {
            if (c.rank > 0) {
                sb.append(c.rank).append(c.url).append('|');
                n++;
            }
        }
        return n + "-" + Integer.toHexString(sb.toString().hashCode());
    }

    private static List<Channel> byName(List<Channel> working) {
        List<Channel> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : NAMES) {
            Channel ch = find(working, name);
            if (ch != null && seen.add(ch.url)) out.add(ch);
        }
        return out;
    }

    /** The best working channel for a popular name: exact name first, then with harmless extra words. */
    static Channel find(List<Channel> working, String name) {
        String want = norm(name);
        Channel exact = null, close = null;
        int closeScore = Integer.MAX_VALUE;
        synchronized (working) {
            for (Channel ch : working) {
                if (ch.name == null) continue;
                String have = NORM.get(ch.url);
                if (have == null) {
                    have = norm(ch.name);
                    NORM.put(ch.url, have);
                }
                if (have.equals(want)) {
                    if (exact == null || better(ch, exact)) exact = ch;
                    continue;
                }
                if (!have.startsWith(want + " ")) continue;
                boolean ok = true;
                for (String w : have.substring(want.length() + 1).split(" ")) {
                    if (!w.isEmpty() && !EXTRA.contains(w)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                int score = have.length() - want.length() + (local(ch) ? 0 : 5);
                if (score < closeScore) {
                    closeScore = score;
                    close = ch;
                }
            }
        }
        return exact != null ? exact : close;
    }

    private static boolean better(Channel a, Channel b) {
        return local(a) && !local(b);
    }

    private static boolean local(Channel c) {
        return "np".equals(c.country) || "in".equals(c.country);
    }

    /** "Zee News HD (1080p) [Geo-blocked]" -> "zee news hd 1080p geo blocked". */
    static String norm(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace("&", " and ");
        t = t.replaceAll("[^a-z0-9]+", " ").trim();
        return t.replaceAll(" +", " ");
    }
}
