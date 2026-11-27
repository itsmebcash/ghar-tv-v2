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
    private val time = tv(96f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val ampm = tv(26f, 0xCCFFFFFF.toInt()).apply { setPadding(dp(10f), 0, 0, dp(14f)) }
    private val date = tv(24f, 0xFFFFC27A.toInt(), bold = true)
    private val weather = tv(19f, 0xE6FFFFFF.toInt())
    private val live: TextView
    private val dot: TextView
    private val station: TextView
    private val onAir = tv(22f, 0xE6FFFFFF.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
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
        // dark to the left and at the bottom, where the text is (as on the news screensaver)
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
        addView(top, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(56f); topMargin = dp(40f) })

        // ---- bottom left: LIVE · station · on air
        val bottom = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val liveRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dot = tv(18f, 0xFFFF3B30.toInt()).apply { text = "●"; setShadowLayer(12f, 0f, 0f, 0xFFFF3B30.toInt()) }
        live = tv(17f, Color.WHITE, bold = true).apply {
            letterSpacing = 0.12f
            setPadding(dp(8f), 0, 0, 0)
        }
        liveRow.addView(dot)
        liveRow.addView(live)
        liveRow.background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(0x99000000.toInt()); setStroke(dp(1f), 0x55FF3B30) }
        liveRow.setPadding(dp(12f), dp(6f), dp(14f), dp(6f))
        bottom.addView(liveRow, LinearLayout.LayoutParams(-2, -2))
        station = tv(54f, 0xFFE8C27A.toInt()).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            typeface = runCatching { Typeface.createFromAsset(c.assets, "gp_special_elite.ttf") }.getOrDefault(Typeface.SERIF)
            letterSpacing = 0.03f
        }
        bottom.addView(station, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
        bottom.addView(onAir, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(56f); rightMargin = dp(300f); bottomMargin = dp(48f)
        })

        // ---- bottom right: what the buttons do
        val hint = tv(15f, 0xCCFFFFFF.toInt()).apply {
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(0x80000000.toInt()); setStroke(dp(1f), 0x33FFFFFF) }
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            tag = "hint"
        }
        addView(hint, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(40f); bottomMargin = dp(52f) })
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
    }

    fun hide() {
        visibility = GONE
        particles.stop()
        blink?.cancel(); shine?.cancel(); drift?.cancel()
        blink = null; shine = null; drift = null
        main.removeCallbacks(tick)
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
