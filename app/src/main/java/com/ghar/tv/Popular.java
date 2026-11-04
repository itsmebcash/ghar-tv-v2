package com.ghar.tv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "For You": about 140 popular channels (Nepal, India, international), in a fixed order so they keep
 * their channel numbers. The same list as Ghar TV Mobile.
 */
final class Popular {

    static final String[] NAMES = {
            // Nepal (the channel list has only some Nepali channels; others are picked up if they appear)
            "Kantipur TV", "Kantipur Max", "Capital TV", "Divya Darshan TV", "Mithila Nepal TV", "TV Today Janakpur",
            "Ray TV", "Public 4K TV", "Nepal Television", "NTV News", "Image Channel", "AP1", "Himalaya TV",
            "Avenues TV", "News24 Nepal",
            // India: news
            "Aaj Tak", "Zee News", "ABP News", "India TV", "NDTV India", "NDTV 24x7", "Republic Bharat",
            "Republic TV", "Times Now", "Times Now Navbharat", "Mirror Now", "News18 India", "DD News",
            "India Today", "WION", "Zee Business", "CNBC TV18", "ET Now", "NDTV Profit", "Good News Today",
            "News Nation", "News24", "TV9 Bharatvarsh", "Bharat Samachar", "India News", "DD India",
            "ABP Ananda", "Republic Bangla", "Zee 24 Taas", "Asianet News", "Manorama News",
            // India: entertainment, movies, music, devotional
            "Zee Cinema", "Zee TV", "Zee Action", "Sony Max", "Sony SAB", "Sony Wah", "Sony Pix", "Sony BBC Earth",
            "Colors", "Colors Cineplex", "Colors Infinity", "Star Gold", "Star Movies", "Star Bharat", "&TV",
            "DD National", "DD Bharati", "DD Kisan", "B4U Movies", "B4U Music", "Movies Now", "Shemaroo",
            "Shemaroo Umang", "Dangal", "Goldmines", "Goldmines Movies", "Bhojpuri Cinema", "Comedy Central",
            "9XM", "9X Jalwa", "Zoom", "Mastiii", "Dhoom Music", "MTV", "Aastha", "Sanskar",
            "Zee Marathi", "Star Pravah", "Colors Marathi", "Zee Bangla", "Colors Bangla", "Star Jalsha",
            "Zee Telugu", "Star Maa", "Sun TV", "PTC Punjabi",
            // International: documentary, movies, news
            "Discovery Channel", "Discovery Science", "Discovery Turbo", "Animal Planet", "National Geographic",
            "History", "BBC Earth", "Love Nature", "TLC", "Food Network", "Travelxp", "Fashion TV", "HBO",
            "Cinemax", "AXN", "CNN", "CNN International", "BBC News", "Al Jazeera English", "Al Jazeera",
            "Fox News", "ABC News", "CBS News", "NBC News NOW", "CNBC", "Bloomberg", "Sky News", "DW English",
            "France 24 English", "Euronews English", "TRT World", "CGTN", "NHK World-Japan", "Arirang",
            // Kids and sport
            "Cartoon Network", "Pogo", "Nick Jr.", "Nickelodeon", "Disney Channel", "Disney Junior",
            "Discovery Kids", "Cartoonito", "Peppa Pig", "DD Sports", "ESPN", "Eurosport", "Fox Sports",
            "Willow", "PTV Sports", "Red Bull TV",
    };

    /** Words a channel's name may add after the popular name ("Zee News HD", "HBO Asia"). */
    private static final Set<String> EXTRA = new HashSet<>(java.util.Arrays.asList(
            "hd", "sd", "4k", "uhd", "india", "asia", "south", "southasia", "english", "hindi", "international",
            "intl", "nepal", "tv", "channel", "live", "1080p", "720p", "576p", "480p", "360p", "geo", "blocked",
            "not", "24", "7", "24x7", "us", "usa", "uk", "europe", "global", "world", "middle", "east", "me"));

    private static final java.util.Map<String, String> NORM = new java.util.HashMap<>();
    private static int lastSize = -1;
    private static List<Channel> cached = new ArrayList<>();

    private Popular() {
    }

    /** The working popular channels in list order (For You, numbered 1, 2, 3...). */
    static synchronized List<Channel> channels(List<Channel> working) {
        if (working.size() == lastSize) return new ArrayList<>(cached);
        lastSize = working.size();
        List<Channel> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : NAMES) {
            Channel ch = find(working, name);
            if (ch != null && seen.add(ch.url)) out.add(ch);
        }
        cached = out;
        return new ArrayList<>(out);
    }

    /** Popular channels among all channels, to check them first in a channel check. */
    static Set<String> urls(List<Channel> all) {
        Set<String> out = new HashSet<>();
        for (String name : NAMES) {
            Channel ch = find(all, name);
            if (ch != null) out.add(ch.url);
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
