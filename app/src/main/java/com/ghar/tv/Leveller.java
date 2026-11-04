package com.ghar.tv;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Evens out the volume between channels: listens to how loud the sound is, slowly turns quiet
 * channels up and loud ones down towards one level, and a limiter stops sudden blasts and
 * crackling. Runs inside the player (16-bit sound); costs no data and very little battery.
 */
final class Leveller extends BaseAudioProcessor {

    /** Where every channel should sit (RMS, dB below full scale): a comfortable TV level. */
    private static final double TARGET_DB = -20;
    private static final double MAX_BOOST_DB = 12;
    private static final double MAX_CUT_DB = -12;
    /** Quieter than this is silence or a pause: don't turn it up. */
    private static final double SILENCE_DB = -50;
    /** Keep peaks just under the top, so turning up never crackles. */
    private static final double PEAK_LIMIT = 0.94 * 32767;

    private volatile boolean enabled = true;
    private volatile double startDb = Double.NaN; // a remembered level for the next channel

    private double loudDb = Double.NaN; // running estimate of the channel's loudness
    private double gainDb;
    private long heardFrames; // how much sound has been measured for this channel
    private int channels = 2;
    private int sampleRate = 48000;

    void setEnabled(boolean on) {
        enabled = on;
    }

    /** A new channel: start from its remembered gain (or neutral) and listen again. */
    void reset(double rememberedGainDb) {
        startDb = Double.isNaN(rememberedGainDb) ? 0 : rememberedGainDb;
    }

    /** The settled gain for this channel, or NaN if it hasn't listened long enough to know. */
    double gainDb() {
        return heardFrames > sampleRate * 8L ? gainDb : Double.NaN;
    }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat in) {
        if (in.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET; // leave other formats alone
        channels = Math.max(1, in.channelCount);
        sampleRate = Math.max(8000, in.sampleRate);
        return in;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int bytes = input.remaining();
        if (bytes == 0) return;
        ByteBuffer out = replaceOutputBuffer(bytes);
        double s = startDb;
        if (!Double.isNaN(s)) {
            startDb = Double.NaN;
            gainDb = s;
            loudDb = Double.NaN;
            heardFrames = 0;
        }
        ByteBuffer in = input.order(ByteOrder.LITTLE_ENDIAN);
        int start = in.position();
        int samples = bytes / 2;
        int frames = Math.max(1, samples / channels);

        if (!enabled) {
            out.put(in);
            out.flip();
            return;
        }

        // 1. How loud is this piece (before any change)?
        double sum = 0;
        int peak = 0;
        for (int i = 0; i < samples; i++) {
            int v = in.getShort(start + i * 2);
            sum += (double) v * v;
            int a = Math.abs(v);
            if (a > peak) peak = a;
        }
        double rms = Math.sqrt(sum / Math.max(1, samples));
        double db = 20 * Math.log10(Math.max(rms, 1) / 32767.0);
        double seconds = frames / (double) sampleRate;

        // 2. Update the running loudness (quick for the first seconds of a channel, then slow).
        if (db > SILENCE_DB) {
            heardFrames += frames;
            double tau = heardFrames < sampleRate * 3L ? 0.8 : 4.0;
            double k = 1 - Math.exp(-seconds / tau);
            loudDb = Double.isNaN(loudDb) ? db : loudDb + (db - loudDb) * k;
        }

        // 3. Move the gain towards the target: down faster than up.
        double from = gainDb;
        if (!Double.isNaN(loudDb)) {
            double want = Math.max(MAX_CUT_DB, Math.min(MAX_BOOST_DB, TARGET_DB - loudDb));
            double step = (want > gainDb ? 3.0 : 8.0) * seconds; // dB per second
            if (heardFrames < sampleRate * 3L) step *= 3; // settle quickly on a new channel
            gainDb += Math.max(-step, Math.min(step, want - gainDb));
        }

        // 4. Limiter: never let this piece go over the top.
        double to = gainDb;
        double linTo = Math.pow(10, to / 20);
        if (peak * linTo > PEAK_LIMIT) {
            linTo = PEAK_LIMIT / Math.max(1, peak);
            to = 20 * Math.log10(linTo);
            gainDb = Math.min(gainDb, to + 1); // remember some of it
        }
        double linFrom = Math.min(Math.pow(10, from / 20), linTo * 1.5);

        // 5. Apply, gliding from the old gain to the new one across the piece (no clicks).
        for (int f = 0; f < frames; f++) {
            double g = linFrom + (linTo - linFrom) * f / frames;
            for (int ch = 0; ch < channels; ch++) {
                int idx = start + (f * channels + ch) * 2;
                if (idx + 1 >= start + bytes) break;
                double v = in.getShort(idx) * g;
                if (v > 32767) v = 32767;
                else if (v < -32768) v = -32768;
                out.putShort((short) v);
            }
        }
        // any odd leftover bytes pass through
        int written = frames * channels * 2;
        for (int i = written; i < bytes; i++) out.put(in.get(start + i));
        in.position(start + bytes);
        out.flip();
    }

    @Override
    protected void onReset() {
        loudDb = Double.NaN;
        gainDb = 0;
        heardFrames = 0;
    }
}
