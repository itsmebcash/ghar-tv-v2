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
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start();
        tv.setOnFocusChangeListener(grow);
        music.setOnFocusChangeListener(grow);
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
