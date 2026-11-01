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
    /** Not a category: the "Search…" row that opens the search box. */
    static final String SEARCH_ROW = "+search";
    /** Search results: "q:" + what you typed. */
    static final String SEARCH = "q:";
    static final String ALL = "All";
    static final String FAVOURITES = "Favourites";
    /**
     * The 6 categories (same as Ghar TV Mobile). Local is Nepal + India (by country); the other five
     * gather the playlist's ~28 genres. A channel can be in more than one.
     */
    static final String LOCAL = "Local";
    static final String NEWS = "News";
    static final String ENTERTAINMENT = "Entertainment";
    static final String EDUCATION = "Education";
    static final String LIFESTYLE = "Lifestyle";
    static final String OTHER = "Other";
    static final String[] CATEGORIES = {LOCAL, NEWS, ENTERTAINMENT, EDUCATION, LIFESTYLE, OTHER};

    private static final java.util.Map<String, String> GROUP = new java.util.HashMap<>();

    static {
        for (String g : new String[]{"News", "Business", "Weather", "Legislative"}) GROUP.put(g, NEWS);
        for (String g : new String[]{"Entertainment", "Movies", "Series", "Comedy", "Classic", "Music",
                "Sports", "Kids", "Animation", "Family"}) GROUP.put(g, ENTERTAINMENT);
        for (String g : new String[]{"Education", "Documentary", "Science"}) GROUP.put(g, EDUCATION);
        for (String g : new String[]{"Travel", "Lifestyle", "Cooking", "Culture", "Outdoor", "Relax",
                "Shop", "Auto"}) GROUP.put(g, LIFESTYLE);
    }

    static String groupOf(String genre) {
        String g = GROUP.get(genre);
        return g != null ? g : OTHER;
    }

    /** A category saved by an older version ("Nepal", "Movies"...) becomes its new category. */
    static String normalize(String key) {
        if (key == null) return ALL;
        if ("Nepal".equals(key) || "India".equals(key)) return LOCAL;
        for (String k : CATEGORIES) if (k.equals(key)) return key;
        if (key.equals(RECENT) || key.equals(ALL) || key.equals(FAVOURITES) || key.startsWith(PLAYLIST)
                || key.startsWith(SEARCH) || isAction(key)) return key;
        return groupOf(key);
    }

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
                    Snapshot.refreshAsync(app);
                    if (visibleScreens > 0) resumeScanIfNeeded();
                    recheckFavouritesWeekly();
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
                    if (ok) {
                        target.working.put(c.url, c.hls);
                        target.okTime.put(c.url, System.currentTimeMillis());
                    }
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
                Snapshot.refreshAsync(app);
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

    // ------------------------------------------------- favourites, weekly

    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    /** Favourites found working again by the weekly check (shown once by the screens). */
    int favouritesBack;
    boolean favouritesBackShown = true;

    /**
     * Once a week, favourites that stopped working are tried again (just them, gently), and
     * the ones that work come back into the lists - no full rescan needed.
     */
    private void recheckFavouritesWeekly() {
        long last = Store.favouritesCheckTime(app);
        if (System.currentTimeMillis() - last < WEEK_MS) return;
        java.util.Set<String> favs = Store.favourites(app);
        List<Channel> missing = new ArrayList<>();
        for (Channel c : all) if (favs.contains(c.url) && !workingUrls.contains(c.url)) missing.add(c);
        Store.setFavouritesCheckTime(app, System.currentTimeMillis());
        if (missing.isEmpty()) return;
        Store.ScanCache target = cache;
        List<Channel> back = java.util.Collections.synchronizedList(new ArrayList<Channel>());
        new Scanner(3).start(missing, new Scanner.Listener() {
            @Override
            public void onChecked(Channel c, boolean ok) {
                if (!ok) return;
                synchronized (target) {
                    target.working.put(c.url, c.hls);
                    target.okTime.put(c.url, System.currentTimeMillis());
                }
                back.add(c);
            }

            @Override
            public void onFinished() {
                main.post(() -> {
                    if (back.isEmpty() || target != cache) return;
                    for (Channel c : back) addWorking(c);
                    favouritesBack = back.size();
                    favouritesBackShown = false;
                    saveAsync();
                    Snapshot.refreshAsync(app);
                    notifyChanged();
                });
            }
        });
    }

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
        Snapshot.refreshAsync(app);
    }

    /** The live entry for a stream address, or null. */
    Channel find(String url) {
        int i = indexOf(working, url);
        return i >= 0 ? working.get(i) : null;
    }

    // -------------------------------------------------------------- queries

    /** Category keys in display order: Last Watched, Favourites, your playlists, then the rest. */
    static List<String> categoryNames(Context c) {
        List<String> names = new ArrayList<>();
        names.add(SEARCH_ROW);
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
        if (SEARCH_ROW.equals(key)) return c.getString(R.string.search_row);
        if (isSearch(key)) return c.getString(R.string.search_label, key.substring(SEARCH.length()));
        if (key.startsWith(PLAYLIST)) {
            Store.UserPlaylist p = Store.playlist(c, key.substring(PLAYLIST.length()));
            return p == null ? "" : "♫  " + p.name;
        }
        return key;
    }

    static boolean isSearch(String key) {
        return key != null && key.startsWith(SEARCH);
    }

    /** Rows that do something when chosen instead of showing channels. */
    static boolean isAction(String key) {
        return NEW_PLAYLIST.equals(key) || SEARCH_ROW.equals(key);
    }

    static boolean isPlaylist(String key) {
        return key != null && key.startsWith(PLAYLIST);
    }

    static List<String> categoryNames() {
        List<String> names = new ArrayList<>();
        names.add(RECENT);
        names.add(ALL);
        names.add(FAVOURITES);
        java.util.Collections.addAll(names, CATEGORIES);
        return names;
    }

    static boolean matches(Channel c, String category, Set<String> favourites) {
        switch (category) {
            case ALL:
                return true;
            case FAVOURITES:
                return favourites.contains(c.url);
            case LOCAL:
                return "np".equals(c.country) || "in".equals(c.country);
            default: // (Last Watched / playlists handled in channels())
                for (String g : c.categories) if (groupOf(g).equals(category)) return true;
                return c.categories.isEmpty() && OTHER.equals(category);
        }
    }

    List<Channel> channels(String category, Set<String> favourites) {
        if (RECENT.equals(category)) return recent();
        if (isAction(category)) return new ArrayList<>();
        if (isSearch(category)) return search(category.substring(SEARCH.length()));
        if (isPlaylist(category)) return playlistChannels(category);
        List<Channel> out = new ArrayList<>();
        for (Channel c : working) if (matches(c, category, favourites)) out.add(c);
        return out;
    }

    int count(String category, Set<String> favourites) {
        if (RECENT.equals(category)) return Session.recent(app).size();
        if (isAction(category)) return 0;
        if (isSearch(category)) return search(category.substring(SEARCH.length())).size();
        if (isPlaylist(category)) return playlistChannels(category).size();
        int n = 0;
        for (Channel c : working) if (matches(c, category, favourites)) n++;
        return n;
    }

    /** Working channels matching what you typed, best first (see {@link Search}). */
    List<Channel> search(String query) {
        return Search.find(working, query); // forgiving: "kisan" finds DD Kishan
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
