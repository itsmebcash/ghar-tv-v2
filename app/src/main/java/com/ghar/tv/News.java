package com.ghar.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Latest Nepali headlines (Setopati, Onlinekhabar, Gorkhapatra, Ratopati, Nagarik) to read while a
 * channel connects. New headlines come first; when there's nothing new, it loops through the recent ones.
 * Refreshed in the background every 15 minutes while you watch.
 */
final class News {

    static final class Item {
        String source;
        String title;
        long time;
    }

    private static final String[][] FEEDS = {
            {"सेतोपाटी", "https://www.setopati.com/feed"},
            {"अनलाइनखबर", "https://www.onlinekhabar.com/feed"},
            {"गोरखापत्र", "https://gorkhapatraonline.com/rss"},
            {"रातोपाटी", "https://www.ratopati.com/feed"},
            {"नागरिक", "https://nagariknews.nagariknetwork.com/feed"},
    };
    private static final String FILE = "news.json";
    private static final String PREFS = "ghar_tv_news";
    private static final long REFRESH_MS = 15 * 60 * 1000L;
    private static final long KEEP_MS = 2 * 24 * 60 * 60 * 1000L; // headlines up to two days old
    private static final int PER_FEED = 15;
    private static final String UA = "Mozilla/5.0 (Linux; Android 9) Ghar TV";
    private static volatile boolean refreshing;

    private News() {
    }

    /** The headline to show now (null when there are none yet). Main thread; reads a small file. */
    static Item next(Context c) {
        List<Item> items = cached(c);
        if (items.isEmpty()) return null;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> seen = new HashSet<>(p.getStringSet("seen", new HashSet<String>()));
        Item pick = null;
        for (Item it : items) { // newest first: anything not shown yet?
            if (!seen.contains(key(it))) {
                pick = it;
                break;
            }
        }
        if (pick == null) { // nothing new: keep looping through the recent ones
            int i = p.getInt("loop", 0);
            pick = items.get(Math.abs(i) % items.size());
            p.edit().putInt("loop", i + 1).apply();
        }
        seen.add(key(pick));
        if (seen.size() > 400) seen.clear();
        p.edit().putStringSet("seen", seen).apply();
        return pick;
    }

    /** "10 min ago", "3 h ago", "yesterday" (in Nepali digits are kept simple: Western digits). */
    static String age(Context c, long time) {
        if (time <= 0) return "";
        long min = Math.max(0, (System.currentTimeMillis() - time) / 60000);
        if (min < 1) return c.getString(R.string.news_now);
        if (min < 60) return c.getString(R.string.news_min, (int) min);
        if (min < 24 * 60) return c.getString(R.string.news_hours, (int) (min / 60));
        return c.getString(R.string.news_yesterday);
    }

    private static String key(Item it) {
        return it.source + "|" + it.title;
    }

    /** Background top-up; quiet when offline. */
    static void refreshIfNeeded(Context ctx) {
        Context c = ctx.getApplicationContext();
        File f = new File(c.getFilesDir(), FILE);
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS;
        if (fresh || refreshing) return;
        refreshing = true;
        new Thread(() -> {
            try {
                Map<String, Item> all = new LinkedHashMap<>();
                for (Item it : cached(c)) all.put(key(it), it);
                boolean any = false;
                for (String[] feed : FEEDS) {
                    try {
                        byte[] data = Net.get(feed[1], UA, null, 10_000, 1024 * 1024).data;
                        for (Item it : parse(data, feed[0])) all.put(key(it), it);
                        any = true;
                    } catch (Exception ignored) {
                        // this site is down or has no feed today: the others still count
                    }
                }
                if (!any) return;
                List<Item> list = new ArrayList<>(all.values());
                long cutoff = System.currentTimeMillis() - KEEP_MS;
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (list.get(i).time > 0 && list.get(i).time < cutoff) list.remove(i);
                }
                Collections.sort(list, (a, b) -> Long.compare(b.time, a.time));
                while (list.size() > 120) list.remove(list.size() - 1);
                save(f, list);
            } catch (Exception ignored) {
                // keep what we have
            } finally {
                refreshing = false;
            }
        }).start();
    }

    static List<Item> parse(byte[] data, String source) throws Exception {
        List<Item> out = new ArrayList<>();
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new ByteArrayInputStream(data), null);
        String tag = null;
        Item cur = null;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && out.size() < PER_FEED; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                tag = p.getName();
                if ("item".equals(tag) || "entry".equals(tag)) {
                    cur = new Item();
                    cur.source = source;
                }
            } else if (ev == XmlPullParser.TEXT && cur != null && tag != null) {
                String t = p.getText().trim();
                if (t.isEmpty()) continue;
                if ("title".equals(tag) && (cur.title == null || cur.title.isEmpty())) cur.title = clean(t);
                else if (("pubDate".equals(tag) || "published".equals(tag) || "updated".equals(tag)
                        || "date".equals(tag)) && cur.time == 0) cur.time = date(t);
            } else if (ev == XmlPullParser.END_TAG) {
                if (("item".equals(p.getName()) || "entry".equals(p.getName())) && cur != null) {
                    if (cur.title != null && cur.title.length() > 8) out.add(cur);
                    cur = null;
                }
                tag = null;
            }
        }
        return out;
    }

    private static String clean(String s) {
        return s.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#039;", "'").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private static long date(String s) {
        String[] formats = {"EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd HH:mm:ss"};
        for (String f : formats) {
            try {
                return new SimpleDateFormat(f, Locale.US).parse(s.trim()).getTime();
            } catch (Exception ignored) {
                // next format
            }
        }
        return 0;
    }

    private static List<Item> cached(Context c) {
        List<Item> out = new ArrayList<>();
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return out;
        try {
            JSONArray arr = new JSONArray(Playlist.read(f));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.source = o.optString("s");
                it.title = o.optString("t");
                it.time = o.optLong("d");
                if (!it.title.isEmpty()) out.add(it);
            }
        } catch (Exception ignored) {
            // unreadable: refetched later
        }
        return out;
    }

    private static void save(File f, List<Item> items) throws Exception {
        JSONArray arr = new JSONArray();
        for (Item it : items) arr.put(new JSONObject().put("s", it.source).put("t", it.title).put("d", it.time));
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(arr.toString().getBytes(Charset.forName("UTF-8")));
        }
        if (!tmp.renameTo(f)) throw new Exception("save failed");
    }
}
