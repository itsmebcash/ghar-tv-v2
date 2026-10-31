package com.ghar.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The app's channel data, shared by the browser and the player (one per process).
 *
 * - Loads the playlist and saved scan results once.
 * - Runs the health-check scan in the background while the app is on screen,
 *   gently (few threads) while a channel is playing so the video keeps its bandwidth.
 * - Pauses the scan and saves progress as soon as the app is no longer visible,
 *   so nothing keeps running after you leave; it resumes next time.
 *
 * All public methods are called on the main thread.
 */
final class Repo {

    static final String RECENT = "Last Watched";
    /** Category key for your own playlists: "pl:" + playlist id. */
    static final String PLAYLIST = "pl:";
    /** Not a category: the "+ New playlist" row. */
    static final String NEW_PLAYLIST = "+new";
    static final String ALL = "All";
    static final String FAVOURITES = "Favourites";
    static final String NEPAL = "Nepal";
    static final String INDIA = "India";

    interface Listener {
        void onRepoChanged();
    }

    private static final int THREADS_BROWSING = 24;
    private static final int THREADS_WHILE_PLAYING = 4;
    private static final long STOP_WHEN_HIDDEN_MS = 3000;

    private static Repo instance;

    static synchronized Repo get(Context c) {
        if (instance == null) instance = new Repo(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new LinkedHashSet<>();

    List<Channel> all = new ArrayList<>();
    final List<Channel> working = new ArrayList<>();
    private final Set<String> workingUrls = new HashSet<>();
    Store.ScanCache cache = new Store.ScanCache();

    boolean loaded;
    private boolean loading;
    boolean loadFailed;
    boolean scanning;
    int toCheck;
    final AtomicInteger checked = new AtomicInteger();

    private Scanner scanner;
    private final ConcurrentLinkedQueue<Channel> passed = new ConcurrentLinkedQueue<>();
    private int ticks;
    private int visibleScreens;
    private boolean playbackActive;

    private Repo(Context app) {
        this.app = app;
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        for (Listener l : new ArrayList<>(listeners)) l.onRepoChanged();
    }

    // ------------------------------------------------------------ lifecycle

    /** Every screen calls this from onStart. */
    void screenStarted() {
        visibleScreens++;
        main.removeCallbacks(stopWhenHidden);
        if (!loaded) load();
        else resumeScanIfNeeded();
    }

    /** Every screen calls this from onStop. */
    void screenStopped() {
        visibleScreens = Math.max(0, visibleScreens - 1);
        if (visibleScreens == 0) main.postDelayed(stopWhenHidden, STOP_WHEN_HIDDEN_MS);
    }

    /** Leaving the app on purpose (Back from the player): stop now. */
    void exitNow() {
        main.removeCallbacks(stopWhenHidden);
        pauseScan();
    }

    private final Runnable stopWhenHidden = this::pauseScan;

    /** The player tells us when a video is playing, so the scan can be gentle. */
    void setPlaybackActive(boolean active) {
        if (playbackActive == active) return;
        playbackActive = active;
        if (scanning) { // restart remaining work with the new thread count
            pauseScan();
            resumeScanIfNeeded();
        }
    }

    // -------------------------------------------------------------- loading

    private void load() {
        if (loading) return;
        loading = true;
        loadFailed = false;
        io.execute(() -> {
            List<Channel> list = null;
            try {
                list = Playlist.load(app);
            } catch (Exception ignored) {
                // reported below
            }
            Store.ScanCache saved = Store.loadScan(app);
            List<Channel> result = list;
            main.post(() -> {
                loading = false;
                if (result == null) {
                    loadFailed = true;
                    notifyChanged();
                    return;
                }
                all = result;
                loaded = true;
                // Saved results are reused forever, unless the last scan found nothing at all
                // (e.g. the internet was down while it ran).
                if (saved.hasResults() && !(saved.complete && saved.working.isEmpty())) {
                    cache = saved;
                    for (Channel c : all) {
                        Boolean hls = cache.working.get(c.url);
                        if (hls != null) {
                            c.hls = hls;
                            addWorking(c);
                        }
                    }
                    notifyChanged();
                    if (visibleScreens > 0) resumeScanIfNeeded();
                } else {
                    fullRescan();
                }
            });
        });
    }

    /** Check every channel again; the ones that worked last time are checked first. */
    void fullRescan() {
        pauseScan();
        Set<String> previouslyWorking;
        synchronized (cache) {
            previouslyWorking = new HashSet<>(cache.working.keySet());
        }
        cache = new Store.ScanCache();
        cache.time = System.currentTimeMillis();
        working.clear();
        workingUrls.clear();
        passed.clear();
        List<Channel> order = new ArrayList<>();
        List<Channel> rest = new ArrayList<>();
        for (Channel c : all) {
            if (previouslyWorking.contains(c.url)) order.add(c);
            else rest.add(c);
        }
        order.addAll(rest);
        notifyChanged();
        startScan(order);
    }

    private void resumeScanIfNeeded() {
        if (!loaded || scanning || cache.complete || cache.time == 0) return;
        List<Channel> rest = new ArrayList<>();
        synchronized (cache) {
            for (Channel c : all) if (!cache.checked.contains(c.url)) rest.add(c);
        }
        startScan(rest);
    }

    private void startScan(List<Channel> list) {
        scanning = true;
        toCheck = list.size();
        checked.set(0);
        Store.ScanCache target = cache;
        scanner = new Scanner(playbackActive ? THREADS_WHILE_PLAYING : THREADS_BROWSING);
        scanner.start(list, new Scanner.Listener() {
            @Override
            public void onChecked(Channel c, boolean ok) {
                synchronized (target) {
                    target.checked.add(c.url);
                    if (ok) target.working.put(c.url, c.hls);
                }
                checked.incrementAndGet();
                if (ok) passed.add(c);
            }

            @Override
            public void onFinished() {
                synchronized (target) {
                    target.complete = true;
                    Store.saveScan(app, target);
                }
                main.post(() -> {
                    if (target != cache) return;
                    scanning = false;
                    scanner = null;
                    drainPassed();
                    notifyChanged();
                });
            }
        });
        main.removeCallbacks(flush);
        main.post(flush);
        notifyChanged();
    }

    /** Stops checking and saves what has been checked so far. */
    private void pauseScan() {
        if (scanner != null) scanner.cancel();
        scanner = null;
        main.removeCallbacks(flush);
        drainPassed();
        if (scanning) {
            scanning = false;
            saveAsync();
            notifyChanged();
        }
    }

    /** Once a second while scanning: publish newly working channels. */
    private final Runnable flush = new Runnable() {
        @Override
        public void run() {
            boolean added = drainPassed();
            if (scanning) {
                if (++ticks % 20 == 0) saveAsync(); // keep progress if Android stops the app
                notifyChanged();
                main.postDelayed(this, 1000);
            } else if (added) {
                notifyChanged();
            }
        }
    };

    private boolean drainPassed() {
        boolean added = false;
        Channel c;
        while ((c = passed.poll()) != null) added |= addWorking(c);
        return added;
    }

    private boolean addWorking(Channel c) {
        if (!workingUrls.add(c.url)) return false;
        working.add(c);
        return true;
    }

    private void saveAsync() {
        Store.ScanCache target = cache;
        io.execute(() -> {
            synchronized (target) {
                Store.saveScan(app, target);
            }
        });
    }

    // ---------------------------------------------------------------- edits

    /** Channels that failed while watching are taken off the list (until the next rescan). */
    void removeDead(Collection<String> urls) {
        if (urls.isEmpty()) return;
        synchronized (cache) {
            for (String u : urls) cache.working.remove(u);
        }
        workingUrls.removeAll(urls);
        for (int i = working.size() - 1; i >= 0; i--) {
            if (urls.contains(working.get(i).url)) working.remove(i);
        }
        saveAsync();
        notifyChanged();
    }

    // -------------------------------------------------------------- queries

    /** Category keys in display order: Last Watched, Favourites, your playlists, then the rest. */
    static List<String> categoryNames(Context c) {
        List<String> names = new ArrayList<>();
        names.add(RECENT);
        names.add(FAVOURITES);
        for (Store.UserPlaylist p : Store.playlists(c)) names.add(PLAYLIST + p.id);
        names.add(NEW_PLAYLIST);
        for (String n : categoryNames()) if (!n.equals(RECENT) && !n.equals(FAVOURITES)) names.add(n);
        return names;
    }

    /** What to show for a category key. */
    static String label(Context c, String key) {
        if (NEW_PLAYLIST.equals(key)) return c.getString(R.string.new_playlist);
        if (key.startsWith(PLAYLIST)) {
            Store.UserPlaylist p = Store.playlist(c, key.substring(PLAYLIST.length()));
            return p == null ? "" : "♫  " + p.name;
        }
        return key;
    }

    static boolean isPlaylist(String key) {
        return key != null && key.startsWith(PLAYLIST);
    }

    static List<String> categoryNames() {
        List<String> names = new ArrayList<>();
        names.add(RECENT);
        names.add(ALL);
        names.add(FAVOURITES);
        names.add(NEPAL);
        names.add(INDIA);
        for (String c : Playlist.CATEGORY_ORDER) names.add(c);
        return names;
    }

    static boolean matches(Channel c, String category, Set<String> favourites) {
        switch (category) {
            case ALL:
                return true;
            case FAVOURITES:
                return favourites.contains(c.url);
            case NEPAL:
                return "np".equals(c.country);
            case INDIA:
                return "in".equals(c.country);
            default:
                return c.categories.contains(category); // (Last Watched / playlists handled in channels())
        }
    }

    List<Channel> channels(String category, Set<String> favourites) {
        if (RECENT.equals(category)) return recent();
        if (NEW_PLAYLIST.equals(category)) return new ArrayList<>();
        if (isPlaylist(category)) return playlistChannels(category);
        List<Channel> out = new ArrayList<>();
        for (Channel c : working) if (matches(c, category, favourites)) out.add(c);
        return out;
    }

    int count(String category, Set<String> favourites) {
        if (RECENT.equals(category)) return Session.recent(app).size();
        if (NEW_PLAYLIST.equals(category)) return 0;
        if (isPlaylist(category)) return playlistChannels(category).size();
        int n = 0;
        for (Channel c : working) if (matches(c, category, favourites)) n++;
        return n;
    }

    /** Your playlist, in the order you added channels (ones no longer working are left out). */
    private List<Channel> playlistChannels(String key) {
        List<Channel> out = new ArrayList<>();
        Store.UserPlaylist p = Store.playlist(app, key.substring(PLAYLIST.length()));
        if (p == null) return out;
        for (String url : p.urls) {
            int i = indexOf(working, url);
            if (i >= 0) out.add(working.get(i));
        }
        return out;
    }

    /** Recently watched, newest first. Uses the live channel entry when it's in the list. */
    private List<Channel> recent() {
        List<Channel> out = new ArrayList<>();
        for (Channel saved : Session.recent(app)) {
            int i = indexOf(working, saved.url);
            out.add(i >= 0 ? working.get(i) : saved);
        }
        return out;
    }

    static int indexOf(List<Channel> list, String url) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).url.equals(url)) return i;
        return -1;
    }
}
