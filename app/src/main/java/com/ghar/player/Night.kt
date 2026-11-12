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
 * Behind the night clock: a few soft, blurred specks of light floating slowly, breathing very
 * gently with the music and blinking faintly. Kept very dim so the room stays dark.
 */
class NightParticles(c: Context) : View(c), Choreographer.FrameCallback {
    private class P(var x: Float, var y: Float, var vx: Float, var vy: Float, val r: Float, val phase: Float, val warm: Boolean)
    private val rnd = java.util.Random()
    private val d = c.resources.displayMetrics.density
    private val ps = List(48) {
        P(rnd.nextFloat(), rnd.nextFloat(), (rnd.nextFloat() - 0.5f) * 0.012f, (rnd.nextFloat() - 0.5f) * 0.010f,
            10f + rnd.nextFloat() * 34f, rnd.nextFloat() * 6.28f, rnd.nextFloat() < 0.4f)
    }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val dst = android.graphics.Rect()
    /** One blurred dot, drawn scaled for every speck (cheap blur). */
    private val sprite: android.graphics.Bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
        val cv = Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.RadialGradient(48f, 48f, 48f, intArrayOf(Color.WHITE, 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.3f, 1f), android.graphics.Shader.TileMode.CLAMP)
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
        val heard = if (fresh) AudioLevel.level else 0f
        level += (heard - level) * 0.12f
        avg += (heard - avg) * (dt * 0.8f)
        // A mild "beat": loudness clearly above its recent average gives a small glow.
        if (heard > avg * 1.3f && heard > 0.12f) pulse = (pulse + 0.25f).coerceAtMost(1f)
        pulse = (pulse - dt * 1.2f).coerceAtLeast(0f)
        val sp = 0.6f + level * 0.8f
        ps.forEach { p ->
            p.vx += (rnd.nextFloat() - 0.5f) * 0.004f * dt
            p.vy += (rnd.nextFloat() - 0.5f) * 0.004f * dt
            p.vx = p.vx.coerceIn(-0.015f, 0.015f); p.vy = p.vy.coerceIn(-0.012f, 0.012f)
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
            // Very subtle blinking, a touch brighter with the music.
            val blink = 0.55f + 0.45f * kotlin.math.sin(t * 0.7f + p.phase)
            val a = (0.08f + 0.10f * blink + 0.08f * level + 0.08f * pulse).coerceIn(0f, 0.34f)
            paint.alpha = (a * 255).toInt()
            paint.colorFilter = android.graphics.PorterDuffColorFilter(
                if (p.warm) Color.rgb(255, 170, 90) else Color.rgb(170, 180, 210), android.graphics.PorterDuff.Mode.MULTIPLY
            )
            val rad = p.r * d * (1f + 0.15f * pulse)
            val cx = p.x * w; val cy = p.y * h
            dst.set((cx - rad).toInt(), (cy - rad).toInt(), (cx + rad).toInt(), (cy + rad).toInt())
            cv.drawBitmap(sprite, null, dst, paint)
        }
    }
}

/**
 * Night mode: black screen with the time, date, the radio station and rotating news headlines
 * in dim grey. The picture moves a little every minute so nothing burns into the screen.
 */
class NightScreen(c: Context, private val onExit: () -> Unit) : FrameLayout(c) {
    private val main = Handler(Looper.getMainLooper())
    private val clock = SegmentClock(c)
    private val date: TextView
    private val weather: TextView
    private val temp: TextView
    private val station: TextView
    private val news: TextView
    private val newsSource: TextView
    private val box = LinearLayout(c)
    private val particles = NightParticles(c)
    private val forecast: TextView
    private var headline = 0
    private val rnd = java.util.Random()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun tv(size: Float, color: String) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(Color.parseColor(color)); gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        // Kailash under the stars, dimmed so the room stays dark (photo: Isha Foundation)
        addView(android.widget.ImageView(c).apply {
            setImageResource(R.drawable.gp_night_kailash)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            alpha = 0.42f
        }, LayoutParams(-1, -1))
        addView(View(c).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x33000000, 0x66000000, 0xB3000000.toInt()))
        }, LayoutParams(-1, -1))
        addView(particles, LayoutParams(-1, -1))
        addView(tv(11f, "#55FFFFFF").apply { text = "Kailash · photo: Isha Foundation (isha.sadhguru.org)" },
            LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18f), dp(12f)) })
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
        station = tv(32f, "#C9A45C").apply {
            setPadding(0, dp(10f), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        box.addView(station, LinearLayout.LayoutParams(dp(820f), -2))
        news = tv(36f, "#B0B0B0").apply { setPadding(0, dp(16f), 0, 0); maxLines = 2; typeface = android.graphics.Typeface.DEFAULT; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }
        box.addView(news, LinearLayout.LayoutParams(dp(880f), -2))
        newsSource = tv(15f, "#6A6A6A").apply { setPadding(0, dp(6f), 0, 0) }
        listOf(date, temp, weather, forecast, station, news, newsSource).forEach { it.setShadowLayer(10f, 0f, 0f, Color.parseColor("#E6000000")) }
        box.addView(newsSource)
        addView(box, LayoutParams(-2, -2, Gravity.CENTER))
    }

    fun show() {
        visibility = VISIBLE
        requestFocus()
        particles.start()
        tick.run()
        rotate.run()
        Thread { NewsFeed.refresh(true); main.post { if (isShown && news.text.isNullOrEmpty()) rotate.run() } }.start()
        Thread { Weather.refresh(); main.post { temp.text = Weather.tempText(); weather.text = Weather.detailText(); forecast.text = Weather.forecastText() } }.start()
    }

    fun hide() {
        visibility = GONE
        particles.stop()
        main.removeCallbacks(tick); main.removeCallbacks(rotate)
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
            station.text = when {
                it == null -> ""
                !n.playing -> ""
                it.stream.isNotEmpty() -> "📻  " + it.title
                else -> "♪  " + it.title
            }
            temp.text = Weather.tempText()
            val ampm = if (now.get(Calendar.AM_PM) == Calendar.AM) TvRadio.t("बिहान", "AM") else TvRadio.t("साँझ", "PM")
            weather.text = listOf(ampm, Weather.detailText()).filter { it.isNotBlank() }.joinToString("   ·   ")
            forecast.text = Weather.forecastText()
            // Burn-in care: drift a little every minute; weather every 30 minutes.
            if (now.get(Calendar.SECOND) == 0) {
                if (now.get(Calendar.MINUTE) % 30 == 0) Thread { Weather.refresh() }.start()
                box.animate().translationX((rnd.nextFloat() - 0.5f) * dp(80f)).translationY((rnd.nextFloat() - 0.5f) * dp(30f)).setDuration(3000).start()
            }
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
