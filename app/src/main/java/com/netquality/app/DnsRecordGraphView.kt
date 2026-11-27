package com.netquality.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

class DnsRecordGraphView(context: Context) : View(context) {
    var recordName: String = ""
        set(value) { field = value; invalidate() }
    var latencySamples: List<Double> = emptyList()
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun color(c: Long) = c.toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val card = RectF(2*d, 2*d, w-2*d, h-2*d)
        p.color = color(0xFFFFFFFF)
        p.setShadowLayer(7*d, 0f, 3*d, color(0x180D2942))
        canvas.drawRoundRect(card, 18*d, 18*d, p)
        p.clearShadowLayer()

        p.color = color(0xFF183653)
        p.textSize = 13*d
        p.typeface = android.graphics.Typeface.create("sans-serif-medium", 1)
        canvas.drawText(recordName, 16*d, 24*d, p)

        val valid = latencySamples.filter { it >= 0.0 }
        val latest = latencySamples.lastOrNull()
        val avg = if (valid.isEmpty()) null else valid.average()
        val maxV = max(10.0, (valid.maxOrNull() ?: 10.0) * 1.18).toFloat()
        val stat = when {
            latest == null -> "در انتظار اولین نمونه..."
            latest < 0 -> "آخرین پاسخ: Timeout"
            else -> "آخرین: ${latest.roundToInt()} ms   •   میانگین: ${avg?.roundToInt() ?: 0} ms"
        }
        p.textSize = 10*d
        p.typeface = android.graphics.Typeface.create("sans-serif", 0)
        p.color = if (latest != null && latest < 0) color(0xFFE05A67) else color(0xFF64788D)
        canvas.drawText(stat, 16*d, 43*d, p)

        val left = 36*d
        val right = w - 14*d
        val top = 57*d
        val bottom = h - 25*d
        if (right <= left || bottom <= top) return

        p.strokeWidth = 1*d
        p.textSize = 9*d
        p.color = color(0xFF91A2B4)
        for (i in 0..3) {
            val y = top + (bottom-top)*i/3f
            p.color = color(0xFFE8EEF4)
            canvas.drawLine(left, y, right, y, p)
            p.color = color(0xFF8293A5)
            val tick = (maxV * (3-i) / 3.0).roundToInt().toString()
            canvas.drawText(tick, 5*d, y+3*d, p)
        }

        if (latencySamples.isEmpty()) {
            p.color = color(0xFF8494A6); p.textSize = 11*d
            canvas.drawText("در حال جمع‌آوری داده...", left, (top+bottom)/2, p)
            return
        }
        val points = latencySamples.size
        val line = Path()
        val fill = Path()
        var started = false
        latencySamples.forEachIndexed { i, value ->
            val x = if (points <= 1) right else left + (right-left)*i/(points-1)
            if (value < 0) {
                started = false
                p.color = color(0xFFE45B68)
                canvas.drawCircle(x, bottom-2*d, 3*d, p)
            } else {
                val y = bottom - (value.toFloat()/maxV)*(bottom-top)
                if (!started) {
                    line.moveTo(x,y); fill.moveTo(x,bottom); fill.lineTo(x,y); started = true
                } else { line.lineTo(x,y); fill.lineTo(x,y) }
            }
        }
        if (started) {
            fill.lineTo(right,bottom); fill.close()
            p.shader = LinearGradient(0f, top, 0f, bottom, color(0x5530B7B0), color(0x0830B7B0), Shader.TileMode.CLAMP)
            canvas.drawPath(fill,p); p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.6*d
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            p.shader = LinearGradient(left,top,right,bottom,color(0xFF13A89E),color(0xFF3979D5),Shader.TileMode.CLAMP)
            canvas.drawPath(line,p)
            p.shader = null; p.style = Paint.Style.FILL
            val last = latencySamples.lastOrNull()?.takeIf { it >= 0 }
            if (last != null) {
                val x = if (points <= 1) right else right
                val y = bottom - (last.toFloat()/maxV)*(bottom-top)
                p.color = color(0xFF13A89E); canvas.drawCircle(x,y,5*d,p)
                p.color = color(0xFFFFFFFF); canvas.drawCircle(x,y,2*d,p)
            }
        }
        p.color = color(0xFF8293A5); p.textSize = 8.5*d
        canvas.drawText("۰", left, h-7*d, p)
        canvas.drawText("نمونه‌های اخیر  •  ms", right-100*d, h-7*d, p)
    }
}
