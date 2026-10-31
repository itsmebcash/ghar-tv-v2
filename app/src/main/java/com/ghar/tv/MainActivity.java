package com.ghar.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Channel browser. Categories on the left, working channels on the right.
 * Channels appear only after passing the health check (see Probe).
 */
public class MainActivity extends Activity {

    private static final String ALL = "All";
    private static final String FAVOURITES = "Favourites";
    private static final String NEPAL = "Nepal";
    private static final String INDIA = "India";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final NumberFormat numbers = NumberFormat.getIntegerInstance();

    private List<Channel> all = new ArrayList<>();
    private final List<Channel> working = new ArrayList<>();
    private final Set<String> workingUrls = new HashSet<>();
    private final List<Channel> shown = new ArrayList<>();
    private Set<String> favourites = new HashSet<>();

    private Store.ScanCache cache = new Store.ScanCache();
    private Scanner scanner;
    private final ConcurrentLinkedQueue<Channel> passed = new ConcurrentLinkedQueue<>();
    private final AtomicInteger checked = new AtomicInteger();
    private int toCheck;
    private boolean scanning;
    private int ticks;

    private String category = ALL;
    private final List<String> categoryNames = new ArrayList<>();
    private final List<View> categoryRows = new ArrayList<>();

    private TextView status;
    private TextView empty;
    private GridView grid;
    private LinearLayout categoryList;
    private ChannelAdapter adapter;
    private View highlighted;
    private boolean destroyed;

    /** While scanning: move newly passed channels onto the screen once a second. */
    private final Runnable flush = new Runnable() {
        @Override
        public void run() {
            drainPassed();
            if (scanning) {
                if (++ticks % 20 == 0) saveCacheAsync(); // keep progress if the app is closed
                handler.postDelayed(this, 1000);
            }
        }
    };

    private String pendingCategory;
    private final Runnable selectPendingCategory = () -> {
        if (pendingCategory != null) selectCategory(pendingCategory, false);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        grid = findViewById(R.id.grid);
        categoryList = findViewById(R.id.categories);
        favourites = Store.favourites(this);
        if (!Device.isTv(this)) ((TextView) findViewById(R.id.hint)).setText(R.string.hint_phone);

        findViewById(R.id.rescan).setOnClickListener(v -> confirmRescan());

        adapter = new ChannelAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, position, id) -> play(position));
        grid.setOnItemLongClickListener((p, v, position, id) -> {
            Channel c = shown.get(position);
            boolean added = Store.toggleFavourite(this, c.url);
            favourites = Store.favourites(this);
            toast(added ? "★ Added " + c.name + " to Favourites" : "Removed " + c.name + " from Favourites");
            updateCategoryCounts();
            if (FAVOURITES.equals(category)) applyFilter(false);
            else adapter.notifyDataSetChanged();
            return true;
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                highlight(grid.hasFocus() ? view : null);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                highlight(null);
            }
        });
        grid.setOnFocusChangeListener((v, has) -> highlight(has ? grid.getSelectedView() : null));

        buildCategories();
        status.setText(R.string.loading_list);
        loadEverything();
    }

    @Override
    protected void onResume() {
        super.onResume();
        removeDeadChannels();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (scanner != null) scanner.cancel();
        if (scanning) saveCacheAsync();
        io.shutdown();
        super.onDestroy();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ----------------------------------------------------------- loading

    private void loadEverything() {
        io.execute(() -> {
            List<Channel> list;
            try {
                list = Playlist.load(this);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    status.setText(R.string.list_error);
                    showEmpty(getString(R.string.list_error_detail));
                });
                return;
            }
            Store.ScanCache saved = Store.loadScan(this);
            runOnUiThread(() -> {
                if (destroyed) return;
                all = list;
                // Reuse saved results forever, unless the last scan found nothing at all
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
                    updateCategoryCounts();
                    applyFilter(true);
                    if (cache.complete) {
                        showDoneStatus();
                    } else {
                        List<Channel> rest = new ArrayList<>();
                        for (Channel c : all) if (!cache.checked.contains(c.url)) rest.add(c);
                        startScan(rest);
                    }
                } else {
                    fullRescan(saved);
                }
            });
        });
    }

    /** A full check takes a while, so ask first. */
    private void confirmRescan() {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.rescan_title)
                .setMessage(getString(R.string.rescan_message, numbers.format(all.size())))
                .setPositiveButton(R.string.rescan, (d, w) -> fullRescan(cache))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Checks every channel again; channels that worked last time are checked first. */
    private void fullRescan(Store.ScanCache previous) {
        if (scanner != null) scanner.cancel();
        Set<String> previouslyWorking;
        synchronized (previous) {
            previouslyWorking = new HashSet<>(previous.working.keySet());
        }
        List<Channel> order = new ArrayList<>();
        List<Channel> rest = new ArrayList<>();
        for (Channel c : all) {
            if (previouslyWorking.contains(c.url)) order.add(c);
            else rest.add(c);
        }
        order.addAll(rest);

        cache = new Store.ScanCache();
        cache.time = System.currentTimeMillis();
        working.clear();
        workingUrls.clear();
        passed.clear();
        updateCategoryCounts();
        applyFilter(true);
        startScan(order);
    }

    private void startScan(List<Channel> list) {
        scanning = true;
        toCheck = list.size();
        checked.set(0);
        updateScanStatus();
        Store.ScanCache target = cache;
        scanner = new Scanner();
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
                    Store.saveScan(MainActivity.this, target);
                }
                runOnUiThread(() -> {
                    if (destroyed || target != cache) return;
                    scanning = false;
                    drainPassed();
                    showDoneStatus();
                });
            }
        });
        handler.removeCallbacks(flush);
        handler.post(flush);
    }

    private void saveCacheAsync() {
        Store.ScanCache target = cache;
        new Thread(() -> {
            synchronized (target) {
                Store.saveScan(getApplicationContext(), target);
            }
        }).start();
    }

    private void drainPassed() {
        boolean added = false;
        Channel c;
        while ((c = passed.poll()) != null) added |= addWorking(c);
        if (added) {
            updateCategoryCounts();
            applyFilter(false);
        } else if (shown.isEmpty()) {
            updateEmpty();
        }
        if (scanning) updateScanStatus();
    }

    private boolean addWorking(Channel c) {
        if (!workingUrls.add(c.url)) return false;
        working.add(c);
        return true;
    }

    private void updateScanStatus() {
        status.setText(getString(R.string.scanning,
                numbers.format(checked.get()), numbers.format(toCheck), numbers.format(working.size())));
    }

    private void showDoneStatus() {
        long hours = (System.currentTimeMillis() - cache.time) / 3_600_000L;
        String when = hours < 1 ? getString(R.string.just_now)
                : hours < 48 ? getString(R.string.hours_ago, hours)
                : getString(R.string.days_ago, hours / 24);
        status.setText(getString(R.string.done, numbers.format(working.size()), when));
        updateEmpty();
    }

    /** Channels that failed while you were watching are taken off the list. */
    private void removeDeadChannels() {
        if (PlayerActivity.DEAD.isEmpty()) return;
        List<String> dead;
        synchronized (PlayerActivity.DEAD) {
            dead = new ArrayList<>(PlayerActivity.DEAD);
            PlayerActivity.DEAD.clear();
        }
        for (String url : dead) {
            workingUrls.remove(url);
            synchronized (cache) {
                cache.working.remove(url);
            }
        }
        for (int i = working.size() - 1; i >= 0; i--) {
            if (dead.contains(working.get(i).url)) working.remove(i);
        }
        updateCategoryCounts();
        applyFilter(false);
        saveCacheAsync();
        if (!scanning) showDoneStatus();
    }

    // -------------------------------------------------------- categories

    private void buildCategories() {
        categoryNames.add(ALL);
        categoryNames.add(FAVOURITES);
        categoryNames.add(NEPAL);
        categoryNames.add(INDIA);
        for (String c : Playlist.CATEGORY_ORDER) categoryNames.add(c);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (String name : categoryNames) {
            View row = inflater.inflate(R.layout.item_category, categoryList, false);
            ((TextView) row.findViewById(R.id.cat_name)).setText(name);
            row.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    pendingCategory = name;
                    handler.removeCallbacks(selectPendingCategory);
                    handler.postDelayed(selectPendingCategory, 300);
                }
            });
            row.setOnClickListener(v -> {
                selectCategory(name, false);
                grid.requestFocus();
            });
            categoryList.addView(row);
            categoryRows.add(row);
        }
        categoryRows.get(0).setActivated(true);
    }

    private void selectCategory(String name, boolean force) {
        if (!force && name.equals(category)) return;
        category = name;
        for (int i = 0; i < categoryRows.size(); i++) {
            categoryRows.get(i).setActivated(categoryNames.get(i).equals(name));
        }
        applyFilter(true);
    }

    private void updateCategoryCounts() {
        for (int i = 0; i < categoryNames.size(); i++) {
            String name = categoryNames.get(i);
            int n = 0;
            for (Channel c : working) if (matches(c, name)) n++;
            View row = categoryRows.get(i);
            ((TextView) row.findViewById(R.id.cat_count)).setText(n == 0 ? "" : numbers.format(n));
            row.setAlpha(n == 0 && !ALL.equals(name) ? 0.45f : 1f);
        }
    }

    private boolean matches(Channel c, String name) {
        switch (name) {
            case ALL:
                return true;
            case FAVOURITES:
                return favourites.contains(c.url);
            case NEPAL:
                return "np".equals(c.country);
            case INDIA:
                return "in".equals(c.country);
            default:
                return c.categories.contains(name);
        }
    }

    // ------------------------------------------------------------- grid

    private void applyFilter(boolean resetPosition) {
        shown.clear();
        for (Channel c : working) if (matches(c, category)) shown.add(c);
        adapter.notifyDataSetChanged();
        if (resetPosition && !shown.isEmpty()) grid.setSelection(0);
        updateEmpty();
    }

    private void updateEmpty() {
        if (!shown.isEmpty()) {
            empty.setVisibility(View.GONE);
            return;
        }
        if (scanning) showEmpty(getString(R.string.empty_scanning));
        else if (FAVOURITES.equals(category)) showEmpty(getString(R.string.empty_favourites));
        else if (!all.isEmpty()) showEmpty(getString(R.string.empty_category));
    }

    private void showEmpty(String text) {
        empty.setText(text);
        empty.setVisibility(View.VISIBLE);
    }

    private void play(int position) {
        if (shown.isEmpty()) return;
        PlayerActivity.start(this, new ArrayList<>(shown), position);
    }

    private void highlight(View view) {
        if (highlighted != null && highlighted != view) setHighlighted(highlighted, false);
        highlighted = view;
        if (view != null) setHighlighted(view, true);
    }

    private static void setHighlighted(View tile, boolean on) {
        tile.setActivated(on);
        View card = tile.findViewById(R.id.card);
        card.animate().scaleX(on ? 1.08f : 1f).scaleY(on ? 1.08f : 1f).setDuration(150).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // After watching, put the selection on the last channel you watched.
        String url = PlayerActivity.lastUrl;
        if (url == null) return;
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).url.equals(url)) {
                grid.setSelection(i);
                grid.requestFocus();
                break;
            }
        }
    }

    private class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Channel getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View tile = convertView != null ? convertView
                    : LayoutInflater.from(MainActivity.this).inflate(R.layout.item_channel, parent, false);
            Channel c = getItem(position);
            ((TextView) tile.findViewById(R.id.name)).setText(
                    favourites.contains(c.url) ? "★ " + c.name : c.name);
            ((TextView) tile.findViewById(R.id.sub)).setText(c.subtitle());
            TextView initials = tile.findViewById(R.id.initials);
            initials.setText(initials(c.name));
            Logos.load(MainActivity.this, (ImageView) tile.findViewById(R.id.logo), c.logo, initials);

            boolean selected = grid.hasFocus() && position == grid.getSelectedItemPosition();
            View card = tile.findViewById(R.id.card);
            card.animate().cancel();
            card.setScaleX(selected ? 1.08f : 1f);
            card.setScaleY(selected ? 1.08f : 1f);
            tile.setActivated(selected);
            if (selected) highlighted = tile;
            else if (highlighted == tile) highlighted = null;
            return tile;
        }
    }

    private static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.split("\\s+")) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.charAt(0))) sb.append(w.charAt(0));
            if (sb.length() == 2) break;
        }
        return sb.toString().toUpperCase(java.util.Locale.US);
    }
}
