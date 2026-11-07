package com.ghar.tv;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Ready-made sound modes (an equaliser inside the player, so it works on every TV):
 * Normal, Clear voice, Music, Movie, Bass boost, Night. Filters are the standard "audio EQ cookbook"
 * shelves and peaks. A little headroom is kept so boosts don't crackle (the Leveller after it evens
 * the loudness back out).
 */
final class SoundEffects extends BaseAudioProcessor {

    static final int NORMAL = 0, VOICE = 1, MUSIC = 2, MOVIE = 3, BASS = 4, NIGHT = 5;

    /** One band: type (0 low shelf, 1 peak, 2 high shelf), frequency, gain dB, Q. */
    private static final double[][][] MODES = {
            {},                                                                      // Normal
            {{0, 180, -5, 0.7}, {1, 2500, 6, 0.9}, {1, 5000, 2, 1.2}},               // Clear voice
            {{0, 90, 4, 0.7}, {1, 1000, -1.5, 0.8}, {2, 9000, 3.5, 0.7}},            // Music
            {{0, 70, 5, 0.7}, {1, 2200, 3, 1.0}, {2, 10000, 2, 0.7}},                // Movie
            {{0, 100, 8, 0.7}, {1, 250, 2, 1.0}},                                     // Bass boost
            {{0, 150, -7, 0.7}, {1, 2500, 5, 0.9}, {2, 9000, -2, 0.7}},              // Night (quiet bass, clear words)
    };

    private volatile int mode = NORMAL;
    private volatile boolean dirty = true;
    private int channels = 2;
    private int sampleRate = 48000;
    private double[][] coeffs = new double[0][]; // per band: b0 b1 b2 a1 a2
    private double[][] state = new double[0][];  // per band x channel: x1 x2 y1 y2
    private double headroom = 1;

    void setMode(int m) {
        mode = Math.max(0, Math.min(MODES.length - 1, m));
        dirty = true;
    }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat in) {
        if (in.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET;
        channels = Math.max(1, in.channelCount);
        sampleRate = Math.max(8000, in.sampleRate);
        dirty = true;
        return in;
    }

    private void build() {
        dirty = false;
        double[][] bands = MODES[mode];
        coeffs = new double[bands.length][];
        state = new double[bands.length * channels][4];
        double maxBoost = 0;
        for (int i = 0; i < bands.length; i++) {
            coeffs[i] = biquad((int) bands[i][0], bands[i][1], bands[i][2], bands[i][3], sampleRate);
            maxBoost = Math.max(maxBoost, bands[i][2]);
        }
        headroom = Math.pow(10, -maxBoost * 0.6 / 20);
    }

    /** RBJ audio EQ cookbook. */
    private static double[] biquad(int type, double f, double gainDb, double q, int rate) {
        double a = Math.pow(10, gainDb / 40);
        double w = 2 * Math.PI * Math.min(f, rate * 0.45) / rate;
        double cos = Math.cos(w), sin = Math.sin(w);
        double alpha = sin / (2 * q);
        double b0, b1, b2, a0, a1, a2;
        if (type == 1) { // peak
            b0 = 1 + alpha * a;
            b1 = -2 * cos;
            b2 = 1 - alpha * a;
            a0 = 1 + alpha / a;
            a1 = -2 * cos;
            a2 = 1 - alpha / a;
        } else {
            double s = 2 * Math.sqrt(a) * alpha;
            if (type == 0) { // low shelf
                b0 = a * ((a + 1) - (a - 1) * cos + s);
                b1 = 2 * a * ((a - 1) - (a + 1) * cos);
                b2 = a * ((a + 1) - (a - 1) * cos - s);
                a0 = (a + 1) + (a - 1) * cos + s;
                a1 = -2 * ((a - 1) + (a + 1) * cos);
                a2 = (a + 1) + (a - 1) * cos - s;
            } else { // high shelf
                b0 = a * ((a + 1) + (a - 1) * cos + s);
                b1 = -2 * a * ((a - 1) + (a + 1) * cos);
                b2 = a * ((a + 1) + (a - 1) * cos - s);
                a0 = (a + 1) - (a - 1) * cos + s;
                a1 = 2 * ((a - 1) - (a + 1) * cos);
                a2 = (a + 1) - (a - 1) * cos - s;
            }
        }
        return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int bytes = input.remaining();
        if (bytes == 0) return;
        if (dirty) build();
        ByteBuffer out = replaceOutputBuffer(bytes);
        if (coeffs.length == 0) { // Normal: untouched
            out.put(input);
            out.flip();
            return;
        }
        ByteBuffer in = input.order(ByteOrder.LITTLE_ENDIAN);
        int start = in.position();
        int samples = bytes / 2;
        for (int i = 0; i < samples; i++) {
            int ch = i % channels;
            double x = in.getShort(start + i * 2) * headroom;
            for (int b = 0; b < coeffs.length; b++) {
                double[] k = coeffs[b];
                double[] st = state[b * channels + ch];
                double y = k[0] * x + k[1] * st[0] + k[2] * st[1] - k[3] * st[2] - k[4] * st[3];
                st[1] = st[0];
                st[0] = x;
                st[3] = st[2];
                st[2] = y;
                x = y;
            }
            if (x > 32767) x = 32767;
            else if (x < -32768) x = -32768;
            out.putShort((short) x);
        }
        if ((bytes & 1) == 1) out.put(in.get(start + bytes - 1));
        in.position(start + bytes);
        out.flip();
    }

    @Override
    protected void onFlush() {
        for (double[] s : state) java.util.Arrays.fill(s, 0);
    }
}
