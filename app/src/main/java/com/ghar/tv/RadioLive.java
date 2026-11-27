package com.ghar.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ghar.player.Item;
import com.ghar.player.PlaybackService;

/**
 * The radio (Ghar Player's FM) keeps playing while you browse and watch TV channels. While it
 * does, the channel screens show a small "● Radio live" sign with two buttons: 🔇 to switch the
 * TV's own sound off and on, and ■ to stop the radio (say, when a good film comes on).
 */
final class RadioLive {

    /** The TV channel's sound is off (only while the radio plays). */
    static volatile boolean tvMuted;
    /** Called when the TV's sound is switched on or off, so the player can follow. */
    static Runnable onMuteChanged;

    private RadioLive() {
    }

    private static Item playingRadio() {
        PlaybackService s = PlaybackService.Companion.getInstance();
        if (s == null) return null;
        PlaybackService.Now n = s.now();
        Item it = n.getItem();
        if (it == null || it.getStream().isEmpty() || !(n.getPlaying() || n.getBuffering())) return null;
        return it;
    }

    static boolean live() {
        return playingRadio() != null;
    }

    static String station() {
        Item it = playingRadio();
        return it == null ? "" : it.getTitle();
    }

    static void setMuted(boolean m) {
        tvMuted = m;
        if (onMuteChanged != null) onMuteChanged.run();
    }

    /** Stop the radio; the TV's sound comes back. */
    static void stopRadio() {
        PlaybackService s = PlaybackService.Companion.getInstance();
        if (s != null) s.radioStop();
        setMuted(false);
    }

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    private static TextView pill(Activity a, String text, View.OnClickListener click) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(a.getResources().getColorStateList(R.color.category_text, a.getTheme()));
        t.setBackgroundResource(R.drawable.pill_button);
        t.setPadding(dp(a, 14), dp(a, 6), dp(a, 14), dp(a, 6));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setSingleLine(true);
        return t;
    }

    /**
     * "● रेडियो लाइभ · Kantipur FM   [🔇 टिभी म्युट]   [■ रेडियो रोक्नुहोस्]" — hidden while no radio
     * plays, and keeps itself up to date.
     */
    static LinearLayout bar(Activity a) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setVisibility(View.GONE);

        TextView dot = new TextView(a);
        dot.setText("●");
        dot.setTextColor(0xFFFF3B30);
        dot.setTextSize(14);
        row.addView(dot);
        android.animation.ObjectAnimator blink = android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.2f);
        blink.setDuration(700);
        blink.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        blink.setRepeatCount(android.animation.ValueAnimator.INFINITE);

        TextView label = new TextView(a);
        label.setTextColor(Color.WHITE);
        label.setTextSize(14);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(a, 230));
        label.setPadding(dp(a, 6), 0, dp(a, 10), 0);
        row.addView(label);

        TextView mute = pill(a, "", v -> setMuted(!tvMuted));
        row.addView(mute);
        TextView stop = pill(a, Lang.isNepali(a) ? "■  रेडियो रोक्नुहोस्" : "■  Stop radio", v -> stopRadio());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.setMarginStart(dp(a, 8));
        row.addView(stop, sl);

        Handler h = new Handler(Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                String name = station();
                boolean on = !name.isEmpty();
                if (on && row.getVisibility() != View.VISIBLE) {
                    row.setVisibility(View.VISIBLE);
                    blink.start();
                } else if (!on && row.getVisibility() == View.VISIBLE) {
                    if (row.hasFocus()) row.getRootView().requestFocus();
                    row.setVisibility(View.GONE);
                    blink.cancel();
                }
                label.setText((Lang.isNepali(a) ? "रेडियो लाइभ · " : "Radio live · ") + name);
                mute.setText(tvMuted ? (Lang.isNepali(a) ? "🔊  टिभीको आवाज खोल्नुहोस्" : "🔊  TV sound on")
                        : (Lang.isNepali(a) ? "🔇  टिभी म्युट" : "🔇  Mute TV"));
                if (row.isAttachedToWindow()) h.postDelayed(this, 1000);
            }
        };
        row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                h.removeCallbacks(tick);
                tick.run();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                h.removeCallbacks(tick);
                blink.cancel();
            }
        });
        return row;
    }

    /** Over the picture, top right: a small, quiet sign that the radio plays (and the TV is muted). */
    static TextView chip(Activity a) {
        TextView t = new TextView(a);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setSingleLine(true);
        t.setPadding(dp(a, 14), dp(a, 7), dp(a, 16), dp(a, 7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 20));
        bg.setColor(0xB3000000);
        bg.setStroke(dp(a, 1), 0x33FFFFFF);
        t.setBackground(bg);
        t.setVisibility(View.GONE);
        Handler h = new Handler(Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                String name = station();
                if (name.isEmpty()) t.setVisibility(View.GONE);
                else {
                    boolean ne = Lang.isNepali(a);
                    t.setText("●  " + (ne ? "रेडियो लाइभ · " : "Radio live · ") + name
                            + (tvMuted ? (ne ? "   ·   🔇 टिभी म्युट" : "   ·   🔇 TV muted") : ""));
                    t.setVisibility(View.VISIBLE);
                }
                h.postDelayed(this, 1000);
            }
        };
        t.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                h.removeCallbacks(tick);
                tick.run();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                h.removeCallbacks(tick);
            }
        });
        return t;
    }
}
