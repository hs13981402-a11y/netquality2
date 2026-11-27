package com.netquality.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max

class DnsRecordGraphView(context: Context) : View(context) {
    var recordName: String = ""
    var latencySamples: List<Double> = emptyList()
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w=width.toFloat(); val h=height.toFloat()
        paint.color=0xFFFFFFFF.toInt(); canvas.drawRoundRect(0f,0f,w,h,18f,18f,paint)
        paint.color=0xFF203653.toInt(); paint.textSize=14f*resources.displayMetrics.scaledDensity; paint.isFakeBoldText=true
        canvas.drawText(recordName,18f,26f,paint)
        val left=20f; val right=w-18f; val top=42f; val bottom=h-25f
        paint.color=0xFFE6EDF5.toInt(); paint.strokeWidth=1f
        for(i in 0..3){ val y=top+(bottom-top)*i/3f; canvas.drawLine(left,y,right,y,paint) }
        val valid=latencySamples.filter{it>=0}
        val maxV=max(10.0, valid.maxOrNull() ?: 10.0).toFloat()
        val path=Path(); var started=false
        latencySamples.forEachIndexed { i,v ->
            val x=left+(right-left)*i/max(1,latencySamples.size-1)
            if(v<0){ started=false; paint.color=0xFFE45B66.toInt(); canvas.drawCircle(x,bottom-2f,3f,paint) }
            else { val y=bottom-(v.toFloat()/maxV)*(bottom-top); if(!started){path.moveTo(x,y);started=true}else path.lineTo(x,y) }
        }
        paint.color=0xFF087E8B.toInt(); paint.style=Paint.Style.STROKE; paint.strokeWidth=3f; canvas.drawPath(path,paint); paint.style=Paint.Style.FILL
        paint.color=0xFF65758B.toInt(); paint.textSize=10f*resources.displayMetrics.scaledDensity
        canvas.drawText("زمان پاسخ (ms)  •  نقاط قرمز: بدون پاسخ",left,bottom+17f,paint)
    }
}
