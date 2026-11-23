package com.aydin.sunostudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Animated glow behind the splash logo. Everything is a function of [t] (seconds), which is in sync
 * with the 12 s intro sound: slow build-up, beat pulses at 96 bpm, and a bright burst at t = 8 s.
 */
class GlowView(ctx: Context) : View(ctx) {
    var t = 0f
    var cyFrac = 0.4f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ray = Path()

    private class Star(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float, val color: Int)

    private val stars: List<Star>

    init {
        val rnd = java.util.Random(11)
        val cols = intArrayOf(Pal.GOLD, Pal.CYAN, Color.WHITE, Pal.MAGENTA, Pal.VIOLET)
        stars = List(70) {
            Star(
                rnd.nextFloat(), rnd.nextFloat(), 0.8f + rnd.nextFloat() * 2.2f,
                0.02f + rnd.nextFloat() * 0.05f, rnd.nextFloat() * 6.28f, cols[rnd.nextInt(cols.size)]
            )
        }
    }

    private fun ca(alpha: Float, rgb: Int): Int =
        (rgb and 0xFFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    private fun smooth(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    private fun bloom(c: Canvas, cx: Float, cy: Float, r: Float, a: Float, color: Int, mid: Int) {
        if (a <= 0.003f || r <= 1f) return
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(ca(a, color), ca(a * 0.4f, mid), 0),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, p)
        p.shader = null
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val d = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h * cyFrac
        val base = min(w, h) * 0.34f

        val intro = smooth(t / 1.6f)
        val rise = smooth((t - 1f) / 7f)
        val beatPhase = (t * 96f / 60f) % 1f
        val pulse = (1f - beatPhase).pow(3) * min(1f, t / 2f)
        val after = if (t >= 8f) t - 8f else -1f
        val flash = if (after >= 0f) exp(-after * 2.4f) else 0f

        // Deep violet bloom, cyan inner bloom, hot gold core
        bloom(c, cx, cy, base * (2.7f + 0.5f * rise + 0.3f * pulse), (0.5f + 0.3f * rise) * intro, Pal.VIOLET, Color.parseColor("#3B1D8F"))
        bloom(c, cx, cy, base * (1.6f + 0.35f * rise + 0.2f * pulse), (0.18f + 0.3f * rise) * intro, Pal.CYAN, Color.parseColor("#1B5E9A"))
        bloom(c, cx, cy, base * 1.2f, (0.22f * (0.6f + 0.4f * pulse) + 0.55f * flash) * intro, Pal.GOLD, Color.parseColor("#B8731F"))

        // Rotating light rays
        val rayAlpha = (0.08f + 0.2f * rise + 0.5f * flash) * intro
        if (rayAlpha > 0.01f) {
            val len = base * (2.0f + 1.6f * rise + 1.4f * flash)
            rayPaint.style = Paint.Style.FILL
            rayPaint.shader = LinearGradient(
                0f, 0f, len, 0f, ca(rayAlpha, Color.parseColor("#CDBBFF")), 0, Shader.TileMode.CLAMP
            )
            ray.reset()
            ray.moveTo(base * 0.45f, 0f)
            ray.lineTo(len, -len * 0.06f)
            ray.lineTo(len, len * 0.06f)
            ray.close()
            val count = 14
            for (i in 0 until count) {
                c.save()
                c.translate(cx, cy)
                c.rotate(i * (360f / count) + t * 9f)
                c.drawPath(ray, rayPaint)
                c.restore()
            }
            rayPaint.shader = null
        }

        // Beat rings and the big ring after the burst at 8 s
        p.style = Paint.Style.STROKE
        p.shader = null
        val beatAlpha = (1f - beatPhase).pow(2) * 0.28f * intro
        if (beatAlpha > 0.01f) {
            p.strokeWidth = 2f * d
            p.color = ca(beatAlpha, Pal.CYAN)
            c.drawCircle(cx, cy, base * (1.05f + 0.55f * beatPhase), p)
        }
        if (after >= 0f) {
            val a1 = exp(-after * 1.3f) * 0.85f
            p.strokeWidth = (4f * d) * (1f - min(1f, after / 3.5f)) + d
            p.color = ca(a1, Pal.GOLD)
            c.drawCircle(cx, cy, base * (1.1f + after * 1.9f), p)
            val a2 = exp(-max(0f, after - 0.25f) * 1.3f) * 0.7f
            if (after > 0.25f) {
                p.strokeWidth = 2.5f * d
                p.color = ca(a2, Pal.CYAN)
                c.drawCircle(cx, cy, base * (1.1f + (after - 0.25f) * 1.9f), p)
            }
        }

        // Sparkles
        p.style = Paint.Style.FILL
        val starFade = intro * (0.4f + 0.6f * rise)
        for (s in stars) {
            val y = (((s.y - t * s.speed) % 1f) + 1f) % 1f * h
            val x = s.x * w + sin(t * 0.6f + s.phase) * 10f * d
            val tw = 0.25f + 0.75f * abs(sin(t * 1.7f + s.phase))
            val a = tw * starFade
            p.color = ca(a, s.color)
            c.drawCircle(x, y, s.r * d, p)
            if (s.r > 2.2f) {
                p.color = ca(a * 0.22f, s.color)
                c.drawCircle(x, y, s.r * d * 3.2f, p)
            }
        }

        if (flash > 0.01f) c.drawColor(ca(0.2f * flash, Color.WHITE))
    }
}
