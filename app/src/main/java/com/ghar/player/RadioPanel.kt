package com.ghar.player

import com.ghar.tv.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Station picker like a turning wheel (the Apple-style tuner / picker): the chosen station
 * sits large and bright in the middle band, the ones above and below get smaller and fade as
 * if printed on a drum. ▲ ▼ turn it smoothly, OK plays, holding OK adds to favourites.
 */
class StationWheel(c: Context) : View(c), Choreographer.FrameCallback {
    var stations: List<TvRadio.Station> = emptyList()
        set(v) { field = v; target = 0; pos = 0f; invalidate() }
    var savedUrls: Set<String> = emptySet()
        set(v) { field = v; invalidate() }
    var onPick: (Int) -> Unit = {}
    /** The station playing now, and whether it is sounding (shown on its row as Stop / Play). */
    var playingUrl: String? = null
        set(v) { field = v; invalidate() }
    var playingOn = false
        set(v) { field = v; invalidate() }
    var onHold: (Int) -> Unit = {}

    val selected get() = target

    private val d = c.resources.displayMetrics.density
    private val sp = c.resources.displayMetrics.scaledDensity
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private var target = 0
    private var pos = 0f
    private var running = false
    private var held = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setSelectedIndex(i: Int) { target = i.coerceIn(0, (stations.size - 1).coerceAtLeast(0)); pos = target.toFloat(); invalidate() }

    private fun kick() { if (!running) { running = true; Choreographer.getInstance().postFrameCallback(this) } }

    override fun doFrame(frameTimeNanos: Long) {
        pos += (target - pos) * 0.22f
        if (abs(target - pos) < 0.002f) pos = target.toFloat()
        invalidate()
        if (pos != target.toFloat()) Choreographer.getInstance().postFrameCallback(this) else running = false
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val n = stations.size
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (n > 0 && target > 0) { target--; kick() }; return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (n > 0 && target < n - 1) { target++; kick() }; return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (n > 0) { target = (target - 8).coerceAtLeast(0); kick() }; return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (n > 0) { target = (target + 8).coerceAtMost(n - 1); kick() }; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) held = false
                else if (!held) { held = true; if (n > 0) onHold(target) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!held && stations.isNotEmpty()) onPick(target)
            held = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cy = h / 2
        val rowH = 74 * d
        val radius = h * 0.55f
        // Middle band.
        val band = RectF(0f, cy - rowH * 0.62f, w, cy + rowH * 0.62f)
        p.color = if (isFocused) Color.parseColor("#3A3A3A") else Color.parseColor("#262626")
        cv.drawRoundRect(band, 18 * d, 18 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d
        p.color = if (isFocused) Color.parseColor("#FFB347") else Color.parseColor("#444444")
        cv.drawRoundRect(band, 18 * d, 18 * d, p); p.style = Paint.Style.FILL
        if (stations.isEmpty()) return
        val first = (pos - 6).toInt().coerceAtLeast(0)
        val last = (pos + 6).toInt().coerceAtMost(stations.size - 1)
        for (i in first..last) {
            val off = (i - pos) * rowH
            val ang = off / radius
            if (abs(ang) >= Math.PI / 2 * 0.98) continue
            val y = cy + sin(ang) * radius
            val k = cos(ang).toFloat()
            val s = stations[i]
            val centre = abs(i - pos) < 0.5f
            txt.typeface = Typeface.DEFAULT_BOLD
            txt.textSize = (20f + 18f * k * k) * sp
            txt.color = if (centre) Color.WHITE else Color.argb((255 * (0.15f + 0.6f * k * k * k)).toInt(), 220, 220, 220)
            txt.textAlign = Paint.Align.LEFT
            val left = 26 * d
            val isNow = s.url == playingUrl
            val heartW = if (centre && isNow) 280 * d else 60 * d
            val name = fit(s.name, w - left - heartW - 30 * d)
            val baseY = y + txt.textSize * (if (centre) 0.12f else 0.35f)
            cv.drawText(name, left, baseY, txt)
            if (isNow && centre) {
                // The station playing now: OK stops / plays it.
                val label = if (playingOn) TvRadio.t("■  रोक्नुहोस्", "■  Stop") else TvRadio.t("▶  बजाउनुहोस्", "▶  Play")
                txt.typeface = Typeface.DEFAULT_BOLD; txt.textSize = 24 * sp
                val tw = txt.measureText(label)
                val pill = RectF(w - 24 * d - tw - 44 * d - (if (s.url in savedUrls) 50 * d else 0f), y - 24 * d, w - 24 * d - (if (s.url in savedUrls) 50 * d else 0f), y + 24 * d)
                p.color = if (playingOn) Color.parseColor("#7F1D1D") else Color.parseColor("#14532D")
                cv.drawRoundRect(pill, 24 * d, 24 * d, p)
                txt.color = Color.WHITE; txt.textAlign = Paint.Align.CENTER
                cv.drawText(label, pill.centerX(), y + 9 * sp, txt)
                txt.textSize = (20f + 18f * k * k) * sp
            } else if (isNow) {
                txt.textAlign = Paint.Align.RIGHT; txt.color = Color.argb((220 * k).toInt(), 255, 179, 71)
                cv.drawText("♪", w - 70 * d, baseY, txt)
            }
            if (s.url in savedUrls) {
                txt.textAlign = Paint.Align.RIGHT; txt.color = if (centre) Color.parseColor("#FF6B6B") else Color.argb((200 * k).toInt(), 255, 107, 107)
                cv.drawText("♥", w - 24 * d, baseY, txt)
            }
            if (centre) {
                txt.typeface = Typeface.DEFAULT; txt.textSize = 18 * sp; txt.textAlign = Paint.Align.LEFT
                txt.color = Color.parseColor("#BDBDBD")
                cv.drawText(fit(s.place.ifBlank { s.quality }, w - left - heartW - 30 * d), left, y + 26 * sp, txt)
            }
        }
        // Drum shading at the top and bottom.
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.3f, Color.parseColor("#F0141414"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h * 0.3f, p)
        p.shader = LinearGradient(0f, h, 0f, h * 0.7f, Color.parseColor("#F0141414"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawRect(0f, h * 0.7f, w, h, p); p.shader = null
        // Position on the right edge of the band: 12 / 287.
        txt.typeface = Typeface.MONOSPACE; txt.textSize = 16 * sp; txt.textAlign = Paint.Align.RIGHT
        txt.color = Color.parseColor("#FFB347")
        cv.drawText("${TvRadio.num(target + 1)} / ${TvRadio.num(stations.size)}", w - 24 * d, band.top - 10 * d, txt)
    }

    private fun fit(s: String, maxW: Float): String {
        if (txt.measureText(s) <= maxW) return s
        var e = s
        while (e.isNotEmpty() && txt.measureText("$e…") > maxW) e = e.dropLast(1)
        return "$e…"
    }
}

/**
 * The TV's own radio, used with the remote: lists on the left (For you, Favourites, Nepal,
 * Hindi, Bhajan, World — each with its number of stations), the station wheel on the right.
 * OK plays (the whole list goes in, so ▲ ▼ change station later), holding OK adds or removes
 * a favourite, Back closes.
 */
class RadioPanel(
    c: Context, private val onPlayed: () -> Unit, private val onLanguage: () -> Unit = {}, private val onNight: () -> Unit = {},
) : FrameLayout(c) {
    private val saffron = Color.parseColor("#FFB347")
    private val dim = Color.parseColor("#A3A3A3")
    private val main = Handler(Looper.getMainLooper())
    private val catViews = ArrayList<TextView>()
    private var cat = TvRadio.Category.FOR_YOU
    private var stations: List<TvRadio.Station> = emptyList()
    private val counts = HashMap<TvRadio.Category, Int>()
    private val allUrls = HashSet<String>()
    private val status: TextView
    private val wheel = StationWheel(c)
    private var gen = 0
    private var opened = false
    /** Put the remote on the wheel (at the station playing now) once the list has loaded. */
    private var focusWheel = false
    private lateinit var heading: TextView
    private lateinit var total: TextView
    private lateinit var langView: TextView
    private lateinit var nightView: TextView
    private lateinit var pairCode: TextView
    private lateinit var hint: TextView
    private lateinit var spinner: LoadingView

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = true
    }

    private fun rowBg(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat(); setColor(Color.parseColor("#5C5C5C")); setStroke(dp(2f), Color.parseColor("#9E9E9E"))
        })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(Color.TRANSPARENT) })
    }

    private fun menuRow(size: Float) = tv(size, Color.WHITE, bold = true).apply {
        isFocusable = true
        background = rowBg()
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(18f), dp(9f), dp(18f), dp(9f))
    }

    init {
        setBackgroundColor(Color.parseColor("#FA141414"))
        isClickable = true
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(36f), dp(22f), dp(36f), dp(16f)) }
        // ---- Left menu (scrolls if the TV screen is small, so nothing is cut) ----
        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        heading = tv(32f, saffron, bold = true).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        left.addView(heading)
        total = tv(18f, dim).apply { setPadding(0, 0, 0, dp(12f)) }
        left.addView(total)
        TvRadio.Category.entries.forEach { k ->
            val v = menuRow(25f).apply {
                setOnClickListener { select(k); wheel.requestFocus() }
                setOnFocusChangeListener { _, f -> if (f && cat != k) select(k) }
            }
            catViews += v
            left.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3f) })
        }
        langView = menuRow(19f).apply {
            setOnClickListener {
                TvRadio.setNepali(context, !TvRadio.nepali)
                relabel(); select(cat)
                onLanguage()
            }
        }
        left.addView(langView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        nightView = menuRow(19f).apply { setOnClickListener { close(); onNight() } }
        left.addView(nightView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3f) })
        pairCode = tv(16f, dim).apply { setPadding(dp(4f), dp(14f), 0, dp(4f)) }
        left.addView(pairCode)
        val scroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(left, LayoutParams(-1, -2))
        }
        row.addView(scroll, LinearLayout.LayoutParams(dp(360f), -1))
        // ---- Station wheel ----
        val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28f), 0, 0, 0) }
        status = tv(22f, dim).apply { setPadding(dp(10f), 0, 0, 0) }
        spinner = LoadingView(c)
        right.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(6f))
            addView(spinner, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { leftMargin = dp(8f) })
            addView(status)
        })
        wheel.onPick = { play(it) }
        wheel.onHold = { toggleSave(it) }
        right.addView(wheel, LinearLayout.LayoutParams(-1, 0, 1f))
        hint = tv(17f, dim).apply { gravity = android.view.Gravity.CENTER; setPadding(0, dp(6f), 0, 0) }
        right.addView(hint, LinearLayout.LayoutParams(-1, -2))
        row.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        addView(row, LayoutParams(-1, -1))
        relabel()
    }

    /** All the words, in the chosen language (with each list's number of stations). */
    fun relabel() {
        heading.text = TvRadio.t("📻  रेडियो", "📻  Radio")
        total.text = if (allUrls.isEmpty()) TvRadio.t("स्टेशन गनिँदैछ…", "Counting stations…")
            else TvRadio.t("कुल ${TvRadio.num(allUrls.size)} स्टेशन", "${allUrls.size} stations in all")
        catViews.forEachIndexed { i, v ->
            val k = TvRadio.Category.entries[i]
            v.text = k.label + (counts[k]?.let { "   " + TvRadio.num(it) } ?: "")
        }
        langView.text = TvRadio.t("भाषा: नेपाली › English", "Language: English › नेपाली")
        nightView.text = TvRadio.t("🌙  रात्रि घडी", "🌙  Night clock")
        hint.text = TvRadio.t(
            "▲ ▼ घुमाउनुहोस्   ·   OK = बजाउनुहोस्   ·   OK थिचिराख्नुहोस् = ♥   ·   ◀ सूची   ·   Back = बन्द",
            "▲ ▼ turn   ·   OK = play   ·   hold OK = ♥   ·   ◀ lists   ·   Back = close"
        )
    }

    fun open() {
        visibility = VISIBLE
        val code = Pairing.code()?.let { it.substring(0, 3) + " " + it.substring(3) } ?: "—"
        pairCode.text = TvRadio.t("📱 फोन जोड्ने कोड:  $code", "📱 Phone pairing code:  $code")
        val now = PlaybackService.instance?.now()
        val playing = now?.item?.stream?.takeIf { it.isNotEmpty() }
        wheel.playingUrl = playing
        if (playing != null) {
            // Open on the list the playing station came from, with the wheel on that station.
            val from = now.item.playlist
            TvRadio.Category.entries.firstOrNull { it.label.removePrefix("♥ ") == from }?.let { cat = it }
            opened = true
            focusWheel = true
            wheel.playingUrl = playing
            wheel.playingOn = now.playing || now.buffering
        } else if (!opened) {
            // Favourites first when there are some, so they are one press away.
            opened = true; cat = if (TvRadio.saved(context).isNotEmpty()) TvRadio.Category.SAVED else TvRadio.Category.FOR_YOU
        }
        select(cat)
        if (focusWheel) wheel.requestFocus() else catViews[cat.ordinal].requestFocus()
        countAll()
    }

    /** Loads every list in the background just to show how many stations each has. */
    private fun countAll() {
        Thread {
            for (k in TvRadio.Category.entries) {
                val list = runCatching { TvRadio.load(context, k) }.getOrNull() ?: continue
                main.post {
                    counts[k] = list.size
                    if (k != TvRadio.Category.SAVED) list.forEach { allUrls += it.url }
                    relabel()
                }
            }
        }.start()
    }

    fun close() { visibility = GONE }

    /**
     * Keep the remote inside this screen while it is open: it moves only between the lists
     * and the wheel, never to the radio behind.
     */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val next = android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction)
        return next ?: focused
    }

    val isOpen get() = visibility == VISIBLE

    private fun select(k: TvRadio.Category) {
        cat = k
        catViews.forEachIndexed { i, v -> v.setTextColor(if (i == k.ordinal) saffron else Color.WHITE) }
        status.text = TvRadio.t("स्टेशनहरू खुल्दैछन्…", "Loading stations…")
        spinner.visibility = VISIBLE
        stations = emptyList(); wheel.stations = emptyList()
        val g = ++gen
        Thread {
            val res = runCatching { TvRadio.load(context, k) }
            val saved = TvRadio.saved(context).map { it.url }.toSet()
            main.post {
                if (g != gen) return@post
                spinner.visibility = GONE
                wheel.savedUrls = saved
                res.onSuccess {
                    stations = it
                    wheel.stations = it
                    counts[k] = it.size
                    // Start at the station playing now, if it is in this list.
                    val playing = PlaybackService.instance?.now()?.item?.stream
                    val at = it.indexOfFirst { s -> s.url == playing }
                    if (at >= 0) wheel.setSelectedIndex(at)
                    if (focusWheel) { focusWheel = false; if (it.isNotEmpty()) wheel.requestFocus() else catViews[k.ordinal].requestFocus() }
                    status.text = when {
                        it.isEmpty() && k == TvRadio.Category.SAVED -> TvRadio.t(
                            "अहिलेसम्म मनपर्ने छैन। स्टेशनमा OK थिचिराख्नुहोस्।",
                            "No favourites yet. Hold OK on a station to add it."
                        )
                        it.isEmpty() -> TvRadio.t("कुनै स्टेशन भेटिएन।", "No stations found.")
                        else -> k.label + "   ·   " + TvRadio.t("${TvRadio.num(it.size)} स्टेशन", "${it.size} stations")
                    }
                    relabel()
                }.onFailure { focusWheel = false; catViews[k.ordinal].requestFocus(); status.text = TvRadio.t("इन्टरनेट जाँच गर्नुहोस् — स्टेशन खुलेनन्।", "Couldn't load stations. Check the TV's internet.") }
            }
        }.start()
    }

    private fun play(pos: Int) {
        if (pos !in stations.indices) return
        val svc = PlaybackService.instance ?: return
        val n = svc.now()
        if (stations[pos].url == n.item?.stream) {
            if (n.playing || n.buffering) svc.radioStop() else svc.radioPlay()
            wheel.playingOn = !(n.playing || n.buffering)
            return
        }
        PlaybackService.instance?.playStations(stations, pos, cat.label.removePrefix("♥ "))
        close()
        onPlayed()
    }

    private fun toggleSave(pos: Int) {
        val s = stations.getOrNull(pos) ?: return
        val now = TvRadio.toggleSaved(context, s)
        val saved = TvRadio.saved(context)
        wheel.savedUrls = saved.map { it.url }.toSet()
        counts[TvRadio.Category.SAVED] = saved.size
        relabel()
        Toast.makeText(
            context,
            if (now) TvRadio.t("♥ मनपर्नेमा थपियो: ${s.name}", "♥ Added to favourites: ${s.name}")
            else TvRadio.t("मनपर्नेबाट हटाइयो", "Removed from favourites"),
            Toast.LENGTH_SHORT
        ).show()
        if (cat == TvRadio.Category.SAVED) select(cat)
    }
}
