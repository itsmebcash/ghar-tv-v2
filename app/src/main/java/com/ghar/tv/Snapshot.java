package com.ghar.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small, ready-to-serve file describing your favourite channels (name, logo, category,
 * stable id). Ghar Launcher reads it through ChannelsProvider, so it never has to wait for
 * the 10,000-channel playlist to load. Rewritten whenever favourites or scan results change.
 *
 * The stream address is kept here only so Ghar TV itself can play a channel by id;
 * the provider never hands it out.
 */
final class Snapshot {

    static final class Entry {
        String id;
        String url;
        String name;
        String logo;
        String category;
        Channel channel; // full details for playback (Ghar TV only)
    }

    private static final String FILE = "favourites_snapshot.json";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Snapshot() {
    }

    /** Safe from any thread: gathers data on the main thread, writes the file in the background. */
    static void refreshAsync(Context ctx) {
        Context app = ctx.getApplicationContext();
        MAIN.post(() -> {
            Set<String> favs = Store.favourites(app);
            Map<String, Entry> old = new HashMap<>();
            for (Entry e : read(app)) old.put(e.url, e);
            List<Channel> recent = Session.recent(app);
            Repo repo = Repo.get(app);

            List<Channel> chosen = new ArrayList<>();
            for (String url : favs) {
                Channel c = repo.find(url);
                if (c == null) c = Repo.indexOf(recent, url) >= 0 ? recent.get(Repo.indexOf(recent, url)) : null;
                if (c == null && old.containsKey(url)) c = old.get(url).channel;
                if (c != null) chosen.add(c);
            }
            java.util.Collections.sort(chosen, (a, b) -> a.name.compareToIgnoreCase(b.name));
            new Thread(() -> write(app, chosen)).start();
        });
    }

    private static synchronized void write(Context c, List<Channel> channels) {
        try {
            JSONArray arr = new JSONArray();
            for (Channel ch : channels) arr.put(ch.toJson());
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(arr.toString().getBytes(Charset.forName("UTF-8")));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(c.getFilesDir(), FILE));
        } catch (Exception ignored) {
            // best effort
        }
    }

    /** Favourite channels, alphabetical. Any thread. */
    static synchronized List<Entry> read(Context c) {
        List<Entry> out = new ArrayList<>();
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return out;
        try {
            JSONArray arr = new JSONArray(Playlist.read(f));
            for (int i = 0; i < arr.length(); i++) out.add(entry(Channel.fromJson(arr.getJSONObject(i))));
        } catch (Exception ignored) {
            // unreadable: empty
        }
        return out;
    }

    static Entry entry(Channel ch) {
        Entry e = new Entry();
        e.channel = ch;
        e.id = ch.id();
        e.url = ch.url;
        e.name = ch.name;
        e.logo = ch.logo == null ? "" : ch.logo;
        e.category = ch.categories.isEmpty() ? "" : ch.categories.get(0);
        return e;
    }

    /** Find a channel by stable id: favourites first, then recent and last watched. */
    static Channel findById(Context c, String id) {
        if (id == null) return null;
        for (Entry e : read(c)) if (id.equals(e.id)) return e.channel;
        for (Channel ch : Session.recent(c)) if (id.equals(ch.id())) return ch;
        Channel last = Session.lastChannel(c);
        if (last != null && id.equals(last.id())) return last;
        return null;
    }
}
