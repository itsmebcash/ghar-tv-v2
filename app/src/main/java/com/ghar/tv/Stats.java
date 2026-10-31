package com.ghar.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Per-channel playback facts, learned while you watch:
 * when it last played successfully and how long it usually takes to start.
 * Kept small (most recent ~300 channels).
 */
final class Stats {

    private static final String PREFS = "ghar_tv_stats";
    private static final String KEY = "stats";
    private static final int MAX = 300;

    static final class Info {
        long lastOk;       // ms since epoch, 0 = unknown
        long startupMs;    // average time to first picture, -1 = unknown
    }

    private Stats() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The picture appeared after {@code ms} milliseconds. */
    static synchronized void playbackStarted(Context c, String url, long ms) {
        try {
            JSONObject all = new JSONObject(prefs(c).getString(KEY, "{}"));
            JSONObject o = all.optJSONObject(url);
            if (o == null) o = new JSONObject();
            int n = o.optInt("n", 0);
            long avg = o.optLong("ms", ms);
            avg = n == 0 ? ms : (avg * Math.min(n, 4) + ms) / (Math.min(n, 4) + 1); // recent-weighted
            o.put("n", n + 1).put("ms", avg).put("ok", System.currentTimeMillis());
            all.remove(url);
            all.put(url, o);
            while (all.length() > MAX) {
                Iterator<String> it = all.keys();
                if (!it.hasNext()) break;
                all.remove(it.next());
            }
            prefs(c).edit().putString(KEY, all.toString()).apply();
        } catch (Exception ignored) {
            // best effort
        }
    }

    static synchronized Info get(Context c, String url) {
        Info i = new Info();
        i.startupMs = -1;
        try {
            JSONObject o = new JSONObject(prefs(c).getString(KEY, "{}")).optJSONObject(url);
            if (o != null) {
                i.lastOk = o.optLong("ok", 0);
                i.startupMs = o.optLong("ms", -1);
            }
        } catch (Exception ignored) {
            // unknown
        }
        return i;
    }
}
