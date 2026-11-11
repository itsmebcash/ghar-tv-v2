package com.ghar.player

import android.content.Context
import android.media.AudioManager
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Fine volume for the remote's volume keys. Many TVs have only a few coarse volume steps
 * (one too loud, the next too soft). Here the volume goes 0–100 in small steps: the TV's own
 * volume is set to the step just above, and the player turns itself down a little to land
 * exactly in between. Changes glide, they never jump.
 */
object TvVolume {
    /** 0–100, as shown on screen. */
    var level = -1
        private set

    /** How much the player turns itself down below the TV's step (0–1). */
    @Volatile var gain = 1f
        private set

    private const val STEP = 2

    private fun am(c: Context) = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun prefs(c: Context) = c.getSharedPreferences("volume", Context.MODE_PRIVATE)

    fun init(c: Context) {
        if (level >= 0) return
        val am = am(c)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val sys = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val saved = prefs(c).getInt("level", -1)
        val savedSys = prefs(c).getInt("sys", -1)
        // Keep our fine level unless the TV volume was changed some other way meanwhile.
        level = if (saved >= 0 && savedSys == sys) saved else (sys * 100f / max).roundToInt()
        apply(c, save = false)
    }

    /** Volume key pressed: +1 up, −1 down. */
    fun step(c: Context, dir: Int) {
        init(c)
        level = (level + dir * STEP).coerceIn(0, 100)
        apply(c)
    }

    private fun apply(c: Context, save: Boolean = true) {
        val am = am(c)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = level / 100f * max
        val idx = if (level == 0) 0 else ceil(target.toDouble()).toInt().coerceIn(1, max)
        val newGain = if (idx == 0) 0f else (target / idx).coerceIn(0f, 1f)
        val oldIdx = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Change in the order that avoids a loud blip: turn the player down before raising the TV.
        if (idx > oldIdx) { gain = newGain; PlaybackService.instance?.applyGainNow() }
        if (idx != oldIdx) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0) }
        if (idx <= oldIdx) { gain = newGain; PlaybackService.instance?.applyGainNow() }
        if (save) prefs(c).edit().putInt("level", level).putInt("sys", idx).apply()
    }
}
