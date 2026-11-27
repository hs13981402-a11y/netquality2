package com.netquality.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max

data class ComparisonMetric(val label: String, val previous: Double?, val latest: Double?, val unit: String = "")

class TestComparisonGraphView(context: Context) : View(context) {
    var comparisonMetrics: List<ComparisonMetric> = emptyList()
        set(value) { field = value; invalidate() }
    private val d = resources.displayMetrics.density
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF263E56.toInt(); textSize = 11f * resources.displayMetrics.scaledDensity }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF52677C.toInt(); textSize = 9f * resources.displayMetrics.scaledDensity }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8EEF4.toInt(); strokeWidth = d }
    private val oldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4387D8.toInt() }
    private val newPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF11A69C.toInt() }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 12*d; val right = width - 12*d; val rowH = 62*d; val top = 48*d
        canvas.drawRoundRect(RectF(2*d,2*d,width-2*d,height-2*d),16*d,16*d,cardPaint)
        canvas.drawCircle(left+4*d,18*d,4*d,oldPaint)
        canvas.drawCircle(left+104*d,18*d,4*d,newPaint)
        valuePaint.textSize = 10*d
        canvas.drawText("تست قبلی",left+13*d,21*d,valuePaint)
        canvas.drawText("تست جدید",left+113*d,21*d,valuePaint)
        comparisonMetrics.forEachIndexed { index, metric ->
            val y = top + index*rowH
            if (y+rowH > height) return@forEachIndexed
            labelPaint.textSize = 10.5*d
            canvas.drawText(metric.label.take(26),left,y+10*d,labelPaint)
            val maxValue = max(1.0,max(metric.previous ?: 0.0,metric.latest ?: 0.0))
            val barWidth = max(1f,right-left)
            val oldY=y+18*d; val newY=y+34*d
            canvas.drawRoundRect(RectF(left,oldY,right,oldY+7*d),4*d,4*d,gridPaint)
            canvas.drawRoundRect(RectF(left,newY,right,newY+7*d),4*d,4*d,gridPaint)
            metric.previous?.let { canvas.drawRoundRect(RectF(left,oldY,left+barWidth*(it.coerceAtLeast(0.0)/maxValue).toFloat(),oldY+7*d),4*d,4*d,oldPaint) }
            metric.latest?.let { canvas.drawRoundRect(RectF(left,newY,left+barWidth*(it.coerceAtLeast(0.0)/maxValue).toFloat(),newY+7*d),4*d,4*d,newPaint) }
            valuePaint.textSize=8.5*d
            val suffix=if(metric.unit.isBlank()) "" else " ${metric.unit}"
            canvas.drawText("قبلی: "+(metric.previous?.let{String.format(java.util.Locale.US,"%.2f",it)} ?: "—")+suffix,left,y+54*d,valuePaint)
            canvas.drawText("جدید: "+(metric.latest?.let{String.format(java.util.Locale.US,"%.2f",it)} ?: "—")+suffix,left+barWidth*0.52f,y+54*d,valuePaint)
            canvas.drawLine(left,y+rowH-2*d,right,y+rowH-2*d,gridPaint)
        }
    }
}
