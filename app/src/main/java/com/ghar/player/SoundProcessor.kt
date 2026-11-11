package com.ghar.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** One EQ filter stage. */
sealed class Filter {
    data class Peak(val hz: Double, val db: Double, val q: Double = 1.0) : Filter()
    data class LowShelf(val hz: Double, val db: Double) : Filter()
    data class HighShelf(val hz: Double, val db: Double) : Filter()
    data class HighPass(val hz: Double, val q: Double = 0.707) : Filter()
}

/**
 * Sound presets, processed inside the app (same result on every phone).
 * [side] scales the stereo "side" signal: 0 = centre only (voice), >1 = wider.
 */
enum class SoundPreset(
    val label: String,
    val description: String,
    val filters: List<Filter> = emptyList(),
    val side: Double = 1.0,
    val makeupDb: Double = 0.0,
) {
    NORMAL("Normal", "No change"),
    VOICE_ONLY(
        "Voice only",
        "Music pushed far back — keeps the centre voice, removes bass and wide instruments",
        listOf(
            Filter.HighPass(220.0), Filter.HighPass(220.0),
            Filter.Peak(380.0, -4.0, 1.0),
            Filter.Peak(1100.0, 2.0, 1.0),
            Filter.Peak(3000.0, 6.0, 1.1),
            Filter.HighShelf(6500.0, -14.0),
        ),
        side = 0.0, makeupDb = 3.0
    ),
    CLEAR_VOICE(
        "Clear voice",
        "Voice clearly in front, music softer",
        listOf(
            Filter.HighPass(140.0),
            Filter.LowShelf(250.0, -4.0),
            Filter.Peak(2500.0, 4.5, 1.0),
            Filter.HighShelf(8000.0, -5.0),
        ),
        side = 0.35, makeupDb = 2.0
    ),
    SURROUND(
        "Surround music",
        "Cinema-style: wider stereo, deeper bass, crisp highs",
        listOf(
            Filter.LowShelf(100.0, 5.0),
            Filter.Peak(350.0, -2.0, 1.0),
            Filter.Peak(2500.0, 1.5, 1.0),
            Filter.HighShelf(9000.0, 4.5),
        ),
        side = 1.6, makeupDb = -1.5
    ),
    DEVOTIONAL(
        "Warm devotional",
        "Fuller, softer, temple-like sound",
        listOf(
            Filter.LowShelf(150.0, 3.0),
            Filter.Peak(2800.0, -1.5, 1.0),
            Filter.HighShelf(8000.0, -2.5),
        ),
        side = 1.1, makeupDb = -1.0
    ),
    NIGHT(
        "Night",
        "Gentle on the ears for late listening",
        listOf(
            Filter.LowShelf(120.0, -4.0),
            Filter.Peak(3000.0, -2.0, 1.0),
            Filter.HighShelf(6000.0, -6.0),
        ),
        side = 0.8
    );

    companion object {
        fun of(name: String) = when (name) {
            "LOW_MUSIC" -> CLEAR_VOICE // old name
            else -> entries.firstOrNull { it.name == name } ?: NORMAL
        }
    }
}

/** Latest audio loudness (0..1), used by the player's particle background. */
object AudioLevel {
    @Volatile var level = 0f
    @Volatile var updatedAt = 0L
}

/**
 * The app's own sound engine, inside the audio pipeline:
 * song volume → stereo width → EQ → "even out volume" → soft limiter → level meter.
 * Settings can change at any time from the main thread.
 */
@OptIn(UnstableApi::class)
class SoundProcessor : BaseAudioProcessor() {
    @Volatile var preset: SoundPreset = SoundPreset.NORMAL
    @Volatile var evenOut = false
    @Volatile var songGainDb = 0f

    private var channels = 2
    private var rate = 44100
    private var builtFor: SoundPreset? = null
    private var stages: Array<Biquad> = emptyArray()   // per channel × filters
    private var makeup = 1f
    private var gain = 1f
    private var envCoef = 0f
    private var upCoef = 0f
    private var downCoef = 0f
    private var gainCoef = 0f
    private var env = 0f
    private var level = 1f
    private var meterSum = 0f
    private var meterCount = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount !in 1..2) return AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        rate = inputAudioFormat.sampleRate
        envCoef = exp(-1.0 / (0.3 * rate)).toFloat()
        upCoef = exp(-1.0 / (3.0 * rate)).toFloat()
        downCoef = exp(-1.0 / (0.5 * rate)).toFloat()
        gainCoef = exp(-1.0 / (0.05 * rate)).toFloat()
        builtFor = null
        return inputAudioFormat
    }

    private fun rebuild(p: SoundPreset) {
        val list = ArrayList<Biquad>()
        repeat(channels) { p.filters.forEach { f -> list += Biquad.of(f, rate.toDouble()) } }
        stages = list.toTypedArray()
        makeup = 10.0.pow(p.makeupDb / 20).toFloat()
        builtFor = p
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        inputBuffer.order(ByteOrder.nativeOrder())
        val p = preset
        if (builtFor != p) rebuild(p)
        val even = evenOut
        val targetGain = 10f.pow(songGainDb / 20f)
        val bypass = p == SoundPreset.NORMAL && !even && abs(targetGain - 1f) < 0.001f && abs(gain - 1f) < 0.001f
        val perCh = p.filters.size
        val side = p.side.toFloat()
        val frames = size / (2 * channels)
        val x = FloatArray(2)

        for (n in 0 until frames) {
            for (c in 0 until channels) x[c] = inputBuffer.short / 32768f
            var peak: Float
            if (bypass) {
                for (c in 0 until channels) out.putShort((x[c] * 32767f).toInt().toShort())
                peak = if (channels == 2) (x[0] * x[0] + x[1] * x[1]) / 2 else x[0] * x[0]
            } else {
                gain = gainCoef * gain + (1f - gainCoef) * targetGain
                for (c in 0 until channels) x[c] *= gain
                if (channels == 2 && side != 1f) {
                    val m = (x[0] + x[1]) * 0.5f
                    val s = (x[0] - x[1]) * 0.5f * side
                    x[0] = m + s; x[1] = m - s
                }
                for (c in 0 until channels) {
                    var v = x[c]
                    val base = c * perCh
                    for (k in 0 until perCh) v = stages[base + k].process(v)
                    x[c] = v * makeup
                }
                var sq = 0f
                for (c in 0 until channels) sq += x[c] * x[c]
                sq /= channels
                if (even) {
                    env = envCoef * env + (1f - envCoef) * sq
                    val rms = sqrt(env)
                    val desired = if (rms < 0.003f) level else (0.12f / rms).coerceIn(0.25f, 4f)
                    level = if (desired < level) downCoef * level + (1f - downCoef) * desired
                    else upCoef * level + (1f - upCoef) * desired
                } else level = 1f
                for (c in 0 until channels) {
                    val v = limit(x[c] * level)
                    x[c] = v
                    out.putShort((v * 32767f).toInt().toShort())
                }
                peak = sq * level * level
            }
            meterSum += peak
            meterCount++
        }
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
        if (meterCount > rate / 30) { // ~30 updates per second
            val rms = sqrt(meterSum / meterCount)
            AudioLevel.level = (rms * 4.5f).coerceIn(0f, 1f)
            AudioLevel.updatedAt = System.currentTimeMillis()
            meterSum = 0f; meterCount = 0
        }
    }

    override fun onFlush() {
        stages.forEach { it.reset() }
    }

    override fun onReset() {
        env = 0f; level = 1f; gain = 1f
        builtFor = null
    }

    companion object {
        private const val KNEE = 0.9f
        fun limit(v: Float): Float {
            val a = abs(v)
            if (a <= KNEE) return v
            return sign(v) * (KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE)))
        }
    }
}

/** Standard RBJ biquad filter (single channel). */
class Biquad(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float,
) {
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0f; z2 = 0f
    }

    companion object {
        fun of(f: Filter, fs: Double): Biquad = when (f) {
            is Filter.Peak -> {
                val a = 10.0.pow(f.db / 40)
                val w = 2 * PI * f.hz / fs
                val alpha = sin(w) / (2 * f.q)
                make(1 + alpha * a, -2 * cos(w), 1 - alpha * a, 1 + alpha / a, -2 * cos(w), 1 - alpha / a)
            }
            is Filter.LowShelf -> {
                val a = 10.0.pow(f.db / 40)
                val w = 2 * PI * f.hz / fs
                val alpha = sin(w) / 2 * sqrt(2.0)
                val c = cos(w); val sa = 2 * sqrt(a) * alpha
                make(
                    a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                    (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa
                )
            }
            is Filter.HighShelf -> {
                val a = 10.0.pow(f.db / 40)
                val w = 2 * PI * f.hz / fs
                val alpha = sin(w) / 2 * sqrt(2.0)
                val c = cos(w); val sa = 2 * sqrt(a) * alpha
                make(
                    a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                    (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa
                )
            }
            is Filter.HighPass -> {
                val w = 2 * PI * f.hz / fs
                val alpha = sin(w) / (2 * f.q)
                val c = cos(w)
                make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }
        }

        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())
    }
}
