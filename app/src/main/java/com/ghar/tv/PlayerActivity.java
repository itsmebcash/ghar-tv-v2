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

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
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
 *   Star (favourite) at the top. Up/Down/Left opens the channel panel: categories + channels,
 *   so you can browse without stopping what's playing.
 *   Remote: CH+/CH- switch, OK info, hold OK favourite, Right/Menu screen mode, Back close/exit.
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

    private static final int[] RESIZE_MODES = {
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
    };
    private static final int[] MODE_LABELS = {R.string.mode_fit, R.string.mode_fill, R.string.mode_stretch};

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
    private final List<String> categoryNames = Repo.categoryNames();
    private String browseCategory;
    private final List<Channel> browseList = new ArrayList<>();
    private java.util.Set<String> favs = new java.util.HashSet<>();

    private boolean isTv;
    private boolean watchPage;   // phone upright
    private int screenMode;
    private int orientation;
    private boolean touchOnControl;
    private boolean gestureInVideo;

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
        if (!categoryNames.contains(category)) category = Repo.ALL;
        pendingQueue = null;
        pendingCategory = null;

        setContentView(R.layout.activity_player);
        findViews();
        wireControls();
        buildCategoryViews();

        playerView.setUseController(false);
        screenMode = Store.screenMode(this);
        applyScreenMode(false);
        if (!isTv) {
            orientation = Store.orientation(this);
            applyOrientation(false);
        }
        setupGestures();

        player = buildPlayer();
        playerView.setPlayer(player);
        player.addListener(new PlayerEvents());

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
    }

    private void wireControls() {
        ((TextView) findViewById(R.id.hint)).setText(isTv ? R.string.player_hint_tv : R.string.player_hint_phone);
        findViewById(R.id.fav_hint).setVisibility(isTv ? View.VISIBLE : View.GONE);
        controls.setVisibility(isTv ? View.GONE : View.VISIBLE);
        channelsButton.setOnClickListener(v -> openPanel());
        modeButton.setOnClickListener(v -> cycleScreenMode());
        rotateButton.setOnClickListener(v -> cycleOrientation());
        favButton.setOnClickListener(v -> toggleFavourite(current()));
        belowFav.setOnClickListener(v -> toggleFavourite(current()));
        findViewById(R.id.panel_scrim).setOnClickListener(v -> closePanel());
        findViewById(R.id.browse_button).setOnClickListener(v -> openBrowser());

        panelAdapter = new ChannelListAdapter();
        panelList.setAdapter(panelAdapter);
        panelList.setOnItemClickListener((p, v, position, id) -> pickFromBrowse(position));
        panelList.setOnItemLongClickListener((p, v, position, id) -> {
            toggleFavourite(browseList.get(position));
            return true;
        });

        belowAdapter = new ChannelListAdapter();
        belowList.setAdapter(belowAdapter);
        belowList.setOnItemClickListener((p, v, position, id) -> pickFromBrowse(position));
        belowList.setOnItemLongClickListener((p, v, position, id) -> {
            toggleFavourite(browseList.get(position));
            return true;
        });
    }

    /** Category chips (watch page) and category rows (panel). */
    private void buildCategoryViews() {
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String n : categoryNames) {
            View chip = inflater.inflate(R.layout.item_chip, belowChips, false);
            ((TextView) chip.findViewById(R.id.cat_name)).setText(n);
            chip.setOnClickListener(v -> setBrowseCategory(n, true));
            belowChips.addView(chip);
            chipViews.add(chip);

            View row = inflater.inflate(R.layout.item_panel_category, panelCategories, false);
            ((TextView) row.findViewById(R.id.cat_name)).setText(n);
            row.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    pendingPanelCategory = n;
                    handler.removeCallbacks(selectPanelCategory);
                    handler.postDelayed(selectPanelCategory, 300);
                }
            });
            row.setOnClickListener(v -> {
                setBrowseCategory(n, true);
                panelList.requestFocus();
            });
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
                spinner.setVisibility(View.GONE);
                statusText.setVisibility(View.GONE);
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
            restoring = false;
            pendingQueue = null;
            pendingCategory = null;
            play(index);
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

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || isPanelOpen()) return false;
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
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) gestureInVideo = isInside(videoArea, ev);
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
        if (event.getAction() == KeyEvent.ACTION_DOWN && !isPanelOpen()) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
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
                    // Press = info; hold = add/remove favourite.
                    if (event.getRepeatCount() == 0) showInfo();
                    else if (event.getRepeatCount() == 1) toggleFavourite(current());
                    return true;
                case KeyEvent.KEYCODE_INFO:
                    showInfo();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MENU:
                    cycleScreenMode();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
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
        ready = false;
        reconnects = 0;
        programme = null;
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
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);

        showStatusRes(R.string.status_connecting);
        handler.removeCallbacks(startTimeout);
        handler.postDelayed(startTimeout, START_TIMEOUT_MS);
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
        playerView.setResizeMode(RESIZE_MODES[screenMode]);
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

    // =============================================================== overlays

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
        if (watchPage) {
            scrollListsToCurrent();
            return;
        }
        hideInfoNow();
        if (!category.equals(browseCategory)) setBrowseCategory(category, false);
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
            ((TextView) row.findViewById(R.id.row_name)).setText(fav ? "★ " + c.name : c.name);
            ((TextView) row.findViewById(R.id.row_sub)).setText(c.subtitle());
            Logos.load(PlayerActivity.this, (ImageView) row.findViewById(R.id.row_logo), c.logo, null);
            Channel now = current();
            row.setActivated(now != null && now.url.equals(c.url)); // the channel playing now
            return row;
        }
    }
}
