package com.ghar.player

import com.ghar.tv.R

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ghar.player.shared.MiniHttp
import com.ghar.player.shared.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ghar Player on the TV: a simple speaker for Bcash Player.
 *
 * The phone sends the queue (with every song's repeat count, speed and volume, spoken
 * instructions and sound settings); the TV streams each file from the phone over Wi-Fi and
 * plays it with the same engine as the phone app. The phone polls [stateJson] to show progress.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        @Volatile var instance: PlaybackService? = null
            private set

        fun ensure(c: Context) {
            runCatching { c.startService(Intent(c, PlaybackService::class.java)) }
        }

        const val BELL_MS = 4824L

        @Volatile private var cacheInst: androidx.media3.datasource.cache.SimpleCache? = null

        /**
         * Songs that have been streamed are kept in a local cache (oldest removed first), so the
         * music keeps playing even if the phone is busy, on a call, or briefly off the Wi-Fi.
         */
        fun cache(c: Context): androidx.media3.datasource.cache.SimpleCache = cacheInst ?: synchronized(this) {
            cacheInst ?: run {
                val dir = java.io.File(c.cacheDir, "songs")
                val free = c.cacheDir.usableSpace
                val max = (free / 3).coerceIn(200L shl 20, 1536L shl 20)
                androidx.media3.datasource.cache.SimpleCache(
                    dir,
                    androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(max),
                    androidx.media3.database.StandaloneDatabaseProvider(c)
                ).also { cacheInst = it }
            }
        }
    }

    private lateinit var dataFactory: androidx.media3.datasource.cache.CacheDataSource.Factory
    private val prefetchExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val prefetchGen = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var writer: androidx.media3.datasource.cache.CacheWriter? = null

    private lateinit var exo: ExoPlayer
    private val audioAttrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    private var focusOn = true
    /** When the YouTube video was first seen paused although it should play (ms, 0 = not paused). */
    private var ytStalledSince = 0L
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val sound = SoundProcessor()
    private var server: MiniHttp? = null
    private var nsd: NsdManager? = null
    private var announce: NsdManager.RegistrationListener? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var retries = 0
    @Volatile var port = -1
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(sound))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8000).setReadTimeoutMs(20000).setAllowCrossProtocolRedirects(true)
        dataFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(cache(this))
            .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, http))
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Keep buffering well ahead, and keep retrying quietly if the phone is slow to answer.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 300_000, 2_500, 5_000)
            .build()
        exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(TvSourceFactory(this, dataFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttrs, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(listener)
        YtBridge.onEnded = { ytEnded() }
        YtBridge.onError = { code ->
            TvHub.message = when (code) {
                100 -> "This YouTube video was removed or is private — skipping"
                101, 150 -> "The owner doesn't allow this video outside YouTube — skipping"
                152, 153 -> "YouTube refused the player on this TV — skipping"
                else -> "YouTube error $code — skipping"
            }
            handler.postDelayed({ if (current()?.yt != null) advance() }, 4000)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, NavPlayer(exo)).setSessionActivity(open).build()
        // Register the session with the service: while something plays, the service becomes a
        // foreground "media playback" service with a notification, so Android doesn't close it
        // when the TV app is in the background (radio keeps playing from the home screen).
        session?.let { addSession(it) }
        applySound()

        server = MiniHttp { r -> ReceiverServer.handle(this, r) }.also { port = it.start(Protocol.TV_PORTS) }
        if (port > 0) announce(port)
        handler.post(snapshotTick)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** Swiped away / app closed from recents: keep playing if something is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        prefetchGen.incrementAndGet()
        writer?.cancel()
        prefetchExec.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        runCatching { announce?.let { nsd?.unregisterService(it) } }
        server?.stop()
        wifiLock?.let { if (it.isHeld) it.release() }
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ helpers

    /** Runs on the main thread and waits for the result (HTTP requests come on worker threads). */
    fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        handler.post {
            result = runCatching(block)
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) error("timeout")
        return result!!.getOrThrow()
    }

    private fun current(): Item? = TvHub.items[exo.currentMediaItem?.mediaId]

    private fun applySound() {
        sound.preset = SoundPreset.of(TvHub.eq)
        sound.evenOut = TvHub.evenOut
    }

    private fun applySongSettings() {
        val it = current()
        val speed = it?.speed ?: 1f
        if (it?.yt != null) {
            if (exo.playbackParameters.speed != 1f) exo.playbackParameters = PlaybackParameters(1f)
            YtBridge.rate(speed)
            return
        }
        if (exo.playbackParameters.speed != speed) exo.playbackParameters = PlaybackParameters(speed)
        sound.songGainDb = it?.gain ?: 0f
    }

    // ---------------- YouTube items ----------------

    private fun isYt() = current()?.yt != null

    /** Starts / stops the YouTube player to match the current queue item. */
    private fun syncYt() {
        val it = current()
        // The YouTube player needs the audio itself: our silent placeholder must not take
        // audio focus from it (that paused the video). Songs use audio focus as usual.
        val wantFocus = it?.yt == null
        if (focusOn != wantFocus) { focusOn = wantFocus; exo.setAudioAttributes(audioAttrs, wantFocus) }
        ytStalledSince = 0L
        if (it?.yt == null) { YtBridge.stop(); return }
        if (YtBridge.current?.id != it.id) {
            YtBridge.load(it, 0, it.speed)
            if (YtBridge.view == null) {
                // The video needs the TV screen: bring Ghar Player to the front.
                runCatching {
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }
        }
        if (exo.playWhenReady) YtBridge.play() else YtBridge.pause()
    }

    /** A video (or a whole YouTube playlist) finished: repeat it, or go to the next item. */
    private fun ytEnded() {
        val it = current() ?: return
        if (it.yt == null) return
        when {
            TvHub.loop == Loop.ONE -> { TvHub.repeatDone++; YtBridge.load(it, 0, it.speed) }
            TvHub.repeatDone < it.repeat - 1 -> { TvHub.repeatDone++; YtBridge.load(it, 0, it.speed) }
            else -> advance()
        }
    }

    /** If the video paused (or never started) although it should play, start it again. */
    private fun ytWatchdog() {
        if (!isYt() || !exo.playWhenReady || YtBridge.view == null) { ytStalledSince = 0L; return }
        val s = YtBridge.state
        val stuck = s == 2 || s == 5 || s == -1
        if (!stuck) { ytStalledSince = 0L; return }
        val now = SystemClock.elapsedRealtime()
        if (ytStalledSince == 0L) ytStalledSince = now
        else if (now - ytStalledSince > (if (s == 2) 1500 else 3000)) {
            ytStalledSince = now
            YtBridge.play()
        }
    }

    private fun advance() {
        val next = if (exo.mediaItemCount == 0) C.INDEX_UNSET
        else exo.currentTimeline.getNextWindowIndex(
            exo.currentMediaItemIndex, if (TvHub.wrapsAround()) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF, false
        )
        if (next == C.INDEX_UNSET) {
            exo.pause(); YtBridge.pause()
        } else if (next == exo.currentMediaItemIndex) {
            TvHub.repeatDone = 0
            current()?.let { YtBridge.load(it, 0, it.speed) }
        } else exo.seekTo(next, 0)
    }

    private fun applyRepeatMode() {
        if (exo.mediaItemCount == 0) return
        val target = current()?.repeat ?: 1
        val mode = when {
            TvHub.loop == Loop.ONE -> Player.REPEAT_MODE_ONE
            TvHub.repeatDone < target - 1 -> Player.REPEAT_MODE_ONE
            TvHub.wrapsAround() -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        if (exo.repeatMode != mode) exo.repeatMode = mode
    }

    private fun setWifiLock(on: Boolean) {
        if (on) {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ghar:stream")
            }
            wifiLock?.let { if (!it.isHeld) it.acquire() }
        } else wifiLock?.let { if (it.isHeld) it.release() }
    }

    // ------------------------------------------------------------ commands from the phone

    /** Radio picked on the TV itself: the whole list goes in, so ◀ ▶ change station. */
    fun playStations(list: List<TvRadio.Station>, index: Int, from: String) {
        if (list.isEmpty()) return
        TvHub.local = true
        TvHub.phoneBase = ""; TvHub.phoneName = ""
        TvHub.single = false
        TvHub.message = ""; TvHub.onAir = ""
        YtBridge.stop()
        prefetchGen.incrementAndGet(); writer?.cancel()
        val items = list.mapIndexed { k, st ->
            Item(
                id = "radio-$k-${st.url.hashCode()}", title = st.name, artist = st.place, album = st.quality,
                durationMs = 0L, url = "", repeat = 1, speed = 1f, gain = 0f, playlist = from,
                round = 1, rounds = 1, group = "", instr = null, stream = st.url,
            )
        }
        TvHub.items.clear()
        items.forEach { TvHub.items[it.id] = it }
        TvHub.repeatDone = 0
        retries = 0
        exo.setMediaItems(items.map { it.toMediaItem("") }, index.coerceIn(0, items.size - 1), 0L)
        exo.prepare()
        exo.play()
        applySongSettings()
        applyRepeatMode()
        TvHub.radioStopped = false
        TvRadio.saveLast(this, list, index, from)
        val uuid = list.getOrNull(index)?.uuid.orEmpty()
        Thread { TvRadio.countClick(uuid) }.start()
    }

    /** Radio "Stop": silence and drop the stream, but keep the stations so OK plays again. */
    fun radioStop() {
        exo.pause(); exo.stop()
        TvHub.radioStopped = true
        TvHub.onAir = ""
    }

    fun radioPlay() {
        if (exo.mediaItemCount == 0) return
        TvHub.radioStopped = false
        exo.prepare(); exo.play()
    }

    /**
     * When the TV app opens and no phone is playing here: play the last station again
     * (or the first of "For you" the very first time).
     */
    fun autoStartRadio() {
        if (exo.mediaItemCount > 0) return
        val last = TvRadio.last(this)
        if (last != null) { playStations(last.stations, last.index, last.from); return }
        if (TvHub.starting) return
        TvHub.starting = true
        TvHub.message = ""
        Thread {
            val list = runCatching { TvRadio.load(this, TvRadio.Category.FOR_YOU) }.getOrDefault(emptyList())
            handler.post {
                TvHub.starting = false
                if (exo.mediaItemCount > 0) return@post
                if (list.isNotEmpty()) playStations(list, 0, TvRadio.Category.FOR_YOU.label)
                else {
                    // No internet yet (TV just switched on?): try again in a little while.
                    TvHub.message = TvRadio.t("इन्टरनेट पर्खँदै… रेडियो आफैं सुरु हुनेछ", "Waiting for the internet… the radio will start by itself")
                    handler.postDelayed({ if (exo.mediaItemCount == 0) autoStartRadio() }, 10_000)
                }
            }
        }.start()
    }

    fun load(o: JSONObject) {
        TvHub.local = false
        TvHub.onAir = ""
        TvHub.phoneBase = o.optString("phone", "").trimEnd('/')
        TvHub.phoneName = o.optString("phoneName", "Phone")
        o.optJSONObject("settings")?.let { TvHub.applySettings(it) }
        applySound()
        val arr = o.optJSONArray("items") ?: JSONArray()
        val all = (0 until arr.length()).map { Item.fromJson(arr.getJSONObject(it)) }
        // Radio is played and chosen on the TV itself: phones send songs and YouTube only.
        val list = all.filter { it.stream.isEmpty() }
        TvHub.items.clear()
        list.forEach { TvHub.items[it.id] = it }
        TvHub.repeatDone = 0
        TvHub.message = ""
        retries = 0
        if (list.isEmpty()) {
            exo.clearMediaItems(); return
        }
        val index = o.optInt("index", 0).coerceIn(0, list.size - 1)
        exo.setMediaItems(list.map { it.toMediaItem(TvHub.phoneBase) }, index, o.optLong("position", 0L))
        exo.prepare()
        exo.playWhenReady = o.optBoolean("play", true)
        applySongSettings()
        applyRepeatMode()
        schedulePrefetch()
    }

    /** Picture to show behind the music ("" removes it). */
    fun picture(o: JSONObject) {
        val url = o.optString("url", "")
        TvHub.picture = url
        if (url.isEmpty()) {
            TvHub.pictureBitmap = null; TvHub.pictureVersion++; return
        }
        val full = "${TvHub.phoneBase}/$url"
        prefetchExec.execute {
            val bmp = runCatching {
                val c = java.net.URL(full).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 20000
                val bytes = c.inputStream.use { it.readBytes() }
                c.disconnect()
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= 1920 || opts.outHeight / (sample * 2) >= 1080) sample *= 2
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
            handler.post { if (TvHub.picture == url) { TvHub.pictureBitmap = bmp; TvHub.pictureVersion++ } }
        }
    }

    // ---------------- download the next songs to the TV cache ----------------

    private fun fileKeyOf(it: Item): Pair<Uri, String>? =
        if (it.stream.isNotEmpty()) null
        else if (it.instr != null) {
            if (it.instr.voice.isEmpty()) null
            else Uri.parse("${TvHub.phoneBase}/${it.instr.voice}") to "${it.instr.voice}/${it.instr.speechMs}"
        } else if (it.url.isNotEmpty()) Uri.parse("${TvHub.phoneBase}/${it.url}") to it.url else null

    /** Downloads the current song and the next ones (up to 40 files) in the background. */
    private fun schedulePrefetch() {
        if (exo.mediaItemCount == 0 || TvHub.phoneBase.isEmpty()) return
        val start = exo.currentMediaItemIndex.coerceAtLeast(0)
        val n = exo.mediaItemCount
        val seen = HashSet<String>()
        val todo = ArrayList<Pair<Uri, String>>()
        for (k in 0 until n) {
            val idx = (start + k) % n
            if (idx < start && !TvHub.wrapsAround()) break
            val item = TvHub.items[exo.getMediaItemAt(idx).mediaId] ?: continue
            val f = fileKeyOf(item) ?: continue
            if (seen.add(f.second)) todo += f
            if (todo.size >= 40) break
        }
        val gen = prefetchGen.incrementAndGet()
        writer?.cancel()
        prefetchExec.execute {
            for ((uri, key) in todo) {
                if (prefetchGen.get() != gen) return@execute
                if (fullyCached(key)) continue
                runCatching {
                    val w = androidx.media3.datasource.cache.CacheWriter(
                        dataFactory.createDataSource(),
                        androidx.media3.datasource.DataSpec.Builder().setUri(uri).setKey(key).build(),
                        null, null
                    )
                    writer = w
                    w.cache()
                }
            }
        }
    }

    private fun fullyCached(key: String): Boolean {
        val c = cache(this)
        val len = androidx.media3.datasource.cache.ContentMetadata.getContentLength(c.getContentMetadata(key))
        return len > 0 && c.isCached(key, 0, len)
    }

    fun control(o: JSONObject) {
        when (o.optString("action")) {
            "play" -> { if (exo.playbackState == Player.STATE_IDLE) exo.prepare(); if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0, 0); exo.play() }
            "pause" -> exo.pause()
            "toggle" -> if (exo.isPlaying) exo.pause() else { if (exo.playbackState == Player.STATE_IDLE) exo.prepare(); exo.play() }
            "seek" -> if (isYt()) YtBridge.seek(o.optLong("position", 0L)) else exo.seekTo(o.optLong("position", 0L))
            "jump" -> {
                val i = o.optInt("index", 0)
                if (i in 0 until exo.mediaItemCount) { exo.seekTo(i, o.optLong("position", 0L)); exo.play() }
            }
            "next" -> if (isYt() && YtBridge.hasNextInList) YtBridge.nextInList()
                else if (onStream()) tuneStation(1) else session?.player?.seekToNext()
            "previous" -> when {
                onStream() -> tuneStation(-1)
                isYt() && YtBridge.positionMs > 3000 -> YtBridge.seek(0)
                isYt() && YtBridge.hasPrevInList -> YtBridge.prevInList()
                else -> session?.player?.seekToPrevious()
            }
            "stop" -> {
                exo.pause(); exo.clearMediaItems(); TvHub.items.clear(); TvHub.message = ""
                TvHub.local = false; TvHub.onAir = ""
                TvHub.picture = ""; TvHub.pictureBitmap = null; TvHub.pictureVersion++
                YtBridge.stop()
                prefetchGen.incrementAndGet(); writer?.cancel()
            }
        }
    }

    private fun onStream() = TvHub.items[exo.currentMediaItem?.mediaId]?.stream?.isNotEmpty() == true

    /** Radio: next / previous station, going round the list like a tuner dial. */
    private fun tuneStation(step: Int) {
        val n = exo.mediaItemCount
        if (n == 0) return
        TvHub.radioStopped = false
        exo.seekTo(((exo.currentMediaItemIndex + step) % n + n) % n, C.TIME_UNSET)
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play()
    }

    fun queue(o: JSONObject) {
        when (o.optString("action")) {
            "add" -> {
                val arr = o.optJSONArray("items") ?: JSONArray()
                val list = (0 until arr.length()).map { Item.fromJson(arr.getJSONObject(it)) }
                list.forEach { TvHub.items[it.id] = it }
                val at = o.optInt("at", exo.mediaItemCount).coerceIn(0, exo.mediaItemCount)
                exo.addMediaItems(at, list.map { it.toMediaItem(TvHub.phoneBase) })
                schedulePrefetch()
            }
            "move" -> {
                val from = o.optInt("from", -1); val to = o.optInt("to", -1); val newIndex = o.optInt("newIndex", -1)
                if (from in 0 until exo.mediaItemCount && to in from + 1..exo.mediaItemCount && newIndex >= 0) {
                    exo.moveMediaItems(from, to, newIndex)
                }
            }
            "remove" -> {
                val from = o.optInt("from", -1); val to = o.optInt("to", -1)
                if (from in 0 until exo.mediaItemCount && to in from + 1..exo.mediaItemCount) exo.removeMediaItems(from, to)
            }
        }
        applyRepeatMode()
    }

    fun meta(o: JSONObject) {
        val arr = o.optJSONArray("items") ?: return
        for (k in 0 until arr.length()) {
            val m = arr.getJSONObject(k)
            val e = TvHub.items[m.optString("id")] ?: continue
            TvHub.items[e.id] = e.copy(
                repeat = m.optInt("repeat", e.repeat).coerceIn(1, 108),
                speed = m.optDouble("speed", e.speed.toDouble()).toFloat().coerceIn(0.5f, 3f),
                gain = m.optDouble("gain", e.gain.toDouble()).toFloat().coerceIn(-12f, 12f),
            )
        }
        applySongSettings()
        applyRepeatMode()
    }

    fun settings(o: JSONObject) {
        TvHub.applySettings(o)
        applySound()
        applyRepeatMode()
    }

    fun stateJson(): JSONObject {
        val it = current()
        val yt = it?.yt != null
        return JSONObject()
            .put("ok", true)
            .put("id", it?.id ?: "")
            .put("index", if (exo.mediaItemCount > 0) exo.currentMediaItemIndex else -1)
            .put("count", exo.mediaItemCount)
            .put("position", if (yt) YtBridge.positionMs else exo.currentPosition)
            .put("duration", if (yt) YtBridge.durationMs else if (exo.duration == C.TIME_UNSET) 0 else exo.duration)
            .put("playing", if (yt) exo.playWhenReady && YtBridge.playing else exo.isPlaying)
            .put("ytTitle", if (yt) YtBridge.title else "")
            .put("playWhenReady", exo.playWhenReady)
            .put("ended", exo.playbackState == Player.STATE_ENDED)
            .put("speed", exo.playbackParameters.speed.toDouble())
            .put("repeatDone", TvHub.repeatDone)
            .put("message", TvHub.message)
            // The TV switched to its own radio: a phone that was playing here lets go.
            .put("local", TvHub.local)
    }

    // ------------------------------------------------------------ for the TV screen

    data class Now(
        val item: Item?, val playing: Boolean, val position: Long, val duration: Long,
        val index: Int, val count: Int, val totalMs: Long, val leftMs: Long,
        /** Waiting for sound: connecting to a station or loading a song. */
        val buffering: Boolean = false,
    )

    fun now(): Now {
        val items = (0 until exo.mediaItemCount).mapNotNull { TvHub.items[exo.getMediaItemAt(it).mediaId] }
        fun playMs(i: Item) = (i.durationMs / i.speed).toLong()
        val total = items.sumOf { playMs(it) * it.repeat }
        val idx = exo.currentMediaItemIndex
        val cur = current()
        val yt = cur?.yt != null
        val pos = if (yt) YtBridge.positionMs else exo.currentPosition
        val dur = if (yt) YtBridge.durationMs else if (exo.duration == C.TIME_UNSET) 0 else exo.duration
        val left = if (cur == null || idx !in items.indices) total else {
            ((dur - pos).coerceAtLeast(0) / cur.speed).toLong() +
                (cur.repeat - 1 - TvHub.repeatDone).coerceAtLeast(0) * playMs(cur) +
                items.drop(idx + 1).sumOf { playMs(it) * it.repeat }
        }
        val buffering = !yt && exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING
        return Now(cur, if (yt) exo.playWhenReady && YtBridge.playing else exo.isPlaying, pos, dur, idx, exo.mediaItemCount, total, left, buffering)
    }

    fun toggle() = control(JSONObject().put("action", "toggle"))
    fun next() = control(JSONObject().put("action", "next"))
    fun previous() = control(JSONObject().put("action", "previous"))
    fun seekBy(ms: Long) = if (isYt()) YtBridge.seek((YtBridge.positionMs + ms).coerceAtLeast(0))
    else exo.seekTo((exo.currentPosition + ms).coerceAtLeast(0))
    fun stopAll() = control(JSONObject().put("action", "stop"))

    // ------------------------------------------------------------ engine (same rules as the phone)

    /** Fade in/out factor of the current song, and the fine-volume gain as heard now. */
    private var fadeFactor = 1f
    private var heardGain = TvVolume.gain

    /** Fine volume changed: glide to it smoothly. */
    fun applyGainNow() { handler.removeCallbacks(gainRamp); handler.post(gainRamp) }

    private val gainRamp = object : Runnable {
        override fun run() {
            heardGain += (TvVolume.gain - heardGain) * 0.35f
            if (kotlin.math.abs(TvVolume.gain - heardGain) < 0.004f) heardGain = TvVolume.gain
            exo.volume = (fadeFactor * heardGain).coerceIn(0f, 1f)
            if (heardGain != TvVolume.gain) handler.postDelayed(this, 16)
        }
    }

    private val volumeTick = object : Runnable {
        override fun run() {
            var factor = 1f
            val dur = exo.duration
            val pos = exo.currentPosition
            val voice = current()?.instr != null || current()?.yt != null
            if (TvHub.fadeMs > 0 && exo.isPlaying && !voice && dur > 0 && dur != C.TIME_UNSET && pos in 0..dur) {
                val f = (TvHub.fadeMs * exo.playbackParameters.speed).toLong().coerceAtMost(dur / 4).coerceAtLeast(1)
                factor = minOf(1f, pos.toFloat() / f, (dur - pos).toFloat() / f).coerceIn(0f, 1f)
                if (pos < f) factor = factor.coerceAtLeast(0.15f)
            }
            fadeFactor = factor
            val v = (factor * heardGain).coerceIn(0f, 1f)
            if (exo.volume != v) exo.volume = v
            ytWatchdog()
            if (exo.isPlaying) handler.postDelayed(this, 120)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (TvHub.local) TvRadio.saveLastIndex(this@PlaybackService, exo.currentMediaItemIndex)
            TvHub.onAir = ""
            retries = 0
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                val target = current()?.repeat ?: 1
                val before = TvHub.repeatDone
                val cycleDone = before + 1 >= target && TvHub.loop != Loop.ONE
                TvHub.repeatDone = if (cycleDone) 0 else before + 1
            } else {
                TvHub.repeatDone = 0
                applySongSettings()
                schedulePrefetch()
            }
            applyRepeatMode()
            syncYt()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!isYt()) return
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                exo.play(); return // never let YouTube's own sound pause the video
            }
            ytStalledSince = 0L
            if (playWhenReady) YtBridge.play() else YtBridge.pause()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            applyRepeatMode()
            applySongSettings()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(volumeTick)
            setWifiLock(isPlaying)
            if (isPlaying) {
                TvHub.message = ""
                retries = 0
                handler.post(volumeTick)
            } else AudioLevel.level = 0f
        }

        override fun onMetadata(metadata: androidx.media3.common.Metadata) {
            for (k in 0 until metadata.length()) {
                val m = metadata.get(k)
                if (m is androidx.media3.extractor.metadata.icy.IcyInfo) {
                    val t = m.title?.trim().orEmpty()
                    if (t.isNotEmpty() && t != "-") TvHub.onAir = t
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (onStream()) {
                // Live radio dropped: reconnect; a station that won't play at all → the next one.
                retries++
                TvHub.message = "Reconnecting to the station…"
                val id = exo.currentMediaItem?.mediaId
                handler.postDelayed({
                    if (exo.currentMediaItem?.mediaId != id) return@postDelayed
                    if (retries >= 3 && exo.mediaItemCount > 1) { retries = 0; tuneStation(1) }
                    else { exo.prepare(); exo.play() }
                }, if (retries == 1) 1500 else 4000)
                return
            }
            // The phone is busy (a call, other sounds) or briefly off the Wi-Fi. Never give up:
            // keep trying, and if the next song is already saved on the TV, play that meanwhile.
            retries++
            val phone = TvHub.phoneName.ifEmpty { "the phone" }
            TvHub.message = "Waiting for $phone… songs already on the TV keep playing"
            val nextIdx = if (exo.mediaItemCount == 0) C.INDEX_UNSET
            else exo.currentTimeline.getNextWindowIndex(
                exo.currentMediaItemIndex,
                if (TvHub.wrapsAround()) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF, false
            )
            val nextCached = nextIdx != C.INDEX_UNSET &&
                TvHub.items[exo.getMediaItemAt(nextIdx).mediaId]?.let { fileKeyOf(it) }?.let { fullyCached(it.second) } == true
            handler.postDelayed({
                if (retries >= 3 && nextCached) exo.seekTo(nextIdx, 0)
                exo.prepare(); exo.play()
            }, (2000L * retries).coerceAtMost(10_000))
        }
    }

    /** Next / previous follow the playlist, not the temporary repeat-one used to count repeats. */
    private inner class NavPlayer(p: ExoPlayer) : ForwardingPlayer(p) {
        private fun navMode() = if (TvHub.wrapsAround()) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        private fun nextIndex() = if (exo.mediaItemCount == 0) C.INDEX_UNSET
        else exo.currentTimeline.getNextWindowIndex(exo.currentMediaItemIndex, navMode(), false)
        private fun prevIndex() = if (exo.mediaItemCount == 0) C.INDEX_UNSET
        else exo.currentTimeline.getPreviousWindowIndex(exo.currentMediaItemIndex, navMode(), false)

        override fun seekToNext() = seekToNextMediaItem()
        override fun seekToNextMediaItem() {
            val i = nextIndex(); if (i != C.INDEX_UNSET) exo.seekTo(i, C.TIME_UNSET)
        }
        override fun seekToPrevious() {
            if (exo.currentPosition > 3000) exo.seekTo(0) else seekToPreviousMediaItem()
        }
        override fun seekToPreviousMediaItem() {
            val i = prevIndex(); if (i != C.INDEX_UNSET) exo.seekTo(i, C.TIME_UNSET) else exo.seekTo(0)
        }
        override fun hasNextMediaItem() = nextIndex() != C.INDEX_UNSET
        override fun hasPreviousMediaItem() = prevIndex() != C.INDEX_UNSET
        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            if (exo.mediaItemCount == 0) return base
            return base.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
        }
        override fun isCommandAvailable(command: Int) = getAvailableCommands().contains(command)
    }

    // ------------------------------------------------------------ Ghar Launcher music card

    private val snapshotTick = object : Runnable {
        override fun run() {
            val it = current()
            val n = NowPlayingProvider.Snapshot()
            n.state = if (it == null) "idle" else if (exo.isPlaying) "playing" else "paused"
            if (it != null) {
                n.title = it.title; n.artist = it.artist; n.album = it.album; n.songId = it.id
                n.position = exo.currentPosition; n.positionAt = SystemClock.elapsedRealtime()
                n.duration = if (exo.duration == C.TIME_UNSET) 0 else exo.duration
                n.speed = exo.playbackParameters.speed
            }
            NowPlayingProvider.now = n
            runCatching { contentResolver.notifyChange(NowPlayingProvider.NOW, null) }
            handler.postDelayed(this, 2000)
        }
    }

    // ------------------------------------------------------------ found on the Wi-Fi

    private fun announce(port: Int) {
        val m = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = m
        val info = NsdServiceInfo().apply {
            serviceName = Pairing.deviceName()
            serviceType = Protocol.SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        announce = l
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { announce = null }
    }
}

/** Songs stream from the phone (through the TV cache); an instruction is voice → silence → bell. */
@OptIn(UnstableApi::class)
class TvSourceFactory(
    private val context: Context,
    data: androidx.media3.datasource.DataSource.Factory,
) : MediaSource.Factory {
    private val default = DefaultMediaSourceFactory(data)
        .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(30))

    override fun setDrmSessionManagerProvider(p: DrmSessionManagerProvider): MediaSource.Factory {
        default.setDrmSessionManagerProvider(p); return this
    }

    // Keep our own patient retry policy.
    override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray = default.supportedTypes

    /** Live radio: straight from the internet, no cache (a live stream never ends). */
    private val live by lazy {
        DefaultMediaSourceFactory(
            androidx.media3.datasource.DefaultDataSource.Factory(
                context,
                androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent("GharPlayer/1.0").setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
            )
        ).setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(30))
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        if (TvHub.items[mediaItem.mediaId]?.stream?.isNotEmpty() == true) return live.createMediaSource(mediaItem)
        // YouTube: a silent placeholder while the official player shows the video.
        if (uri?.scheme == Item.YT_SCHEME) {
            // Wrapped so the player still reports our item (id, title) as the current one.
            return ConcatenatingMediaSource2.Builder().setMediaItem(mediaItem)
                .add(SilenceMediaSource.Factory().setDurationUs(24L * 3600 * 1_000_000).createMediaSource(), 24L * 3600 * 1000)
                .build()
        }
        if (uri?.scheme != Item.INSTR_SCHEME) return default.createMediaSource(mediaItem)
        val item = TvHub.items[uri.host]
        val ins = item?.instr
        val b = ConcatenatingMediaSource2.Builder().setMediaItem(mediaItem).setMediaSourceFactory(default)
        var parts = 0
        if (ins != null && ins.voice.isNotEmpty()) {
            val voice = MediaItem.Builder()
                .setUri(Uri.parse("${TvHub.phoneBase}/${ins.voice}"))
                .setCustomCacheKey("${ins.voice}/${ins.speechMs}")
                .build()
            b.add(voice, ins.speechMs.coerceAtLeast(1)); parts++
        }
        if (ins != null && ins.pauseSec > 0) {
            b.add(SilenceMediaSource.Factory().setDurationUs(ins.pauseSec * 1_000_000L).createMediaSource(), ins.pauseSec * 1000L); parts++
        }
        if (ins == null || ins.bell || parts == 0) {
            b.add(MediaItem.fromUri(Uri.parse("android.resource://${context.packageName}/${R.raw.gp_bell}")), PlaybackService.BELL_MS)
        }
        return b.build()
    }
}
