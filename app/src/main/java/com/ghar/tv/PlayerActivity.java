package com.ghar.tv;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
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
 * Full-screen live TV, for both TV remotes and phones.
 *
 * TV:    Up / Down = open channel list (scroll, OK to watch), CH+ / CH- = switch directly,
 *        OK = channel info, Right = screen mode, Back = close list / back to browser.
 * Phone: swipe up = next channel, swipe down = previous, tap = controls
 *        (Channels list, Fit / Fill / Stretch, rotation: Auto / Portrait / Landscape).
 *        Holding the phone upright plays upright; nothing is forced.
 *
 * A channel that doesn't start within START_TIMEOUT_MS, or errors, is skipped automatically.
 */
public class PlayerActivity extends Activity implements Repo.Listener {

    private static final long START_TIMEOUT_MS = 12_000;
    private static final long INFO_VISIBLE_MS = 4_000;

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
    private int orientation;

    private static final int MAX_RECONNECTS = 3;

    private static List<Channel> pendingQueue;
    private static int pendingIndex;
    private static String pendingCategory;

    private Repo repo;
    /** Opened from a saved session: the full channel list is filled in once it has loaded. */
    private boolean restoring;
    private String category;
    private int reconnects;
    private TextView statusText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<Channel> queue;
    private int index;
    private int direction = 1;
    private boolean ready;
    private boolean isTv;
    private int screenMode;
    private boolean touchOnControl;

    private ExoPlayer player;
    private PlayerView playerView;
    private View info;
    private TextView number;
    private TextView name;
    private TextView sub;
    private ImageView logo;
    private ProgressBar spinner;
    private TextView message;
    private TextView modeButton;
    private TextView rotateButton;
    private View controls;
    private View panel;
    private ListView panelList;
    private PanelAdapter panelAdapter;
    private GestureDetector gestures;

    /** Didn't start in time: skip it — unless we're simply offline. */
    private final Runnable startTimeout = () -> {
        if (!Device.isOnline(this)) waitForNetwork();
        else skip(getString(R.string.skip_slow));
    };
    private final Runnable hideInfo = this::hideInfoNow;
    private final Runnable hideMessage = () -> message.setVisibility(View.GONE);

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
        pendingQueue = null;
        pendingCategory = null;

        setContentView(R.layout.activity_player);
        statusText = findViewById(R.id.status_text);
        findViewById(R.id.browse_button).setOnClickListener(v -> openBrowser());
        info = findViewById(R.id.info);
        number = findViewById(R.id.number);
        name = findViewById(R.id.channel_name);
        sub = findViewById(R.id.channel_sub);
        logo = findViewById(R.id.channel_logo);
        spinner = findViewById(R.id.spinner);
        message = findViewById(R.id.message);
        modeButton = findViewById(R.id.mode_button);
        panel = findViewById(R.id.panel);
        panelList = findViewById(R.id.panel_list);

        ((TextView) findViewById(R.id.hint)).setText(isTv ? R.string.player_hint_tv : R.string.player_hint_phone);
        controls = findViewById(R.id.controls);
        controls.setVisibility(View.GONE);
        rotateButton = findViewById(R.id.rotate_button);
        findViewById(R.id.channels_button).setOnClickListener(v -> openPanel());
        modeButton.setOnClickListener(v -> cycleScreenMode());
        rotateButton.setOnClickListener(v -> cycleOrientation());
        if (!isTv) {
            orientation = Store.orientation(this);
            applyOrientation(false);
        }
        findViewById(R.id.panel_scrim).setOnClickListener(v -> closePanel());

        panelAdapter = new PanelAdapter();
        panelList.setAdapter(panelAdapter);
        panelList.setOnItemClickListener((parent, view, position, id) -> {
            closePanel();
            direction = 1;
            play(position);
        });

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        screenMode = Store.screenMode(this);
        applyScreenMode(false);

        float density = getResources().getDisplayMetrics().density;
        float minSwipe = 60 * density;
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

        player = buildPlayer();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
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

            @Override
            public void onPlayerError(PlaybackException error) {
                handlePlaybackError(error);
            }
        });

        repo.addListener(this);
        onRepoChanged();
        play(index);
    }

    /**
     * One player for the whole screen; channels are swapped inside it, never a second stream.
     * - Starts as soon as ~2 s of video is buffered, then keeps up to 60 s in reserve.
     * - Starts at a modest quality and adapts up/down to the connection (HLS adaptive streaming).
     * - Hardware video decoding, falling back to another decoder if the first one fails.
     */
    private ExoPlayer buildPlayer() {
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        15_000,  // keep at least 15 s buffered once playing
                        60_000,  // buffer up to 60 s ahead when the connection allows
                        2_000,   // start after 2 s
                        4_000)   // after a stall, resume once 4 s are ready
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        DefaultBandwidthMeter bandwidth = new DefaultBandwidthMeter.Builder(this)
                .setInitialBitrateEstimate(800_000) // start low = fast first picture on slow links
                .build();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);
        return new ExoPlayer.Builder(this, renderers)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidth)
                .build();
    }

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

    /** Once the channel list has loaded, a restored session gets its full category back. */
    @Override
    public void onRepoChanged() {
        if (!restoring || !repo.loaded || queue == null || queue.isEmpty()) return;
        List<Channel> list = repo.channels(category, Store.favourites(this));
        Channel current = queue.get(index);
        int i = Repo.indexOf(list, current.url);
        if (i < 0) {
            list.add(0, current); // keep what's playing in the list
            i = 0;
        }
        queue = list;
        index = i;
        restoring = false;
        number.setText(String.valueOf(index + 1));
        panelAdapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------- errors

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

    private final Runnable retry = () -> restartStream();

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
            if (Device.isOnline(PlayerActivity.this)) {
                restartStream();
            } else {
                handler.postDelayed(this, 3000);
            }
        }
    };

    private void restartStream() {
        reconnects = Math.min(reconnects, MAX_RECONNECTS);
        int keep = reconnects;
        play(index);
        reconnects = keep;
    }

    private void showStatusRes(int resId) {
        showStatus(getString(resId));
    }

    private void showStatus(String text) {
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
    }

    /**
     * Rotating the phone doesn't restart the stream (the activity handles it itself),
     * so re-apply the portrait / landscape sizes from dimens.xml here.
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        android.content.res.Resources r = getResources();
        int side = r.getDimensionPixelSize(R.dimen.info_side);
        info.setPadding(side, info.getPaddingTop(), side, info.getPaddingBottom());
        ViewGroup.LayoutParams lp = logo.getLayoutParams();
        lp.width = r.getDimensionPixelSize(R.dimen.info_logo_w);
        lp.height = r.getDimensionPixelSize(R.dimen.info_logo_h);
        logo.setLayoutParams(lp);
        View box = findViewById(R.id.panel_box);
        ViewGroup.LayoutParams bp = box.getLayoutParams();
        bp.width = r.getDimensionPixelSize(R.dimen.panel_width);
        box.setLayoutParams(bp);
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

    /** Back: close the channel list if open; otherwise save, stop everything and leave the app. */
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

    /** Full-screen browser; Back there returns here. */
    private void openBrowser() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        MainActivity.browse(this);
        finish();
    }

    // -------------------------------------------------------------- input

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (isPanelOpen()) return super.dispatchTouchEvent(ev);
        boolean handled = super.dispatchTouchEvent(ev); // buttons get their taps
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) touchOnControl = handled;
        gestures.onTouchEvent(ev);
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && !isPanelOpen()) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    openPanel();
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

    // ------------------------------------------------------------ playback

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
        handler.removeCallbacks(retry);
        handler.removeCallbacks(networkWatch);
        Session.channelStarted(this, c, category);

        number.setText(String.valueOf(index + 1));
        name.setText(c.name);
        sub.setText(c.subtitle());
        Logos.load(this, logo, c.logo, null);
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
        if (isPanelOpen()) panelAdapter.notifyDataSetChanged();
    }

    /** Drop the current channel and move on in the direction you were going. */
    private void skip(String reason) {
        if (queue.isEmpty()) return;
        handler.removeCallbacks(startTimeout);
        Channel bad = queue.remove(index);
        // Favourites stay in your list even if they fail once (they may be back later).
        if (!Store.favourites(this).contains(bad.url)) {
            repo.removeDead(Collections.singletonList(bad.url));
        }
        if (queue.isEmpty()) Session.forgetIfLast(this, bad.url);
        showMessage(getString(R.string.skipping, bad.name, reason));
        panelAdapter.notifyDataSetChanged();
        if (queue.isEmpty()) {
            finish();
            return;
        }
        // After removing, "index" already points at the next channel.
        play(direction > 0 ? index : index - 1);
    }

    // --------------------------------------------------------- screen mode

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

    // ---------------------------------------------------------- rotation

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

    // ------------------------------------------------------ overlays

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideMessage);
        handler.postDelayed(hideMessage, 3000);
    }

    private void showInfo() {
        handler.removeCallbacks(hideInfo);
        info.animate().cancel();
        info.setVisibility(View.VISIBLE);
        if (!isTv) {
            controls.animate().cancel();
            controls.setVisibility(View.VISIBLE);
            controls.setAlpha(1f);
        }
        info.setAlpha(1f);
        handler.postDelayed(hideInfo, INFO_VISIBLE_MS);
    }

    private void hideInfoNow() {
        handler.removeCallbacks(hideInfo);
        info.animate().alpha(0f).setDuration(300).withEndAction(() -> info.setVisibility(View.GONE)).start();
        if (!isTv) {
            controls.animate().alpha(0f).setDuration(300)
                    .withEndAction(() -> controls.setVisibility(View.GONE)).start();
        }
    }

    private void toggleInfo() {
        if (info.getVisibility() == View.VISIBLE && info.getAlpha() > 0.5f) hideInfoNow();
        else showInfo();
    }

    private boolean isPanelOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    private void openPanel() {
        hideInfoNow();
        panel.setVisibility(View.VISIBLE);
        panelAdapter.notifyDataSetChanged();
        panelList.setSelection(Math.max(0, index - 3));
        panelList.post(() -> {
            panelList.requestFocus();
            panelList.setSelection(index);
        });
    }

    private void closePanel() {
        panel.setVisibility(View.GONE);
        if (!isTv) hideSystemBars();
    }

    /** The channel list shown over the video. */
    private class PanelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return queue == null ? 0 : queue.size();
        }

        @Override
        public Channel getItem(int position) {
            return queue.get(position);
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
            ((TextView) row.findViewById(R.id.row_number)).setText(String.valueOf(position + 1));
            ((TextView) row.findViewById(R.id.row_name)).setText(c.name);
            ((TextView) row.findViewById(R.id.row_sub)).setText(c.subtitle());
            Logos.load(PlayerActivity.this, (ImageView) row.findViewById(R.id.row_logo), c.logo, null);
            row.setActivated(position == index); // the channel playing now
            return row;
        }
    }
}
