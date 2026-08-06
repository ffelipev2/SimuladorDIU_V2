package com.felipe.endoscopeviewer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class ForceGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(103, 111, 143)
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }
    private var displayedValue = 0f
    private var animator: ValueAnimator? = null

    fun setValue(grams: Float) {
        val target = grams.coerceAtLeast(0f)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayedValue, target).apply {
            duration = 220
            addUpdateListener {
                displayedValue = it.animatedValue as Float
                contentDescription = "Fuerza ${"%.2f".format(displayedValue)} gramos"
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Reserva espacio para que "0" y "120+" no queden cortados en los bordes.
        val left = paddingLeft + dp(8f)
        val right = width - paddingRight - dp(18f)
        val top = paddingTop + dp(13f)
        val barHeight = dp(18f)
        val bar = RectF(left, top, right, top + barHeight)
        val radius = barHeight / 2f

        val clipPath = Path().apply { addRoundRect(bar, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clipPath)
        drawSegment(canvas, bar, 0f, 40f, BLUE)
        drawSegment(canvas, bar, 40f, 80f, YELLOW)
        drawSegment(canvas, bar, 80f, 95f, GREEN)
        drawSegment(canvas, bar, 95f, MAX_GRAMS, RED)
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.argb(100, 110, 116, 139)
        canvas.drawRoundRect(bar, radius, radius, paint)

        val markerValue = min(MAX_GRAMS, max(0f, displayedValue))
        val markerX = left + (right - left) * (markerValue / MAX_GRAMS)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(markerX, top + barHeight / 2f, dp(7f), paint)
        paint.color = Color.rgb(70, 18, 177)
        canvas.drawCircle(markerX, top + barHeight / 2f, dp(3f), paint)

        val labelY = bar.bottom + dp(19f)
        drawLabel(canvas, "0", left, labelY)
        drawLabel(canvas, "40", xFor(40f, left, right), labelY)
        drawLabel(canvas, "80", xFor(80f, left, right), labelY)
        drawLabel(canvas, "95", xFor(95f, left, right), labelY)
        drawLabel(canvas, "120+", right, labelY)
    }

    private fun drawSegment(canvas: Canvas, bar: RectF, start: Float, end: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRect(
            xFor(start, bar.left, bar.right),
            bar.top,
            xFor(end, bar.left, bar.right),
            bar.bottom,
            paint
        )
    }

    private fun xFor(value: Float, left: Float, right: Float): Float =
        left + (right - left) * (value / MAX_GRAMS)

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        canvas.drawText(text, x, y, labelPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val MAX_GRAMS = 120f
        private val BLUE = Color.rgb(37, 99, 235)
        private val YELLOW = Color.rgb(250, 204, 21)
        private val GREEN = Color.rgb(34, 197, 94)
        private val RED = Color.rgb(239, 68, 68)
    }
}
