package com.netquality.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Compact chart showing DNS answer counts for each queried record type. */
class DnsGraphView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var values: Map<String, Int> = emptyMap()
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF334155.toInt()
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF102A43.toInt()
        textSize = 14f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E9AA7.toInt() }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE7EDF3.toInt(); strokeWidth = d
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val names = listOf("A", "AAAA", "MX", "NS", "TXT", "CNAME", "SOA", "SRV", "CAA")
        val left = 34f * d
        val top = 42f * d
        val bottom = height - 30f * d
        val chartHeight = (bottom - top).coerceAtLeast(1f)
        canvas.drawText("تعداد پاسخ‌ها به تفکیک نوع رکورد", 14f * d, 22f * d, title)
        val maxCount = max(1, values.values.maxOrNull() ?: 0)
        for (i in 0..3) {
            val y = top + chartHeight * i / 3f
            canvas.drawLine(left, y, width - 8f * d, y, grid)
        }
        val slot = (width - left - 8f * d) / names.size
        names.forEachIndexed { i, name ->
            val count = values[name] ?: 0
            val bh = chartHeight * count / maxCount.toFloat()
            val x = left + i * slot + slot * .18f
            val w = slot * .64f
            if (count > 0) canvas.drawRoundRect(RectF(x, bottom - bh, x + w, bottom), 5f * d, 5f * d, bar)
            canvas.drawText(count.toString(), x + w / 2f - text.measureText(count.toString()) / 2f,
                (bottom - bh - 5f * d).coerceAtLeast(top + 10f * d), text)
            canvas.drawText(name, x + w / 2f - text.measureText(name) / 2f, height - 8f * d, text)
        }
    }
}
