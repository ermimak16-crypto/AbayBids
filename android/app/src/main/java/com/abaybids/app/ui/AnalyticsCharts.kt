package com.abaybids.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.abaybids.app.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Three small custom Canvas-drawn charts used by AnalyticsActivity.
 *
 * All three read from a `setData(...)` call and draw on a plain View — no
 * third-party chart library, just `android.graphics.Canvas`. They honor the
 * active Light/Dark theme by reading the `bg`, `text_primary`, `text_muted`,
 * `divider`, and `brand_primary` color resources at draw time.
 */

// ─── 1. Win Rate Trend (line chart) ──────────────────────────────────────────

class WinRateChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Each Pair is (label, valuePercent) — value is 0..100. */
    private var points: List<Pair<String, Float>> = emptyList()

    fun setData(values: List<Pair<String, Float>>) {
        points = values
        invalidate()
    }

    private val linePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = ContextCompat.getColor(context, R.color.brand_primary)
    }
    private val dotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.brand_primary)
    }
    private val gridPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.divider)
    }
    private val labelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 24f
        color = ContextCompat.getColor(context, R.color.text_muted)
    }
    private val valPaint = Paint().apply {
        isAntiAlias = true
        textSize = 22f
        color = ContextCompat.getColor(context, R.color.text_primary)
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 32f; val padR = 24f; val padT = 16f; val padB = 36f
        val chartW = w - padL - padR
        val chartH = h - padT - padB

        // Three horizontal grid lines (0 / 50 / 100)
        for (pct in listOf(0f, 50f, 100f)) {
            val y = padT + chartH * (1 - pct / 100f)
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
        }

        if (points.isEmpty()) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("No data", w / 2, h / 2, labelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            return
        }

        // Compute x position for each point
        val xs = if (points.size == 1) listOf(w / 2)
                 else points.indices.map { i -> padL + chartW * i / (points.size - 1) }

        fun yFor(v: Float) = padT + chartH * (1 - v.coerceIn(0f, 100f) / 100f)

        // Polyline connecting points
        if (points.size > 1) {
            val path = android.graphics.Path()
            path.moveTo(xs[0], yFor(points[0].second))
            for (i in 1 until points.size) {
                path.lineTo(xs[i], yFor(points[i].second))
            }
            canvas.drawPath(path, linePaint)
        }

        // Points + labels
        for (i in points.indices) {
            val x = xs[i]; val y = yFor(points[i].second)
            canvas.drawCircle(x, y, 7f, dotPaint)
            // Value label above the point
            canvas.drawText("${points[i].second.toInt()}%", x, y - 14f, valPaint)
            // Month label below
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(points[i].first, x, h - 10f, labelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
        }
    }
}

// ─── 2. Tender Value Trend (vertical bar chart) ────────────────────────────────

class ValueTrendChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Each Pair is (label, valueETB). */
    private var points: List<Pair<String, Double>> = emptyList()

    fun setData(values: List<Pair<String, Double>>) {
        points = values
        invalidate()
    }

    private val barPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.brand_primary)
    }
    private val gridPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.divider)
    }
    private val labelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 22f
        color = ContextCompat.getColor(context, R.color.text_muted)
    }
    private val valPaint = Paint().apply {
        isAntiAlias = true
        textSize = 20f
        color = ContextCompat.getColor(context, R.color.text_primary)
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 48f; val padR = 16f; val padT = 16f; val padB = 36f
        val chartW = w - padL - padR
        val chartH = h - padT - padB

        // Baseline
        canvas.drawLine(padL, padT + chartH, w - padR, padT + chartH, gridPaint)

        if (points.isEmpty()) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("No data", w / 2, h / 2, labelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            return
        }

        val max = (points.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(1.0)
        val barWidth = if (points.size > 0) chartW / points.size * 0.6f else 0f
        val slotWidth = if (points.size > 0) chartW / points.size else 0f

        for (i in points.indices) {
            val cx = padL + slotWidth * (i + 0.5f)
            val barH = (chartH * (points[i].second / max)).toFloat()
            val left = cx - barWidth / 2
            val top = padT + chartH - barH
            val right = cx + barWidth / 2
            val bottom = padT + chartH
            // Rounded rect bar
            val rect = RectF(left, top, right, bottom)
            canvas.drawRoundRect(rect, 6f, 6f, barPaint)
            // Value label
            val label = humanReadable(points[i].second)
            canvas.drawText(label, cx, top - 8f, valPaint)
            // Month label below
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(points[i].first, cx, h - 10f, labelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun humanReadable(v: Double): String = when {
        v >= 1_000_000 -> "%.1fM".format(v / 1_000_000)
        v >= 1_000 -> "%.0fK".format(v / 1_000)
        else -> v.toInt().toString()
    }
}

// ─── 3. Category Split (horizontal bar chart) ─────────────────────────────────

class CategoryBarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Each Pair is (category, count). */
    private var points: List<Pair<String, Int>> = emptyList()

    fun setData(values: List<Pair<String, Int>>) {
        points = values.sortedByDescending { it.second }
        // Re-layout (since the view's height depends on the number of bars).
        requestLayout()
        invalidate()
    }

    private val barPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.brand_primary)
    }
    private val trackPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.divider)
    }
    private val labelPaint = Paint().apply {
        isAntiAlias = true
        textSize = 24f
        color = ContextCompat.getColor(context, R.color.text_primary)
    }
    private val valPaint = Paint().apply {
        isAntiAlias = true
        textSize = 22f
        color = ContextCompat.getColor(context, R.color.text_muted)
        textAlign = Paint.Align.RIGHT
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val minH = (48 + points.size * 56).coerceAtLeast(160)
        // Honor a minHeight set in XML (160dp) but grow to fit all bars.
        val width = MeasureSpec.getSize(widthSpec)
        val height = resolveSize(minH, heightSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (points.isEmpty()) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("No data", w / 2, h / 2, labelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            return
        }

        val max = (points.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
        val labelWidth = w * 0.34f
        val barAreaLeft = labelWidth + 12f
        val barAreaRight = w - 56f
        val barAreaW = (barAreaRight - barAreaLeft).coerceAtLeast(20f)
        val rowH = 48f
        val barH = 22f

        for (i in points.indices) {
            val yCenter = 28f + i * rowH
            val (cat, count) = points[i]

            // Label (truncate if too long)
            val displayCat = if (cat.length > 22) cat.substring(0, 21) + "…" else cat
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(displayCat, 0f, yCenter + 8f, labelPaint)

            // Track background
            canvas.drawRoundRect(
                RectF(barAreaLeft, yCenter - barH / 2, barAreaRight, yCenter + barH / 2),
                6f, 6f, trackPaint
            )
            // Bar fill
            val fillRight = barAreaLeft + barAreaW * count / max.toFloat()
            canvas.drawRoundRect(
                RectF(barAreaLeft, yCenter - barH / 2, fillRight, yCenter + barH / 2),
                6f, 6f, barPaint
            )
            // Count value
            canvas.drawText(count.toString(), w - 8f, yCenter + 8f, valPaint)
        }
    }
}

/**
 * Helper: returns the last [n] month labels (short form) and the
 * corresponding (year, monthIndex) tuples, oldest first.
 *
 * Example for n=6 on 2024-08-22:
 *   [("Mar", 2024, 2), ("Apr", 2024, 3), ..., ("Aug", 2024, 7)]
 */
internal fun lastNMonths(n: Int): List<Triple<String, Int, Int>> {
    val fmt = SimpleDateFormat("MMM", Locale.US)
    val cal = Calendar.getInstance()
    // Start n-1 months before now so the current month is the last entry.
    cal.add(Calendar.MONTH, -(n - 1))
    val out = ArrayList<Triple<String, Int, Int>>(n)
    repeat(n) {
        val label = fmt.format(cal.time)
        out += Triple(label, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
        cal.add(Calendar.MONTH, 1)
    }
    return out
}
