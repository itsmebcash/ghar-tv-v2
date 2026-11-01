package com.ghar.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * What you were doing, saved on disk every time the channel changes — not only on exit —
 * so it survives Android killing the app:
 * last channel (full details), selected category, and recently watched channels.
 *
 * Favourites, scan results, screen mode and rotation are saved separately in Store.
 */
final class Session {

    private static final String PREFS = "ghar_tv_session";
    private static final String KEY_LAST = "last_channel";
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_RECENT = "recent";
    private static final String KEY_LAST_TIME = "last_time";
    static final int MAX_RECENT = 20;

    private Session() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Call whenever a channel starts playing. */
    static void channelStarted(Context c, Channel ch, String category) {
        try {
            List<Channel> recent = recent(c);
            for (int i = recent.size() - 1; i >= 0; i--) {
                if (recent.get(i).url.equals(ch.url)) recent.remove(i);
            }
            recent.add(0, ch);
            while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
            JSONArray arr = new JSONArray();
            for (Channel r : recent) arr.put(r.toJson());

            SharedPreferences.Editor e = prefs(c).edit()
                    .putString(KEY_LAST, ch.toJson().toString())
                    .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                    .putString(KEY_RECENT, arr.toString());
            if (category != null) e.putString(KEY_CATEGORY, category);
            e.apply();
        } catch (Exception ignored) {
            // saving is best effort; never interrupt playback for it
        }
    }

    static Channel lastChannel(Context c) {
        String s = prefs(c).getString(KEY_LAST, null);
        if (s == null) return null;
        try {
            return Channel.fromJson(new org.json.JSONObject(s));
        } catch (Exception e) {
            return null;
        }
    }

    /** When the last channel was started (0 if unknown). */
    static long lastWatchedTime(Context c) {
        return prefs(c).getLong(KEY_LAST_TIME, 0);
    }

    static String category(Context c) {
        return Repo.normalize(prefs(c).getString(KEY_CATEGORY, Repo.ALL));
    }

    static void setCategory(Context c, String category) {
        prefs(c).edit().putString(KEY_CATEGORY, category).apply();
    }

    static List<Channel> recent(Context c) {
        List<Channel> out = new ArrayList<>();
        String s = prefs(c).getString(KEY_RECENT, null);
        if (s == null) return out;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) out.add(Channel.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {
            // unreadable: start a fresh list
        }
        return out;
    }

    /** A channel that is dead for good shouldn't be auto-played next launch. */
    static void forgetIfLast(Context c, String url) {
        Channel last = lastChannel(c);
        if (last != null && last.url.equals(url)) prefs(c).edit().remove(KEY_LAST).apply();
    }
}
