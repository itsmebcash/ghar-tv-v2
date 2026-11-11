package com.ghar.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject

/** A YouTube video or playlist, played in the official YouTube player on the TV. */
data class Yt(val type: String, val id: String) {
    val isList get() = type == "list"
}

/** A spoken instruction sent by the phone: voice file → silent pause → optional bell. */
data class Instr(val voice: String, val speechMs: Long, val pauseSec: Int, val bell: Boolean)

/** One queued item, exactly as Bcash Player describes it. */
data class Item(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val url: String,
    val repeat: Int,
    val speed: Float,
    val gain: Float,
    val playlist: String,
    val round: Int,
    val rounds: Int,
    val group: String,
    val instr: Instr?,
    val yt: Yt? = null,
    /** A live internet radio stream, played straight from its address (never cached). */
    val stream: String = "",
) {
    fun toMediaItem(base: String): MediaItem {
        val uri = when {
            yt != null -> Uri.parse("$YT_SCHEME://$id")
            stream.isNotEmpty() -> Uri.parse(stream)
            instr != null -> Uri.parse("$INSTR_SCHEME://$id")
            else -> Uri.parse("$base/$url")
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            // Same file = same cache entry, even in a new session (the address secret changes).
            .setCustomCacheKey(if (instr != null || yt != null || stream.isNotEmpty()) null else url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist.ifEmpty { null })
                    .setAlbumTitle(album.ifEmpty { null })
                    .build()
            )
            .build()
    }

    companion object {
        const val INSTR_SCHEME = "bcash-instr"
        const val YT_SCHEME = "bcash-yt"

        fun fromJson(o: JSONObject): Item {
            val i = o.optJSONObject("instr")
            return Item(
                id = o.getString("id"),
                title = o.optString("title", "Song"),
                artist = o.optString("artist", ""),
                album = o.optString("album", ""),
                durationMs = o.optLong("dur", 0L),
                url = o.optString("url", ""),
                repeat = o.optInt("repeat", 1).coerceIn(1, 108),
                speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 3f),
                gain = o.optDouble("gain", 0.0).toFloat().coerceIn(-12f, 12f),
                playlist = o.optString("pl", ""),
                round = o.optInt("round", 1),
                rounds = o.optInt("rounds", 1),
                group = o.optString("group", ""),
                instr = i?.let {
                    Instr(it.optString("voice", ""), it.optLong("speechMs", 0L), it.optInt("pauseSec", 0), it.optBoolean("bell", true))
                },
                yt = o.optJSONObject("yt")?.let { Yt(it.optString("type", "video"), it.optString("id")) }?.takeIf { it.id.isNotEmpty() },
                stream = o.optString("stream", ""),
            )
        }
    }
}

enum class Loop { OFF, ALL, ONE }

/** Everything the TV knows about what to play. Main thread only. */
object TvHub {
    val items = HashMap<String, Item>()

    /** Phone's song server, e.g. http://192.168.1.20:8820/secret */
    var phoneBase = ""
    var phoneName = ""

    var loop = Loop.OFF
    var fadeMs = 2000L
    var eq = "NORMAL"
    var evenOut = false
    var single = false

    /** True while the TV plays something chosen on the TV itself (its own radio), not from a phone. */
    var local = false

    /** Live radio: the song or show the station says is on air ("" if it doesn't say). */
    var onAir = ""

    /** The TV's own radio was stopped with the big Stop button (stations kept, OK plays again). */
    var radioStopped = false

    /** The radio is being started by itself (station list loading). */
    var starting = false

    /** Completed plays of the current song in this cycle. */
    var repeatDone = 0

    /** Shown on the TV screen, e.g. "Waiting for the phone…". */
    var message = ""

    /** Picture sent from the phone to show while the music plays (path on the phone server), "" = none. */
    var picture = ""
    var pictureVersion = 0
    @Volatile var pictureBitmap: android.graphics.Bitmap? = null

    fun wrapsAround() = loop == Loop.ALL && !single

    fun applySettings(o: JSONObject) {
        loop = runCatching { Loop.valueOf(o.optString("loop", loop.name)) }.getOrDefault(loop)
        fadeMs = o.optLong("fadeMs", fadeMs)
        eq = o.optString("eq", eq)
        evenOut = o.optBoolean("evenOut", evenOut)
        single = o.optBoolean("single", single)
    }
}
