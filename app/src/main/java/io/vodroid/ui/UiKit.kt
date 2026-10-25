package io.vodroid.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.*
import android.widget.*

object Palette {
    val bg=Color.rgb(248,249,252)
    val surface=Color.WHITE
    val ink=Color.rgb(31,31,31)
    val secondary=Color.rgb(95,99,104)
    val muted=Color.rgb(128,134,139)
    val line=Color.rgb(229,232,238)
    val accent=Color.rgb(11,87,208)
    val soft=Color.rgb(233,240,255)
    val mint=Color.rgb(195,215,255)
    val hero=Color.rgb(225,233,255)
    val success=Color.rgb(20,108,46)
    val red=Color.rgb(179,38,30)
    val redSoft=Color.rgb(252,235,232)
    val amber=Color.rgb(138,96,0)
}

class UiKit(val context: Context) {
    fun dp(value:Int)=(value*context.resources.displayMetrics.density+.5f).toInt()
    fun shape(color:Int,radius:Int=22,border:Boolean=false)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat();if(border)setStroke(dp(1),Palette.line)}
    fun touch(view:View,fill:Int=Color.TRANSPARENT,radius:Int=16,action:()->Unit):View {
        view.background=RippleDrawable(ColorStateList.valueOf(0x180B57D0),shape(fill,radius),shape(Color.WHITE,radius))
        view.isClickable=true;view.isFocusable=true;view.setOnClickListener{action()};return view
    }
    fun text(value:String,size:Float=15f,color:Int=Palette.ink,weight:Boolean=false)=TextView(context).apply{
        layoutParams=LinearLayout.LayoutParams(-2,-2)
        text=value;textSize=size;setTextColor(color);includeFontPadding=false
        typeface=Typeface.create(if(weight)"sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(),1f)
    }
    fun mono(value:String,size:Float=12f,color:Int=Palette.secondary)=text(value,size,color).apply{typeface=Typeface.MONOSPACE}
    fun vertical()=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    fun row()=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    fun icon(symbol:Symbol,size:Int=22,color:Int=Palette.ink)=IconView(context,symbol,color).apply{layoutParams=LinearLayout.LayoutParams(dp(size),dp(size));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun iconButton(symbol:Symbol,label:String,color:Int=Palette.ink,fill:Int=Color.TRANSPARENT,size:Int=48,action:()->Unit):FrameLayout {
        val frame=FrameLayout(context).apply{contentDescription=label;layoutParams=LinearLayout.LayoutParams(dp(size),dp(size))}
        frame.addView(icon(symbol,22,color),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))
        touch(frame,fill,size/2,action);return frame
    }
    fun action(label:String,symbol:Symbol?=null,fill:Int=Palette.accent,color:Int=Color.WHITE,action:()->Unit):LinearLayout {
        val row=row().apply{gravity=Gravity.CENTER;minimumHeight=dp(52);contentDescription=label}
        if(symbol!=null)row.addView(icon(symbol,19,color),LinearLayout.LayoutParams(dp(19),dp(19)).apply{marginEnd=dp(9)})
        row.addView(text(label,15f,color,true));touch(row,fill,16,action);return row
    }
    fun badge(label:String,color:Int=Palette.accent,fill:Int=Palette.soft,dot:Boolean=false):LinearLayout {
        val row=row().apply{setPadding(dp(10),dp(6),dp(10),dp(6));background=shape(fill,20)}
        if(dot)row.addView(this.dot(color,6),LinearLayout.LayoutParams(dp(6),dp(6)).apply{marginEnd=dp(6)})
        row.addView(text(label,11f,color,true));return row
    }
    fun dot(color:Int,size:Int=7)=View(context).apply{background=shape(color,size);tag=color;layoutParams=LinearLayout.LayoutParams(dp(size),dp(size))}
    fun tintDot(view:View,color:Int){if(view.tag==color)return;(view.background as? GradientDrawable)?.setColor(color);view.tag=color}
    fun card(padding:Int=18,fill:Int=Palette.surface)=vertical().apply{setPadding(dp(padding),dp(padding),dp(padding),dp(padding));background=shape(fill,24)}
    fun space(height:Int)=View(context).apply{layoutParams=LinearLayout.LayoutParams(1,dp(height));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun divider(inset:Int=0)=View(context).apply{setBackgroundColor(Palette.line);layoutParams=LinearLayout.LayoutParams(-1,dp(1)).apply{marginStart=dp(inset)}}
    fun section(label:String)=text(label,12f,Palette.secondary,true).apply{setPadding(dp(4),dp(20),0,dp(10));letterSpacing=.05f}
    fun setting(symbol:Symbol,label:String,value:String="",action:()->Unit):LinearLayout {
        val row=row().apply{minimumHeight=dp(68);setPadding(dp(16),dp(10),dp(12),dp(10))}
        val tile=FrameLayout(context).apply{background=shape(Palette.bg,12)}
        tile.addView(icon(symbol,20,Palette.accent),FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER))
        row.addView(tile,LinearLayout.LayoutParams(dp(38),dp(38)).apply{marginEnd=dp(13)})
        val title=text(label,15f,weight=true);row.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        val detail=text(value,12f,Palette.secondary).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;maxWidth=dp(110);tag="value"};row.addView(detail)
        row.addView(icon(Symbol.CHEVRON,16,Palette.muted),LinearLayout.LayoutParams(dp(16),dp(16)).apply{marginStart=dp(8)})
        touch(row,Palette.surface,18,action);return row
    }
    fun field(label:String,value:String,hint:String="",phone:Boolean=false,multiline:Boolean=false):Pair<LinearLayout,EditText> {
        val group=vertical();group.addView(text(label,12f,Palette.secondary,true),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        val edit=EditText(context).apply{
            setText(value);this.hint=hint;contentDescription=label;textSize=16f;setTextColor(Palette.ink);setHintTextColor(Palette.muted)
            setPadding(dp(14),dp(13),dp(14),dp(13));background=shape(Palette.bg,13);isSingleLine=!multiline
            inputType=if(phone)InputType.TYPE_CLASS_PHONE else if(multiline)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else InputType.TYPE_CLASS_TEXT
            if(multiline){minLines=3;maxLines=5;gravity=Gravity.TOP}
        }
        group.addView(edit,LinearLayout.LayoutParams(-1,-2));group.addView(space(17));return group to edit
    }
    fun empty(symbol:Symbol,title:String):LinearLayout {
        val box=vertical().apply{gravity=Gravity.CENTER;setPadding(dp(20),dp(56),dp(20),dp(44))}
        val circle=FrameLayout(context).apply{background=shape(Palette.soft,30)}
        circle.addView(icon(symbol,30,Palette.accent),FrameLayout.LayoutParams(dp(30),dp(30),Gravity.CENTER));box.addView(circle,LinearLayout.LayoutParams(dp(66),dp(66)))
        box.addView(space(18));box.addView(text(title,15f,Palette.secondary));return box
    }
}
