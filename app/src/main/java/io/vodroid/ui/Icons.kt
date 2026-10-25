package io.vodroid.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View

enum class Symbol {
    STATUS, DIAL, MESSAGE, SETTINGS, SIM, WIFI, SHIELD, PHONE, PLUS, SEND, BACK,
    CHEVRON, CLOSE, BACKSPACE, POWER, CHECK, ALERT, CLOCK, LOG, COPY, SHARE,
    MORE, REFRESH, DOWNLOAD, USB, CHIP, ROOT, EDIT, TRASH, INFO, MUTE, SPEAKER,
    SEARCH, ARROW_UP, ARROW_DOWN, LOCK, EXTERNAL, HEART, PERSON
}

/** A single, consistent 24-unit stroke icon family; no font glyph or emoji dependency. */
class Glyph(val symbol: Symbol, var color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = color
        fun line(vararg points: Float) {
            val path = Path(); path.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1])
            canvas.drawPath(path, paint)
        }
        fun circle(x: Float, y: Float, r: Float) = canvas.drawCircle(x, y, r, paint)
        fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float = 2f) = canvas.drawRoundRect(l,t,r,b,radius,radius,paint)
        fun path(build: Path.() -> Unit) = canvas.drawPath(Path().apply(build),paint)
        when (symbol) {
            Symbol.STATUS -> { rect(3f,3f,10f,10f);rect(14f,3f,21f,10f);rect(3f,14f,10f,21f);rect(14f,14f,21f,21f) }
            Symbol.DIAL -> for (y in listOf(5f,12f,19f)) for (x in listOf(5f,12f,19f)) circle(x,y,1.2f)
            Symbol.MESSAGE -> path { moveTo(5f,3.5f);lineTo(19f,3.5f);quadTo(21f,3.5f,21f,5.5f);lineTo(21f,15.5f);quadTo(21f,17.5f,19f,17.5f);lineTo(9f,17.5f);lineTo(4f,21f);lineTo(4f,17.2f);quadTo(3f,16.5f,3f,15f);lineTo(3f,5.5f);quadTo(3f,3.5f,5f,3.5f) }.also { line(7f,8f,17f,8f);line(7f,12f,14f,12f) }
            Symbol.SETTINGS -> { path { for (i in 0..23) { val a=(i*15-90)*Math.PI/180;val r=if(i%3==0||i%3==1)10f else 8f;val x=12+r*kotlin.math.cos(a).toFloat();val y=12+r*kotlin.math.sin(a).toFloat();if(i==0)moveTo(x,y) else lineTo(x,y) };close() };circle(12f,12f,3.4f) }
            Symbol.SIM -> { path{moveTo(7f,2.5f);lineTo(15f,2.5f);lineTo(20f,7.5f);lineTo(20f,19f);quadTo(20f,21f,18f,21f);lineTo(6f,21f);quadTo(4f,21f,4f,19f);lineTo(4f,5f);quadTo(4f,2.5f,7f,2.5f);close()};rect(7.5f,10f,16.5f,17.5f,1f);line(12f,10f,12f,17.5f);line(7.5f,14f,16.5f,14f) }
            Symbol.WIFI -> { path{moveTo(2f,8.5f);cubicTo(7.5f,3.2f,16.5f,3.2f,22f,8.5f)};path{moveTo(5.5f,12f);cubicTo(9f,8.7f,15f,8.7f,18.5f,12f)};path{moveTo(9f,15.5f);quadTo(12f,12.8f,15f,15.5f)};circle(12f,19f,.6f) }
            Symbol.SHIELD -> { path {moveTo(12f,2.5f);lineTo(20f,6f);lineTo(20f,11f);quadTo(20f,17.5f,12f,21.5f);quadTo(4f,17.5f,4f,11f);lineTo(4f,6f);close()};line(8.5f,12f,11f,14.5f,15.5f,9.5f) }
            Symbol.PHONE -> path {moveTo(6f,3f);lineTo(9f,7.5f);quadTo(9.5f,8.5f,8.5f,9.5f);lineTo(7f,11f);quadTo(9f,15f,13f,17f);lineTo(14.5f,15.5f);quadTo(15.5f,14.5f,16.5f,15f);lineTo(21f,18f);quadTo(20f,22f,17f,21f);quadTo(5f,18f,3f,7f);quadTo(2.5f,4f,6f,3f);close()}
            Symbol.PLUS -> {line(12f,4f,12f,20f);line(4f,12f,20f,12f)}
            Symbol.SEND -> {line(3f,4f,22f,12f,3f,20f,6f,12f,3f,4f);line(6f,12f,21f,12f)}
            Symbol.BACK -> {line(14f,5f,7f,12f,14f,19f);line(7f,12f,21f,12f)}
            Symbol.CHEVRON -> line(9f,5f,16f,12f,9f,19f)
            Symbol.CLOSE -> {line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
            Symbol.BACKSPACE -> {line(9f,5f,21f,5f,21f,19f,9f,19f,2f,12f,9f,5f);line(12f,9f,17f,15f);line(17f,9f,12f,15f)}
            Symbol.POWER -> {canvas.drawArc(4f,4f,20f,20f,-50f,280f,false,paint);line(12f,2f,12f,11f)}
            Symbol.CHECK -> line(5f,12f,10f,17f,20f,6f)
            Symbol.ALERT -> {path{moveTo(10.4f,4f);quadTo(12f,1.5f,13.6f,4f);lineTo(22f,18f);quadTo(23f,20f,20f,20f);lineTo(4f,20f);quadTo(1f,20f,2f,18f);close()};line(12f,8f,12f,13f);circle(12f,17f,.25f)}
            Symbol.CLOCK -> {circle(12f,12f,9f);line(12f,7f,12f,12f,16f,14f)}
            Symbol.LOG -> {rect(4f,3f,20f,21f);line(8f,8f,9f,8f);line(12f,8f,16f,8f);line(8f,12f,9f,12f);line(12f,12f,16f,12f);line(8f,16f,9f,16f);line(12f,16f,16f,16f)}
            Symbol.COPY -> {rect(8f,8f,21f,21f);path{moveTo(16f,5f);lineTo(16f,3f);lineTo(3f,3f);lineTo(3f,16f);lineTo(5f,16f)}}
            Symbol.SHARE -> {circle(18f,4f,3f);circle(6f,12f,3f);circle(18f,20f,3f);line(8.5f,10.5f,15.5f,5.5f);line(8.5f,13.5f,15.5f,18.5f)}
            Symbol.MORE -> {circle(12f,5f,.8f);circle(12f,12f,.8f);circle(12f,19f,.8f)}
            Symbol.REFRESH -> {canvas.drawArc(4f,4f,20f,20f,45f,285f,false,paint);line(20f,3f,20f,9f,14f,9f)}
            Symbol.DOWNLOAD -> {line(12f,3f,12f,15f);line(7f,10f,12f,15f,17f,10f);line(4f,17f,4f,21f,20f,21f,20f,17f)}
            Symbol.USB -> {line(12f,3f,12f,18f);line(9f,6f,12f,3f,15f,6f);line(12f,15f,6f,12f,6f,8f);line(12f,12f,18f,9f,18f,7f);circle(6f,7f,1f);rect(16.5f,4f,19.5f,7f,.4f);circle(12f,20f,2f)}
            Symbol.CHIP -> {rect(6f,6f,18f,18f);rect(9f,9f,15f,15f,1f);for(x in listOf(8f,12f,16f)){line(x,2f,x,5f);line(x,19f,x,22f);line(2f,x,5f,x);line(19f,x,22f,x)}}
            Symbol.ROOT -> {line(7f,3f,4f,21f);line(17f,3f,14f,21f);line(2f,8f,22f,8f);line(1f,16f,21f,16f)}
            Symbol.EDIT -> {line(4f,15f,16f,3f,21f,8f,9f,20f,3f,21f,4f,15f);line(13f,6f,18f,11f)}
            Symbol.TRASH -> {line(3f,6f,21f,6f);line(9f,3f,15f,3f);path{moveTo(5f,6f);lineTo(6f,21f);lineTo(18f,21f);lineTo(19f,6f)};line(10f,10f,10f,17f);line(14f,10f,14f,17f)}
            Symbol.INFO -> {circle(12f,12f,9f);line(12f,11f,12f,17f);circle(12f,7f,.4f)}
            Symbol.MUTE -> {rect(9f,3f,15f,14f,3f);path{moveTo(5f,10f);lineTo(5f,12f);quadTo(5f,19f,12f,19f);quadTo(19f,19f,19f,12f);lineTo(19f,10f)};line(12f,19f,12f,22f);line(3f,3f,21f,21f)}
            Symbol.SPEAKER -> {line(3f,9f,7f,9f,13f,4f,13f,20f,7f,15f,3f,15f,3f,9f);canvas.drawArc(11f,6f,21f,18f,-65f,130f,false,paint);canvas.drawArc(8f,2f,26f,22f,-55f,110f,false,paint)}
            Symbol.SEARCH -> {circle(10.5f,10.5f,6.5f);line(16f,16f,21f,21f)}
            Symbol.ARROW_UP -> {line(12f,20f,12f,4f);line(6f,10f,12f,4f,18f,10f)}
            Symbol.ARROW_DOWN -> {line(12f,4f,12f,20f);line(6f,14f,12f,20f,18f,14f)}
            Symbol.LOCK -> {rect(5f,10f,19f,21f);path{moveTo(8f,10f);lineTo(8f,7f);cubicTo(8f,1f,16f,1f,16f,7f);lineTo(16f,10f)};circle(12f,15f,1f);line(12f,16f,12f,18f)}
            Symbol.EXTERNAL -> {line(13f,3f,21f,3f,21f,11f);line(21f,3f,10f,14f);path{moveTo(10f,4f);lineTo(4f,4f);lineTo(4f,20f);lineTo(20f,20f);lineTo(20f,14f)}}
            Symbol.HEART -> path{moveTo(12f,21f);lineTo(3.8f,12.9f);cubicTo(-1.8f,7.3f,6.3f,-.2f,12f,6.1f);cubicTo(17.7f,-.2f,25.8f,7.3f,20.2f,12.9f);close()}
            Symbol.PERSON -> {circle(12f,7f,4f);path{moveTo(4f,21f);lineTo(4f,19f);cubicTo(4f,11f,20f,11f,20f,19f);lineTo(20f,21f)}}
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class IconView(context: Context, val symbol: Symbol, color: Int) : View(context) {
    var color: Int=color;set(value){field=value;invalidate()}
    private val glyph=Glyph(symbol,color)
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas);glyph.color=color;val w=(width-paddingLeft-paddingRight).coerceAtLeast(0);val h=(height-paddingTop-paddingBottom).coerceAtLeast(0);val size=minOf(w,h);val left=paddingLeft+(w-size)/2;val top=paddingTop+(h-size)/2;glyph.setBounds(left,top,left+size,top+size);glyph.draw(canvas) }
}
