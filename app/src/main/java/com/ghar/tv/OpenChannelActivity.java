package com.ghar.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Entry point for Ghar Launcher (protected by the same signature permission as the provider).
 *
 *   action com.ghar.tv.action.PLAY_CHANNEL, extra "channel_id" = stable id  -> play that channel
 *   action com.ghar.tv.action.RESUME                                         -> continue last channel
 *
 * The channel is played inside Ghar TV's own player; Back from there returns to the launcher.
 */
public class OpenChannelActivity extends Activity {

    static final String ACTION_PLAY = "com.ghar.tv.action.PLAY_CHANNEL";
    static final String ACTION_RESUME = "com.ghar.tv.action.RESUME";
    static final String EXTRA_ID = "channel_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        String action = in == null ? null : in.getAction();

        if (ACTION_PLAY.equals(action)) {
            Channel c = Snapshot.findById(this, in.getStringExtra(EXTRA_ID));
            if (c != null) {
                // Start with this channel; channel up/down then follows your Favourites.
                PlayerActivity.startExpanding(this, c, Repo.FAVOURITES);
            } else {
                Toast.makeText(this, R.string.channel_not_found, Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_BROWSE, true));
            }
        } else if (Session.lastChannel(this) != null) {
            PlayerActivity.resume(this);
        } else {
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_BROWSE, true));
        }
        finish();
    }
}
