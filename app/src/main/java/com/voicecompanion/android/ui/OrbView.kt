package com.voicecompanion.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** The voice panel's animated orb, driven by mic level and voice state. */
class OrbView(ctx: Context) : View(ctx) {
    var color: Int = Color.parseColor("#c96442")
        set(v) {
            field = v
            shader = null
            invalidate()
        }

    /** idle | listening | user_speaking | transcribing | speaking */
    var state: String = "idle"

    private var target = 0f
    private var level = 0f
    private var shader: RadialGradient? = null
    private var shaderSize = 0
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setLevel(rms: Float) {
        target = rms.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000.0
        val drive = when (state) {
            "listening", "user_speaking" -> target
            "speaking" -> (0.35 + 0.25 * sin(t * 2 * PI * 1.6) + 0.15 * sin(t * 2 * PI * 3.7)).toFloat()
            "transcribing" -> (0.15 + 0.1 * sin(t * 2 * PI * 0.8)).toFloat()
            else -> 0.05f
        }
        level += (drive - level) * 0.25f

        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) * 0.30f
        val size = min(width, height)
        if (shader == null || shaderSize != size) {
            shaderSize = size
            shader = RadialGradient(cx - base * 0.3f, cy - base * 0.35f, base * 1.6f,
                intArrayOf(lighten(color, 0.45f), color, darken(color, 0.35f)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        val breathe = if (state == "idle") 0f else (0.02 * sin(t * 2 * PI * 0.5)).toFloat()
        val r = base * (1f + level * 0.28f + breathe)
        haloPaint.color = alpha(color, 0.10f + level * 0.18f)
        canvas.drawCircle(cx, cy, r * (1.25f + level * 0.35f), haloPaint)
        haloPaint.color = alpha(color, 0.08f)
        canvas.drawCircle(cx, cy, r * (1.55f + level * 0.5f), haloPaint)
        orbPaint.shader = shader
        canvas.drawCircle(cx, cy, r, orbPaint)
        if (isShown) postInvalidateOnAnimation()
    }

    private fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt())

    private fun darken(c: Int, f: Float) = Color.rgb(
        (Color.red(c) * (1 - f)).toInt(), (Color.green(c) * (1 - f)).toInt(), (Color.blue(c) * (1 - f)).toInt())
}
