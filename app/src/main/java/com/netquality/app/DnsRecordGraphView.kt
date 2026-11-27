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
    private fun c(v: Long) = v.toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w=width.toFloat(); val h=height.toFloat()
        if(w<=0f||h<=0f)return
        p.color=c(0xFFFFFFFF); p.setShadowLayer(7*d,0f,3*d,c(0x180D2942))
        canvas.drawRoundRect(RectF(2*d,2*d,w-2*d,h-2*d),18*d,18*d,p); p.clearShadowLayer()
        p.color=c(0xFF183653); p.textSize=13*d
        p.typeface=android.graphics.Typeface.create("sans-serif-medium",1)
        canvas.drawText(recordName,16*d,24*d,p)
        val valid=latencySamples.filter{it>=0.0}
        val latest=latencySamples.lastOrNull()
        val avg=if(valid.isEmpty())null else valid.average()
        val maxV=max(10.0,(valid.maxOrNull()?:10.0)*1.18).toFloat()
        p.textSize=10*d; p.typeface=android.graphics.Typeface.create("sans-serif",0)
        p.color=if(latest!=null&&latest<0)c(0xFFE05A67) else c(0xFF64788D)
        val stat=when{latest==null->"در انتظار اولین نمونه...";latest<0->"آخرین پاسخ: Timeout";else->"آخرین: ${latest.roundToInt()} ms   •   میانگین: ${avg?.roundToInt()?:0} ms"}
        canvas.drawText(stat,16*d,43*d,p)
        val left=36*d; val right=w-14*d; val top=57*d; val bottom=h-25*d
        if(right<=left||bottom<=top)return
        p.strokeWidth=1*d; p.textSize=9*d
        for(i in 0..3){
            val y=top+(bottom-top)*i/3f
            p.color=c(0xFFE8EEF4); canvas.drawLine(left,y,right,y,p)
            p.color=c(0xFF8293A5); canvas.drawText((maxV*(3-i)/3.0).roundToInt().toString(),5*d,y+3*d,p)
        }
        if(latencySamples.isEmpty()){
            p.color=c(0xFF8494A6);p.textSize=11*d;canvas.drawText("در حال جمع‌آوری داده...",left,(top+bottom)/2,p);return
        }
        val line=Path();val fill=Path();var started=false
        latencySamples.forEachIndexed{i,v->
            val x=if(latencySamples.size<=1)right else left+(right-left)*i/(latencySamples.size-1)
            if(v<0){started=false;p.color=c(0xFFE45B68);canvas.drawCircle(x,bottom-2*d,3*d,p)}
            else{
                val y=bottom-(v.toFloat()/maxV)*(bottom-top)
                if(!started){line.moveTo(x,y);fill.moveTo(x,bottom);fill.lineTo(x,y);started=true}
                else{line.lineTo(x,y);fill.lineTo(x,y)}
            }
        }
        if(started){
            fill.lineTo(right,bottom);fill.close()
            p.shader=LinearGradient(0f,top,0f,bottom,c(0x5530B7B0),c(0x0830B7B0),Shader.TileMode.CLAMP)
            canvas.drawPath(fill,p);p.shader=null
            p.style=Paint.Style.STROKE;p.strokeWidth=2.6*d;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
            p.shader=LinearGradient(left,top,right,bottom,c(0xFF13A89E),c(0xFF3979D5),Shader.TileMode.CLAMP)
            canvas.drawPath(line,p);p.shader=null;p.style=Paint.Style.FILL
            latencySamples.lastOrNull()?.takeIf{it>=0}?.let{
                val y=bottom-(it.toFloat()/maxV)*(bottom-top)
                p.color=c(0xFF13A89E);canvas.drawCircle(right,y,5*d,p)
                p.color=c(0xFFFFFFFF);canvas.drawCircle(right,y,2*d,p)
            }
        }
        p.color=c(0xFF8293A5);p.textSize=8.5*d
        canvas.drawText("نمونه‌های اخیر  •  ms",right-100*d,h-7*d,p)
    }
}
