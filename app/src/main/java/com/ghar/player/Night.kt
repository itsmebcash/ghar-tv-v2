package com.ghar.player

import com.ghar.tv.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A small spinning arc: shows that something is loading or connecting. */
class LoadingView(c: Context, private val color: Int = Color.parseColor("#FF9933")) : View(c), Choreographer.FrameCallback {
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private var angle = 0f
    private var sweepT = 0f
    private var running = false

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown && !running && isAttachedToWindow) { running = true; Choreographer.getInstance().postFrameCallback(this) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (isShown && !running) { running = true; Choreographer.getInstance().postFrameCallback(this) } }
    override fun onDetachedFromWindow() { Choreographer.getInstance().removeFrameCallback(this); running = false; super.onDetachedFromWindow() }

    override fun doFrame(frameTimeNanos: Long) {
        angle = (angle + 6f) % 360f
        sweepT += 0.05f
        invalidate()
        if (isShown && isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this) else running = false
    }

    override fun onDraw(cv: Canvas) {
        val s = minOf(width, height).toFloat()
        val sw = s * 0.12f
        p.strokeWidth = sw
        r.set(sw, sw, s - sw, s - sw)
        r.offset((width - s) / 2, (height - s) / 2)
        p.color = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
        cv.drawOval(r, p)
        p.color = color
        val sweep = 60f + 180f * (0.5f + 0.5f * kotlin.math.sin(sweepT))
        cv.drawArc(r, angle, sweep, false, p)
    }
}

/** Weather for the night clock, from Open-Meteo (free, no key). Set for Janakpur. */
object Weather {
    const val PLACE_NE = "जनकपुर"
    const val PLACE_EN = "Janakpur"
    private const val LAT = 26.7288
    private const val LON = 85.9263

    @Volatile var tempC: Int? = null
        private set
    @Volatile var code = -1
        private set
    @Volatile var hi: Int? = null
        private set
    @Volatile var lo: Int? = null
        private set
    @Volatile private var fetchedAt = 0L

    /** Tomorrow and the day after: (weather code, low, high). */
    @Volatile var nextDays: List<Triple<Int, Int, Int>> = emptyList()
        private set

    /** Call off the main thread; refreshes at most every 30 minutes. */
    fun refresh() {
        if (System.currentTimeMillis() - fetchedAt < 30 * 60_000 && tempC != null) return
        runCatching {
            val c = URL("https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=3")
                .openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000; c.readTimeout = 12000
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val cur = o.getJSONObject("current")
                tempC = Math.round(cur.getDouble("temperature_2m")).toInt()
                code = cur.optInt("weather_code", -1)
                val daily = o.optJSONObject("daily")
                hi = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.let { Math.round(it).toInt() }
                lo = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.let { Math.round(it).toInt() }
                val codes = daily?.optJSONArray("weather_code")
                val maxs = daily?.optJSONArray("temperature_2m_max")
                val mins = daily?.optJSONArray("temperature_2m_min")
                nextDays = (1..2).mapNotNull { i ->
                    if (codes == null || maxs == null || mins == null || i >= maxs.length()) null
                    else Triple(codes.optInt(i, -1), Math.round(mins.optDouble(i)).toInt(), Math.round(maxs.optDouble(i)).toInt())
                }
                fetchedAt = System.currentTimeMillis()
            } finally { c.disconnect() }
        }
    }

    /** Big part: "⛅ २२°" (empty until the weather is known). */
    fun tempText(): String {
        val t = tempC ?: return ""
        return "${icon()}  ${TvRadio.num(t)}°"
    }

    /** Small part: "आंशिक बादल · जनकपुर · १४° – २६°". */
    fun detailText(): String {
        if (tempC == null) return ""
        val l = lo; val h = hi
        return listOf(
            words(), TvRadio.t(PLACE_NE, PLACE_EN),
            if (h != null && l != null) TvRadio.t("${TvRadio.num(l)}° – ${TvRadio.num(h)}°", "$l° – $h°") else "",
        ).filter { it.isNotBlank() }.joinToString("   ·   ")
    }

    /** Small: "भोलि ⛅ १४° – २६°     पर्सि ☀ १३° – २५°" */
    fun forecastText(): String {
        val names = listOf(TvRadio.t("भोलि", "Tomorrow"), TvRadio.t("पर्सि", "Day after"))
        return nextDays.mapIndexed { i, (c, l, h) ->
            "${names.getOrElse(i) { "" }}  ${icon(c)}  " + TvRadio.t("${TvRadio.num(l)}° – ${TvRadio.num(h)}°", "$l° – $h°")
        }.joinToString("        ")
    }

    private fun icon(code: Int = this.code) = when (code) {
        0 -> "☀"; 1, 2 -> "⛅"; 3 -> "☁"; 45, 48 -> "🌫"; 51, 53, 55, 56, 57 -> "🌦"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧"; 71, 73, 75, 77, 85, 86 -> "❄"; 95, 96, 99 -> "⛈"; else -> ""
    }

    private fun words() = when (code) {
        0 -> TvRadio.t("सफा आकाश", "Clear"); 1, 2 -> TvRadio.t("आंशिक बादल", "Partly cloudy"); 3 -> TvRadio.t("बादल", "Cloudy")
        45, 48 -> TvRadio.t("हुस्सु", "Fog"); 51, 53, 55, 56, 57 -> TvRadio.t("सिमसिम पानी", "Drizzle")
        61, 63, 65, 66, 67, 80, 81, 82 -> TvRadio.t("वर्षा", "Rain"); 71, 73, 75, 77, 85, 86 -> TvRadio.t("हिउँ", "Snow")
        95, 96, 99 -> TvRadio.t("चट्याङ", "Thunderstorm"); else -> ""
    }

    /** "☀ 22°C · सफा आकाश · जनकपुर   14° – 26°" for a WMO weather code. */
    fun describe(): String {
        val (icon, ne, en) = when (code) {
            0 -> Triple("☀", "सफा आकाश", "Clear")
            1, 2 -> Triple("⛅", "आंशिक बादल", "Partly cloudy")
            3 -> Triple("☁", "बादल", "Cloudy")
            45, 48 -> Triple("🌫", "हुस्सु", "Fog")
            51, 53, 55, 56, 57 -> Triple("🌦", "सिमसिम पानी", "Drizzle")
            61, 63, 65, 66, 67, 80, 81, 82 -> Triple("🌧", "वर्षा", "Rain")
            71, 73, 75, 77, 85, 86 -> Triple("❄", "हिउँ", "Snow")
            95, 96, 99 -> Triple("⛈", "चट्याङ", "Thunderstorm")
            else -> Triple("", "", "")
        }
        val t = tempC ?: return ""
        val l = lo; val h = hi
        val range = if (h != null && l != null) "   " + TvRadio.t("${TvRadio.num(l)}° – ${TvRadio.num(h)}°", "$l° – $h°") else ""
        return listOf("$icon  ${TvRadio.num(t)}°C", TvRadio.t(ne, en), TvRadio.t(PLACE_NE, PLACE_EN))
            .filter { it.isNotBlank() }.joinToString("   ·   ") + range
    }
}

/** News headlines for the night clock: from Bikash's news feed, else Google News (Nepali / English). */
object NewsFeed {
    @Volatile var headlines: List<String> = emptyList()
        private set
    @Volatile private var fetchedAt = 0L

    /** One Nepali news source (Onlinekhabar); BBC Nepali only if it can't be reached. */
    private val FEEDS = listOf(
        "https://www.onlinekhabar.com/feed" to "अनलाइनखबर",
        "https://feeds.bbci.co.uk/nepali/rss.xml" to "बीबीसी नेपाली",
    )

    /** Name of the source the headlines came from. */
    @Volatile var source = ""
        private set

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "GharPlayer/1.0")
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Every "title" in a JSON feed, whatever its shape. */
    private fun titlesFromJson(any: Any?, out: MutableList<String>) {
        when (any) {
            is JSONObject -> any.keys().forEach { k ->
                val v = any.opt(k)
                if (k.equals("title", true) && v is String) out += v
                else titlesFromJson(v, out)
            }
            is JSONArray -> for (i in 0 until any.length()) titlesFromJson(any.opt(i), out)
        }
    }

    private fun titlesFromRss(xml: String): List<String> {
        val out = ArrayList<String>()
        val pp = android.util.Xml.newPullParser()
        pp.setInput(xml.reader())
        var inItem = false
        var ev = pp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (pp.name) {
                    "item", "entry" -> inItem = true
                    "title" -> if (inItem) out += pp.nextText()
                }
            } else if (ev == XmlPullParser.END_TAG && (pp.name == "item" || pp.name == "entry")) inItem = false
            ev = pp.next()
        }
        return out
    }

    private fun parse(text: String): List<String> {
        val t = text.trim()
        val raw = if (t.startsWith("{") || t.startsWith("[")) {
            ArrayList<String>().also { titlesFromJson(if (t.startsWith("{")) JSONObject(t) else JSONArray(t), it) }
        } else titlesFromRss(t)
        // Google News adds " - Source": keep the headline short.
        return raw.map { android.text.Html.fromHtml(it).toString().trim() }
            .filter { it.length in 12..200 }
            .distinct()
    }

    /** Refreshes at most every 15 minutes (call off the main thread). */
    fun refresh(nepali: Boolean) {
        if (System.currentTimeMillis() - fetchedAt < 15 * 60_000 && headlines.isNotEmpty()) return
        for ((url, name) in FEEDS) {
            val list = runCatching { parse(get(url)) }.getOrDefault(emptyList())
            if (list.size >= 3) { headlines = list.take(40); source = name; fetchedAt = System.currentTimeMillis(); return }
        }
    }
}

/**
 * Old digital clock: a seven-segment display with faint "ghost" segments, in dim grey on
 * black. Almost all of the screen is black, so the TV gives only a soft glow — about as much
 * light as a small night bulb.
 */
class SegmentClock(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B8B8B8")
        setShadowLayer(14f, 0f, 0f, Color.parseColor("#CC000000")) // readable on the photo
    }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#161616") }
    private val path = Path()
    var text = "12:00"
        set(v) { if (field != v) { field = v; invalidate() } }
    var colonOn = true
        set(v) { if (field != v) { field = v; invalidate() } }

    // Segments: a top, b upper-right, c lower-right, d bottom, e lower-left, f upper-left, g middle.
    private val digits = mapOf(
        '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
        '5' to "afgcd", '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abfgcd", ' ' to "",
    )

    override fun onDraw(cv: Canvas) {
        val h = height.toFloat()
        val dw = h * 0.52f          // digit width
        val gap = h * 0.12f
        val colonW = h * 0.22f
        val total = text.sumOf { (if (it == ':') colonW else dw + gap).toDouble() }.toFloat()
        var x = (width - total) / 2
        for (ch in text) {
            if (ch == ':') {
                val r = h * 0.05f
                if (colonOn) {
                    cv.drawCircle(x + colonW / 2 - gap / 2, h * 0.32f, r, lit)
                    cv.drawCircle(x + colonW / 2 - gap / 2, h * 0.68f, r, lit)
                }
                x += colonW
            } else {
                val on = digits[ch] ?: ""
                // Only the lit segments: plain digits on black.
                for (seg in on) segment(cv, seg, x, 0f, dw, h, lit)
                x += dw + gap
            }
        }
    }

    private fun segment(cv: Canvas, s: Char, x: Float, y: Float, w: Float, h: Float, p: Paint) {
        val t = w * 0.17f        // thickness
        val sl = w * 0.08f       // slant (old displays lean a little)
        fun hseg(cy: Float) {
            path.reset()
            path.moveTo(x + t * 0.6f + sl * (1 - cy / h), cy)
            path.lineTo(x + t + sl * (1 - cy / h), cy - t / 2)
            path.lineTo(x + w - t + sl * (1 - cy / h), cy - t / 2)
            path.lineTo(x + w - t * 0.6f + sl * (1 - cy / h), cy)
            path.lineTo(x + w - t + sl * (1 - cy / h), cy + t / 2)
            path.lineTo(x + t + sl * (1 - cy / h), cy + t / 2)
            path.close(); cv.drawPath(path, p)
        }
        fun vseg(cx: Float, y0: Float, y1: Float) {
            val s0 = sl * (1 - y0 / h); val s1 = sl * (1 - y1 / h)
            path.reset()
            path.moveTo(cx + s0, y0 + t * 0.6f)
            path.lineTo(cx + t / 2 + s0, y0 + t)
            path.lineTo(cx + t / 2 + s1, y1 - t)
            path.lineTo(cx + s1, y1 - t * 0.6f)
            path.lineTo(cx - t / 2 + s1, y1 - t)
            path.lineTo(cx - t / 2 + s0, y0 + t)
            path.close(); cv.drawPath(path, p)
        }
        val m = h / 2
        when (s) {
            'a' -> hseg(y + t / 2)
            'g' -> hseg(m)
            'd' -> hseg(h - t / 2)
            'f' -> vseg(x + t / 2, t / 2, m)
            'b' -> vseg(x + w - t / 2, t / 2, m)
            'e' -> vseg(x + t / 2, m, h - t / 2)
            'c' -> vseg(x + w - t / 2, m, h - t / 2)
        }
    }
}

/**
 * Behind the night clock: white specks of light, like the stars in the picture, wandering
 * slowly in random directions. Almost dark when it is quiet; they brighten and flicker with the
 * music, and glow a little more on each beat.
 */
class NightParticles(c: Context) : View(c), Choreographer.FrameCallback {
    private class P(var x: Float, var y: Float, var vx: Float, var vy: Float, val r: Float, val phase: Float, val star: Boolean)
    private val rnd = java.util.Random()
    private val d = c.resources.displayMetrics.density
    private val ps = List(64) {
        val star = rnd.nextFloat() < 0.6f            // small sharp specks; the rest soft and blurred
        P(rnd.nextFloat(), rnd.nextFloat(), (rnd.nextFloat() - 0.5f) * 0.02f, (rnd.nextFloat() - 0.5f) * 0.016f,
            if (star) 3f + rnd.nextFloat() * 6f else 12f + rnd.nextFloat() * 24f, rnd.nextFloat() * 6.28f, star)
    }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val dst = android.graphics.Rect()
    /** One white blurred dot, drawn scaled for every speck (cheap blur). */
    private val sprite: android.graphics.Bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
        val cv = Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.RadialGradient(48f, 48f, 48f, intArrayOf(Color.WHITE, 0xAAFFFFFF.toInt(), 0x33FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.15f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
        cv.drawCircle(48f, 48f, 48f, p)
    }
    private var level = 0f
    private var avg = 0f
    private var pulse = 0f
    private var t = 0f
    private var last = 0L
    private var running = false

    fun start() { if (!running) { running = true; last = 0L; Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { Choreographer.getInstance().removeFrameCallback(this); running = false }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun doFrame(now: Long) {
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        t += dt
        val fresh = System.currentTimeMillis() - AudioLevel.updatedAt < 500
        val heard = if (fresh) (AudioLevel.level * 1.6f).coerceAtMost(1f) else 0f
        level += (heard - level) * 0.15f
        avg += (heard - avg) * (dt * 0.8f)
        // A "beat": loudness clearly above its recent average gives a glow.
        if (heard > avg * 1.3f && heard > 0.12f) pulse = (pulse + 0.35f).coerceAtMost(1f)
        pulse = (pulse - dt * 1.5f).coerceAtLeast(0f)
        val sp = 0.7f + level * 1.3f + pulse * 0.6f
        ps.forEach { p ->
            // Random wandering: each speck keeps turning a little, never in a straight line.
            p.vx += (rnd.nextFloat() - 0.5f) * 0.03f * dt
            p.vy += (rnd.nextFloat() - 0.5f) * 0.03f * dt
            p.vx = p.vx.coerceIn(-0.02f, 0.02f); p.vy = p.vy.coerceIn(-0.016f, 0.016f)
            p.x += p.vx * dt * sp; p.y += p.vy * dt * sp
            if (p.x < -0.05f) p.x = 1.05f else if (p.x > 1.05f) p.x = -0.05f
            if (p.y < -0.05f) p.y = 1.05f else if (p.y > 1.05f) p.y = -0.05f
        }
        invalidate()
        if (running && isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this) else running = false
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        ps.forEach { p ->
            // Twinkle (faster with the music); dim when quiet, bright when loud.
            val blink = 0.5f + 0.5f * kotlin.math.sin(t * (0.8f + level * 2.5f) + p.phase)
            val a = if (p.star) (0.06f + 0.10f * blink + 0.55f * level * blink + 0.30f * pulse).coerceIn(0f, 0.85f)
                    else (0.03f + 0.05f * blink + 0.22f * level + 0.15f * pulse).coerceIn(0f, 0.4f)
            paint.alpha = (a * 255).toInt()
            val rad = p.r * d * (1f + 0.25f * pulse + 0.2f * level)
            val cx = p.x * w; val cy = p.y * h
            dst.set((cx - rad).toInt(), (cy - rad).toInt(), (cx + rad).toInt(), (cy + rad).toInt())
            cv.drawBitmap(sprite, null, dst, paint)
        }
    }
}

/**
 * Night mode: black screen with the time, date, the radio station and rotating news headlines
 * in dim grey, all held still in the middle of the screen. While the radio plays, a red dot
 * blinks beside its name, which is written in an old typewriter face and slowly shines.
 */
class NightScreen(c: Context, private val onExit: () -> Unit) : FrameLayout(c) {
    private val main = Handler(Looper.getMainLooper())
    private val clock = SegmentClock(c)
    private val date: TextView
    private val weather: TextView
    private val temp: TextView
    private val station: TextView
    private val dot: TextView
    private val stationRow: LinearLayout
    private var blink: android.animation.ObjectAnimator? = null
    private var shine: android.animation.ValueAnimator? = null
    private val shineMatrix = android.graphics.Matrix()
    private var shineW = 0f
    private val news: TextView
    private val newsSource: TextView
    private val box = LinearLayout(c)
    private val particles = NightParticles(c)
    private val forecast: TextView
    private var headline = 0

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun tv(size: Float, color: String) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(Color.parseColor(color)); gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        // Adiyogi and Kedarnath under the Milky Way, dimmed so the room stays dark
        addView(android.widget.ImageView(c).apply {
            setImageResource(R.drawable.gp_night_adiyogi)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            alpha = 0.42f
        }, LayoutParams(-1, -1))
        addView(View(c).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x33000000, 0x66000000, 0xB3000000.toInt()))
        }, LayoutParams(-1, -1))
        addView(particles, LayoutParams(-1, -1))
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.addView(clock, LinearLayout.LayoutParams(dp(560f), dp(150f)))
        // Nepali date and the temperature: same size, side by side, readable from the sofa.
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        date = tv(44f, "#A8A8A8").apply { setPadding(0, dp(6f), 0, 0); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        temp = tv(44f, "#A8A8A8").apply { setPadding(dp(40f), dp(6f), 0, 0); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        row.addView(date)
        row.addView(temp)
        box.addView(row)
        weather = tv(22f, "#8C8C8C").apply { setPadding(0, dp(2f), 0, 0) }
        box.addView(weather)
        forecast = tv(17f, "#7A7A7A").apply { setPadding(0, dp(4f), 0, 0) }
        box.addView(forecast)
        // Now playing: a blinking red "on air" dot and the station's name in an old typewriter face.
        stationRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(12f), 0, 0) }
        dot = tv(24f, "#FF2A2A").apply { text = "●"; typeface = android.graphics.Typeface.DEFAULT; setPadding(0, 0, dp(16f), 0) }
        station = tv(38f, "#E8C27A").apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(780f)
            typeface = runCatching { android.graphics.Typeface.createFromAsset(c.assets, "gp_special_elite.ttf") }
                .getOrDefault(android.graphics.Typeface.SERIF)
            letterSpacing = 0.04f
        }
        stationRow.addView(dot)
        stationRow.addView(station)
        box.addView(stationRow)
        news = tv(36f, "#B0B0B0").apply { setPadding(0, dp(16f), 0, 0); maxLines = 2; typeface = android.graphics.Typeface.DEFAULT; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }
        box.addView(news, LinearLayout.LayoutParams(dp(880f), -2))
        newsSource = tv(15f, "#6A6A6A").apply { setPadding(0, dp(6f), 0, 0) }
        listOf(date, temp, weather, forecast, news, newsSource).forEach { it.setShadowLayer(10f, 0f, 0f, Color.parseColor("#E6000000")) }
        box.addView(newsSource)
        addView(box, LayoutParams(-2, -2, Gravity.CENTER))
    }

    fun show() {
        visibility = VISIBLE
        requestFocus()
        particles.start()
        startOnAir()
        tick.run()
        rotate.run()
        Thread { NewsFeed.refresh(true); main.post { if (isShown && news.text.isNullOrEmpty()) rotate.run() } }.start()
        Thread { Weather.refresh(); main.post { temp.text = Weather.tempText(); weather.text = Weather.detailText(); forecast.text = Weather.forecastText() } }.start()
    }

    fun hide() {
        visibility = GONE
        particles.stop()
        blink?.cancel(); shine?.cancel(); blink = null; shine = null
        main.removeCallbacks(tick); main.removeCallbacks(rotate)
    }

    /** The red dot blinks like an "on air" lamp; a soft light sweeps across the name and it glows. */
    private fun startOnAir() {
        blink?.cancel(); shine?.cancel()
        blink = android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.12f).apply {
            duration = 750; repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE; start()
        }
        shine = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { va ->
                val v = va.animatedValue as Float
                val w = station.width.toFloat()
                if (w < 10f) return@addUpdateListener
                val base = Color.parseColor("#E8C27A")
                val sh = (if (shineW == w) station.paint.shader as? android.graphics.LinearGradient else null)
                    ?: android.graphics.LinearGradient(0f, 0f, w * 0.5f, 0f,
                        intArrayOf(base, Color.parseColor("#FFF6DC"), base), floatArrayOf(0.2f, 0.5f, 0.8f),
                        android.graphics.Shader.TileMode.CLAMP).also { station.paint.shader = it; shineW = w }
                // the bright band crosses the name, then rests off to the side for a moment
                shineMatrix.setTranslate(-w * 0.5f + v * w * 2.2f, 0f)
                sh.setLocalMatrix(shineMatrix)
                // the glow breathes with it
                val g = 0.5f + 0.5f * kotlin.math.sin(v * 2 * Math.PI).toFloat()
                station.setShadowLayer(8f + 18f * g, 0f, 0f, Color.argb((120 + 100 * g).toInt(), 255, 170, 60))
                station.invalidate()
            }
            start()
        }
    }

    val isOn get() = visibility == VISIBLE

    private val tick = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance()
            val raw = SimpleDateFormat("h:mm", Locale.US).format(now.time)
            clock.text = raw.padStart(5, ' ')
            clock.colonOn = now.get(Calendar.SECOND) % 2 == 0
            // Nepali date, month and day only: "बिहीबार, कात्तिक २६"
            val bs = NepaliDate.today()
            date.text = if (bs != null) NepaliDate.WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + ", " + bs.monthDay()
                else SimpleDateFormat("EEEE, d MMMM", Locale("ne", "NP")).format(Date())
            val n = PlaybackService.instance?.now()
            val it = n?.item
            val title = if (it == null || !n.playing) "" else it.title
            if (station.text.toString() != title) { station.text = title; shineW = 0f }
            stationRow.visibility = if (title.isEmpty()) View.GONE else View.VISIBLE
            temp.text = Weather.tempText()
            val ampm = if (now.get(Calendar.AM_PM) == Calendar.AM) TvRadio.t("बिहान", "AM") else TvRadio.t("साँझ", "PM")
            weather.text = listOf(ampm, Weather.detailText()).filter { it.isNotBlank() }.joinToString("   ·   ")
            forecast.text = Weather.forecastText()
            // Weather every 30 minutes. (Everything stays still in the middle: no drifting.)
            if (now.get(Calendar.SECOND) == 0 && now.get(Calendar.MINUTE) % 30 == 0) Thread { Weather.refresh() }.start()
            main.postDelayed(this, 1000)
        }
    }

    private val rotate = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            val list = NewsFeed.headlines
            if (list.isNotEmpty()) {
                val text = list[headline % list.size]
                headline++
                news.animate().alpha(0f).setDuration(600).withEndAction {
                    news.text = text
                    newsSource.text = "— " + NewsFeed.source
                    news.animate().alpha(1f).setDuration(900).start()
                }.start()
            } else news.text = TvRadio.t("समाचार खुल्दैछ…", "Loading news…")
            main.postDelayed(this, if (list.isEmpty()) 5000 else 10000)
            if (list.isEmpty() || headline % 60 == 0) Thread { NewsFeed.refresh(true) }.start()
        }
    }
}
