package com.ghar.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Bridge between the TV engine and the official YouTube IFrame player (in a WebView on the
 * TV screen). The engine keeps its normal queue; a YouTube item holds a silent placeholder
 * while the video plays here. Repeat counts, speed (YouTube max 2×) and next / previous are
 * handled by the engine through this bridge.
 */
object YtBridge {
    private val main = Handler(Looper.getMainLooper())

    /** The player view on the TV screen (null while the screen is closed). */
    var view: YtView? = null
        set(v) {
            field = v
            if (v != null) pending?.let { p -> pending = null; v.load(p.first, p.second, p.third) }
        }

    /** Video waiting for the screen to open: (item, start ms, rate). */
    private var pending: Triple<Item, Long, Float>? = null
    var current: Item? = null
        private set

    // Latest state reported by the player.
    @Volatile var positionMs = 0L
    @Volatile var durationMs = 0L
    @Volatile var state = -1          // YT: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued
    @Volatile var listIndex = -1
    @Volatile var listLength = 0
    @Volatile var title = ""

    /** Called when a video (or the last video of a playlist) ends. */
    var onEnded: (() -> Unit)? = null
    var onError: ((Int) -> Unit)? = null

    val active get() = current != null
    val playing get() = state == 1 || state == 3

    fun load(item: Item, startMs: Long, rate: Float) {
        current = item
        positionMs = startMs; durationMs = 0; state = 3; listIndex = -1; listLength = 0; title = ""
        val v = view
        if (v == null) pending = Triple(item, startMs, rate) else v.load(item, startMs, rate)
    }

    fun stop() {
        if (current == null) return
        current = null
        pending = null
        state = -1
        view?.js("stop()")
    }

    fun play() = view?.js("play()")
    fun pause() = view?.js("pause()")
    fun seek(ms: Long) { positionMs = ms; view?.js("seek(${ms / 1000.0})") }
    fun rate(r: Float) = view?.js("rate(${r.coerceIn(0.25f, 2f)})")
    fun nextInList() = view?.js("nextInList()")
    fun prevInList() = view?.js("prevInList()")

    /** In a playlist and not on its last video. */
    val hasNextInList get() = current?.yt?.isList == true && listLength > 0 && listIndex in 0 until listLength - 1
    val hasPrevInList get() = current?.yt?.isList == true && listIndex > 0

    // ----- called from JavaScript (any thread) -----
    internal fun fromState(s: Int) = main.post {
        state = s
        if (s == 0) {
            // In a playlist the player moves on by itself; only the end of the last video counts.
            val list = current?.yt?.isList == true
            if (!list || listLength == 0 || listIndex >= listLength - 1) onEnded?.invoke()
        }
    }

    internal fun fromTime(pos: Double, dur: Double, s: Int, idx: Int, len: Int, t: String) = main.post {
        if (current == null) return@post
        positionMs = pos.toLong(); durationMs = dur.toLong(); state = s; listIndex = idx; listLength = len; title = t
    }

    internal fun fromError(code: Int) = main.post { onError?.invoke(code) }
}

/** WebView holding the official YouTube player page (assets/yt.html). */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class YtView(c: Context) : WebView(c) {
    private var ready = false
    private val queue = ArrayList<String>()

    init {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        isFocusable = false
        webChromeClient = WebChromeClient()
        webViewClient = WebViewClient()
        addJavascriptInterface(object {
            @JavascriptInterface fun onReady() { post { ready = true; queue.forEach { evaluateJavascript(it, null) }; queue.clear() } }
            @JavascriptInterface fun onState(s: Int) = YtBridge.fromState(s).let { }
            @JavascriptInterface fun onError(code: Int) = YtBridge.fromError(code).let { }
            @JavascriptInterface fun onTime(pos: Double, dur: Double, s: Int, idx: Int, len: Int, t: String) =
                YtBridge.fromTime(pos, dur, s, idx, len, t).let { }
        }, "Ghar")
        val html = c.assets.open("yt.html").bufferedReader().use { it.readText() }
        // YouTube needs a real https origin for embeds in apps (otherwise error 152/153).
        loadDataWithBaseURL("https://com.ghar.player", html, "text/html", "utf-8", null)
    }

    fun js(code: String) {
        if (ready) evaluateJavascript(code, null) else queue += code
    }

    fun load(item: Item, startMs: Long, rate: Float) {
        val yt = item.yt ?: return
        val start = startMs / 1000.0
        val r = rate.coerceIn(0.25f, 2f)
        val id = yt.id.replace("'", "")
        js(if (yt.isList) "loadList('$id', 0, $start, $r)" else "loadVideo('$id', $start, $r)")
    }
}
