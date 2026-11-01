package com.ghar.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The heart of the app: watch and browse in one screen.
 *
 * TV / phone sideways: full-screen video. Bottom bar = channel name, programme, clock, controls.
 *   Star (favourite) at the top. Left/Right opens the channel panel: categories + channels,
 *   so you can browse without stopping what's playing.
 *   Remote: Up/Down and CH+/CH- switch channel, OK info, hold OK favourite,
 *   Menu options (subtitles, screen mode, playlists), Back close/exit.
 *
 * Phone upright: YouTube-style watch page - video on top, then channel info with clock and
 *   star, category chips (Last Watched, Favourites, All, Nepal...) and the channel list.
 *   Swipe up/down on the video changes channel; tap the video for controls.
 *
 * One player for the whole screen; channels are swapped inside it, never a second stream.
 */
public class PlayerActivity extends Activity implements Repo.Listener {

    private static final long START_TIMEOUT_MS = 12_000;
    private static final long INFO_VISIBLE_MS = 4_000;
    private static final int MAX_RECONNECTS = 3;
    private static final int REQUEST_FROM_PHONE = 41;

    /** Fit, Fill (crop), Stretch, 16:9 and 4:3 (the last two: a box of that shape, picture stretched into it). */
    private static final int[] RESIZE_MODES = {
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
    };
    private static final int[] MODE_LABELS = {R.string.mode_fit, R.string.mode_fill, R.string.mode_stretch,
            R.string.mode_wide, R.string.mode_classic};

    /** Phone only: follow how the phone is held, or lock it. */
    private static final int[] ORIENTATIONS = {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
    };
    private static final int[] ORIENTATION_LABELS = {R.string.rotate_auto, R.string.rotate_portrait, R.string.rotate_landscape};

    private static List<Channel> pendingQueue;
    private static int pendingIndex;
    private static String pendingCategory;
    /** Opened with one channel (e.g. from Ghar Launcher): fill in its category once loaded. */
    private static boolean pendingExpand;
    private long playStartedAt;
    private boolean startRecorded;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Repo repo;

    // What's playing
    private List<Channel> queue;
    private int index;
    private String category;
    private int direction = 1;
    private boolean ready;
    private int reconnects;
    private boolean restoring;
    private String programme;

    // What's being browsed (panel / watch page); becomes the queue when you pick a channel
    private final List<String> categoryNames = new ArrayList<>();
    private Taps taps;
    private String browseCategory;
    private final List<Channel> browseList = new ArrayList<>();
    private java.util.Set<String> favs = new java.util.HashSet<>();

    // Previous channel (flip back and forth)
    private String nowUrl;
    private String previousUrl;
    private long lastOkAt;

    // Sleep timer
    private long sleepAt;          // elapsedRealtime when playback stops; 0 = off
    private boolean sleepWarning;  // the last minute: any button keeps watching

    private boolean isTv;
    private boolean watchPage;   // phone upright
    private int screenMode;
    private int orientation;
    private boolean touchOnControl;
    private boolean gestureInVideo;
    /** Where a swipe started on the video: left = brightness, middle = channels, right = volume. */
    private int zone;
    private static final int ZONE_LEFT = 0, ZONE_MIDDLE = 1, ZONE_RIGHT = 2;
    private float volumeCarry;
    private View levelBox;
    private TextView levelLabel;
    private ProgressBar levelBar;
    private final Runnable hideLevel = () -> levelBox.setVisibility(View.GONE);
    private View jokeBox;
    private TextView jokeText;
    /** Only for slow starts: fast channels never flash a joke. */
    private final Runnable showJoke = () -> {
        jokeText.setText(Jokes.next(this));
        jokeBox.setAlpha(0f);
        jokeBox.setVisibility(View.VISIBLE);
        jokeBox.animate().alpha(1f).setDuration(250).start();
    };
    /** Warms up the next channel's connection (one small request, never a second video). */
    private final java.util.concurrent.ExecutorService warmer = java.util.concurrent.Executors.newSingleThreadExecutor();

    private ExoPlayer player;
    private PlayerView playerView;
    private View videoArea;
    private View below;
    private View info;
    private View infoTexts;
    private View topBar;
    private TextView number;
    private TextView name;
    private TextView sub;
    private ImageView logo;
    private View clock;
    private ProgressBar spinner;
    private TextView statusText;
    private TextView message;
    private View controls;
    private View channelsButton;
    private TextView modeButton;
    private TextView rotateButton;
    private TextView favButton;
    private View panel;
    private LinearLayout panelCategories;
    private ListView panelList;
    private ImageView belowLogo;
    private TextView belowName;
    private TextView belowSub;
    private TextView belowFav;
    private LinearLayout belowChips;
    private ListView belowList;
    private ChannelListAdapter panelAdapter;
    private ChannelListAdapter belowAdapter;
    private final List<View> panelCategoryRows = new ArrayList<>();
    private final List<View> chipViews = new ArrayList<>();
    private GestureDetector gestures;

    /** Didn't start in time: skip it, unless we're simply offline. */
    private final Runnable startTimeout = () -> {
        if (!Device.isOnline(this)) waitForNetwork();
        else skip(getString(R.string.skip_slow));
    };
    private final Runnable hideInfo = this::hideInfoNow;
    private final Runnable hideMessage = () -> message.setVisibility(View.GONE);
    private final Runnable retry = this::restartStream;
    private String pendingPanelCategory;
    private final Runnable selectPanelCategory = () -> {
        if (pendingPanelCategory != null) setBrowseCategory(pendingPanelCategory, false);
    };

    /** Watch a channel picked in the browser. */
    static void start(Activity from, List<Channel> channels, int index, String category) {
        pendingQueue = channels;
        pendingIndex = index;
        pendingCategory = category;
        from.startActivity(new Intent(from, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    /** Play one channel now; channel up/down follows {@code category} once the list has loaded. */
    static void startExpanding(Activity from, Channel channel, String category) {
        List<Channel> one = new ArrayList<>();
        one.add(channel);
        pendingQueue = one;
        pendingIndex = 0;
        pendingCategory = category;
        pendingExpand = true;
        from.startActivity(new Intent(from, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    /** Re-open the last-watched channel (app launch, or Back from the browser). */
    static void resume(Activity from) {
        pendingQueue = null;
        from.startActivity(new Intent(from, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    // =================================================================== setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isTv = Device.isTv(this);
        repo = Repo.get(this);
        if (pendingQueue != null && !pendingQueue.isEmpty()) {
            queue = new ArrayList<>(pendingQueue);
            index = pendingIndex;
            category = pendingCategory != null ? pendingCategory : Session.category(this);
            restoring = pendingExpand;
        } else {
            // Restore the session, also after Android has killed the app.
            Channel last = Session.lastChannel(this);
            if (last == null) {
                startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_BROWSE, true));
                finish();
                return;
            }
            queue = new ArrayList<>();
            queue.add(last);
            index = 0;
            category = Session.category(this);
            restoring = true;
        }
        categoryNames.addAll(Repo.categoryNames(this));
        if ((!categoryNames.contains(category) && !Repo.isSearch(category)) || Repo.isAction(category)) category = Repo.ALL;
        taps = new Taps(isTv);
        pendingQueue = null;
        pendingCategory = null;
        pendingExpand = false;

        setContentView(R.layout.activity_player);
        findViews();
        wireControls();
        buildCategoryViews();

        playerView.setUseController(false);
        screenMode = Store.screenMode(this);
        applyScreenMode(false);
        findViewById(R.id.video_area).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(() -> applyScreenMode(false)); // 16:9 / 4:3 box
        });
        if (!isTv) {
            orientation = Store.orientation(this);
            applyOrientation(false);
        }
        setupGestures();

        player = buildPlayer();
        playerView.setPlayer(player);
        player.addListener(new PlayerEvents());
        applySubtitlePreference();

        // Top up the joke file later, away from the channel start.
        handler.postDelayed(() -> Jokes.refreshIfNeeded(this), 15_000);

        repo.addListener(this);
        browseCategory = category;
        markBrowseCategory();
        onRepoChanged();
        applyPageLayout();
        play(index);
    }

    private void findViews() {
        videoArea = findViewById(R.id.video_area);
        below = findViewById(R.id.below);
        playerView = findViewById(R.id.player_view);
        info = findViewById(R.id.info);
        infoTexts = findViewById(R.id.info_texts);
        topBar = findViewById(R.id.top_bar);
        number = findViewById(R.id.number);
        name = findViewById(R.id.channel_name);
        sub = findViewById(R.id.channel_sub);
        logo = findViewById(R.id.channel_logo);
        clock = findViewById(R.id.clock);
        spinner = findViewById(R.id.spinner);
        statusText = findViewById(R.id.status_text);
        message = findViewById(R.id.message);
        controls = findViewById(R.id.controls);
        channelsButton = findViewById(R.id.channels_button);
        modeButton = findViewById(R.id.mode_button);
        rotateButton = findViewById(R.id.rotate_button);
        favButton = findViewById(R.id.fav_button);
        panel = findViewById(R.id.panel);
        panelCategories = findViewById(R.id.panel_categories);
        panelList = findViewById(R.id.panel_list);
        belowLogo = findViewById(R.id.below_logo);
        belowName = findViewById(R.id.below_name);
        belowSub = findViewById(R.id.below_sub);
        belowFav = findViewById(R.id.below_fav);
        belowChips = findViewById(R.id.below_chips);
        belowList = findViewById(R.id.below_list);
        levelBox = findViewById(R.id.level_box);
        levelLabel = findViewById(R.id.level_label);
        levelBar = findViewById(R.id.level_bar);
        jokeBox = findViewById(R.id.joke_box);
        jokeText = findViewById(R.id.joke_text);
    }

    private void wireControls() {
        ((TextView) findViewById(R.id.hint)).setText(isTv ? R.string.player_hint_tv : R.string.player_hint_phone);
        findViewById(R.id.fav_hint).setVisibility(isTv ? View.VISIBLE : View.GONE);
        controls.setVisibility(isTv ? View.GONE : View.VISIBLE);
        channelsButton.setOnClickListener(v -> openPanel());
        modeButton.setOnClickListener(v -> cycleScreenMode());
        rotateButton.setOnClickListener(v -> cycleOrientation());
        findViewById(R.id.cc_button).setOnClickListener(v -> chooseSubtitles());
        favButton.setOnClickListener(v -> toggleFavourite(current()));
        belowFav.setOnClickListener(v -> toggleFavourite(current()));
        findViewById(R.id.panel_scrim).setOnClickListener(v -> closePanel());
        findViewById(R.id.browse_button).setOnClickListener(v -> openBrowser());
        findViewById(R.id.search_button).setOnClickListener(v -> openLiveSearch());
        findViewById(R.id.panel_settings).setOnClickListener(v -> showOptions()); // remotes without a Menu key
        findViewById(R.id.more_button).setOnClickListener(v -> showOptions());

        // Tap = watch; double-tap (phone) = favourite; hold OK / long-press = options & playlists.
        panelAdapter = new ChannelListAdapter();
        panelList.setAdapter(panelAdapter);
        panelList.setOnItemClickListener((p, v, position, id) -> onChannelTap(position));
        panelList.setOnItemLongClickListener((p, v, position, id) -> onChannelHold(position));
        // Browsing a category: Up/Down stay inside its channel list (no jumping to other
        // categories or buttons at the ends); Left goes to the category you are in.
        panelList.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pos = panelList.getSelectedItemPosition();
            int last = panelList.getCount() - 1;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && pos >= last) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && pos <= 0) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                View row = activeCategoryRow();
                if (row != null) {
                    row.requestFocus();
                    return true;
                }
            }
            return false;
        });

        belowAdapter = new ChannelListAdapter();
        belowList.setAdapter(belowAdapter);
        belowList.setOnItemClickListener((p, v, position, id) -> onChannelTap(position));
        belowList.setOnItemLongClickListener((p, v, position, id) -> onChannelHold(position));
    }

    private void onChannelTap(int position) {
        taps.tap(position, () -> pickFromBrowse(position), () -> {
            if (position < browseList.size()) toggleFavourite(browseList.get(position));
        });
    }

    private boolean onChannelHold(int position) {
        taps.cancel();
        if (position < browseList.size()) {
            Menus.channel(this, browseList.get(position), browseCategory, this::channelsChanged);
        }
        return true;
    }

    /** Favourites or playlists changed somewhere: refresh names, lists and the star. */
    private void channelsChanged() {
        List<String> names = Repo.categoryNames(this);
        if (!names.equals(categoryNames)) {
            categoryNames.clear();
            categoryNames.addAll(names);
            if (!categoryNames.contains(browseCategory)) browseCategory = Repo.ALL;
            if (!categoryNames.contains(category)) category = Repo.ALL;
            buildCategoryViews();
            markBrowseCategory();
        }
        refreshBrowse(false);
        updateStar();
    }

    /** Category chips (watch page) and category rows (panel). */
    private void buildCategoryViews() {
        belowChips.removeAllViews();
        panelCategories.removeAllViews();
        chipViews.clear();
        panelCategoryRows.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String n : categoryNames) {
            boolean isNew = Repo.isAction(n);
            String label = Repo.label(this, n);
            View.OnClickListener click = v -> {
                if (Repo.SEARCH_ROW.equals(n)) openLiveSearch();
                else if (Repo.NEW_PLAYLIST.equals(n)) Menus.newPlaylist(this, null, this::channelsChanged);
                else setBrowseCategory(n, true);
            };
            View.OnLongClickListener hold = v -> {
                Menus.playlist(this, n, this::channelsChanged);
                return true;
            };

            View chip = inflater.inflate(R.layout.item_chip, belowChips, false);
            ((TextView) chip.findViewById(R.id.cat_name)).setText(label);
            chip.setOnClickListener(click);
            if (Repo.isPlaylist(n)) chip.setOnLongClickListener(hold);
            belowChips.addView(chip);
            chipViews.add(chip);

            View row = inflater.inflate(R.layout.item_panel_category, panelCategories, false);
            ((TextView) row.findViewById(R.id.cat_name)).setText(label);
            row.setOnFocusChangeListener((v, has) -> {
                if (has && !isNew) {
                    pendingPanelCategory = n;
                    handler.removeCallbacks(selectPanelCategory);
                    handler.postDelayed(selectPanelCategory, 300);
                }
            });
            row.setOnClickListener(v -> {
                click.onClick(v);
                if (!isNew) panelList.requestFocus();
            });
            if (Repo.isPlaylist(n)) row.setOnLongClickListener(hold);
            panelCategories.addView(row);
            panelCategoryRows.add(row);
        }
    }

    /**
     * One player for the whole screen.
     * - Starts as soon as ~2 s of video is buffered, then keeps up to 60 s in reserve.
     * - Starts at a modest quality and adapts up/down to the connection (HLS adaptive streaming).
     * - Hardware video decoding, falling back to another decoder if the first one fails.
     */
    private ExoPlayer buildPlayer() {
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 2_000, 4_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        DefaultBandwidthMeter bandwidth = new DefaultBandwidthMeter.Builder(this)
                .setInitialBitrateEstimate(800_000)
                .build();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);
        return new ExoPlayer.Builder(this, renderers)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidth)
                .build();
    }

    private class PlayerEvents implements Player.Listener {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) {
                ready = true;
                reconnects = 0;
                handler.removeCallbacks(startTimeout);
                Channel now = current();
                if (!startRecorded && now != null) {
                    startRecorded = true; // remember how fast this channel starts
                    Stats.playbackStarted(PlayerActivity.this, now.url,
                            System.currentTimeMillis() - playStartedAt);
                }
                spinner.setVisibility(View.GONE);
                statusText.setVisibility(View.GONE);
                hideJoke();
                repo.setPlaybackActive(true);
            } else if (state == Player.STATE_BUFFERING) {
                spinner.setVisibility(View.VISIBLE);
                showStatusRes(ready ? R.string.status_buffering : R.string.status_connecting);
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            // Keep the screen awake only while video is actually playing (saves battery).
            if (isPlaying) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        /** Some streams carry the current programme's title; show it when they do. */
        @Override
        public void onMediaMetadataChanged(MediaMetadata md) {
            CharSequence t = md.title != null ? md.title : md.displayTitle;
            Channel c = current();
            if (t == null || c == null) return;
            String title = t.toString().trim();
            if (title.isEmpty() || title.equalsIgnoreCase(c.name)) return;
            programme = title;
            showNowPlaying(c);
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            handlePlaybackError(error);
        }
    }

    // =============================================================== lifecycle

    /** A different channel was picked in the browser while this screen was open. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (pendingQueue != null && !pendingQueue.isEmpty()) {
            queue = new ArrayList<>(pendingQueue);
            index = pendingIndex;
            if (pendingCategory != null) category = pendingCategory;
            restoring = pendingExpand;
            pendingQueue = null;
            pendingCategory = null;
            pendingExpand = false;
            play(index);
            onRepoChanged();
        }
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
        // Live TV: when you leave (Home button, another app), stop and release the stream.
        // The session is already saved, so the next launch continues this channel.
        if (!isFinishing()) {
            releasePlayer();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (taps != null) taps.cancel();
        warmer.shutdownNow();
        if (repo != null) repo.removeListener(this);
        releasePlayer();
        super.onDestroy();
    }

    private void releasePlayer() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        if (repo != null) repo.setPlaybackActive(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Back: close the channel panel if open; otherwise stop everything and leave the app. */
    @Override
    public void onBackPressed() {
        if (isPanelOpen()) {
            closePanel();
            return;
        }
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        repo.exitNow();
        finish();
    }

    /** Full-screen grid of all channels; Back there returns here. */
    private void openBrowser() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        MainActivity.browse(this);
        finish();
    }

    /** Rotating doesn't restart the stream: re-arrange the screen instead. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Resources r = getResources();
        int side = r.getDimensionPixelSize(R.dimen.info_side);
        info.setPadding(side, info.getPaddingTop(), side, info.getPaddingBottom());
        topBar.setPadding(side, topBar.getPaddingTop(), side, topBar.getPaddingBottom());
        ViewGroup.LayoutParams lp = logo.getLayoutParams();
        lp.width = r.getDimensionPixelSize(R.dimen.info_logo_w);
        lp.height = r.getDimensionPixelSize(R.dimen.info_logo_h);
        logo.setLayoutParams(lp);
        View box = findViewById(R.id.panel_box);
        ViewGroup.LayoutParams bp = box.getLayoutParams();
        bp.width = r.getDimensionPixelSize(R.dimen.panel_width);
        box.setLayoutParams(bp);
        jokeText.setMaxWidth(r.getDimensionPixelSize(R.dimen.joke_width));
        jokeText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, r.getDimension(R.dimen.joke_text));
        ViewGroup.MarginLayoutParams jp = (ViewGroup.MarginLayoutParams) jokeBox.getLayoutParams();
        jp.topMargin = r.getDimensionPixelSize(R.dimen.joke_offset);
        jokeBox.setLayoutParams(jp);
        applyPageLayout();
    }

    /** Phone upright = watch page (video on top, list below). Everything else = full-screen video. */
    private void applyPageLayout() {
        watchPage = !isTv && getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        ViewGroup.LayoutParams lp = videoArea.getLayoutParams();
        if (watchPage) {
            lp.height = getResources().getDisplayMetrics().widthPixels * 9 / 16;
            closePanel();
        } else {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        videoArea.setLayoutParams(lp);
        below.setVisibility(watchPage ? View.VISIBLE : View.GONE);
        // On the watch page the details are shown below the video, so the overlay keeps only controls.
        int detail = watchPage ? View.GONE : View.VISIBLE;
        number.setVisibility(detail);
        logo.setVisibility(detail);
        infoTexts.setVisibility(watchPage ? View.INVISIBLE : View.VISIBLE);
        clock.setVisibility(detail);
        channelsButton.setVisibility(watchPage ? View.GONE : View.VISIBLE);
        if (watchPage) scrollBelowToCurrent();
        if (!isTv) hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !isTv) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ==================================================================== data

    /** Once the channel list has loaded, a restored session gets its full category back. */
    @Override
    public void onRepoChanged() {
        if (restoring && repo.loaded && queue != null && !queue.isEmpty()) {
            List<Channel> list = repo.channels(category, Store.favourites(this));
            Channel cur = current();
            int i = Repo.indexOf(list, cur.url);
            if (i < 0) {
                list.add(0, cur); // keep what's playing in the list
                i = 0;
            }
            queue = list;
            index = i;
            restoring = false;
            number.setText(String.valueOf(index + 1));
        }
        refreshBrowse(false);
        if (!repo.favouritesBackShown && repo.favouritesBack > 0) {
            repo.favouritesBackShown = true;
            favs = Store.favourites(this);
            showMessage(repo.favouritesBack == 1 ? getString(R.string.favourite_back_one)
                    : getString(R.string.favourites_back, repo.favouritesBack));
        }
    }

    private Channel current() {
        return queue == null || queue.isEmpty() ? null : queue.get(Math.min(index, queue.size() - 1));
    }

    private void setBrowseCategory(String name, boolean scrollToCurrent) {
        if (!name.equals(browseCategory)) {
            browseCategory = name;
            markBrowseCategory();
            refreshBrowse(true);
        }
        if (scrollToCurrent) scrollListsToCurrent();
    }

    private View activeCategoryRow() {
        int i = categoryNames.indexOf(browseCategory);
        return i >= 0 && i < panelCategoryRows.size() ? panelCategoryRows.get(i) : null;
    }

    /** Number of channels in each category, on the chips and the panel rows. */
    private void updateCategoryCounts() {
        java.text.NumberFormat nf = java.text.NumberFormat.getIntegerInstance();
        for (int i = 0; i < categoryNames.size() && i < chipViews.size(); i++) {
            String key = categoryNames.get(i);
            String text = Repo.NEW_PLAYLIST.equals(key) ? "" : nf.format(repo.count(key, favs));
            ((TextView) chipViews.get(i).findViewById(R.id.cat_count)).setText(text);
            ((TextView) panelCategoryRows.get(i).findViewById(R.id.cat_count)).setText(text);
        }
    }

    private void markBrowseCategory() {
        for (int i = 0; i < categoryNames.size(); i++) {
            boolean on = categoryNames.get(i).equals(browseCategory);
            chipViews.get(i).setActivated(on);
            panelCategoryRows.get(i).setActivated(on);
        }
    }

    private void refreshBrowse(boolean toTop) {
        favs = Store.favourites(this);
        browseList.clear();
        browseList.addAll(repo.channels(browseCategory, favs));
        updateCategoryCounts();
        TextView title = findViewById(R.id.panel_title);
        if (Repo.isSearch(browseCategory)) {
            title.setText(getString(R.string.search_results,
                    browseCategory.substring(Repo.SEARCH.length()), browseList.size()));
        } else {
            title.setText(R.string.channels);
        }
        panelAdapter.notifyDataSetChanged();
        belowAdapter.notifyDataSetChanged();
        if (toTop) {
            panelList.setSelection(0);
            belowList.setSelection(0);
        }
    }

    private void scrollListsToCurrent() {
        Channel c = current();
        int i = c == null ? -1 : Repo.indexOf(browseList, c.url);
        if (i >= 0) {
            panelList.setSelection(i);
            belowList.setSelection(Math.max(0, i - 1));
        }
    }

    private void scrollBelowToCurrent() {
        belowList.post(this::scrollListsToCurrent);
    }

    /** A channel picked from the panel or watch page: that category becomes the channel order. */
    private void pickFromBrowse(int position) {
        if (position < 0 || position >= browseList.size()) return;
        queue = new ArrayList<>(browseList);
        index = position;
        category = browseCategory;
        restoring = false;
        direction = 1;
        closePanel();
        play(index);
    }

    // ================================================================== input

    private void setupGestures() {
        float minSwipe = 60 * getResources().getDisplayMetrics().density;
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            /** Edges: drag up/down for brightness (left) or volume (right). */
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (e1 == null || isPanelOpen() || zone == ZONE_MIDDLE) return false;
                if (Math.abs(e2.getY() - e1.getY()) < Math.abs(e2.getX() - e1.getX())) return false;
                float delta = distanceY / Math.max(1, videoArea.getHeight()) * 1.3f; // up = more
                if (zone == ZONE_LEFT) changeBrightness(delta);
                else changeVolume(delta);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || isPanelOpen() || zone != ZONE_MIDDLE) return false;
                float dy = e2.getY() - e1.getY();
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dy) < minSwipe || Math.abs(dy) < Math.abs(dx)) return false;
                if (dy < 0) next();   // swipe up
                else previous();      // swipe down
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!touchOnControl && !isPanelOpen()) toggleInfo();
                return true;
            }
        });
    }

    /** Gestures only on the video itself, so the watch-page list below scrolls normally. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (isPanelOpen()) return super.dispatchTouchEvent(ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            gestureInVideo = isInside(videoArea, ev);
            if (gestureInVideo) {
                int[] loc = new int[2];
                videoArea.getLocationOnScreen(loc);
                float x = (ev.getRawX() - loc[0]) / Math.max(1, videoArea.getWidth());
                zone = x < 0.33f ? ZONE_LEFT : x > 0.67f ? ZONE_RIGHT : ZONE_MIDDLE;
                volumeCarry = 0;
            }
        }
        if (!gestureInVideo) return super.dispatchTouchEvent(ev);
        boolean handled = super.dispatchTouchEvent(ev); // buttons get their taps
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) touchOnControl = handled;
        gestures.onTouchEvent(ev);
        return true;
    }

    private static boolean isInside(View v, MotionEvent ev) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float x = ev.getRawX();
        float y = ev.getRawY();
        return x >= loc[0] && x < loc[0] + v.getWidth() && y >= loc[1] && y < loc[1] + v.getHeight();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Last minute of the sleep timer: any button means "still watching".
        if (sleepWarning && event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) keepWatching();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !isPanelOpen()) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_LAST_CHANNEL || event.getKeyCode() == KeyEvent.KEYCODE_0) {
                if (event.getRepeatCount() == 0) flipToPrevious();
                return true;
            }
            switch (event.getKeyCode()) {
                // TV remote: Up / Down change channel, Left / Right open the channel menu.
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (event.getRepeatCount() == 0) next();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (event.getRepeatCount() == 0) previous();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (event.getRepeatCount() == 0) openPanel();
                    return true;
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_PAGE_UP:
                    next();
                    return true;
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    previous();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    // Press = info; press twice quickly = previous channel; hold = add/remove favourite.
                    if (event.getRepeatCount() == 0) {
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastOkAt < 450) {
                            lastOkAt = 0;
                            flipToPrevious();
                        } else {
                            lastOkAt = now;
                            showInfo();
                        }
                    } else if (event.getRepeatCount() == 1) {
                        lastOkAt = 0;
                        toggleFavourite(current());
                    }
                    return true;
                case KeyEvent.KEYCODE_INFO:
                    showInfo();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    showOptions();
                    return true;
                case KeyEvent.KEYCODE_CAPTIONS:
                    chooseSubtitles();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ================================================================ back key

    /** Back from "Get from phone": new favourites and playlists show up straight away. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FROM_PHONE && resultCode == RESULT_OK) {
            favs = Store.favourites(this);
            channelsChanged();
            refreshBrowse(false);
        }
    }

    /** Back: tracked, so holding it can open the search instead (see onKeyLongPress). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Hold Back: live channel search. (A short press still goes back.) */
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            searchOnRelease = true; // open it when Back is let go, or the release would close the keyboard
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    private boolean searchOnRelease;

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && searchOnRelease) {
            searchOnRelease = false;
            openLiveSearch();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void openLiveSearch() {
        SearchDialog.show(this, repo, (results, i, q) -> {
            if (results.isEmpty()) return;
            queue = new ArrayList<>(results);
            index = i;
            category = q.isEmpty() ? Repo.RECENT : Repo.SEARCH + q;
            restoring = false;
            direction = 1;
            if (isPanelOpen()) closePanel();
            play(index);
        });
    }

    // ======================================================== previous channel

    /** Flip back to the channel you watched before this one (and again to come back). */
    private void flipToPrevious() {
        if (previousUrl == null) {
            showMessage(getString(R.string.no_previous));
            return;
        }
        int i = Repo.indexOf(queue, previousUrl);
        if (i < 0) {
            Channel c = repo.find(previousUrl);
            if (c == null) {
                for (Channel r : Session.recent(this)) if (r.url.equals(previousUrl)) c = r;
            }
            if (c == null) {
                showMessage(getString(R.string.no_previous));
                return;
            }
            queue.add(index + 1, c);
            i = index + 1;
        }
        direction = i >= index ? 1 : -1;
        play(i);
    }

    // ============================================================ sleep timer

    private static final int[] SLEEP_MINUTES = {0, 15, 30, 60, 90, 120};
    private static final long SLEEP_WARNING_MS = 60_000;

    private String sleepLabel() {
        if (sleepAt == 0) return getString(R.string.sleep_off);
        long left = sleepAt - android.os.SystemClock.elapsedRealtime();
        return getString(R.string.sleep_left, Math.max(1, (int) Math.ceil(left / 60_000.0)));
    }

    private void chooseSleepTimer() {
        String[] labels = new String[SLEEP_MINUTES.length];
        for (int i = 0; i < SLEEP_MINUTES.length; i++) {
            labels[i] = SLEEP_MINUTES[i] == 0 ? getString(R.string.sleep_off)
                    : getString(R.string.sleep_minutes, SLEEP_MINUTES[i]);
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(getString(R.string.option_sleep, sleepLabel()))
                .setItems(labels, (d, w) -> setSleepTimer(SLEEP_MINUTES[w]))
                .show();
    }

    private void setSleepTimer(int minutes) {
        handler.removeCallbacks(sleepTick);
        sleepWarning = false;
        if (minutes <= 0) {
            sleepAt = 0;
            showMessage(getString(R.string.sleep_cancelled));
            return;
        }
        Store.setSleepChoice(this, minutes);
        sleepAt = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L;
        handler.postDelayed(sleepTick, Math.max(0, minutes * 60_000L - SLEEP_WARNING_MS));
        showMessage(getString(R.string.sleep_set, minutes));
    }

    /** The last minute: a countdown, then playback stops and the TV can go to sleep. */
    private final Runnable sleepTick = new Runnable() {
        @Override
        public void run() {
            if (sleepAt == 0) return;
            long left = sleepAt - android.os.SystemClock.elapsedRealtime();
            if (left <= 0) {
                sleepAt = 0;
                sleepWarning = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                handler.removeCallbacksAndMessages(null);
                releasePlayer();
                repo.exitNow();
                finish();
                return;
            }
            sleepWarning = true;
            showMessage(getString(R.string.sleep_warning, (int) Math.ceil(left / 1000.0)));
            handler.postDelayed(this, 1000);
        }
    };

    private void keepWatching() {
        handler.removeCallbacks(sleepTick);
        sleepAt = 0;
        sleepWarning = false;
        showMessage(getString(R.string.sleep_kept));
    }

    private void next() {
        direction = 1;
        play(index + 1);
    }

    private void previous() {
        direction = -1;
        play(index - 1);
    }

    // =============================================================== playback

    private void play(int i) {
        if (player == null) return;
        if (queue.isEmpty()) {
            finish();
            return;
        }
        int size = queue.size();
        index = ((i % size) + size) % size;
        Channel c = queue.get(index);
        if (nowUrl != null && !nowUrl.equals(c.url)) previousUrl = nowUrl;
        nowUrl = c.url;
        ready = false;
        reconnects = 0;
        programme = null;
        playStartedAt = System.currentTimeMillis();
        startRecorded = false;
        handler.removeCallbacks(retry);
        handler.removeCallbacks(networkWatch);
        Session.channelStarted(this, c, category);

        showNowPlaying(c);
        showInfo();
        spinner.setVisibility(View.VISIBLE);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(c.userAgent != null ? c.userAgent : Net.DEFAULT_UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000);
        if (c.referrer != null) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", c.referrer);
            http.setDefaultRequestProperties(headers);
        }
        MediaItem.Builder item = new MediaItem.Builder().setUri(c.url);
        if (c.hls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        MediaSource source = new DefaultMediaSourceFactory(http).createMediaSource(item.build());

        player.stop();
        clearSubtitleOverride();
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);

        showStatusRes(R.string.status_connecting);
        handler.removeCallbacks(startTimeout);
        handler.postDelayed(startTimeout, START_TIMEOUT_MS);
        hideJoke();
        handler.postDelayed(showJoke, 900);
        preloadNeighbours();
        if (Repo.RECENT.equals(browseCategory)) refreshBrowse(false); // new top entry
        else {
            panelAdapter.notifyDataSetChanged();
            belowAdapter.notifyDataSetChanged();
        }
    }

    /** Channel name, programme (or category · country when unknown), logo and star, everywhere. */
    private void showNowPlaying(Channel c) {
        String detail = programme != null ? getString(R.string.now_playing, programme) : c.subtitle();
        number.setText(String.valueOf(index + 1));
        name.setText(c.name);
        sub.setText(detail);
        Logos.load(this, logo, c.logo, null);
        belowName.setText(c.name);
        belowSub.setText(detail);
        Logos.load(this, belowLogo, c.logo, null);
        updateStar();
    }

    private void updateStar() {
        Channel c = current();
        boolean fav = c != null && favs.contains(c.url);
        favButton.setText(fav ? R.string.fav_remove : R.string.fav_add);
        favButton.setTextColor(fav ? 0xFFFF3B47 : 0xFFFFFFFF); // red heart when saved
        belowFav.setText(fav ? R.string.star_full : R.string.star_empty);
    }

    private void toggleFavourite(Channel c) {
        if (c == null) return;
        boolean added = Store.toggleFavourite(this, c.url);
        favs = Store.favourites(this);
        showMessage(getString(added ? R.string.fav_added : R.string.fav_removed, c.name));
        updateStar();
        if (Repo.FAVOURITES.equals(browseCategory)) refreshBrowse(false);
        else {
            panelAdapter.notifyDataSetChanged();
            belowAdapter.notifyDataSetChanged();
        }
    }

    /** Drop the current channel and move on in the direction you were going. */
    private void skip(String reason) {
        if (queue.isEmpty()) return;
        handler.removeCallbacks(startTimeout);
        Channel bad = queue.remove(index);
        // Favourites stay in your list even if they fail once (they may be back later).
        if (!favs.contains(bad.url)) {
            repo.removeDead(Collections.singletonList(bad.url));
        }
        if (queue.isEmpty()) Session.forgetIfLast(this, bad.url);
        showMessage(getString(R.string.skipping, bad.name, reason));
        if (queue.isEmpty()) {
            finish();
            return;
        }
        // After removing, "index" already points at the next channel.
        play(direction > 0 ? index : index - 1);
    }

    // ================================================================ errors

    private void handlePlaybackError(PlaybackException error) {
        handler.removeCallbacks(retry);
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            // Fell behind the live stream (e.g. after a long stall): jump back to live.
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        if (!Device.isOnline(this)) {
            waitForNetwork();
            return;
        }
        if (ready && reconnects < MAX_RECONNECTS) {
            // It was playing: probably a network hiccup. Reconnect with a growing pause.
            reconnects++;
            ready = false;
            showStatus(getString(R.string.status_reconnecting, reconnects, MAX_RECONNECTS));
            handler.postDelayed(retry, 1500L * reconnects);
            return;
        }
        skip(getString(R.string.skip_error));
    }

    /** Offline: never skip channels; wait and continue once the internet is back. */
    private void waitForNetwork() {
        handler.removeCallbacks(startTimeout);
        spinner.setVisibility(View.VISIBLE);
        showStatusRes(R.string.status_offline);
        handler.removeCallbacks(networkWatch);
        handler.postDelayed(networkWatch, 3000);
    }

    private final Runnable networkWatch = new Runnable() {
        @Override
        public void run() {
            if (Device.isOnline(PlayerActivity.this)) restartStream();
            else handler.postDelayed(this, 3000);
        }
    };

    private void restartStream() {
        int keep = Math.min(reconnects, MAX_RECONNECTS);
        play(index);
        reconnects = keep;
    }

    // ============================================================ screen mode

    private void cycleScreenMode() {
        screenMode = (screenMode + 1) % RESIZE_MODES.length;
        Store.setScreenMode(this, screenMode);
        applyScreenMode(true);
    }

    private void applyScreenMode(boolean announce) {
        if (screenMode < 0 || screenMode >= RESIZE_MODES.length) screenMode = 0;
        View area = findViewById(R.id.video_area);
        int w = ViewGroup.LayoutParams.MATCH_PARENT;
        int h = ViewGroup.LayoutParams.MATCH_PARENT;
        int resize = RESIZE_MODES[screenMode];
        if (screenMode >= 3 && !watchPage && area.getWidth() > 0) {
            float r = screenMode == 3 ? 16f / 9f : 4f / 3f;
            w = area.getWidth();
            h = Math.round(w / r);
            if (h > area.getHeight()) {
                h = area.getHeight();
                w = Math.round(h * r);
            }
        } else if (screenMode >= 3) {
            resize = AspectRatioFrameLayout.RESIZE_MODE_FIT; // phone watch page: the box is already 16:9
        }
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) playerView.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            lp.gravity = android.view.Gravity.CENTER;
            playerView.setLayoutParams(lp);
        }
        playerView.setResizeMode(resize);
        String label = getString(MODE_LABELS[screenMode]);
        modeButton.setText(label);
        if (announce) showMessage(getString(R.string.screen_mode, label));
    }

    private void cycleOrientation() {
        orientation = (orientation + 1) % ORIENTATIONS.length;
        Store.setOrientation(this, orientation);
        applyOrientation(true);
        showInfo();
    }

    private void applyOrientation(boolean announce) {
        if (orientation < 0 || orientation >= ORIENTATIONS.length) orientation = 0;
        setRequestedOrientation(ORIENTATIONS[orientation]);
        String label = getString(ORIENTATION_LABELS[orientation]);
        rotateButton.setText(label);
        if (announce) showMessage(getString(R.string.rotation, label));
    }

    // ============================================================ subtitles

    /** English subtitles are chosen automatically when a channel has them (unless turned off). */
    private void applySubtitlePreference() {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !Store.subtitlesOn(this))
                .setPreferredTextLanguage(Store.subtitleLanguage(this))
                .build());
    }

    private void clearSubtitleOverride() {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build());
        applySubtitlePreference();
    }

    /** CC button / Captions key: list this channel's subtitle tracks. */
    private void chooseSubtitles() {
        if (player == null) return;
        List<String> labels = new ArrayList<>();
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> trackIndexes = new ArrayList<>();
        labels.add(getString(R.string.subtitles_off));
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                labels.add(trackLabel(g.getTrackFormat(i), labels.size()));
                groups.add(g);
                trackIndexes.add(i);
            }
        }
        if (groups.isEmpty()) {
            showMessage(getString(R.string.subtitles_none));
            return;
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.subtitles_title)
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        Store.setSubtitles(this, false, null);
                        applySubtitlePreference();
                        showMessage(getString(R.string.subtitles_on, labels.get(0)));
                        return;
                    }
                    Tracks.Group g = groups.get(w - 1);
                    int i = trackIndexes.get(w - 1);
                    Store.setSubtitles(this, true, g.getTrackFormat(i).language);
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i))
                            .build());
                    showMessage(getString(R.string.subtitles_on, labels.get(w)));
                })
                .show();
    }

    private static String trackLabel(Format f, int n) {
        if (f.label != null && !f.label.isEmpty()) return f.label;
        if (f.language != null && !f.language.isEmpty() && !"und".equals(f.language)) {
            String name = new java.util.Locale(f.language).getDisplayLanguage();
            return name.isEmpty() ? f.language : name;
        }
        return "Track " + n;
    }

    private void rescan() {
        Menus.confirmRescan(this, repo.all.size(), () -> {
            repo.fullRescan();
            showMessage(getString(R.string.rescan_started));
        });
    }

    /** TV Menu key: subtitles, screen mode, favourite and playlists in one place. */
    private void showOptions() {
        Channel c = current();
        if (c == null) return;
        boolean fav = favs.contains(c.url);
        Channel prev = previousUrl == null ? null : repo.find(previousUrl);
        String[] items = {
                getString(R.string.option_search),
                getString(fav ? R.string.menu_unfavourite : R.string.menu_favourite),
                getString(R.string.option_previous, prev != null ? prev.name : "–"),
                getString(R.string.option_screen, getString(MODE_LABELS[screenMode])),
                getString(R.string.option_sleep, sleepLabel()),
                getString(R.string.option_subtitles),
                getString(R.string.menu_add_to_playlist),
                getString(R.string.option_from_phone),
                getString(R.string.option_rescan),
                getString(R.string.option_about),
        };
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(getString(R.string.settings_for, c.name))
                .setItems(items, (d, w) -> {
                    if (w == 0) openLiveSearch();
                    else if (w == 1) toggleFavourite(c);
                    else if (w == 2) flipToPrevious();
                    else if (w == 3) cycleScreenMode();
                    else if (w == 4) chooseSleepTimer();
                    else if (w == 5) chooseSubtitles();
                    else if (w == 6) Menus.addToPlaylist(this, c, this::channelsChanged);
                    else if (w == 7) startActivityForResult(new Intent(this, ReceiveActivity.class), REQUEST_FROM_PHONE);
                    else if (w == 8) rescan();
                    else MainActivity.showAbout(this);
                })
                .show();
    }

    // ===================================================== volume & brightness

    private void changeBrightness(float delta) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        float now = lp.screenBrightness;
        if (now < 0) { // still following the system setting: start from it
            int sys = android.provider.Settings.System.getInt(getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
            now = sys / 255f;
        }
        now = Math.max(0.02f, Math.min(1f, now + delta));
        lp.screenBrightness = now; // this app's window only; the phone's own setting is untouched
        getWindow().setAttributes(lp);
        showLevel(R.string.brightness, now);
    }

    private void changeVolume(float delta) {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        volumeCarry += delta * max;
        int steps = (int) volumeCarry;
        if (steps != 0) {
            volumeCarry -= steps;
            cur = Math.max(0, Math.min(max, cur + steps));
            try {
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, cur, 0);
            } catch (SecurityException ignored) {
                // e.g. Do Not Disturb: Android doesn't allow changing volume now
            }
        }
        showLevel(R.string.volume, max == 0 ? 0 : cur / (float) max);
    }

    private void showLevel(int label, float fraction) {
        levelLabel.setText(getString(label) + "  " + Math.round(fraction * 100) + "%");
        levelBar.setProgress(Math.round(fraction * 100));
        levelBox.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideLevel);
        handler.postDelayed(hideLevel, 900);
    }

    // ============================================================ preloading

    /** Logos of the 5 channels before and after load in advance; the next stream is warmed up. */
    private void preloadNeighbours() {
        int size = queue.size();
        if (size < 2) return;
        for (int d = -5; d <= 5; d++) {
            if (d == 0) continue;
            Logos.prefetch(this, queue.get(((index + d) % size + size) % size).logo);
        }
        Channel next = queue.get(((index + direction) % size + size) % size);
        try {
            warmer.execute(() -> {
                try {
                    Net.get(next.url, next.userAgent, next.referrer, 4000, 16 * 1024);
                } catch (Exception ignored) {
                    // only a warm-up
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // screen is closing
        }
    }

    // =============================================================== overlays

    private void hideJoke() {
        handler.removeCallbacks(showJoke);
        jokeBox.animate().cancel();
        jokeBox.setVisibility(View.GONE);
    }

    private void showStatusRes(int resId) {
        showStatus(getString(resId));
    }

    private void showStatus(String text) {
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideMessage);
        handler.postDelayed(hideMessage, 3000);
    }

    private void showInfo() {
        handler.removeCallbacks(hideInfo);
        for (View v : new View[]{info, topBar}) {
            v.animate().cancel();
            v.setVisibility(View.VISIBLE);
            v.setAlpha(1f);
        }
        handler.postDelayed(hideInfo, INFO_VISIBLE_MS);
    }

    private void hideInfoNow() {
        handler.removeCallbacks(hideInfo);
        for (View v : new View[]{info, topBar}) {
            v.animate().alpha(0f).setDuration(300).withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    private void toggleInfo() {
        if (info.getVisibility() == View.VISIBLE && info.getAlpha() > 0.5f) hideInfoNow();
        else showInfo();
    }

    private boolean isPanelOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    /** Browse without stopping playback. On the phone's watch page the list is already visible. */
    private void openPanel() {
        openPanel(false);
    }

    /** @param keepBrowsing show what's being browsed (e.g. search results), not the playing category */
    private void openPanel(boolean keepBrowsing) {
        if (watchPage) {
            scrollListsToCurrent();
            return;
        }
        hideInfoNow();
        if (!keepBrowsing && !category.equals(browseCategory)) setBrowseCategory(category, false);
        else refreshBrowse(false);
        panel.setVisibility(View.VISIBLE);
        panelList.post(() -> {
            panelList.requestFocus();
            Channel c = current();
            int i = c == null ? -1 : Repo.indexOf(browseList, c.url);
            panelList.setSelection(Math.max(0, i));
        });
    }

    private void closePanel() {
        if (panel.getVisibility() != View.VISIBLE) return;
        panel.setVisibility(View.GONE);
        handler.removeCallbacks(selectPanelCategory);
        if (!isTv) hideSystemBars();
    }

    /** Channel rows for the panel and the watch-page list. */
    private class ChannelListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return browseList.size();
        }

        @Override
        public Channel getItem(int position) {
            return browseList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(PlayerActivity.this).inflate(R.layout.item_panel_channel, parent, false);
            Channel c = getItem(position);
            boolean fav = favs.contains(c.url);
            ((TextView) row.findViewById(R.id.row_number)).setText(String.valueOf(position + 1));
            ((TextView) row.findViewById(R.id.row_name)).setText(c.name);
            row.findViewById(R.id.row_fav).setVisibility(fav ? View.VISIBLE : View.GONE);
            ((TextView) row.findViewById(R.id.row_sub)).setText(c.subtitle());
            Logos.load(PlayerActivity.this, (ImageView) row.findViewById(R.id.row_logo), c.logo, null);
            Channel now = current();
            row.setActivated(now != null && now.url.equals(c.url)); // the channel playing now
            return row;
        }
    }
}
