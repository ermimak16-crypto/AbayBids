package com.abaybids.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.abaybids.app.R

/**
 * Circular "donut" chart for the Tender Status Overview card — replaces the
 * old stacked horizontal bars with a ring split into one arc per status,
 * plus the total tender count in the center. Reads theme colors (track,
 * center text) at draw time so it follows Light/Dark automatically.
 */
class StatusDonutChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Slice(val value: Float, val color: Int)

    private var slices: List<Slice> = emptyList()
    private var centerValue: String = "0"
    private var centerLabel: String = ""

    fun setData(slices: List<Slice>, centerValue: String, centerLabel: String) {
        this.slices = slices
        this.centerValue = centerValue
        this.centerLabel = centerLabel
        invalidate()
    }

    private val trackPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = ContextCompat.getColor(context, R.color.divider)
    }
    private val arcPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val centerValuePaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val centerLabelPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Re-read theme colors every draw — this view survives theme
        // recreation like everything else, so no caching across onDraw.
        trackPaint.color = ContextCompat.getColor(context, R.color.divider)
        centerValuePaint.color = ContextCompat.getColor(context, R.color.text_primary)
        centerLabelPaint.color = ContextCompat.getColor(context, R.color.text_muted)

        val strokeW = w * 0.14f
        trackPaint.strokeWidth = strokeW
        arcPaint.strokeWidth = strokeW
        val pad = strokeW / 2f + 2f
        val rect = RectF(pad, pad, w - pad, h - pad)

        // Background track (full ring)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)

        val total = slices.sumOf { it.value.toDouble() }.toFloat()
        if (total > 0f) {
            var startAngle = -90f
            val gapDeg = 4f
            slices.forEach { s ->
                if (s.value <= 0f) return@forEach
                val sweep = (s.value / total) * 360f
                arcPaint.color = s.color
                val drawSweep = (sweep - gapDeg).coerceAtLeast(2f)
                canvas.drawArc(rect, startAngle + gapDeg / 2f, drawSweep, false, arcPaint)
                startAngle += sweep
            }
        }

        val cx = w / 2f
        val cy = h / 2f
        centerValuePaint.textSize = h * 0.26f
        centerLabelPaint.textSize = h * 0.11f

        val fm = centerValuePaint.fontMetrics
        val valueBaseline = cy - (fm.ascent + fm.descent) / 2f - h * 0.06f
        canvas.drawText(centerValue, cx, valueBaseline, centerValuePaint)
        canvas.drawText(centerLabel, cx, cy + h * 0.16f, centerLabelPaint)
    }
}
