package com.ghar.player

import com.ghar.tv.R

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

/**
 * Radio screensaver (Back once while the radio plays), laid out like Ghar Launcher's news
 * screensaver: the wallpaper slowly drifting, white particles moving with the music, the time,
 * Nepali date and weather at the top left, and the station at the bottom left — a blinking
 * "● LIVE" lamp, its name in the old typewriter face with a light sweeping across it, and what's
 * on air. The screen stays at its normal brightness (the Night clock is the dim one).
 */
class RadioSaver(c: Context) : FrameLayout(c) {
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setShadowLayer(14f, 0f, 2f, 0xCC000000.toInt())
        includeFontPadding = false
    }

    private val photo = ImageView(c)
    private val particles = NightParticles(c)
    // (font padding kept on the time: tall Nepali digits were cut at the top without it)
    private val time = tv(80f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); includeFontPadding = true }
    private val ampm = tv(26f, 0xCCFFFFFF.toInt()).apply { setPadding(dp(10f), 0, 0, dp(14f)) }
    private val date = tv(24f, 0xFFFFC27A.toInt(), bold = true)
    private val weather = tv(19f, 0xE6FFFFFF.toInt())
    private val live: TextView
    private val dot: TextView
    private val station: TextView
    private val onAir = tv(22f, 0xE6FFFFFF.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private lateinit var newsCard: LinearLayout
    private lateinit var newsPic: ImageView
    private lateinit var newsMeta: TextView
    private lateinit var newsTitle: TextView
    private lateinit var newsBrief: TextView
    private var newsIndex = 0
    private var blink: ObjectAnimator? = null
    private var shine: ValueAnimator? = null
    private var drift: ValueAnimator? = null
    private val shineMatrix = Matrix()
    private var shineW = 0f

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        photo.setImageResource(R.drawable.gp_night_adiyogi)
        photo.scaleType = ImageView.ScaleType.CENTER_CROP
        photo.alpha = 0.8f
        addView(photo, LayoutParams(-1, -1))
        // darker where the words are: left, bottom and top (as on the news screensaver)
        addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xCC000000.toInt(), 0x55000000, 0x00000000))
        }, LayoutParams(-1, -1))
        addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x66000000, 0x00000000))
        }, LayoutParams(-1, dp(300f), Gravity.BOTTOM))
        addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000))
        }, LayoutParams(-1, dp(200f), Gravity.TOP))
        addView(particles, LayoutParams(-1, -1))

        // ---- top left: time, date, weather
        val top = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val clockRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        clockRow.addView(time)
        clockRow.addView(ampm)
        top.addView(clockRow)
        top.addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })
        top.addView(weather, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        addView(top, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(56f); topMargin = dp(36f) })

        // ---- top right: LIVE · station (scrolls when long) · on air — small
        val radioBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val liveRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dot = tv(13f, 0xFFFF3B30.toInt()).apply { text = "●"; setShadowLayer(10f, 0f, 0f, 0xFFFF3B30.toInt()) }
        live = tv(12f, Color.WHITE, bold = true).apply {
            letterSpacing = 0.12f
            setPadding(dp(6f), 0, 0, 0)
        }
        liveRow.addView(dot)
        liveRow.addView(live)
        liveRow.background = GradientDrawable().apply { cornerRadius = dp(7f).toFloat(); setColor(0x99000000.toInt()); setStroke(dp(1f), 0x55FF3B30) }
        liveRow.setPadding(dp(10f), dp(4f), dp(12f), dp(4f))
        radioBox.addView(liveRow, LinearLayout.LayoutParams(-2, -2))
        station = tv(26f, 0xFFE8C27A.toInt()).apply {
            // the whole name, scrolling slowly when it doesn't fit
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            maxWidth = dp(380f)
            typeface = runCatching { Typeface.createFromAsset(c.assets, "gp_special_elite.ttf") }.getOrDefault(Typeface.SERIF)
            letterSpacing = 0.03f
            includeFontPadding = true
        }
        radioBox.addView(station, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) })
        onAir.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        onAir.gravity = Gravity.END
        onAir.maxWidth = dp(380f)
        radioBox.addView(onAir, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f) })
        addView(radioBox, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = dp(48f); topMargin = dp(44f) })

        // ---- bottom left: the news, part of the screen (no box) — source · time, photo, big headline
        newsCard = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.INVISIBLE
        }
        newsMeta = tv(13f, 0xFFFFC27A.toInt(), bold = true).apply { letterSpacing = 0.06f }
        newsCard.addView(newsMeta)
        val story = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        newsPic = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12f).toFloat()) }
            }
            clipToOutline = true
            elevation = dp(6f).toFloat()
        }
        story.addView(newsPic, LinearLayout.LayoutParams(dp(156f), dp(100f)).apply { rightMargin = dp(18f) })
        newsTitle = tv(23f, Color.WHITE, bold = true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.12f) }
        newsBrief = tv(12f, 0xCCFFFFFF.toInt()).apply { visibility = GONE }
        story.addView(newsTitle, LinearLayout.LayoutParams(0, -2, 1f))
        newsCard.addView(story, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        addView(newsCard, LayoutParams(dp(620f), -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(56f); bottomMargin = dp(46f) })

        // ---- bottom right: what the buttons do
        val hint = tv(12f, 0xCCFFFFFF.toInt()).apply {
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(0x80000000.toInt()); setStroke(dp(1f), 0x33FFFFFF) }
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            tag = "hint"
        }
        addView(hint, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(48f); bottomMargin = dp(48f) })
    }

    val isOn get() = visibility == VISIBLE

    fun show() {
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(600).start()
        requestFocus()
        particles.start()
        findViewWithTag<TextView>("hint").text = TvRadio.t("▲ ▼  स्टेशन   ·   ब्याक: रेडियो   ·   ब्याक दुई पटक: बाहिर", "▲ ▼  station   ·   Back: radio   ·   Back twice: leave")
        live.text = TvRadio.t("लाइभ रेडियो", "LIVE RADIO")
        startAnimations()
        tick.run()
        Thread { Weather.refresh(); main.post { tick.run() } }.start()
        newsIndex = 0
        nextNews.run()
        Thread { NewsStories.refresh(); main.post { if (isOn && newsCard.visibility != VISIBLE) nextNews.run() } }.start()
    }

    /** The next story on the small card (cross-fade). */
    private val nextNews = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            val list = NewsStories.stories
            if (list.isNotEmpty()) {
                val st = list[newsIndex++ % list.size]
                val show = {
                    newsMeta.text = TvRadio.t("समाचार", "NEWS") + "   ·   " + st.source + (if (st.ago.isNotEmpty()) "  ·  " + st.ago else "")
                    newsTitle.text = st.title
                    newsBrief.text = st.brief
                    newsBrief.visibility = GONE
                    newsPic.setImageBitmap(null)
                    newsPic.visibility = if (st.image.isEmpty()) GONE else VISIBLE
                    station.isSelected = true
                    if (st.image.isNotEmpty()) NewsStories.picture(st.image) { b -> if (newsTitle.text == st.title) newsPic.setImageBitmap(b) }
                    newsCard.visibility = VISIBLE
                    newsCard.animate().alpha(1f).setDuration(700).start()
                }
                if (newsCard.visibility == VISIBLE) newsCard.animate().alpha(0f).setDuration(500).withEndAction(show).start()
                else { newsCard.alpha = 0f; show() }
            }
            main.postDelayed(this, if (list.isEmpty()) 5000 else 12_000)
        }
    }

    fun hide() {
        visibility = GONE
        particles.stop()
        blink?.cancel(); shine?.cancel(); drift?.cancel()
        blink = null; shine = null; drift = null
        main.removeCallbacks(tick)
        main.removeCallbacks(nextNews)
    }

    private fun startAnimations() {
        blink?.cancel(); shine?.cancel(); drift?.cancel()
        blink = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.15f).apply {
            duration = 750; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
        // the photo drifts and zooms very slowly
        drift = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 40_000; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                photo.scaleX = 1.04f + 0.08f * v; photo.scaleY = photo.scaleX
                photo.translationX = -dp(24f) * v
            }
            start()
        }
        // a light sweeps across the station's name, and it glows
        shine = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val v = va.animatedValue as Float
                val w = station.paint.measureText(station.text.toString()).coerceAtMost(station.width.toFloat())
                if (w < 10f) return@addUpdateListener
                val base = Color.parseColor("#E8C27A")
                val sh = (if (shineW == w) station.paint.shader as? LinearGradient else null)
                    ?: LinearGradient(0f, 0f, w * 0.5f, 0f, intArrayOf(base, Color.parseColor("#FFF6DC"), base),
                        floatArrayOf(0.2f, 0.5f, 0.8f), Shader.TileMode.CLAMP).also { station.paint.shader = it; shineW = w }
                shineMatrix.setTranslate(-w * 0.5f + v * w * 2.2f, 0f)
                sh.setLocalMatrix(shineMatrix)
                val g = 0.5f + 0.5f * kotlin.math.sin(v * 2 * Math.PI).toFloat()
                station.setShadowLayer(10f + 20f * g, 0f, 0f, Color.argb((110 + 100 * g).toInt(), 255, 170, 60))
                station.invalidate()
            }
            start()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            val now = Calendar.getInstance()
            time.text = SimpleDateFormat("h:mm", Locale.US).format(now.time).let { t -> if (TvRadio.nepali) t.map { if (it in '0'..'9') '०' + (it - '0') else it }.joinToString("") else t }
            ampm.text = if (now.get(Calendar.AM_PM) == Calendar.AM) TvRadio.t("बिहान", "AM") else TvRadio.t("बेलुका", "PM")
            val bs = NepaliDate.today()
            date.text = if (bs != null) NepaliDate.WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + ", " + bs.monthDay()
                else SimpleDateFormat("EEEE, d MMMM", Locale.US).format(Date())
            weather.text = listOf(Weather.tempText(), Weather.detailText()).filter { it.isNotBlank() }.joinToString("   ")
            val n = PlaybackService.instance?.now()
            val item = n?.item
            val name = item?.title.orEmpty()
            if (station.text.toString() != name) { station.text = name; shineW = 0f }
            onAir.text = when {
                n?.buffering == true -> TvRadio.t("जोडिँदै…", "Tuning in…")
                TvHub.onAir.isNotEmpty() && n?.playing == true -> TvRadio.t("अहिले बज्दैछ:  ", "On air:  ") + TvHub.onAir
                else -> item?.artist.orEmpty()
            }
            live.alpha = if (n?.playing == true) 1f else 0.5f
            main.postDelayed(this, 1000)
        }
    }
}

/**
 * Top stories for the radio screensaver: headline, a line or two and a photo, from Onlinekhabar
 * (BBC Nepali if it can't be reached), refreshed every 20 minutes.
 */
object NewsStories {
    class Story(val title: String, val brief: String, val image: String, val source: String, val ago: String)

    @Volatile var stories: List<Story> = emptyList()
        private set
    @Volatile private var fetchedAt = 0L
    private val pics = android.util.LruCache<String, android.graphics.Bitmap>(12)
    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val FEEDS = listOf(
        "https://www.onlinekhabar.com/feed" to "अनलाइनखबर",
        "https://feeds.bbci.co.uk/nepali/rss.xml" to "बीबीसी नेपाली",
    )

    private fun open(url: String): java.net.HttpURLConnection {
        var u = url
        repeat(4) {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 15000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; TV) GharTV/4.3")
            val code = c.responseCode
            if (code in 300..399) { u = java.net.URL(java.net.URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); return@repeat }
            if (code !in 200..299) { c.disconnect(); throw IllegalStateException("HTTP $code") }
            return c
        }
        throw IllegalStateException("too many redirects")
    }

    private fun clean(html: String) = android.text.Html.fromHtml(html).toString().replace('\uFFFC', ' ').replace(Regex("\\s+"), " ").trim()

    private fun brief(text: String): String {
        val t = text.replace(Regex("The post .*appeared first on.*$"), "").trim()
        return if (t.length <= 170) t else t.substring(0, 170).substringBeforeLast(' ') + "…"
    }

    private fun parse(xml: String, source: String): List<Story> {
        val out = ArrayList<Story>()
        val pp = android.util.Xml.newPullParser()
        pp.setInput(xml.substring(0, xml.lastIndexOf("</rss>").let { if (it < 0) xml.length else it + 6 }).reader())
        var inItem = false
        var title = ""; var desc = ""; var img = ""; var date = ""; var content = ""
        var ev = pp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val n = pp.name
                if (n == "item") { inItem = true; title = ""; desc = ""; img = ""; date = ""; content = "" }
                else if (inItem) when (n) {
                    "title" -> title = clean(pp.nextText())
                    "description" -> desc = pp.nextText()
                    "content:encoded" -> content = pp.nextText()
                    "pubDate" -> date = pp.nextText()
                    "image" -> if (img.isEmpty()) img = pp.nextText().trim()
                    "media:thumbnail", "media:content", "enclosure" -> if (img.isEmpty()) img = pp.getAttributeValue(null, "url").orEmpty()
                }
            } else if (ev == XmlPullParser.END_TAG && pp.name == "item") {
                inItem = false
                if (img.isEmpty()) img = Regex("<img[^>]+src=[\"']([^\"']+)").find(content + desc)?.groupValues?.get(1).orEmpty()
                img = img.replace("/240/", "/480/")
                if (title.length >= 8) out += Story(title, brief(clean(desc)), img, source, ago(date))
            }
            ev = pp.next()
        }
        return out
    }

    private fun ago(rfc: String): String {
        val t = runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(rfc.trim())?.time }.getOrNull() ?: return ""
        val m = ((System.currentTimeMillis() - t) / 60_000).coerceAtLeast(0)
        val (n, ne, en) = when {
            m < 60 -> Triple(m.toInt(), "मिनेट अघि", "min ago")
            m < 48 * 60 -> Triple((m / 60).toInt(), "घण्टा अघि", "h ago")
            else -> Triple((m / 1440).toInt(), "दिन अघि", "days ago")
        }
        return TvRadio.t(TvRadio.num(n) + " " + ne, "$n $en")
    }

    /** Off the main thread. */
    fun refresh() {
        if (System.currentTimeMillis() - fetchedAt < 20 * 60_000 && stories.isNotEmpty()) return
        for ((url, name) in FEEDS) {
            val list = runCatching { open(url).let { c -> try { parse(c.inputStream.bufferedReader().use { it.readText() }, name) } finally { c.disconnect() } } }
                .getOrDefault(emptyList())
            if (list.size >= 3) { stories = list.take(20); fetchedAt = System.currentTimeMillis(); return }
        }
    }

    /** A small copy of a story's photo (cached). */
    fun picture(url: String, done: (android.graphics.Bitmap) -> Unit) {
        pics.get(url)?.let { done(it); return }
        loader.execute {
            val b = runCatching {
                val bytes = open(url).let { c -> try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() } }
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= 320) sample *= 2
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull() ?: return@execute
            pics.put(url, b)
            ui.post { done(b) }
        }
    }
}
