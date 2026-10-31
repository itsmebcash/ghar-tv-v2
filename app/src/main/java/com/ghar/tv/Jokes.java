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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A light joke while a channel connects.
 *
 * Family-friendly dad jokes from icanhazdadjoke.com, downloaded in the background a few
 * pages at a time (never while a channel is starting), kept in a small file so they also
 * show offline, and shown in a shuffled order so the same one doesn't keep coming back.
 */
final class Jokes {

    private static final String FILE = "jokes.json";
    private static final String PREFS = "ghar_tv_jokes";
    private static final long REFRESH_MS = 3L * 24 * 60 * 60 * 1000; // every 3 days
    private static final int KEEP = 300;
    private static final String UA = "Ghar TV (https://github.com/itsmebcash/ghar-tv-v2)";

    /** Shown until the first download has happened. */
    private static final String[] BUILT_IN = {
            "Why did the TV go to school? To improve its reception.",
            "I told the remote a joke. It didn't respond, but at least it changed the subject.",
            "Buffering is just the internet taking a deep breath before the good part.",
            "Why are channels never lonely? They always have a stream of friends.",
            "The antenna got married to the satellite dish. The wedding was okay, but the reception was amazing.",
            "Why don't TVs ever get lost? They always follow the program.",
            "I'd tell you a joke about Wi-Fi, but it might take a while to connect.",
            "My TV and I are very close. We have a lot of screen time together.",
    };

    private static volatile boolean refreshing;

    private Jokes() {
    }

    /** The next joke to show (call on the main thread; reads a small local file). */
    static String next(Context c) {
        List<String> jokes = cached(c);
        if (jokes.isEmpty()) jokes = java.util.Arrays.asList(BUILT_IN);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int i = p.getInt("next", 0);
        p.edit().putInt("next", i + 1).apply();
        return jokes.get(Math.abs(i) % jokes.size());
    }

    /** Background top-up of the joke file; cheap and silent if offline. */
    static void refreshIfNeeded(Context ctx) {
        Context c = ctx.getApplicationContext();
        File f = new File(c.getFilesDir(), FILE);
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS;
        if (fresh || refreshing) return;
        refreshing = true;
        new Thread(() -> {
            try {
                Set<String> all = new LinkedHashSet<>(cached(c));
                Random r = new Random();
                for (int k = 0; k < 3; k++) {
                    int page = 1 + r.nextInt(20);
                    String url = "https://icanhazdadjoke.com/search?limit=30&page=" + page;
                    JSONArray results = new JSONObject(fetchJson(url)).getJSONArray("results");
                    for (int i = 0; i < results.length(); i++) {
                        String joke = results.getJSONObject(i).getString("joke").trim();
                        if (!joke.isEmpty() && joke.length() < 220) all.add(joke);
                    }
                }
                List<String> list = new ArrayList<>(all);
                Collections.shuffle(list);
                while (list.size() > KEEP) list.remove(list.size() - 1);
                save(f, list);
            } catch (Exception ignored) {
                // offline or site down: keep what we have
            } finally {
                refreshing = false;
            }
        }).start();
    }

    private static String fetchJson(String url) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        byte[] data = Net.get(url, UA, null, 10_000, 256 * 1024, headers).data;
        return new String(data, Charset.forName("UTF-8"));
    }

    private static List<String> cached(Context c) {
        List<String> out = new ArrayList<>();
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return out;
        try {
            JSONArray arr = new JSONArray(Playlist.read(f));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {
            // unreadable: built-in jokes are used
        }
        return out;
    }

    private static void save(File f, List<String> jokes) throws Exception {
        JSONArray arr = new JSONArray();
        for (String j : jokes) arr.put(j);
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(arr.toString().getBytes(Charset.forName("UTF-8")));
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }
}
