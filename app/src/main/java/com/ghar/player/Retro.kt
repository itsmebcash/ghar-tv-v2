package com.ghar.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private val AMBER = Color.parseColor("#FFB347")
private val AMBER_DIM = Color.parseColor("#6B4A1E")
private val SAFFRON = Color.parseColor("#FFB347")
private val NEEDLE = Color.parseColor("#FF4B3A")

/** Loudness heard now on the TV (0 when silent or stale), smoothed like a real VU needle. */
private class Vu {
    var v = 0f
    fun step(active: Boolean, jitter: Float = 0f): Float {
        val fresh = System.currentTimeMillis() - AudioLevel.updatedAt < 500
        val target = if (active && fresh) (AudioLevel.level * (1f + jitter)).coerceIn(0f, 1f) else 0f
        v += (target - v) * (if (target > v) 0.35f else 0.07f)
        return v
    }
}

/**
 * The retro now-playing set, drawn for a big modern screen.
 *  • RADIO: a warm cabinet with two speaker grilles, an amber FM read-out, a VU meter and a
 *    back-lit tuning dial whose needle glides to the station.
 *  • CASSETTE (songs): a tape deck — a cassette with turning reels whose tape moves from left
 *    to right as the song plays, the title on its label, and two VU meters (L / R).
 */
class RetroDeck(c: Context) : View(c), Choreographer.FrameCallback {
    enum class Mode { RADIO, CASSETTE }

    var mode = Mode.CASSETTE
        set(v) { if (field != v) { field = v; invalidate() } }
    var playing = false
        set(v) { field = v; kick() }
    /** Radio. */
    var stationName = ""
    var stationIndex = 0
    var stationCount = 0
    /** Connecting / buffering: the set shows it is tuning in. */
    var tuning = false
        set(v) { field = v; if (v) kick() }
    private var scrollX = 0f
    private var clock = 0f
    /** Cassette. */
    var title = ""
    var fraction = 0f
    var speed = 1f

    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val r = RectF()
    private val vuL = Vu()
    private val vuR = Vu()
    private var lv = 0f
    private var rv = 0f
    private var glow = 0.35f
    private var needle = 0.5f
    private var needleVel = 0f
    private var reel = 0f
    private var last = 0L
    private var running = false
    private val rnd = java.util.Random()

    private fun kick() {
        if (!running && isAttachedToWindow) { running = true; last = 0L; Choreographer.getInstance().postFrameCallback(this) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); kick() }
    override fun onDetachedFromWindow() { Choreographer.getInstance().removeFrameCallback(this); running = false; super.onDetachedFromWindow() }

    private fun dialTarget(): Float {
        val fm = TvRadio.fmOf(stationName)
        return when {
            fm != null -> (fm - 88f) / 20f
            stationCount > 1 -> 0.04f + 0.92f * stationIndex / (stationCount - 1).toFloat()
            else -> 0.5f
        }.coerceIn(0f, 1f)
    }

    override fun doFrame(now: Long) {
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        lv = vuL.step(playing, (rnd.nextFloat() - 0.5f) * 0.12f)
        rv = vuR.step(playing, (rnd.nextFloat() - 0.5f) * 0.12f)
        glow += ((if (playing) 1f else 0.35f) - glow) * 0.05f
        // Spring needle: glides to the station and settles with a little wobble.
        // While tuning in, the needle searches a little around the station.
        val target = (dialTarget() + if (tuning) 0.03f * sin(clock * 5f) else 0f).coerceIn(0f, 1f)
        needleVel += ((target - needle) * 18f - needleVel * 5.5f) * dt
        needle += needleVel * dt
        if (playing && !tuning) reel = (reel + dt * 140f * speed) % 360f
        clock += dt
        scrollX += dt * 70f * d
        invalidate()
        val settling = kotlin.math.abs(target - needle) > 0.001f || kotlin.math.abs(needleVel) > 0.001f
        if (isAttachedToWindow && visibility == VISIBLE && (playing || tuning || lv > 0.005f || settling || glow > 0.36f))
            Choreographer.getInstance().postFrameCallback(this)
        else running = false
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) kick()
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        // Cabinet.
        r.set(0f, 0f, w, h)
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.parseColor("#2E2E2E"), Color.parseColor("#1F1F1F"), Color.parseColor("#151515")), null, Shader.TileMode.CLAMP)
        cv.drawRoundRect(r, 26 * d, 26 * d, p)
        p.shader = null
        // Wood grain.
        p.style = Paint.Style.STROKE; p.strokeWidth = 1 * d
        for (k in 0 until 14) {
            val y = h * (k + 0.5f) / 14f
            p.color = Color.argb(8, 255, 255, 255)
            cv.drawLine(20 * d, y + sin(k * 1.7f) * 3 * d, w - 20 * d, y + cos(k * 1.3f) * 3 * d, p)
        }
        p.color = Color.parseColor("#444444"); p.strokeWidth = 1.5f * d
        r.inset(1 * d, 1 * d); cv.drawRoundRect(r, 25 * d, 25 * d, p)
        p.style = Paint.Style.FILL
        when (mode) {
            Mode.RADIO -> drawRadio(cv, w, h)
            Mode.CASSETTE -> drawDeck(cv, w, h)
        }
    }

    /** Text shown on the radio's LCD. */
    var lcdTop = ""
    var lcdInfo = ""
    var lcdQuality = ""
    var favourite = false
    /** Height (px) kept free at the bottom of the radio for its buttons. */
    var trayPx = 0f

    private fun fitText(s: String, maxW: Float): String {
        if (txt.measureText(s) <= maxW) return s
        var e = s
        while (e.isNotEmpty() && txt.measureText("$e…") > maxW) e = e.dropLast(1)
        return "$e…"
    }

    /** Tall radio: speaker grilles at the sides, a big amber LCD with all the words, the tuning dial below. */
    private fun drawRadio(cv: Canvas, w: Float, hAll: Float) {
        // The buttons sit in a tray along the bottom of the cabinet.
        if (trayPx > 0f) {
            val tray = RectF(18 * d, hAll - trayPx, w - 18 * d, hAll - 12 * d)
            p.color = Color.parseColor("#101010"); cv.drawRoundRect(tray, 16 * d, 16 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1 * d; p.color = Color.parseColor("#333333"); cv.drawRoundRect(tray, 16 * d, 16 * d, p); p.style = Paint.Style.FILL
        }
        val h = hAll - trayPx
        val pad = h * 0.05f
        val rad = min(h * 0.34f, w * 0.11f)
        grille(cv, pad + rad, h * 0.44f, rad, lv)
        grille(cv, w - pad - rad, h * 0.44f, rad, rv)
        val left = pad * 2 + rad * 2
        val right = w - pad * 2 - rad * 2
        // ---- LCD ----
        val lcd = RectF(left, pad, right, pad + h * 0.64f)
        p.shader = LinearGradient(0f, lcd.top, 0f, lcd.bottom, Color.parseColor("#17130C"), Color.parseColor("#0B0906"), Shader.TileMode.CLAMP)
        cv.drawRoundRect(lcd, 14 * d, 14 * d, p); p.shader = null
        p.shader = RadialGradient(lcd.centerX(), lcd.centerY(), lcd.width() * 0.6f, withAlpha(AMBER, 0.10f * glow), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawRoundRect(lcd, 14 * d, 14 * d, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = AMBER_DIM; cv.drawRoundRect(lcd, 14 * d, 14 * d, p); p.style = Paint.Style.FILL
        val inL = lcd.left + 18 * d
        val inR = lcd.right - 18 * d
        val inW = inR - inL
        val on = withAlpha(AMBER, 0.55f + 0.45f * glow)
        val soft = withAlpha(AMBER, 0.40f + 0.30f * glow)
        // Line 1: list name (left) · station number (right).
        txt.typeface = Typeface.DEFAULT_BOLD; txt.isFakeBoldText = false
        txt.textSize = h * 0.075f; txt.color = soft
        val fm = TvRadio.fmOf(stationName)
        val num = if (stationCount > 0) TvRadio.t("स्टेशन ${TvRadio.num(stationIndex + 1)} / ${TvRadio.num(stationCount)}", "STATION ${stationIndex + 1} / $stationCount") else ""
        val right1 = listOf(fm?.let { "FM %.1f".format(it) } ?: "", num).filter { it.isNotEmpty() }.joinToString("   ")
        txt.textAlign = Paint.Align.RIGHT
        val y1 = lcd.top + h * 0.11f
        cv.drawText(right1, inR, y1, txt)
        txt.textAlign = Paint.Align.LEFT
        cv.drawText(fitText(lcdTop, inW - txt.measureText(right1) - 24 * d), inL, y1, txt)
        // Line 2: the station name, big (scrolls when long) — or "tuning…".
        txt.textSize = h * 0.16f
        val y2 = lcd.top + h * 0.30f
        cv.save(); cv.clipRect(inL, lcd.top, inR, lcd.bottom)
        if (tuning) {
            val dots = ".".repeat(1 + ((clock * 3).toInt() % 3))
            txt.color = withAlpha(AMBER, 0.55f + 0.35f * sin(clock * 6f).coerceAtLeast(0f))
            txt.textAlign = Paint.Align.CENTER
            cv.drawText(TvRadio.t("ट्युन हुँदैछ", "TUNING") + dots, lcd.centerX(), y2, txt)
            val bx = inL + inW * ((clock * 0.8f) % 1f)
            p.shader = LinearGradient(bx - 60 * d, 0f, bx + 60 * d, 0f, intArrayOf(Color.TRANSPARENT, withAlpha(AMBER, 0.28f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            cv.drawRect(inL, lcd.top, inR, lcd.bottom, p); p.shader = null
        } else {
            txt.color = on
            txt.setShadowLayer(12 * d * glow, 0f, 0f, withAlpha(AMBER, 0.7f * glow))
            val name = (if (favourite) "★ " else "") + stationName.ifEmpty { "—" }
            val tw = txt.measureText(name)
            txt.textAlign = Paint.Align.LEFT
            if (tw <= inW) cv.drawText(name, lcd.centerX() - tw / 2, y2, txt)
            else {
                val loop = tw + 80 * d
                val off = scrollX % loop
                cv.drawText(name, inL - off, y2, txt)
                cv.drawText(name, inL - off + loop, y2, txt)
            }
            txt.clearShadowLayer()
        }
        // Line 3: what's on air, or where the station is from.
        txt.textSize = h * 0.08f; txt.color = soft; txt.textAlign = Paint.Align.CENTER
        cv.drawText(fitText(lcdInfo, inW), lcd.centerX(), lcd.top + h * 0.44f, txt)
        cv.restore()
        // Line 4: status light + LIVE / stopped + quality (left), level bars (right).
        val y4 = lcd.bottom - h * 0.06f
        p.color = when { tuning -> withAlpha(AMBER, 0.5f + 0.5f * sin(clock * 8f).coerceAtLeast(0f)); playing -> Color.parseColor("#4ADE80"); else -> Color.parseColor("#3A3A3A") }
        cv.drawCircle(inL + 6 * d, y4 - h * 0.022f, 6 * d, p)
        txt.textSize = h * 0.065f; txt.color = soft; txt.textAlign = Paint.Align.LEFT
        val status = when {
            tuning -> TvRadio.t("जोडिँदैछ…", "CONNECTING…")
            playing -> TvRadio.t("प्रत्यक्ष", "LIVE")
            else -> TvRadio.t("रोकिएको", "STOPPED")
        }
        cv.drawText(listOf(status, lcdQuality).filter { it.isNotEmpty() }.joinToString("   ·   "), inL + 20 * d, y4, txt)
        // LCD level bars, like an old hi-fi.
        val bars = 14
        val bw = 7 * d; val bg = 4 * d
        val bh = h * 0.07f
        val level = ((lv + rv) / 2).coerceIn(0f, 1f)
        for (k in 0 until bars) {
            val x = inR - (bars - k) * (bw + bg)
            val lit = k < (level * bars).toInt()
            p.color = if (lit) (if (k >= bars - 3) NEEDLE else AMBER) else withAlpha(AMBER, 0.10f)
            val hh = bh * (0.35f + 0.65f * k / (bars - 1))
            cv.drawRoundRect(RectF(x, y4 - hh, x + bw, y4), 2 * d, 2 * d, p)
        }
        // ---- Dial ----
        dial(cv, RectF(left, lcd.bottom + h * 0.05f, right, h - pad))
    }

    private fun drawDeck(cv: Canvas, w: Float, h: Float) {
        val pad = h * 0.09f
        val g = h - pad * 2
        val vuW = g * 1.05f
        vu(cv, RectF(pad, pad + g * 0.12f, pad + vuW, pad + g * 0.88f), lv, "L")
        vu(cv, RectF(w - pad - vuW, pad + g * 0.12f, w - pad, pad + g * 0.88f), rv, "R")
        // Cassette window.
        val left = pad * 2 + vuW
        val right = w - pad * 2 - vuW
        r.set(left, pad, right, pad + g)
        p.color = Color.parseColor("#0C0806"); cv.drawRoundRect(r, 14 * d, 14 * d, p)
        val cas = RectF(r.left + 10 * d, r.top + 10 * d, r.right - 10 * d, r.bottom - 10 * d)
        // Keep a cassette shape (about 1.6 : 1).
        val ch = min(cas.height(), cas.width() / 1.6f)
        val cw = ch * 1.6f
        cas.set(cas.centerX() - cw / 2, cas.centerY() - ch / 2, cas.centerX() + cw / 2, cas.centerY() + ch / 2)
        p.shader = LinearGradient(0f, cas.top, 0f, cas.bottom, Color.parseColor("#9E9E9E"), Color.parseColor("#6E6E6E"), Shader.TileMode.CLAMP)
        cv.drawRoundRect(cas, 10 * d, 10 * d, p); p.shader = null
        // Label with saffron stripes and the title.
        val lab = RectF(cas.left + cw * 0.06f, cas.top + ch * 0.08f, cas.right - cw * 0.06f, cas.top + ch * 0.62f)
        p.color = Color.parseColor("#FFF6E4"); cv.drawRoundRect(lab, 6 * d, 6 * d, p)
        p.color = SAFFRON; cv.drawRect(lab.left, lab.top + lab.height() * 0.18f, lab.right, lab.top + lab.height() * 0.26f, p)
        p.color = Color.parseColor("#C2410C"); cv.drawRect(lab.left, lab.top + lab.height() * 0.27f, lab.right, lab.top + lab.height() * 0.31f, p)
        txt.textAlign = Paint.Align.LEFT; txt.color = Color.parseColor("#3B2A18")
        txt.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        txt.textSize = lab.height() * 0.13f
        val t = ellipsize(title.ifEmpty { "Bcash Player" }, lab.width() * 0.9f)
        cv.drawText(t, lab.left + lab.width() * 0.05f, lab.top + lab.height() * 0.13f, txt)
        txt.typeface = Typeface.MONOSPACE
        // Reel window.
        val win = RectF(lab.left + lab.width() * 0.16f, lab.top + lab.height() * 0.40f, lab.right - lab.width() * 0.16f, lab.bottom - lab.height() * 0.08f)
        p.color = Color.parseColor("#2A2017"); cv.drawRoundRect(win, win.height() / 2, win.height() / 2, p)
        val rr = win.height() * 0.42f
        val lx = win.left + win.height() * 0.55f
        val rx = win.right - win.height() * 0.55f
        val cy = win.centerY()
        // Tape on each reel: the left one empties as the song plays.
        val hub = rr * 0.42f
        val full = rr * 1.25f
        val f = fraction.coerceIn(0f, 1f)
        val leftTape = hub + (full - hub) * sqrt(1f - f)
        val rightTape = hub + (full - hub) * sqrt(f)
        p.color = Color.parseColor("#4A2E18")
        cv.save(); cv.clipRect(win)
        cv.drawCircle(lx, cy, leftTape, p); cv.drawCircle(rx, cy, rightTape, p)
        cv.restore()
        hubWheel(cv, lx, cy, hub, reel)
        hubWheel(cv, rx, cy, hub, reel * 0.9f)
        // Bottom of the shell: the head window and screws.
        p.color = Color.parseColor("#A89A7C")
        val bt = RectF(cas.left + cw * 0.22f, cas.bottom - ch * 0.26f, cas.right - cw * 0.22f, cas.bottom - ch * 0.04f)
        cv.drawRoundRect(bt, 6 * d, 6 * d, p)
        p.color = Color.parseColor("#6D604A")
        for (sx in listOf(cas.left + cw * 0.05f, cas.right - cw * 0.05f)) for (sy in listOf(cas.top + ch * 0.06f + 4 * d, cas.bottom - ch * 0.08f))
            cv.drawCircle(sx, sy, 3.5f * d, p)
        // Playing light.
        p.color = if (playing) NEEDLE else Color.parseColor("#3A3A3A")
        cv.drawCircle(r.right - 14 * d, r.top + 14 * d, 5 * d, p)
        txt.textSize = 10 * d; txt.color = Color.parseColor("#B8ACA0"); txt.textAlign = Paint.Align.RIGHT
        cv.drawText(if (playing) "PLAY" else "PAUSE", r.right - 24 * d, r.top + 18 * d, txt)
    }

    private fun hubWheel(cv: Canvas, x: Float, y: Float, rad: Float, angle: Float) {
        p.color = Color.parseColor("#F2EBDD"); cv.drawCircle(x, y, rad, p)
        p.color = Color.parseColor("#2A2017"); cv.drawCircle(x, y, rad * 0.42f, p)
        p.color = Color.parseColor("#F2EBDD")
        for (k in 0 until 6) {
            val a = Math.toRadians((angle + k * 60).toDouble())
            cv.drawCircle(x + (rad * 0.32f * cos(a)).toFloat(), y + (rad * 0.32f * sin(a)).toFloat(), rad * 0.11f, p)
        }
    }

    private fun grille(cv: Canvas, cx: Float, cy: Float, rad: Float, level: Float) {
        p.color = Color.parseColor("#0E0E0E"); cv.drawCircle(cx, cy, rad, p)
        val step = rad / 7.5f
        var y = cy - rad
        var row = 0
        while (y <= cy + rad) {
            var x = cx - rad + if (row % 2 == 0) 0f else step / 2
            while (x <= cx + rad) {
                val dd = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                if (dd < rad - step * 0.6f) {
                    val near = 1f - dd / rad
                    p.color = withAlpha(SAFFRON, (0.16f + 0.25f * near + level * 0.6f * near).coerceIn(0f, 1f))
                    cv.drawCircle(x, y, step * 0.22f, p)
                }
                x += step
            }
            y += step * 0.87f; row++
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = withAlpha(AMBER_DIM, 0.6f)
        cv.drawCircle(cx, cy, rad - 1 * d, p); p.style = Paint.Style.FILL
    }

    private fun vu(cv: Canvas, box: RectF, level: Float, label: String) {
        p.shader = LinearGradient(0f, box.top, 0f, box.bottom, Color.parseColor("#1C1C1C"), Color.parseColor("#101010"), Shader.TileMode.CLAMP)
        cv.drawRoundRect(box, 12 * d, 12 * d, p); p.shader = null
        // Soft backlight while playing.
        p.shader = RadialGradient(box.centerX(), box.bottom, box.width() * 0.8f, withAlpha(Color.parseColor("#FFD27A"), 0.45f * glow), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawRoundRect(box, 12 * d, 12 * d, p); p.shader = null
        val px = box.centerX(); val py = box.bottom - box.height() * 0.08f
        val rad = min(box.width() * 0.46f, box.height() * 0.78f)
        val start = -140f; val sweep = 100f
        val arc = RectF(px - rad, py - rad, px + rad, py + rad)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d; p.color = Color.parseColor("#8A8A8A"); cv.drawArc(arc, start, sweep * 0.72f, false, p)
        p.strokeWidth = 3.5f * d; p.color = Color.parseColor("#D7261E"); cv.drawArc(arc, start + sweep * 0.72f, sweep * 0.28f, false, p)
        p.strokeWidth = 1.3f * d
        for (k in 0..10) {
            val a = Math.toRadians((start + sweep * k / 10f).toDouble())
            val r2 = rad - (if (k % 5 == 0) 10 * d else 6 * d)
            p.color = if (k >= 8) Color.parseColor("#D7261E") else Color.parseColor("#8A8A8A")
            cv.drawLine(px + (rad * cos(a)).toFloat(), py + (rad * sin(a)).toFloat(), px + (r2 * cos(a)).toFloat(), py + (r2 * sin(a)).toFloat(), p)
        }
        p.style = Paint.Style.FILL
        txt.textAlign = Paint.Align.CENTER; txt.color = Color.parseColor("#8A8A8A"); txt.textSize = box.height() * 0.14f; txt.isFakeBoldText = true
        cv.drawText(label, px, py - rad * 0.38f, txt); txt.isFakeBoldText = false
        val a = Math.toRadians((start + sweep * level.coerceIn(0f, 1f)).toDouble())
        p.color = AMBER; p.strokeWidth = 2.5f * d; p.strokeCap = Paint.Cap.ROUND
        cv.drawLine(px, py, px + ((rad + 2 * d) * cos(a)).toFloat(), py + ((rad + 2 * d) * sin(a)).toFloat(), p)
        cv.drawCircle(px, py, 5 * d, p)
    }

    private fun dial(cv: Canvas, box: RectF) {
        p.shader = LinearGradient(0f, box.top, 0f, box.bottom, Color.parseColor("#1E1A14"), Color.parseColor("#100E0B"), Shader.TileMode.CLAMP)
        cv.drawRoundRect(box, 10 * d, 10 * d, p); p.shader = null
        val padX = 16 * d
        val span = box.width() - padX * 2
        val nx = box.left + padX + span * needle.coerceIn(0f, 1f)
        p.shader = RadialGradient(nx, box.centerY(), box.width() * 0.5f, withAlpha(AMBER, 0.25f * glow), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawRoundRect(box, 10 * d, 10 * d, p); p.shader = null
        txt.textAlign = Paint.Align.CENTER; txt.textSize = box.height() * 0.22f
        for (k in 0..40) {
            val x = box.left + padX + span * (k / 40f)
            val big = k % 4 == 0
            p.color = withAlpha(AMBER, (if (big) 0.85f else 0.4f) * (0.5f + 0.5f * glow)); p.strokeWidth = (if (big) 1.6f else 1f) * d
            cv.drawLine(x, box.top + box.height() * 0.16f, x, box.top + box.height() * (if (big) 0.46f else 0.32f), p)
            if (big) {
                txt.color = withAlpha(AMBER, 0.6f + 0.4f * glow)
                cv.drawText((88 + k / 2).toString(), x, box.top + box.height() * 0.8f, txt)
            }
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = AMBER_DIM; cv.drawRoundRect(box, 10 * d, 10 * d, p); p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.ROUND
        p.color = withAlpha(NEEDLE, 0.25f); p.strokeWidth = 8 * d; cv.drawLine(nx, box.top + 5 * d, nx, box.bottom - 5 * d, p)
        p.color = NEEDLE; p.strokeWidth = 2.5f * d; cv.drawLine(nx, box.top + 5 * d, nx, box.bottom - 5 * d, p)
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (txt.measureText(s) <= maxW) return s
        var e = s
        while (e.isNotEmpty() && txt.measureText("$e…") > maxW) e = e.dropLast(1)
        return "$e…"
    }
}

private fun withAlpha(c: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

/**
 * An old TV set around the YouTube video: walnut cabinet, rounded picture tube, a control panel
 * with two knobs (the channel knob turns with the list position), speaker slots and a power light.
 * [screen] says where the video sits, so the video view can be placed inside it.
 */
class RetroTvFrame(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val screen = RectF()
    var channel = 0
        set(v) { field = v; invalidate() }
    var on = true
        set(v) { field = v; invalidate() }

    /** Fraction of the width used by the knob panel on the right. */
    val panel = 0.15f

    fun layoutScreen(w: Int, h: Int) {
        val m = h * 0.07f
        screen.set(m * 1.2f, m, w * (1f - panel) - m * 0.6f, h - m)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutScreen(w, h) }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, w, h, intArrayOf(Color.parseColor("#2C2C2C"), Color.parseColor("#1C1C1C"), Color.parseColor("#121212")), null, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p); p.shader = null
        // Grain.
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d
        for (k in 0 until 40) {
            val y = h * k / 40f
            p.color = Color.argb(6, 255, 255, 255)
            cv.drawLine(0f, y + sin(k * 0.9f) * 6 * d, w, y + cos(k * 0.7f) * 6 * d, p)
        }
        p.style = Paint.Style.FILL
        // Bezel around the tube.
        val bez = RectF(screen.left - 14 * d, screen.top - 14 * d, screen.right + 14 * d, screen.bottom + 14 * d)
        p.shader = LinearGradient(0f, bez.top, 0f, bez.bottom, Color.parseColor("#2A2622"), Color.parseColor("#0E0C0A"), Shader.TileMode.CLAMP)
        cv.drawRoundRect(bez, 46 * d, 46 * d, p); p.shader = null
        p.color = Color.BLACK; cv.drawRoundRect(screen, 34 * d, 34 * d, p)
        // Control panel.
        val px0 = w * (1f - panel)
        val cx = px0 + (w - px0) / 2 - 6 * d
        knob(cv, cx, h * 0.22f, (w - px0) * 0.3f, channel * 30f, "CHANNEL")
        knob(cv, cx, h * 0.47f, (w - px0) * 0.24f, 210f, "VOLUME")
        // Speaker slots.
        p.color = Color.parseColor("#0C0C0C")
        for (k in 0 until 8) {
            val y = h * 0.62f + k * 9 * d
            cv.drawRoundRect(RectF(cx - (w - px0) * 0.3f, y, cx + (w - px0) * 0.3f, y + 4 * d), 2 * d, 2 * d, p)
        }
        // Brand plate + power light.
        p.color = Color.parseColor("#8E8E8E")
        val plate = RectF(cx - (w - px0) * 0.28f, h * 0.86f, cx + (w - px0) * 0.28f, h * 0.86f + 22 * d)
        cv.drawRoundRect(plate, 5 * d, 5 * d, p)
        p.color = Color.parseColor("#3B2A18"); p.textAlign = Paint.Align.CENTER; p.textSize = 12 * d; p.isFakeBoldText = true
        p.typeface = Typeface.SERIF
        cv.drawText("GHAR", plate.centerX(), plate.centerY() + 4 * d, p); p.isFakeBoldText = false
        p.color = if (on) NEEDLE else Color.parseColor("#3A3A3A")
        cv.drawCircle(cx, h * 0.94f, 5 * d, p)
        if (on) { p.color = withAlpha(NEEDLE, 0.3f); cv.drawCircle(cx, h * 0.94f, 10 * d, p) }
    }

    private fun knob(cv: Canvas, x: Float, y: Float, rad: Float, angle: Float, label: String) {
        p.color = Color.parseColor("#0E0906"); cv.drawCircle(x, y + 3 * d, rad, p)
        p.shader = RadialGradient(x - rad * 0.3f, y - rad * 0.3f, rad * 1.4f, Color.parseColor("#D0D0D0"), Color.parseColor("#4E4E4E"), Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, rad, p); p.shader = null
        p.color = Color.parseColor("#2B1D0E")
        for (k in 0 until 24) {
            val a = Math.toRadians(k * 15.0)
            cv.drawCircle(x + (rad * 0.92f * cos(a)).toFloat(), y + (rad * 0.92f * sin(a)).toFloat(), 1.4f * d, p)
        }
        val a = Math.toRadians((angle - 90).toDouble())
        p.strokeWidth = 3 * d; p.strokeCap = Paint.Cap.ROUND; p.color = Color.parseColor("#2B1D0E")
        cv.drawLine(x, y, x + (rad * 0.7f * cos(a)).toFloat(), y + (rad * 0.7f * sin(a)).toFloat(), p)
        p.color = Color.parseColor("#B8ACA0"); p.textAlign = Paint.Align.CENTER; p.textSize = 9 * d; p.typeface = Typeface.MONOSPACE
        cv.drawText(label, x, y + rad + 14 * d, p)
    }
}

/** Over the video: a gentle picture-tube vignette and faint scan lines. */
class TubeOverlay(c: Context) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.65f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(120, 0, 0, 0)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p); p.shader = null
        p.color = Color.argb(14, 0, 0, 0)
        var y = 0f
        while (y < h) { cv.drawRect(0f, y, w, y + 1 * d, p); y += 3 * d }
        // Glass reflection.
        p.shader = LinearGradient(0f, 0f, w * 0.4f, h * 0.4f, Color.argb(22, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p); p.shader = null
    }
}
