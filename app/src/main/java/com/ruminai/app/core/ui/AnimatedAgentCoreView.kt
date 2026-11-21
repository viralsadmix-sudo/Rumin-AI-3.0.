package com.ruminai.app.core.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/** Lightweight animated 3D-style Rumin core; drawn locally, no remote image or data. */
class AnimatedAgentCoreView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 9000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.31f
        val angle = Math.toRadians(phase.toDouble())

        paint.shader = RadialGradient(cx, cy, radius * 1.9f,
            intArrayOf(0x663B82F6, 0x33240046, 0x00101830),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * 1.9f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.5f
        paint.color = 0xAAFF2BD6.toInt()
        canvas.save()
        canvas.rotate(phase, cx, cy)
        canvas.drawOval(cx - radius * 1.55f, cy - radius * 0.52f,
            cx + radius * 1.55f, cy + radius * 0.52f, paint)
        canvas.restore()

        paint.color = 0xAA3B82F6.toInt()
        canvas.save()
        canvas.rotate(-phase * 0.72f, cx, cy)
        canvas.drawOval(cx - radius * 1.32f, cy - radius * 0.72f,
            cx + radius * 1.32f, cy + radius * 0.72f, paint)
        canvas.restore()

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx - radius * 0.25f, cy - radius * 0.32f,
            radius * 1.45f,
            intArrayOf(0xFFFFB7F1.toInt(), 0xFFFF2BD6.toInt(),
                0xFF6D28D9.toInt(), 0xFF101A45.toInt()),
            floatArrayOf(0f, 0.34f, 0.72f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius * (0.82f + 0.035f * sin(angle).toFloat()), paint)
        paint.shader = null

        paint.color = 0x99FFFFFF.toInt()
        val dotAngle = angle * 1.7
        canvas.drawCircle(cx + cos(dotAngle).toFloat() * radius * 1.55f,
            cy + sin(dotAngle).toFloat() * radius * 0.62f, 7f, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
