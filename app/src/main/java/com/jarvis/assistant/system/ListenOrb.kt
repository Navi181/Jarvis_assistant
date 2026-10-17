package com.jarvis.assistant.system

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.jarvis.assistant.core.Assistant
import com.jarvis.assistant.core.Phase
import com.jarvis.assistant.core.Prefs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small orange orb that floats at the top of the screen while Jarvis is listening and you are in another app
 * (after you say "Jarvis"), so you know when to speak. Needs "Display over other apps".
 */
object ListenOrb {
    @Volatile
    var foreground = false

    private var view: OrbView? = null

    fun update(ctx: Context, phase: Phase) {
        val want = Prefs.listenOrb && !foreground && phase == Phase.LISTENING && canShow(ctx)
        if (want) show(ctx) else hide()
    }

    private fun canShow(ctx: Context) = try {
        Settings.canDrawOverlays(ctx)
    } catch (e: Exception) {
        false
    }

    private fun show(ctx: Context) {
        if (view != null) return
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val size = (ctx.resources.displayMetrics.density * 96).toInt()
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (ctx.resources.displayMetrics.density * 56).toInt()
            val v = OrbView(ctx)
            wm.addView(v, lp)
            v.start()
            view = v
        } catch (e: Exception) {
            view = null
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try {
            v.stop()
            (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }

    private class OrbView(ctx: Context) : View(ctx) {
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val body = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var t = 0f
        private var anim: ValueAnimator? = null
        private var lvl = 0f

        fun start() {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2400
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stop() {
            anim?.cancel()
            anim = null
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val max = width / 2f
            lvl += (Assistant.level.value - lvl) * 0.25f
            val pulse = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() * 2f)
            val r = max * (0.38f + 0.05f * pulse + 0.14f * lvl)
            val tau = 2f * PI.toFloat()

            glow.shader = RadialGradient(cx, cy, max, intArrayOf(0x88FF9F1C.toInt(), 0x22FF7A00, 0x00000000), floatArrayOf(0.2f, 0.65f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, max, glow)

            body.shader = RadialGradient(
                cx - r * 0.3f, cy - r * 0.3f, r * 1.4f,
                intArrayOf(0xFFFFF3D6.toInt(), 0xFFFFA52E.toInt(), 0xFFB34700.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r, body)

            ring.color = 0xCCFFB347.toInt()
            ring.strokeWidth = max * 0.045f
            val rr = max * 0.74f
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(rect, t * 360f * 2f, 70f, false, ring)
            c.drawArc(rect, t * 360f * 2f + 180f, 70f, false, ring)
            ring.color = 0x55FFB347
            ring.strokeWidth = max * 0.02f
            val r2 = max * 0.9f
            rect.set(cx - r2, cy - r2, cx + r2, cy + r2)
            c.drawArc(rect, -t * 360f * 3f, 120f, false, ring)

            dot.color = 0xFFFFFFFF.toInt()
            for (k in 0 until 3) {
                val a = t * tau * (k + 1) + k * 2.1f
                val d = max * 0.82f
                c.drawCircle(cx + cos(a) * d, cy + sin(a) * d, max * 0.03f, dot)
            }
        }
    }
}
