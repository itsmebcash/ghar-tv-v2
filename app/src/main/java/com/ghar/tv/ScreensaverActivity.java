package com.ghar.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Ghar Launcher's channel screensaver: your favourite channels, muted, one after another.
 *
 *   action com.ghar.tv.action.SCREENSAVER, extra "minutes" = how long each channel plays
 *
 * Starts on a random favourite, then moves through the list (looping) every few minutes;
 * a channel that won't play is skipped. The name and a clock appear softly in the corners.
 * Any button (or a touch) ends it and you're back on the home screen.
 * Protected by the same signature permission as the rest of the launcher link.
 */
public class ScreensaverActivity extends Activity {

    static final String ACTION = "com.ghar.tv.action.SCREENSAVER";
    private static final long START_TIMEOUT_MS = 15_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Channel> channels = new ArrayList<>();
    private ExoPlayer player;
    private PlayerView video;
    private View info;
    private ImageView logo;
    private TextView name;
    private TextView sub;
    private int index;
    private int failuresInARow;
    private long perChannelMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int minutes = getIntent() == null ? 10 : getIntent().getIntExtra("minutes", 10);
        perChannelMs = Math.max(1, Math.min(120, minutes)) * 60_000L;

        for (Snapshot.Entry e : Snapshot.read(this)) channels.add(e.channel);
        if (channels.isEmpty()) {
            finish(); // no favourites yet: nothing to show
            return;
        }
        index = new Random().nextInt(channels.size());
        buildScreen();

        player = new ExoPlayer.Builder(this).build();
        player.setVolume(0f); // muted, always
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    failuresInARow = 0;
                    handler.removeCallbacks(startTimeout);
                    video.animate().alpha(1f).setDuration(1200).start();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                skip();
            }
        });
        video.setPlayer(player);
        play();
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish(); // leaving for any reason ends the screensaver
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) finish();
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) finish();
        return true;
    }

    // ---------------------------------------------------------------- playing

    private void play() {
        Channel c = channels.get(index);
        handler.removeCallbacks(next);
        handler.removeCallbacks(startTimeout);
        video.animate().cancel();
        video.setAlpha(0f);

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
        player.stop();
        player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(item.build()));
        player.prepare();
        player.setPlayWhenReady(true);

        showInfo(c);
        handler.postDelayed(startTimeout, START_TIMEOUT_MS);
        handler.postDelayed(next, perChannelMs);
    }

    private final Runnable next = () -> {
        index = (index + 1) % channels.size();
        play();
    };

    private final Runnable startTimeout = this::skip;

    /** This channel won't play: try the next; stop if none of them work right now. */
    private void skip() {
        handler.removeCallbacks(startTimeout);
        if (++failuresInARow >= channels.size()) {
            finish();
            return;
        }
        handler.removeCallbacks(next);
        handler.postDelayed(next, 600);
    }

    // ----------------------------------------------------------------- screen

    private void buildScreen() {
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new PlayerView(this);
        video.setUseController(false);
        video.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        video.setShutterBackgroundColor(Color.TRANSPARENT);
        video.setAlpha(0f);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x99000000, 0x00000000}));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, (int) (200 * d), Gravity.BOTTOM);
        root.addView(shade, sp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        logo = new ImageView(this);
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(0xF2FFFFFF);
        plate.setCornerRadius(10 * d);
        logo.setBackground(plate);
        logo.setPadding((int) (6 * d), (int) (6 * d), (int) (6 * d), (int) (6 * d));
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(logo, new LinearLayout.LayoutParams((int) (96 * d), (int) (56 * d)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        name = text(24, 0xFFFFFFFF);
        sub = text(15, 0xCCFFFFFF);
        texts.addView(name);
        texts.addView(sub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart((int) (18 * d));
        box.addView(texts, tp);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        bp.setMargins((int) (48 * d), 0, 0, (int) (40 * d));
        root.addView(box, bp);
        info = box;

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(34);
        clock.setTextColor(0xE6FFFFFF);
        clock.setShadowLayer(10, 0, 0, 0x99000000);
        clock.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.setMargins(0, (int) (30 * d), (int) (48 * d), 0);
        root.addView(clock, cp);

        setContentView(root, new ViewGroup.LayoutParams(-1, -1));
    }

    private TextView text(float size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setShadowLayer(8, 0, 1, 0xB3000000);
        return t;
    }

    /** Channel name fades in, stays a while, then fades to a faint whisper. */
    private void showInfo(Channel c) {
        name.setText(c.name);
        sub.setText(getString(R.string.saver_sub, c.subtitle()).replaceAll("^\\s*·\\s*", ""));
        Logos.load(this, logo, c.logo, null);
        info.animate().cancel();
        info.setAlpha(0f);
        info.animate().alpha(1f).setDuration(900).withEndAction(() ->
                info.animate().alpha(0.35f).setStartDelay(8000).setDuration(1500).start()).start();
    }
}
