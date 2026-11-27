package com.ghar.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * Ghar TV and Ghar Player in one app: the first screen asks what you'd like — TV channels, or
 * music and FM (songs played from Bcash Player on the phone, and the FM radio). The choice from
 * last time is selected, so OK alone opens it again. Back from either comes back here.
 */
public class StartActivity extends Activity {

    private static final String PREFS = "ghar_start";
    private static final String KEY = "choice";

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);
        setContentView(R.layout.activity_start);
        View tv = findViewById(R.id.start_tv);
        View music = findViewById(R.id.start_music);
        tv.setOnClickListener(v -> open("tv"));
        music.setOnClickListener(v -> open("music"));
        View.OnFocusChangeListener grow = (v, has) ->
                v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(160).start();
        tv.setOnFocusChangeListener(grow);
        music.setOnFocusChangeListener(grow);
        // Keep the corners round when chosen: the TV's own square focus frame is switched off,
        // and the card (and anything drawn on it) is cut to its rounded shape.
        float radius = 24 * getResources().getDisplayMetrics().density;
        for (View card : new View[]{tv, music}) {
            if (android.os.Build.VERSION.SDK_INT >= 26) card.setDefaultFocusHighlightEnabled(false);
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);
            card.setStateListAnimator(null);
            if (android.os.Build.VERSION.SDK_INT >= 23) card.setForeground(null);
        }
    }

    private long lastBack;

    /** Back once: a reminder. Back again within 2 seconds: close (the radio keeps playing). */
    @Override
    public void onBackPressed() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastBack < 2000) {
            finish();
            return;
        }
        lastBack = now;
        android.widget.Toast.makeText(this, R.string.press_back_exit, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, "tv");
        // Music playing in the background: offer that first.
        String song = com.ghar.player.NowPlayingProvider.playingTitle();
        TextView playing = findViewById(R.id.start_playing);
        if (song != null) {
            playing.setText(getString(R.string.start_playing, song));
            playing.setVisibility(View.VISIBLE);
            last = "music";
        } else {
            playing.setVisibility(View.GONE);
        }
        findViewById("music".equals(last) ? R.id.start_music : R.id.start_tv).requestFocus();
    }

    private void open(String choice) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, choice).apply();
        Intent i = "music".equals(choice)
                ? new Intent(this, com.ghar.player.MainActivity.class)
                : new Intent(this, MainActivity.class);
        startActivity(i);
    }
}
