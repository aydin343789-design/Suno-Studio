package com.aydin.sunostudio

import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.exp
import kotlin.math.pow

/** 12-second intro: glowing logo + background sound, then the main app. */
class SplashActivity : ComponentActivity() {
    private val totalSeconds = 12f

    private lateinit var glow: GlowView
    private lateinit var logo: ImageView
    private lateinit var titleView: TextView
    private lateinit var tagView: TextView
    private lateinit var skip: TextView
    private lateinit var timeline: ThinProgress

    private var player: MediaPlayer? = null
    private var done = false
    private var running = false
    private var startedAt = 0L
    private var elapsedBeforePause = 0f

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || done) return
            val t = elapsedBeforePause + (SystemClock.elapsedRealtime() - startedAt) / 1000f
            render(t)
            if (t >= totalSeconds) goMain() else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#05030F"))

        val metrics = resources.displayMetrics
        val logoSize = dp(210)
        val logoTop = (metrics.heightPixels * 0.27f).toInt()

        glow = GlowView(this)
        glow.cyFrac = (logoTop + logoSize / 2f) / metrics.heightPixels
        root.addView(glow, FrameLayout.LayoutParams(MATCH, MATCH))

        logo = ImageView(this)
        logo.setImageResource(R.drawable.logo_splash)
        logo.scaleType = ImageView.ScaleType.CENTER_CROP
        logo.clipToOutline = true
        logo.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.22f)
            }
        }
        logo.alpha = 0f
        root.addView(logo, FrameLayout.LayoutParams(logoSize, logoSize, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = logoTop
        })

        titleView = TextView(this)
        titleView.text = "Suno Studio"
        titleView.textSize = 32f
        titleView.setTextColor(Color.WHITE)
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        titleView.letterSpacing = 0.06f
        titleView.setShadowLayer(dpf(22f), 0f, 0f, Pal.VIOLET)
        titleView.alpha = 0f
        root.addView(titleView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = logoTop + logoSize + dp(30)
        })

        tagView = TextView(this)
        tagView.text = "دانلودر و مبدل حرفه‌ای موسیقی و ویدیو"
        tagView.textSize = 14f
        tagView.setTextColor(Pal.MUTED)
        tagView.alpha = 0f
        root.addView(tagView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = logoTop + logoSize + dp(78)
        })

        timeline = ThinProgress(this)
        timeline.colors = intArrayOf(Pal.VIOLET, Pal.CYAN, Pal.GOLD)
        root.addView(timeline, FrameLayout.LayoutParams(dp(160), dp(3), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(70)
        })

        skip = TextView(this)
        skip.text = "رد کردن"
        skip.textSize = 13f
        skip.setTextColor(Pal.MUTED)
        skip.setPadding(dp(18), dp(9), dp(18), dp(9))
        skip.background = roundedBg(this, Color.parseColor("#1A1740"), 20f, Pal.STROKE, 1)
        skip.alpha = 0f
        skip.setOnClickListener { goMain() }
        root.addView(skip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(18)
        })

        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setContentView(root)

        val controller = WindowInsetsControllerCompat(window, root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        try {
            player = MediaPlayer.create(this, R.raw.intro_sound)
            player?.setVolume(0.92f, 0.92f)
        } catch (_: Throwable) {
            player = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (done) return
        try {
            player?.start()
        } catch (_: Throwable) {
        }
        startedAt = SystemClock.elapsedRealtime()
        running = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        if (running) {
            elapsedBeforePause += (SystemClock.elapsedRealtime() - startedAt) / 1000f
            running = false
            Choreographer.getInstance().removeFrameCallback(frame)
        }
        try {
            player?.pause()
        } catch (_: Throwable) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
    }

    private fun fade(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

    private fun render(t: Float) {
        glow.t = t
        glow.invalidate()

        // Logo: pop in, breathe with the beat, punch on the burst at 8 s
        val appear = fade(t, 0f, 1.3f)
        val back = 1f + 2.2f * (appear - 1f).pow(3) + 1.2f * (appear - 1f).pow(2) // easeOutBack-ish
        var scale = 0.7f + 0.3f * back
        val beat = (t * 96f / 60f) % 1f
        if (t > 1.3f) scale += 0.035f * (1f - beat).pow(3)
        if (t >= 8f) scale += 0.09f * exp(-(t - 8f) * 5f)
        logo.alpha = appear
        logo.scaleX = scale
        logo.scaleY = scale

        titleView.alpha = fade(t, 1.8f, 2.8f)
        titleView.translationY = (1f - fade(t, 1.8f, 2.8f)) * dpf(14f)
        tagView.alpha = fade(t, 3.0f, 4.0f)
        skip.alpha = 0.75f * fade(t, 1.0f, 1.6f)
        timeline.setProgress(t / totalSeconds)

        // soft final fade to black for a clean hand-over
        val out = fade(t, totalSeconds - 0.6f, totalSeconds)
        logo.alpha = appear * (1f - out)
        titleView.alpha *= (1f - out)
        tagView.alpha *= (1f - out)
    }

    private fun goMain() {
        if (done) return
        done = true
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        releasePlayer()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
