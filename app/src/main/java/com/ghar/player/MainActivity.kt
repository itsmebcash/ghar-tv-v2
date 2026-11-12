package com.ghar.player

import com.ghar.tv.R

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * The only TV screen: what's playing from Bcash Player, a picture sent from the phone (optional),
 * simple controls, the pairing code and a QR code to download Bcash Player.
 * Remote: ◀ ▶ move between buttons, OK presses; media keys work too; Menu = forget phones.
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_NIGHT = "com.ghar.player.extra.NIGHT"
        /** Where phones download Bcash Player (latest release). */
        const val BCASH_DOWNLOAD = "https://github.com/itsmebcash/Bcash-Player/releases/latest/download/BcashPlayer.apk"

        fun fmt(ms: Long): String {
            if (ms <= 0) return "0:00"
            val t = ms / 1000
            val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }

        fun trim(f: Float) = if (f % 1f == 0f) f.toInt().toString() else "%.2f".format(f).trimEnd('0').trimEnd('.')
    }

    // Dark grey, calculator style: amber "display" colour, grey keys, big plain text.
    private val saffron = Color.parseColor("#FFB347")
    private val dim = Color.parseColor("#A3A3A3")
    private val keyGrey = Color.parseColor("#73000000")   // black, see-through
    private val keyFocus = Color.parseColor("#5C5C5C")    // grey when selected
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var bg: GlowBackground
    private lateinit var ytView: YtView
    private lateinit var ytFrame: FrameLayout
    private lateinit var retroTv: RetroTvFrame
    private lateinit var tube: TubeOverlay
    private lateinit var deck: RetroDeck
    private lateinit var radioPanel: RadioPanel
    private lateinit var radioBtn: LinearLayout
    private val songButtons = ArrayList<View>()
    private lateinit var radioLabel: TextView
    private lateinit var radioStopBtn: LinearLayout
    private lateinit var radioStopIcon: ImageView
    private lateinit var radioStopLabel: TextView
    private lateinit var radioHint: TextView
    private lateinit var favFlash: TextView
    /** Favourite station addresses (refreshed when changed). */
    private var favUrls: Set<String> = emptySet()
    /** OK is being held (long press = favourite). */
    private var centerLong = false
    private var autoStartTries = 0
    private lateinit var night: NightScreen
    private lateinit var nightTop: LinearLayout
    private lateinit var heart: ImageView
    private lateinit var heartLabel: TextView
    private lateinit var heartBox: LinearLayout
    private var deckTall = false
    private lateinit var deckBox: FrameLayout
    private lateinit var radioKeys: LinearLayout
    /** When Back was last pressed (Back twice quickly = close the app). */
    private var lastBack = 0L
    private lateinit var stopKey: LinearLayout
    private lateinit var volBox: LinearLayout
    private lateinit var volText: TextView
    private lateinit var volBar: ProgressLine
    private lateinit var nightBtn: ImageButton
    private lateinit var loadRow: LinearLayout
    private lateinit var loadText: TextView
    private lateinit var col: LinearLayout
    /** While a YouTube video plays, the info and buttons show for a few seconds after a key press. */
    private var overlayUntil = 0L
    private lateinit var picture: ImageView
    private lateinit var scrim: View
    private lateinit var playlist: TextView
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var totalPill: TextView
    private lateinit var leftPill: TextView
    private lateinit var timesRow: LinearLayout
    private lateinit var progress: ProgressLine
    private lateinit var times: TextView
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var playBtn: ImageButton
    private lateinit var codeBox: LinearLayout
    private lateinit var codeText: TextView
    private lateinit var pairedText: TextView
    private var shownPicture = -1

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun text(size: Float, color: Int, bold: Boolean = false, serif: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        gravity = Gravity.CENTER

    }

    private fun pill(): TextView = text(22f, Color.WHITE, bold = true).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(Color.parseColor("#262626")); setStroke(dp(1f), Color.parseColor("#3A3A3A")) }
        setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
    }

    /** Calculator key: rounded square, a thick amber edge when selected. */
    private fun roundBg(normal: Int, focused: Int): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = dp(20f).toFloat(); setColor(focused); setStroke(dp(2f), Color.parseColor("#9E9E9E"))
        })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(normal) })
    }

    /** Material TV focus: the selected key grows a little and lifts. */
    private fun liftOnFocus(v: View) {
        v.setOnFocusChangeListener { view, f ->
            view.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).translationZ(if (f) dp(8f).toFloat() else 0f).setDuration(150).start()
        }
    }

    private fun control(icon: Int, desc: String, big: Boolean = false, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(if (big) 22f else 20f)
        setPadding(pad, pad, pad, pad)
        background = roundBg(keyGrey, keyFocus)
        liftOnFocus(this)
        isFocusable = true
        setOnClickListener { onClick(); refresh() }
        layoutParams = LinearLayout.LayoutParams(dp(if (big) 100f else 86f), dp(if (big) 100f else 86f)).apply {
            leftMargin = dp(12f); rightMargin = dp(12f)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlaybackService.ensure(this)
        TvRadio.loadLang(this)
        TvVolume.init(this)
        // Android 13+: allow the "now playing" notification that keeps the radio alive in the background.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7) }
        favUrls = TvRadio.saved(this).map { it.url }.toSet()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#141414")) }
        picture = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        root.addView(picture, FrameLayout.LayoutParams(-1, -1))
        bg = GlowBackground(this)
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))
        scrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x33000000, 0x22000000, 0xCC000000.toInt()))
            visibility = View.GONE
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        // YouTube plays inside an old TV set: walnut cabinet, rounded tube, knobs.
        ytFrame = FrameLayout(this).apply { visibility = View.GONE }
        retroTv = RetroTvFrame(this)
        ytFrame.addView(retroTv, FrameLayout.LayoutParams(-1, -1))
        ytView = YtView(this)
        ytView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(34f).toFloat())
            }
        }
        ytView.clipToOutline = true
        tube = TubeOverlay(this)
        ytFrame.addView(ytView, FrameLayout.LayoutParams(0, 0))
        ytFrame.addView(tube, FrameLayout.LayoutParams(0, 0))
        retroTv.addOnLayoutChangeListener { _, l, t, rr, b, _, _, _, _ ->
            retroTv.layoutScreen(rr - l, b - t)
            val sc = retroTv.screen
            for (v in listOf<View>(ytView, tube)) {
                val lp = v.layoutParams as FrameLayout.LayoutParams
                val w = sc.width().toInt(); val h = sc.height().toInt()
                if (lp.width != w || lp.height != h || lp.leftMargin != sc.left.toInt() || lp.topMargin != sc.top.toInt()) {
                    lp.width = w; lp.height = h; lp.leftMargin = sc.left.toInt(); lp.topMargin = sc.top.toInt()
                    v.post { v.layoutParams = lp }
                }
            }
        }
        root.addView(ytFrame, FrameLayout.LayoutParams(-1, -1))

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(70f), dp(16f), dp(70f), dp(12f))
        }
        val brand = text(15f, Color.parseColor("#7A7A7A"), bold = true).apply { letterSpacing = 0.3f; text = "GHAR PLAYER" }
        playlist = text(32f, saffron, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        deck = RetroDeck(this)
        title = text(38f, Color.parseColor("#F2F2F2"), bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        sub = text(24f, dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        totalPill = pill(); leftPill = pill().apply { setTextColor(saffron) }
        timesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(totalPill); addView(View(context), LinearLayout.LayoutParams(dp(14f), 1)); addView(leftPill)
        }
        progress = ProgressLine(this, saffron)
        times = text(24f, dim).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        status = text(22f, saffron)

        playBtn = control(R.drawable.gp_ctl_play, "Play or pause", big = true) { PlaybackService.instance?.toggle() }
        radioBtn = bigRadioButton()
        val prevB = control(R.drawable.gp_ctl_prev, "Previous") { PlaybackService.instance?.previous() }
        val stopB = control(R.drawable.gp_ctl_stop, "Stop") { PlaybackService.instance?.stopAll() }
        val nextB = control(R.drawable.gp_ctl_next, "Next") { PlaybackService.instance?.next() }
        songButtons += listOf(prevB, playBtn, stopB, nextB)
        radioStopBtn = bigStopButton()
        // Big heart: red and filled when this station is a favourite, an outline when not.
        heart = ImageView(this)
        heartLabel = text(20f, dim, bold = true)
        heartBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(heart, LinearLayout.LayoutParams(dp(84f), dp(84f)))
            addView(heartLabel)
            visibility = View.GONE
        }
        heartBox.orientation = LinearLayout.HORIZONTAL
        (heart.layoutParams as? LinearLayout.LayoutParams)?.let { it.width = dp(62f); it.height = dp(62f) }
        heartLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        heartLabel.setPadding(dp(14f), 0, 0, 0)
        radioStopBtn.layoutParams = LinearLayout.LayoutParams(-2, dp(96f))
        radioKeys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(radioStopBtn)
            stopKey = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                isFocusable = true; isClickable = true
                setPadding(dp(36f), 0, dp(42f), 0)
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        cornerRadius = dp(24f).toFloat(); setColor(keyFocus); setStroke(dp(2f), Color.parseColor("#9E9E9E"))
                    })
                    addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24f).toFloat(); setColor(keyGrey) })
                }
                addView(ImageView(context).apply { setImageResource(R.drawable.gp_ctl_stop); setColorFilter(Color.parseColor("#FF6B5E")) }, LinearLayout.LayoutParams(dp(46f), dp(46f)))
                addView(text(34f, Color.WHITE, bold = true).apply { tag = "label"; setPadding(dp(16f), 0, 0, 0) })
                setOnClickListener { PlaybackService.instance?.radioStop(); refresh(); radioStopBtn.requestFocus() }
                liftOnFocus(this)
            }
            addView(stopKey, LinearLayout.LayoutParams(-2, dp(96f)).apply { leftMargin = dp(34f) })
            visibility = View.GONE
        }
        nightBtn = control(R.drawable.gp_ctl_moon, "Night clock") { nightOn() }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(prevB)
            addView(playBtn)
            addView(stopB)
            addView(nextB)
            addView(radioBtn)
            addView(nightBtn)
        }

        col.addView(brand)
        col.addView(space(6))
        col.addView(playlist)
        col.addView(space(8))
        col.addView(timesRow)
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        deckBox = FrameLayout(this).apply {
            clipChildren = false
            addView(deck, FrameLayout.LayoutParams(-1, -1))
            addView(radioKeys, FrameLayout.LayoutParams(-1, dp(120f), Gravity.BOTTOM).apply { bottomMargin = dp(12f) })
        }
        col.clipChildren = false
        col.addView(deckBox, LinearLayout.LayoutParams(-1, dp(165f)).apply { leftMargin = dp(30f); rightMargin = dp(30f) })
        col.addView(space(10))
        col.addView(title, LinearLayout.LayoutParams(-1, -2))
        col.addView(space(8))
        col.addView(sub, LinearLayout.LayoutParams(-1, -2))
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(progress, LinearLayout.LayoutParams(-1, dp(10f)))
        col.addView(space(6))
        col.addView(times, LinearLayout.LayoutParams(-1, -2))
        // What is happening: connecting to a station, loading a song…
        loadText = text(24f, saffron)
        loadRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(LoadingView(context), LinearLayout.LayoutParams(dp(34f), dp(34f)))
            addView(loadText.apply { setPadding(dp(12f), 0, 0, 0) })
            visibility = View.INVISIBLE
        }
        col.addView(loadRow, LinearLayout.LayoutParams(-1, dp(40f)).apply { topMargin = dp(2f) })
        col.addView(space(14))
        col.addView(controls, LinearLayout.LayoutParams(-1, -2))
        radioHint = text(20f, Color.parseColor("#BDBDBD")).apply { visibility = View.GONE; setPadding(0, dp(8f), 0, 0) }
        col.addView(heartBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        col.addView(radioHint, LinearLayout.LayoutParams(-1, -2))
        col.addView(space(10))
        col.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Bottom-right: pairing code (big) + QR to download Bcash Player.
        codeBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.parseColor("#F21F1F1F")); setStroke(dp(1f), Color.parseColor("#3A3A3A")) }
            setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
        }
        val qr = ImageView(this).apply { setImageBitmap(qrBitmap(BCASH_DOWNLOAD, 360)) }
        val qrCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(qr, LinearLayout.LayoutParams(dp(104f), dp(104f)))
            addView(text(14f, dim).apply { text = "Get Bcash Player" })
        }
        val codeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18f), 0, 0, 0)
            addView(text(16f, dim).apply { text = "PAIRING CODE"; letterSpacing = 0.2f })
            codeText = text(58f, saffron, bold = true).apply { letterSpacing = 0.12f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
            addView(codeText)
            addView(text(15f, dim).apply { text = "Bcash Player › player › TV button" })
            pairedText = text(14f, dim)
            addView(pairedText)
        }
        codeBox.addView(codeCol)
        root.addView(codeBox, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(28f); bottomMargin = dp(24f)
        })
        radioPanel = RadioPanel(this, onPlayed = {
            favUrls = TvRadio.saved(this).map { it.url }.toSet()
            refresh(); radioStopBtn.requestFocus()
        }, onLanguage = { radioLabel.text = TvRadio.t("रेडियो", "Radio"); refresh() }, onNight = { nightOn() }).apply { visibility = View.GONE }
        root.addView(radioPanel, FrameLayout.LayoutParams(-1, -1))
        favFlash = text(42f, Color.WHITE, bold = true).apply {
            background = GradientDrawable().apply { cornerRadius = dp(24f).toFloat(); setColor(Color.parseColor("#E6000000")); setStroke(dp(3f), saffron) }
            setPadding(dp(36f), dp(20f), dp(36f), dp(20f))
            visibility = View.GONE
        }
        nightTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            isFocusable = true; isClickable = true
            setPadding(dp(22f), dp(10f), dp(26f), dp(10f))
            background = roundBg(keyGrey, keyFocus)
            addView(ImageView(context).apply { setImageResource(R.drawable.gp_ctl_moon) }, LinearLayout.LayoutParams(dp(34f), dp(34f)))
            addView(text(22f, Color.WHITE, bold = true).apply { tag = "label"; setPadding(dp(12f), 0, 0, 0) })
            setOnClickListener { nightOn() }
        }
        liftOnFocus(nightTop)
        root.addView(nightTop, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(18f); rightMargin = dp(24f) })
        // Volume shown big while the volume keys are pressed.
        volText = text(34f, Color.WHITE, bold = true)
        volBar = ProgressLine(this, saffron)
        volBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(36f), dp(20f), dp(36f), dp(24f))
            background = GradientDrawable().apply { cornerRadius = dp(24f).toFloat(); setColor(Color.parseColor("#E6141414")); setStroke(dp(1f), Color.parseColor("#3A3A3A")) }
            addView(volText)
            addView(volBar, LinearLayout.LayoutParams(dp(420f), dp(16f)).apply { topMargin = dp(14f) })
            visibility = View.GONE
        }
        night = NightScreen(this) { nightOff() }.apply { visibility = View.GONE }
        root.addView(night, FrameLayout.LayoutParams(-1, -1))
        root.addView(favFlash, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(volBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(40f) })
        setContentView(root)
        handler.postDelayed(autoStart, 1200)
        playBtn.requestFocus()
    }

    /** A large, clearly labelled Radio button (easy to find and press with the remote). */
    private fun bigRadioButton() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        contentDescription = "Radio"
        setPadding(dp(26f), 0, dp(30f), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat(); setColor(keyFocus); setStroke(dp(2f), Color.parseColor("#9E9E9E"))
            })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(22f).toFloat(); setColor(keyGrey) })
        }
        liftOnFocus(this)
        addView(ImageView(context).apply { setImageResource(R.drawable.gp_ctl_radio); setColorFilter(saffron) }, LinearLayout.LayoutParams(dp(46f), dp(46f)))
        radioLabel = text(30f, Color.WHITE, bold = true).apply { text = TvRadio.t("रेडियो", "Radio"); setPadding(dp(16f), 0, 0, 0) }
        addView(radioLabel)
        // Nothing playing: start the radio right away (last station). Otherwise: the station list.
        setOnClickListener {
            val s = PlaybackService.instance
            if (s != null && s.now().item == null) { s.autoStartRadio(); refresh() } else openRadio()
        }
        layoutParams = LinearLayout.LayoutParams(-2, dp(100f)).apply { leftMargin = dp(22f); rightMargin = dp(12f) }
    }

    /** The big Stop / Play button shown while radio plays. */
    private fun bigStopButton() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setPadding(dp(40f), 0, dp(46f), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat(); setColor(keyFocus); setStroke(dp(2f), Color.parseColor("#9E9E9E"))
            })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24f).toFloat(); setColor(keyGrey) })
        }
        liftOnFocus(this)
        radioStopIcon = ImageView(context).apply { setImageResource(R.drawable.gp_ctl_stop); setColorFilter(Color.parseColor("#FF6B5E")) }
        addView(radioStopIcon, LinearLayout.LayoutParams(dp(50f), dp(50f)))
        radioStopLabel = text(36f, Color.WHITE, bold = true).apply { setPadding(dp(18f), 0, 0, 0) }
        addView(radioStopLabel)
        setOnClickListener { radioOk() }
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(-2, dp(112f))
    }

    /** OK on the radio: playing → night clock; stopped → play again. */
    private fun radioOk() = radioToggle()

    private fun radioToggle() {
        val s = PlaybackService.instance ?: return
        if (s.now().playing) s.radioStop() else s.radioPlay()
        refresh()
    }

    /** Hold OK while a station plays: add it to favourites (or take it out). */
    private fun toggleFavourite() {
        val item = PlaybackService.instance?.now()?.item ?: return
        if (item.stream.isEmpty()) return
        val now = TvRadio.toggleSaved(this, TvRadio.stationOf(item))
        favUrls = TvRadio.saved(this).map { it.url }.toSet()
        favFlash.text = if (now) TvRadio.t("♥  मनपर्नेमा थपियो", "♥  Added to favourites") else TvRadio.t("♡  मनपर्नेबाट हटाइयो", "♡  Removed from favourites")
        favFlash.visibility = View.VISIBLE
        favFlash.alpha = 1f
        favFlash.animate().cancel()
        favFlash.animate().alpha(0f).setStartDelay(1600).setDuration(500).withEndAction { favFlash.visibility = View.GONE }.start()
        refresh()
    }

    /** App opened and no phone is playing here: start the radio (last station) by itself. */
    private val autoStart = object : Runnable {
        override fun run() {
            val s = PlaybackService.instance
            if (s == null) { if (autoStartTries++ < 20) handler.postDelayed(this, 500); return }
            if (s.now().item == null && !radioPanel.isOpen) s.autoStartRadio()
        }
    }

    /** Night clock: black screen, dim grey time + news, the screen turned right down. */
    private fun nightOn() {
        night.show()
        window.attributes = window.attributes.apply { screenBrightness = 0.03f }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun nightOff() {
        night.hide()
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        refresh()
        (if (radioStopBtn.visibility == View.VISIBLE) radioStopBtn else if (radioBtn.visibility == View.VISIBLE) radioBtn else playBtn).requestFocus()
    }

    /**
     * Back: normal screen → night clock → normal screen (also after Stop).
     * Back twice within 2.5 seconds: close the app.
     */
    private val hideVolume = Runnable { volBox.animate().alpha(0f).setDuration(400).withEndAction { volBox.visibility = View.GONE }.start() }

    private fun showVolume() {
        val lv = TvVolume.level
        volText.text = (if (lv == 0) "🔇  " else "🔊  ") + TvRadio.t("आवाज  ${TvRadio.num(lv)}", "Volume  $lv")
        volBar.fraction = lv / 100f
        volBox.animate().cancel()
        volBox.alpha = if (night.isOn) 0.5f else 1f
        volBox.visibility = View.VISIBLE
        volBox.bringToFront()
        handler.removeCallbacks(hideVolume)
        handler.postDelayed(hideVolume, 1800)
    }

    /**
     * Back once: main screen ⇄ night clock.
     * Back twice quickly: the TV home screen (the radio keeps playing).
     */
    private fun backPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBack < 1200) { lastBack = 0L; goHome(); return }
        lastBack = now
        if (night.isOn) nightOff() else nightOn()
    }

    /** Back to the TV's home screen; the radio keeps playing in the background. */
    private fun goHome() {
        night.hide()
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        refresh()
        if (!moveTaskToBack(true)) {
            runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private var backDialog: android.app.Dialog? = null

    /** Back pressed twice: "Go to clock" or "Stop the radio and close", in big keys. */
    private fun showBackChoice() {
        if (backDialog?.isShowing == true) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(40f), dp(30f), dp(40f), dp(30f))
            background = GradientDrawable().apply { cornerRadius = dp(28f).toFloat(); setColor(Color.parseColor("#F2202020")); setStroke(dp(1f), Color.parseColor("#3A3A3A")) }
        }
        box.addView(text(30f, Color.WHITE, bold = true).apply { text = TvRadio.t("के गर्ने?", "What would you like?"); setPadding(0, 0, 0, dp(20f)) })
        val dlg = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        fun key(label: String, icon: Int, tint: Int, action: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true
            setPadding(dp(30f), 0, dp(36f), 0)
            background = roundBg(Color.parseColor("#73000000"), keyFocus)
            addView(ImageView(context).apply { setImageResource(icon); setColorFilter(tint) }, LinearLayout.LayoutParams(dp(46f), dp(46f)))
            addView(text(30f, Color.WHITE, bold = true).apply { text = label; setPadding(dp(18f), 0, 0, 0) })
            setOnClickListener { dlg.dismiss(); action() }
            liftOnFocus(this)
        }
        val clock = key(TvRadio.t("घडी मोडमा जानुहोस्", "Go to the night clock"), R.drawable.gp_ctl_moon, saffron) { if (!night.isOn) nightOn() }
        val stop = key(TvRadio.t("रेडियो बन्द गर्नुहोस्", "Stop the radio and close"), R.drawable.gp_ctl_stop, Color.parseColor("#FF6B5E")) { closeApp() }
        val back = key(TvRadio.t("रेडियोमा फर्कनुहोस्", "Back to the radio"), R.drawable.gp_ctl_radio, Color.WHITE) { if (night.isOn) nightOff() }
        for (k in listOf(clock, stop, back)) box.addView(k, LinearLayout.LayoutParams(dp(560f), dp(92f)).apply { bottomMargin = dp(14f) })
        dlg.setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        })
        // Back inside the choice simply closes it.
        dlg.setOnKeyListener { d, code, ev -> if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) { d.dismiss(); true } else false }
        dlg.setOnDismissListener { backDialog = null }
        backDialog = dlg
        dlg.show()
        clock.requestFocus()
    }

    private fun closeApp() {
        // The TV's own radio stops with the app; music sent from a phone keeps playing.
        if (TvHub.local) PlaybackService.instance?.radioStop()
        if (night.isOn) night.hide()
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        finish()
    }

    private fun openRadio() {
        radioPanel.open()
    }

    @Deprecated("Back closes the radio list first")
    override fun onBackPressed() {
        if (radioPanel.isOpen) {
            radioPanel.close()
            favUrls = TvRadio.saved(this).map { it.url }.toSet()
            if (radioStopBtn.visibility == View.VISIBLE) radioStopBtn.requestFocus() else radioBtn.requestFocus()
            return
        }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h.toFloat())) }

    private fun qrBitmap(content: String, size: Int): Bitmap? = runCatching {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Opened straight into the night clock (a shortcut, or the emulator test).
        if (intent?.getBooleanExtra(EXTRA_NIGHT, false) == true) {
            intent.removeExtra(EXTRA_NIGHT)
            handler.postDelayed({ if (!night.isOn) nightOn() }, 1500)
        }
        YtBridge.view = ytView
        Pairing.open()
        Pairing.onChanged = { refresh() }
        handler.post(tick)
    }

    override fun onPause() {
        Pairing.close()
        Pairing.onChanged = null
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onDestroy() {
        if (YtBridge.view === ytView) YtBridge.view = null
        runCatching { ytView.destroy() }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Volume keys: fine, gradual steps (works on every screen, also the night clock).
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                TvVolume.step(this, if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
                showVolume()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !radioPanel.isOpen) {
            if (event.action == KeyEvent.ACTION_UP) backPressed()
            return true
        }
        if (night.isOn) {
            // Night clock: ▲ ▼ / CH still change station; any other button brings the screen back.
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (event.action == KeyEvent.ACTION_DOWN) PlaybackService.instance?.next(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) PlaybackService.instance?.previous(); return true }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
            }
            if (event.action == KeyEvent.ACTION_UP) { lastBack = 0L; nightOff() }
            return true
        }
        if (radioPanel.isOpen) return super.dispatchKeyEvent(event)
        // Radio playing: the remote works like a radio.
        //   ▲ ▼ / CH+ CH−  change station    ◀ ▶  station list
        //   OK  stop / play    hold OK  favourite
        val cur = PlaybackService.instance?.now()?.item
        if (cur != null && cur.stream.isNotEmpty()) {
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    if (down && event.repeatCount == 0) nightOn()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (down) { PlaybackService.instance?.next(); refresh() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (down) { PlaybackService.instance?.previous(); refresh() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (down && event.repeatCount == 0) openRadio()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down && event.repeatCount == 0) openRadio()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (down) {
                        if (event.repeatCount == 0) centerLong = false
                        else if (!centerLong && event.repeatCount >= 1) { centerLong = true; toggleFavourite() }
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        if (!centerLong) radioToggle()
                        centerLong = false
                    }
                    return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && YtBridge.active) {
            val hidden = System.currentTimeMillis() > overlayUntil
            overlayUntil = System.currentTimeMillis() + 6000
            if (hidden) { refresh(); return true } // first press only shows the controls
        }
        return super.dispatchKeyEvent(event)
    }

    private fun refresh() {
        val s = PlaybackService.instance
        val n = s?.now()
        val item = n?.item
        codeText.text = Pairing.code()?.let { it.substring(0, 3) + " " + it.substring(3) } ?: "—"
        val paired = Pairing.pairedCount(this)
        pairedText.text = if (paired > 0) "$paired phone${if (paired > 1) "s" else ""} paired · Menu to forget" else ""

        if (TvHub.pictureVersion != shownPicture) {
            shownPicture = TvHub.pictureVersion
            val b = TvHub.pictureBitmap
            picture.setImageBitmap(b)
            picture.visibility = if (b != null) View.VISIBLE else View.GONE
            scrim.visibility = picture.visibility
            bg.overPicture = b != null
        }

        if (item == null) {
            ytFrame.visibility = View.GONE
            col.visibility = View.VISIBLE
            playlist.text = ""
            timesRow.visibility = View.INVISIBLE
            deck.visibility = View.GONE
            title.text = TvRadio.t("नमस्ते 🙏", "Namaste 🙏")
            sub.text = TvRadio.t(
                "रेडियो सुन्न रेडियो बटन थिच्नुहोस् — वा फोनको Bcash Player बाट बजाउनुहोस्।",
                "Press the Radio button to listen to FM — or play from Bcash Player on your phone."
            )
            radioStopBtn.visibility = View.GONE
            radioHint.visibility = View.GONE
            heartBox.visibility = View.GONE
            radioKeys.visibility = View.GONE
            nightTop.visibility = View.VISIBLE
            title.visibility = View.VISIBLE; sub.visibility = View.VISIBLE; playlist.visibility = View.VISIBLE; times.visibility = View.VISIBLE
            nightTop.findViewWithTag<TextView>("label").text = TvRadio.t("रात्रि घडी", "Night clock")
            radioBtn.visibility = View.VISIBLE
            nightBtn.visibility = View.VISIBLE
            loadRow.visibility = if (TvHub.starting) View.VISIBLE else View.INVISIBLE
            radioLabel.text = TvRadio.t("रेडियो बजाउनुहोस्", "Play radio")
            loadText.text = TvRadio.t("रेडियो सुरु हुँदैछ…", "Starting the radio…")
            progress.fraction = 0f
            progress.visibility = View.INVISIBLE
            times.text = ""
            status.text = TvHub.message
            // Only the radio button while nothing plays.
            controls.visibility = View.VISIBLE
            songButtons.forEach { it.visibility = View.GONE }
            if (!radioPanel.isOpen && (currentFocus == null || currentFocus?.visibility != View.VISIBLE)) radioBtn.requestFocus()
            codeBox.visibility = View.VISIBLE
            bg.active = false
            if (!night.isOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        controls.visibility = View.VISIBLE
        val yt = item.yt != null && YtBridge.active
        val radio = item.stream.isNotEmpty()
        // Radio: one big Stop / Play button; the remote's arrows do the rest.
        songButtons.forEach { it.visibility = if (radio) View.GONE else View.VISIBLE }
        radioBtn.visibility = if (radio) View.GONE else View.VISIBLE
        radioLabel.text = TvRadio.t("रेडियो", "Radio")
        radioStopBtn.visibility = if (radio) View.VISIBLE else View.GONE
        radioHint.visibility = if (radio) View.VISIBLE else View.GONE
        nightBtn.visibility = if (radio) View.GONE else View.VISIBLE
        heartBox.visibility = if (radio) View.VISIBLE else View.GONE
        // The radio has its own Clock key, so the top one is only for songs and videos.
        nightTop.visibility = if (radio || yt) View.GONE else View.VISIBLE
        nightTop.findViewWithTag<TextView>("label").text = TvRadio.t("रात्रि घडी", "Night clock")
        // Radio: one tall set with every word on its LCD; nothing else to read.
        playlist.visibility = if (radio) View.GONE else View.VISIBLE
        title.visibility = if (radio) View.GONE else View.VISIBLE
        sub.visibility = if (radio) View.GONE else View.VISIBLE
        times.visibility = if (radio) View.GONE else View.VISIBLE
        if (deckTall != radio) {
            deckTall = radio
            deckBox.layoutParams = (deckBox.layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (radio) 400f else 165f) }
            deck.trayPx = if (radio) dp(132f).toFloat() else 0f
        }
        radioKeys.visibility = if (radio) View.VISIBLE else View.GONE
        // With radio, every button is inside the radio: hide the row underneath.
        controls.visibility = if (radio) View.GONE else View.VISIBLE
        if (radio) {
            val fav = item.stream in favUrls
            heart.setImageResource(if (fav) R.drawable.gp_ic_heart_red else R.drawable.gp_ic_heart)
            heart.alpha = if (fav) 1f else 0.6f
            heartLabel.text = if (fav) TvRadio.t("मनपर्नेमा छ   ·   हटाउन OK थिचिराख्नुहोस्", "In favourites   ·   hold OK to remove")
                else TvRadio.t("मनपर्नेमा थप्न OK थिचिराख्नुहोस्", "Hold OK to add to favourites")
            heartLabel.setTextColor(if (fav) Color.parseColor("#FF6B6B") else dim)
            deck.lcdTop = item.playlist.ifEmpty { TvRadio.t("रेडियो", "Radio") } +
                (if (!TvHub.local && TvHub.phoneName.isNotEmpty()) "  ·  " + TvHub.phoneName else "")
            deck.lcdInfo = if (TvHub.onAir.isNotEmpty() && n.playing) TvRadio.t("अहिले: ", "On air: ") + TvHub.onAir else item.artist
            deck.lcdQuality = item.album
            deck.favourite = false
        }
        // Loading animation with words for what is happening.
        loadRow.visibility = if (n.buffering) View.VISIBLE else View.INVISIBLE
        loadText.text = when {
            radio -> TvRadio.t("स्टेशन जोडिँदैछ…  ${item.title}", "Connecting to ${item.title}…")
            item.yt != null -> TvRadio.t("भिडियो खुल्दैछ…", "Opening the video…")
            else -> TvRadio.t("गीत लोड हुँदैछ…", "Loading the song…")
        }
        if (radio) {
            val on = n.playing || n.buffering
            radioStopIcon.setImageResource(if (on) R.drawable.gp_ctl_stop else R.drawable.gp_ctl_play)
            radioStopIcon.setColorFilter(if (on) Color.parseColor("#FF6B5E") else Color.parseColor("#4ADE80"))
            radioStopLabel.text = if (on) TvRadio.t("रोक्नुहोस्", "Stop") else TvRadio.t("बजाउनुहोस्", "Play")
            radioHint.text = TvRadio.t(
                "▲ ▼ च्यानल  ·  ◀ ▶ सूची  ·  OK = रोक्ने / बजाउने  ·  OK थिचिराख्नुहोस् = ♥  ·  Back = 🌙 घडी  ·  Back ×२ = होम",
                "▲ ▼ channel  ·  ◀ ▶ list  ·  OK = stop / play  ·  hold OK = ♥  ·  Back = 🌙 clock  ·  Back ×2 = home"
            )
            stopKey.visibility = View.GONE
            stopKey.findViewWithTag<TextView>("label").text = TvRadio.t("रोक्नुहोस्", "Stop")
            if (!radioPanel.isOpen && currentFocus !== radioStopBtn && !(currentFocus === stopKey && stopKey.visibility == View.VISIBLE)) radioStopBtn.requestFocus()
        }
        ytFrame.visibility = if (yt) View.VISIBLE else View.GONE
        retroTv.channel = n.index
        retroTv.on = n.playing
        if (yt) {
            val showOverlay = System.currentTimeMillis() < overlayUntil || !n.playing
            col.visibility = if (showOverlay) View.VISIBLE else View.GONE
            scrim.visibility = if (showOverlay) View.VISIBLE else View.GONE
            codeBox.visibility = View.GONE
        } else {
            col.visibility = View.VISIBLE
            scrim.visibility = if (TvHub.pictureBitmap != null) View.VISIBLE else View.GONE
        }
        // While music plays the code card steps aside unless no phone is paired yet.
        if (!yt) codeBox.visibility = if (!radio && (paired == 0 || !n.playing)) View.VISIBLE else View.GONE
        playlist.text = buildString {
            append(item.playlist.ifEmpty { if (TvHub.local) "Radio" else "From ${TvHub.phoneName.ifEmpty { "phone" }}" })
            if (item.rounds > 1) append("  ·  round ${item.round}/${item.rounds}")
            if (radio && !TvHub.local && TvHub.phoneName.isNotEmpty()) append("  ·  from ${TvHub.phoneName}")
        }
        // The retro set: a radio for stations, a cassette deck for songs.
        deck.visibility = if (yt) View.GONE else View.VISIBLE
        deck.mode = if (radio) RetroDeck.Mode.RADIO else RetroDeck.Mode.CASSETTE
        deck.stationName = item.title
        deck.stationIndex = n.index
        deck.stationCount = n.count
        deck.title = item.title
        deck.fraction = if (n.duration > 0) (n.position.toFloat() / n.duration).coerceIn(0f, 1f) else 0f
        deck.speed = item.speed
        deck.playing = n.playing
        deck.tuning = radio && n.buffering
        progress.visibility = if (radio) View.INVISIBLE else View.VISIBLE
        timesRow.visibility = if (radio) View.GONE else View.VISIBLE
        totalPill.text = "TOTAL  ${fmt(n.totalMs)}"
        leftPill.text = "LEFT  ${fmt(n.leftMs)}"
        title.text = if (yt && YtBridge.title.isNotEmpty()) YtBridge.title
            else if (radio && item.stream in favUrls) "★ ${item.title}" else item.title
        sub.text = if (radio) buildList {
            add(if (n.playing) TvRadio.t("● प्रत्यक्ष", "● LIVE") else TvRadio.t("■ रोकिएको", "■ Stopped"))
            if (TvHub.onAir.isNotEmpty() && n.playing) add(TvRadio.t("अहिले: ", "On air: ") + TvHub.onAir) else if (item.artist.isNotEmpty()) add(item.artist)
            if (item.album.isNotEmpty()) add(item.album)
        }.joinToString("   ·   ") else buildList {
            if (item.yt != null) add(if (item.yt.isList) "YouTube playlist" + (if (YtBridge.listLength > 0) " · ${YtBridge.listIndex + 1}/${YtBridge.listLength}" else "") else "YouTube")
            else if (item.instr != null) add("Spoken instruction") else {
                if (item.artist.isNotEmpty()) add(item.artist)
                if (item.album.isNotEmpty()) add(item.album)
            }
            if (item.repeat > 1) add("repeat ${TvHub.repeatDone + 1} of ${item.repeat}")
            if (item.speed != 1f) add("${trim(item.speed)}×")
        }.joinToString("   ·   ")
        progress.fraction = if (n.duration > 0) (n.position.toFloat() / n.duration).coerceIn(0f, 1f) else 0f
        times.text = if (radio) TvRadio.t("स्टेशन ${TvRadio.num(n.index + 1)} / ${TvRadio.num(n.count)}", "Station ${n.index + 1} of ${n.count}")
            else "${fmt(n.position)}  /  ${fmt(n.duration)}     ·     ${n.index + 1} of ${n.count}"
        status.text = TvHub.message
        playBtn.setImageResource(if (n.playing) R.drawable.gp_ctl_pause else R.drawable.gp_ctl_play)
        bg.active = n.playing
        if (n.playing || night.isOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = PlaybackService.instance ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> s.toggle()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> s.previous()
            KeyEvent.KEYCODE_MEDIA_NEXT -> s.next()
            KeyEvent.KEYCODE_MEDIA_STOP -> s.stopAll()
            KeyEvent.KEYCODE_MENU -> {
                AlertDialog.Builder(this)
                    .setTitle("Forget paired phones?")
                    .setMessage("Phones will need the code again to play on this TV.")
                    .setPositiveButton("Forget") { _, _ -> Pairing.forgetAll(this) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        refresh()
        return true
    }
}

/** Thin rounded progress line. */
class ProgressLine(c: Context, private val color: Int) : View(c) {
    var fraction = 0f
        set(v) { field = v; invalidate() }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#333333") }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(r, h / 2, h / 2, bgPaint)
        r.right = width * fraction
        fg.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.parseColor("#C98A3A"), color, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, h / 2, h / 2, fg)
    }
}

/**
 * Slowly moving colour gradient with soft, blurred white particles. Everything follows the
 * music: loud parts make the glow and the particles brighter (pure white) and faster, quiet
 * parts leave them dim. Animates only while music plays.
 */
class GlowBackground(c: Context) : View(c), Choreographer.FrameCallback {
    private class P(var x: Float, var y: Float, val vx: Float, val vy: Float, val r: Float, val phase: Float, val sens: Float)

    private val rnd = Random(11)
    private val ps = List(70) {
        P(rnd.nextFloat(), rnd.nextFloat(), (rnd.nextFloat() - 0.5f) * 0.010f, -(0.002f + rnd.nextFloat() * 0.012f),
            6f + rnd.nextFloat() * 22f, rnd.nextFloat() * 6.28f, 0.5f + rnd.nextFloat())
    }
    private val density = c.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = Rect()
    private var level = 0f
    private var last = 0L
    private var t = 0f
    private var running = false

    /** One soft dot (white centre fading to clear) drawn scaled for every particle: cheap blur. */
    private val sprite: Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b ->
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(64f, 64f, 64f, intArrayOf(Color.WHITE, 0x88FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(64f, 64f, 64f, p)
    }

    var overPicture = false
        set(v) { field = v; invalidate() }

    var active = false
        set(v) {
            field = v
            if (v && !running && isAttachedToWindow) { running = true; last = 0L; Choreographer.getInstance().postFrameCallback(this) }
        }

    override fun doFrame(now: Long) {
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        val fresh = System.currentTimeMillis() - AudioLevel.updatedAt < 500
        val target = if (active && fresh) AudioLevel.level else 0f
        level += (target - level) * (if (target > level) 0.3f else 0.05f)
        val speed = 0.35f + level * 2.4f
        t += dt * (0.25f + level * 0.8f)
        ps.forEach { p ->
            p.x += p.vx * dt * speed; p.y += p.vy * dt * speed
            if (p.y < -0.05f) p.y = 1.05f
            if (p.x < -0.05f) p.x = 1.05f
            if (p.x > 1.05f) p.x = -0.05f
        }
        invalidate()
        if (isAttachedToWindow && (active || level > 0.01f)) Choreographer.getInstance().postFrameCallback(this)
        else running = false
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (!overPicture) {
            // Deep violet → warm saffron gradient that drifts slowly.
            val ax = w * (0.5f + 0.35f * cos(t * 0.6f)); val ay = h * (0.35f + 0.25f * sin(t * 0.45f))
            glow.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF1A1A1A.toInt(), 0xFF141414.toInt(), 0xFF101010.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, glow)
            val a = (18 + 40 * level).toInt().coerceIn(0, 255)
            glow.shader = RadialGradient(ax, ay, w * (0.45f + 0.15f * level), intArrayOf(Color.argb(a, 200, 200, 200), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, glow)
            val bx = w * (0.5f + 0.4f * sin(t * 0.37f + 2f)); val by = h * (0.7f + 0.2f * cos(t * 0.5f))
            glow.shader = RadialGradient(bx, by, w * 0.4f, intArrayOf(Color.argb((a * 0.6f).toInt(), 255, 179, 71), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, glow)
        }
        val tw = System.nanoTime() / 1e9f
        ps.forEach { p ->
            val twinkle = 0.75f + 0.25f * sin(tw * 1.3f + p.phase)
            val alpha = ((0.05f + 0.35f * (level * p.sens).coerceIn(0f, 1f)) * twinkle).coerceIn(0.03f, 0.4f)
            val rad = p.r * (1f + level * 0.7f) * density
            val cx = p.x * w; val cy = p.y * h
            dst.set((cx - rad).toInt(), (cy - rad).toInt(), (cx + rad).toInt(), (cy + rad).toInt())
            paint.alpha = (alpha * 255).toInt()
            canvas.drawBitmap(sprite, null, dst, paint)
        }
    }
}
