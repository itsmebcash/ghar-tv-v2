package com.ghar.tv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads and parses the iptv-org M3U playlist (kept on the TV for offline starts). */
final class Playlist {

    static final String URL = "https://iptv-org.github.io/iptv/index.m3u";
    private static final String FILE = "playlist.m3u";
    private static final long MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    /** Shown in this order on the left; anything else goes to "Other". */
    static final String[] CATEGORY_ORDER = {
            "News", "Sports", "Movies", "Series", "Entertainment", "Kids", "Music",
            "Documentary", "Animation", "Business", "Classic", "Comedy", "Cooking", "Culture",
            "Education", "Family", "General", "Legislative", "Lifestyle", "Outdoor", "Relax",
            "Religious", "Science", "Shop", "Travel", "Weather", "Auto", "Other",
    };

    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private static final Pattern COUNTRY = Pattern.compile("\\.([a-z]{2})(?:@|$)");
    private static final Pattern QUALITY = Pattern.compile("\\((\\d{3,4}p)\\)");
    /** Removes "(720p)", "[Geo-blocked]", "[Not 24/7]" etc. from display names. */
    private static final Pattern TAGS = Pattern.compile("\\s*(\\([^)]*\\)|\\[[^\\]]*\\])");

    private Playlist() {
    }

    /** Background thread only. Uses the saved copy if it is fresh or the download fails. */
    static List<Channel> load(Context c) throws Exception {
        File f = new File(c.getFilesDir(), FILE);
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS;
        String text = null;
        if (!fresh) {
            try {
                byte[] data = Net.get(URL, null, null, 120_000, 20 * 1024 * 1024).data;
                text = new String(data, Charset.forName("UTF-8"));
                if (text.contains("#EXTINF")) save(f, data);
                else text = null;
            } catch (Exception ignored) {
                // fall back to the saved copy
            }
        }
        if (text == null) {
            if (!f.exists()) throw new Exception("Couldn't download the channel list");
            text = read(f);
        }
        return parse(text);
    }

    static List<Channel> parse(String text) {
        List<Channel> out = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        Channel pending = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                pending = new Channel();
                int lastQuote = line.lastIndexOf('"');
                int comma = line.indexOf(',', Math.max(lastQuote, 0));
                String title = comma >= 0 ? line.substring(comma + 1).trim() : "";
                Matcher q = QUALITY.matcher(title);
                if (q.find()) pending.quality = q.group(1);
                pending.name = TAGS.matcher(title).replaceAll("").trim();

                Matcher m = ATTR.matcher(comma >= 0 ? line.substring(0, comma) : line);
                while (m.find()) {
                    String key = m.group(1);
                    String value = m.group(2);
                    if ("tvg-logo".equals(key)) pending.logo = value;
                    else if ("group-title".equals(key)) addCategories(pending, value);
                    else if ("tvg-id".equals(key)) {
                        Matcher cm = COUNTRY.matcher(value.toLowerCase(Locale.US));
                        if (cm.find()) pending.country = cm.group(1);
                    } else if ("http-user-agent".equals(key)) pending.userAgent = value;
                    else if ("http-referrer".equals(key)) pending.referrer = value;
                }
            } else if (line.startsWith("#EXTVLCOPT:http-user-agent=") && pending != null) {
                pending.userAgent = line.substring(line.indexOf('=') + 1);
            } else if (line.startsWith("#EXTVLCOPT:http-referrer=") && pending != null) {
                pending.referrer = line.substring(line.indexOf('=') + 1);
            } else if (!line.startsWith("#") && pending != null) {
                pending.url = line;
                if (pending.name.isEmpty()) pending.name = "Channel";
                if (pending.categories.isEmpty()) pending.categories.add("Other");
                if (line.startsWith("http") && seenUrls.add(line)) out.add(pending);
                pending = null;
            }
        }
        // Check Nepal first, then India, then the rest of the world.
        Collections.sort(out, (a, b) -> rank(a) - rank(b));
        return out;
    }

    private static int rank(Channel c) {
        if ("np".equals(c.country)) return 0;
        if ("in".equals(c.country)) return 1;
        return 2;
    }

    private static void addCategories(Channel c, String value) {
        for (String part : value.split(";")) {
            String p = part.trim();
            boolean known = false;
            for (String k : CATEGORY_ORDER) {
                if (k.equalsIgnoreCase(p)) {
                    if (!c.categories.contains(k)) c.categories.add(k);
                    known = true;
                    break;
                }
            }
            if (!known && !c.categories.contains("Other")) c.categories.add("Other");
        }
    }

    private static void save(File f, byte[] data) {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        } catch (Exception ignored) {
            // best effort
        }
    }

    static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(data, 0, off, Charset.forName("UTF-8"));
        }
    }
}
