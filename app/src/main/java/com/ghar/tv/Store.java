package com.ghar.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remembers scan results permanently, so the app opens instantly and never rescans
 * on its own: which channels work (and whether each is HLS), which were checked, and when.
 * Also keeps the favourites list (separately, so a rescan never touches it).
 */
final class Store {

    private static final String FILE = "scan.json";
    private static final String PREFS = "ghar_tv";
    private static final String KEY_FAVS = "favourites";

    static final class ScanCache {
        long time;
        boolean complete;
        final Map<String, Boolean> working = new HashMap<>(); // url -> isHls
        final Map<String, Long> okTime = new HashMap<>();     // url -> when it last passed the check
        final Set<String> checked = new HashSet<>();

        /** Saved results are kept until you press Rescan; they never expire on their own. */
        boolean hasResults() {
            return time > 0 && !checked.isEmpty();
        }
    }

    private Store() {
    }

    // ----------------------------------------------------------------- scan

    static ScanCache loadScan(Context c) {
        ScanCache sc = new ScanCache();
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return sc;
        try {
            JSONObject o = new JSONObject(Playlist.read(f));
            sc.time = o.getLong("time");
            sc.complete = o.getBoolean("complete");
            JSONArray w = o.getJSONArray("working");
            for (int i = 0; i < w.length(); i++) {
                JSONObject e = w.getJSONObject(i);
                sc.working.put(e.getString("u"), e.getBoolean("h"));
                long t = e.optLong("t", 0);
                if (t > 0) sc.okTime.put(e.getString("u"), t);
            }
            JSONArray ch = o.getJSONArray("checked");
            for (int i = 0; i < ch.length(); i++) sc.checked.add(ch.getString(i));
        } catch (Exception e) {
            return new ScanCache(); // unreadable: start over
        }
        return sc;
    }

    /** Background thread only. */
    static synchronized void saveScan(Context c, ScanCache sc) {
        try {
            JSONArray w = new JSONArray();
            for (Map.Entry<String, Boolean> e : sc.working.entrySet()) {
                Long t = sc.okTime.get(e.getKey());
                w.put(new JSONObject().put("u", e.getKey()).put("h", e.getValue()).put("t", t == null ? 0 : t));
            }
            JSONArray ch = new JSONArray();
            for (String u : sc.checked) ch.put(u);
            JSONObject o = new JSONObject().put("time", sc.time).put("complete", sc.complete)
                    .put("working", w).put("checked", ch);
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(Charset.forName("UTF-8")));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(c.getFilesDir(), FILE));
        } catch (Exception ignored) {
            // best effort
        }
    }

    // ---------------------------------------------------------- screen mode

    /** 0 = Fit (whole picture), 1 = Fill (zoom, no black bars), 2 = Stretch (full screen). */
    static int screenMode(Context c) {
        return prefs(c).getInt("screen_mode", 0);
    }

    static void setScreenMode(Context c, int mode) {
        prefs(c).edit().putInt("screen_mode", mode).apply();
    }

    /** Phone: 0 = follow the phone, 1 = portrait, 2 = landscape. */
    static int orientation(Context c) {
        return prefs(c).getInt("orientation", 0);
    }

    static void setOrientation(Context c, int value) {
        prefs(c).edit().putInt("orientation", value).apply();
    }

    // ------------------------------------------------------------ subtitles

    static boolean subtitlesOn(Context c) {
        return prefs(c).getBoolean("subs_on", true);
    }

    /** Preferred subtitle language (ISO code). English by default. */
    static String subtitleLanguage(Context c) {
        return prefs(c).getString("subs_lang", "en");
    }

    static void setSubtitles(Context c, boolean on, String language) {
        SharedPreferences.Editor e = prefs(c).edit().putBoolean("subs_on", on);
        if (language != null) e.putString("subs_lang", language);
        e.apply();
    }

    // ------------------------------------------------------------ playlists

    /** A playlist you made: a name and the channels in it (by stream address). */
    static final class UserPlaylist {
        String id;
        String name;
        final List<String> urls = new ArrayList<>();
    }

    static List<UserPlaylist> playlists(Context c) {
        List<UserPlaylist> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("playlists", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                UserPlaylist p = new UserPlaylist();
                p.id = o.getString("id");
                p.name = o.getString("name");
                JSONArray urls = o.getJSONArray("urls");
                for (int j = 0; j < urls.length(); j++) p.urls.add(urls.getString(j));
                out.add(p);
            }
        } catch (Exception ignored) {
            // unreadable: no playlists
        }
        return out;
    }

    static UserPlaylist playlist(Context c, String id) {
        for (UserPlaylist p : playlists(c)) if (p.id.equals(id)) return p;
        return null;
    }

    private static void savePlaylists(Context c, List<UserPlaylist> list) {
        try {
            JSONArray arr = new JSONArray();
            for (UserPlaylist p : list) {
                JSONArray urls = new JSONArray();
                for (String u : p.urls) urls.put(u);
                arr.put(new JSONObject().put("id", p.id).put("name", p.name).put("urls", urls));
            }
            prefs(c).edit().putString("playlists", arr.toString()).apply();
        } catch (Exception ignored) {
            // best effort
        }
    }

    static UserPlaylist createPlaylist(Context c, String name) {
        List<UserPlaylist> list = playlists(c);
        UserPlaylist p = new UserPlaylist();
        p.id = Long.toString(System.currentTimeMillis(), 36);
        p.name = name;
        list.add(p);
        savePlaylists(c, list);
        return p;
    }

    static void renamePlaylist(Context c, String id, String name) {
        List<UserPlaylist> list = playlists(c);
        for (UserPlaylist p : list) if (p.id.equals(id)) p.name = name;
        savePlaylists(c, list);
    }

    static void deletePlaylist(Context c, String id) {
        List<UserPlaylist> list = playlists(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        savePlaylists(c, list);
    }

    /** @return true if added, false if it was already there */
    static boolean addToPlaylist(Context c, String id, String url) {
        List<UserPlaylist> list = playlists(c);
        boolean added = false;
        for (UserPlaylist p : list) {
            if (p.id.equals(id) && !p.urls.contains(url)) {
                p.urls.add(url);
                added = true;
            }
        }
        savePlaylists(c, list);
        return added;
    }

    static void removeFromPlaylist(Context c, String id, String url) {
        List<UserPlaylist> list = playlists(c);
        for (UserPlaylist p : list) if (p.id.equals(id)) p.urls.remove(url);
        savePlaylists(c, list);
    }

    // ----------------------------------------------------------- favourites

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> favourites(Context c) {
        return new HashSet<>(prefs(c).getStringSet(KEY_FAVS, new HashSet<String>()));
    }

    /** @return true if it is now a favourite */
    static boolean toggleFavourite(Context c, String url) {
        Set<String> favs = favourites(c);
        boolean added;
        if (favs.contains(url)) {
            favs.remove(url);
            added = false;
        } else {
            favs.add(url);
            added = true;
        }
        prefs(c).edit().putStringSet(KEY_FAVS, favs).apply();
        Snapshot.refreshAsync(c); // keep Ghar Launcher's view of favourites up to date
        return added;
    }
}
