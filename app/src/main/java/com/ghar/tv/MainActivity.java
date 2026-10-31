package com.ghar.tv;

import android.app.Activity;
import android.content.Intent;
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

/**
 * Channel browser (the full grid). Categories on the left, working channels on the right.
 *
 * When the app is launched and you have watched something before, this screen hands over
 * to the player straight away, so the last channel resumes without any browsing.
 */
public class MainActivity extends Activity implements Repo.Listener {

    /** Opened on purpose from the player ("All channels"), not as the app's start. */
    static final String EXTRA_BROWSE = "browse";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NumberFormat numbers = NumberFormat.getIntegerInstance();

    private Repo repo;
    private final List<Channel> shown = new ArrayList<>();
    private Set<String> favourites = new HashSet<>();

    private String category = Repo.ALL;
    private final List<String> categoryNames = new ArrayList<>();
    private Taps taps;
    private final List<View> categoryRows = new ArrayList<>();
    private boolean browseMode;
    private boolean positionedOnLast;

    private TextView status;
    private TextView empty;
    private GridView grid;
    private LinearLayout categoryList;
    private ChannelAdapter adapter;
    private View highlighted;

    private String pendingCategory;
    private final Runnable selectPendingCategory = () -> {
        if (pendingCategory != null) selectCategory(pendingCategory);
    };

    /** Opens the browser from inside the player. */
    static void browse(Activity from) {
        from.startActivity(new Intent(from, MainActivity.class)
                .putExtra(EXTRA_BROWSE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        browseMode = getIntent().getBooleanExtra(EXTRA_BROWSE, false);

        // App launch with a saved session: go straight back to the last channel.
        if (!browseMode && savedInstanceState == null && Session.lastChannel(this) != null) {
            PlayerActivity.resume(this);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        repo = Repo.get(this);
        status = findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        grid = findViewById(R.id.grid);
        categoryList = findViewById(R.id.categories);
        favourites = Store.favourites(this);
        category = Session.category(this);
        categoryNames.addAll(Repo.categoryNames(this));
        if (!categoryNames.contains(category) || Repo.isAction(category)) category = Repo.ALL;
        taps = new Taps(Device.isTv(this));
        if (!Device.isTv(this)) ((TextView) findViewById(R.id.hint)).setText(R.string.hint_phone);

        findViewById(R.id.rescan).setOnClickListener(v -> confirmRescan());

        adapter = new ChannelAdapter();
        grid.setAdapter(adapter);
        // Tap = watch; double-tap (phone) = favourite; hold OK / long-press = options.
        grid.setOnItemClickListener((p, v, position, id) ->
                taps.tap(position, () -> play(position), () -> quickFavourite(position)));
        grid.setOnItemLongClickListener((p, v, position, id) -> {
            taps.cancel();
            Menus.channel(this, shown.get(position), category, this::channelsChanged);
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
        repo.addListener(this);
        onRepoChanged();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (repo != null) repo.screenStarted();
    }

    @Override
    protected void onStop() {
        if (repo != null) repo.screenStopped();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (taps != null) taps.cancel();
        if (repo != null) repo.removeListener(this);
        super.onDestroy();
    }

    /** Back from the browser returns to the channel you were watching (if any); otherwise exits. */
    @Override
    public void onBackPressed() {
        if (browseMode && Session.lastChannel(this) != null) {
            PlayerActivity.resume(this);
            finish();
        } else {
            repo.exitNow();
            finish();
        }
    }

    private void quickFavourite(int position) {
        if (position >= shown.size()) return;
        Channel c = shown.get(position);
        boolean added = Store.toggleFavourite(this, c.url);
        toast(getString(added ? R.string.fav_added : R.string.fav_removed, c.name));
        channelsChanged();
    }

    /** Favourites or playlists changed: refresh counts, the list, and the category names. */
    private void channelsChanged() {
        favourites = Store.favourites(this);
        List<String> names = Repo.categoryNames(this);
        if (!names.equals(categoryNames)) {
            categoryNames.clear();
            categoryNames.addAll(names);
            if (!categoryNames.contains(category)) category = Repo.ALL;
            buildCategories();
        }
        updateCategoryCounts();
        applyFilter(false);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------- repo

    @Override
    public void onRepoChanged() {
        updateCategoryCounts();
        applyFilter(false);
        if (repo.loadFailed) {
            status.setText(R.string.list_error);
            showEmpty(getString(R.string.list_error_detail));
        } else if (!repo.loaded) {
            status.setText(R.string.loading_list);
        } else if (repo.scanning) {
            status.setText(getString(R.string.scanning, numbers.format(repo.checked.get()),
                    numbers.format(repo.toCheck), numbers.format(repo.working.size())));
        } else {
            showDoneStatus();
        }
        // Coming from the player: put the selection on the channel you were watching.
        if (browseMode && !positionedOnLast && !shown.isEmpty()) {
            Channel last = Session.lastChannel(this);
            int i = last == null ? -1 : Repo.indexOf(shown, last.url);
            if (i >= 0) {
                grid.setSelection(i);
                grid.requestFocus();
                positionedOnLast = true;
            }
        }
    }

    private void confirmRescan() {
        Menus.confirmRescan(this, repo.all.size(), repo::fullRescan);
    }

    private void showDoneStatus() {
        long time = repo.cache.time;
        if (time == 0) {
            status.setText("");
            return;
        }
        long hours = (System.currentTimeMillis() - time) / 3_600_000L;
        String when = hours < 1 ? getString(R.string.just_now)
                : hours < 48 ? getString(R.string.hours_ago, hours)
                : getString(R.string.days_ago, hours / 24);
        String text = getString(R.string.done, numbers.format(repo.working.size()), when);
        if (!repo.cache.complete) text += getString(R.string.scan_paused);
        status.setText(text);
    }

    // -------------------------------------------------------- categories

    private void buildCategories() {
        categoryList.removeAllViews();
        categoryRows.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String name : categoryNames) {
            View row = inflater.inflate(R.layout.item_category, categoryList, false);
            ((TextView) row.findViewById(R.id.cat_name)).setText(Repo.label(this, name));
            boolean isNew = Repo.isAction(name);
            row.setOnFocusChangeListener((v, has) -> {
                if (has && !isNew) {
                    pendingCategory = name;
                    handler.removeCallbacks(selectPendingCategory);
                    handler.postDelayed(selectPendingCategory, 300);
                }
            });
            row.setOnClickListener(v -> {
                if (Repo.SEARCH_ROW.equals(name)) {
                    askSearch();
                    return;
                }
                if (isNew) {
                    Menus.newPlaylist(this, null, this::channelsChanged);
                    return;
                }
                selectCategory(name);
                grid.requestFocus();
            });
            if (Repo.isPlaylist(name)) {
                row.setOnLongClickListener(v -> {
                    Menus.playlist(this, name, this::channelsChanged);
                    return true;
                });
            }
            categoryList.addView(row);
            categoryRows.add(row);
        }
        markCategory();
    }

    private void askSearch() {
        String current = Repo.isSearch(category) ? category.substring(Repo.SEARCH.length()) : "";
        Menus.search(this, current, q -> {
            selectCategory(Repo.SEARCH + q);
            status.setText(getString(R.string.search_results, q, shown.size()));
            grid.requestFocus();
        });
    }

    private void selectCategory(String name) {
        if (name.equals(category)) return;
        category = name;
        Session.setCategory(this, name);
        markCategory();
        applyFilter(true);
    }

    private void markCategory() {
        for (int i = 0; i < categoryRows.size(); i++) {
            categoryRows.get(i).setActivated(categoryNames.get(i).equals(category));
        }
    }

    private void updateCategoryCounts() {
        for (int i = 0; i < categoryNames.size(); i++) {
            String name = categoryNames.get(i);
            int n = repo.count(name, favourites);
            View row = categoryRows.get(i);
            ((TextView) row.findViewById(R.id.cat_count)).setText(n == 0 ? "" : numbers.format(n));
            boolean dim = n == 0 && !Repo.ALL.equals(name) && !Repo.isAction(name)
                    && !Repo.isPlaylist(name);
            row.setAlpha(dim ? 0.45f : 1f);
        }
    }

    // ------------------------------------------------------------- grid

    private void applyFilter(boolean resetPosition) {
        shown.clear();
        shown.addAll(repo.channels(category, favourites));
        adapter.notifyDataSetChanged();
        if (resetPosition && !shown.isEmpty()) grid.setSelection(0);
        updateEmpty();
    }

    private void updateEmpty() {
        if (!shown.isEmpty()) {
            empty.setVisibility(View.GONE);
            return;
        }
        if (repo.scanning || !repo.loaded) showEmpty(getString(R.string.empty_scanning));
        else if (Repo.FAVOURITES.equals(category)) showEmpty(getString(R.string.empty_favourites));
        else if (Repo.RECENT.equals(category)) showEmpty(getString(R.string.empty_recent));
        else if (Repo.isPlaylist(category)) showEmpty(getString(R.string.empty_playlist));
        else if (Repo.isSearch(category)) showEmpty(getString(R.string.search_none, category.substring(Repo.SEARCH.length())));
        else showEmpty(getString(R.string.empty_category));
    }

    private void showEmpty(String text) {
        empty.setText(text);
        empty.setVisibility(View.VISIBLE);
    }

    /** Watching replaces this screen, so Back from the player leaves the app cleanly. */
    private void play(int position) {
        if (shown.isEmpty()) return;
        PlayerActivity.start(this, new ArrayList<>(shown), position, category);
        finish();
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

    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.split("\\s+")) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.charAt(0))) sb.append(w.charAt(0));
            if (sb.length() == 2) break;
        }
        return sb.toString().toUpperCase(java.util.Locale.US);
    }
}
