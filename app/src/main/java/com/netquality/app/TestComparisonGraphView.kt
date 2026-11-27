package com.netquality.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/** Compact per-test bar chart. Each metric is normalized against the same cross-test scale. */
class TestComparisonGraphView(context: Context) : View(context) {
    var metricLabels: List<String> = emptyList()
        set(value) { field = value; invalidate() }
    var metricValues: List<Double> = emptyList()
        set(value) { field = value; invalidate() }
    var metricScales: List<Double> = emptyList()
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF34495E.toInt()
        textSize = 9f * resources.displayMetrics.scaledDensity
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E9F9A.toInt() }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5EBF2.toInt(); strokeWidth = density }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF17324D.toInt()
        textSize = 9f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 8f * density
        val right = width - 8f * density
        val top = 12f * density
        val rowHeight = 66f * density
        metricLabels.forEachIndexed { index, label ->
            val y = top + index * rowHeight
            canvas.drawText(label.take(15), left, y + 10f * density, text)
            val scale = max(1.0, metricScales.getOrElse(index) { 1.0 })
            val value = metricValues.getOrElse(index) { 0.0 }.coerceAtLeast(0.0)
            val barTop = y + 18f * density
            val barWidth = (right - left).coerceAtLeast(1f)
            canvas.drawRoundRect(RectF(left, barTop, right, barTop + 12f * density), 5f, 5f, grid)
            val ratio = (value / scale).toFloat().coerceIn(0f, 1f)
            bar.color = 0xFF0E9F9A.toInt()
            canvas.drawRoundRect(RectF(left, barTop, left + barWidth * ratio, barTop + 12f * density), 5f, 5f, bar)
            canvas.drawText(String.format(java.util.Locale.US, "%.1f", value), left, barTop + 27f * density, valuePaint)
            canvas.drawLine(left, y + 48f * density, right, y + 48f * density, grid)
        }
        if (metricLabels.isEmpty()) canvas.drawText("داده‌ای برای نمودار موجود نیست", left, 30f * density, text)
    }
}
