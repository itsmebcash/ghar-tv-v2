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
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
public class PlayerActivity extends Activity {

    private static final long START_TIMEOUT_MS = 12_000;
    private static final long INFO_VISIBLE_MS = 4_000;
    private static final int REQUEST = 1;

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

    /** Channels that failed while watching; MainActivity removes them from the list. */
    static final Set<String> DEAD = Collections.synchronizedSet(new HashSet<String>());
    static String lastUrl;

    private static List<Channel> pendingQueue;
    private static int pendingIndex;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<Channel> queue;
    private int index;
    private int direction = 1;
    private boolean ready;
    private boolean retried;
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

    private final Runnable startTimeout = () -> skip(getString(R.string.skip_slow));
    private final Runnable hideInfo = this::hideInfoNow;
    private final Runnable hideMessage = () -> message.setVisibility(View.GONE);

    static void start(Activity from, List<Channel> channels, int index) {
        pendingQueue = channels;
        pendingIndex = index;
        from.startActivityForResult(new Intent(from, PlayerActivity.class), REQUEST);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (pendingQueue == null || pendingQueue.isEmpty()) {
            finish();
            return;
        }
        queue = new ArrayList<>(pendingQueue);
        index = pendingIndex;
        pendingQueue = null;
        isTv = Device.isTv(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
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

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    ready = true;
                    retried = false;
                    handler.removeCallbacks(startTimeout);
                    spinner.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING) {
                    spinner.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (ready && !retried) {
                    // It was playing fine: probably a network hiccup. Try once more.
                    retried = true;
                    ready = false;
                    play(index);
                } else {
                    skip(getString(R.string.skip_error));
                }
            }
        });

        play(index);
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
        super.onStop();
        // Live TV: when you leave (Home button, another app), stop and release the stream.
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (isPanelOpen()) closePanel();
        else super.onBackPressed();
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
        lastUrl = c.url;
        ready = false;

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
        if (!Store.favourites(this).contains(bad.url)) DEAD.add(bad.url);
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
