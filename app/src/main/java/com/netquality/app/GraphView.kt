package com.netquality.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Lightweight live line chart used for Ping, Jitter and Packet Loss. */
class GraphView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var data: List<Double> = emptyList()
        set(value) { field = value; invalidate() }

    var chartTitle: String = "نمودار"
        set(value) { field = value; invalidate() }
    var chartUnit: String = "ms"
        set(value) { field = value; invalidate() }
    var summaryText: String = "در انتظار داده"
        set(value) { field = value; invalidate() }

    private val maxPoints = 150
    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF087E8B.toInt(); strokeWidth = 3f * density
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val lossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD64545.toInt(); strokeWidth = 2f * density; style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33087E8B; style = Paint.Style.FILL }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF087E8B.toInt(); style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE4EAF0.toInt(); strokeWidth = 1f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF607080.toInt(); textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF17324D.toInt(); textSize = 13f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = data.takeLast(maxPoints)
        val w = width.toFloat(); val h = height.toFloat()
        val left = 42f * density; val right = 12f * density
        val top = 58f * density; val bottom = 28f * density
        val plotW = (w - left - right).coerceAtLeast(1f)
        val plotH = (h - top - bottom).coerceAtLeast(1f)
        canvas.drawText("$chartTitle ($chartUnit)", left, 19f * density, titlePaint)
        val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF087E8B.toInt()
            textSize = 11f * resources.displayMetrics.scaledDensity
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        canvas.drawText(summaryText, left, 40f * density, summaryPaint)

        val valid = d.filter { it >= 0.0 }
        val maxValue = max(1.0, (valid.maxOrNull() ?: 1.0) * 1.15)
        for (i in 0..3) {
            val y = top + plotH * i / 3f
            canvas.drawLine(left, y, w - right, y, gridPaint)
            val value = maxValue * (3 - i) / 3.0
            canvas.drawText(String.format(java.util.Locale.US, "%.0f", value), 2f * density, y + 4f * density, textPaint)
        }
        if (d.isEmpty()) {
            canvas.drawText("پس از شروع تست، نمودار نمایش داده می‌شود", left, top + plotH / 2f, textPaint)
            return
        }
        val step = plotW / (maxPoints - 1).toFloat()
        val offset = maxPoints - d.size
        val path = Path()
        val area = Path()
        var previousX = -1f; var previousY = -1f
        var areaStarted = false
        d.forEachIndexed { index, value ->
            val x = left + step * (index + offset)
            if (value < 0.0) {
                canvas.drawLine(x, top, x, top + plotH, lossPaint)
                previousX = -1f
                areaStarted = false
            } else {
                val y = top + plotH - (value / maxValue).toFloat() * plotH
                if (previousX < 0f) {
                    path.moveTo(x, y)
                    area.moveTo(x, top + plotH)
                    area.lineTo(x, y)
                    areaStarted = true
                } else {
                    path.lineTo(x, y)
                    if (areaStarted) area.lineTo(x, y)
                }
                previousX = x; previousY = y
                if (index == d.lastIndex && areaStarted) {
                    area.lineTo(x, top + plotH)
                    area.close()
                }
            }
        }
        canvas.drawPath(area, fillPaint)
        canvas.drawPath(path, linePaint)
        if (previousX >= 0f) canvas.drawCircle(previousX, previousY, 3.5f * density, pointPaint)
    }
}
