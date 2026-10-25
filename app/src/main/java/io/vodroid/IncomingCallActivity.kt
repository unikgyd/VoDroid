package io.vodroid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import io.vodroid.ui.*

/** A private lock-screen destination reached by our incoming-call PendingIntent. */
class IncomingCallActivity:Activity(){
    private val observer:()->Unit={refresh()}
    private var answering=false
    override fun onCreate(state:Bundle?){super.onCreate(state);setShowWhenLocked(true);setTurnScreenOn(true);Engine.observers.add(observer);render();if(intent.getBooleanExtra("answer",false))answer()}
    override fun onResume(){super.onResume();refresh()}
    override fun onDestroy(){Engine.observers.remove(observer);super.onDestroy()}
    private fun valid()=Engine.call?.takeIf{it.id()==intent.getStringExtra("call_id")&&it.incoming()}
    private fun refresh(){val call=valid();if(call==null||call.state()=="ended"||call.state().startsWith("failed")){finish();return};if(call.state()!="ringing"){startActivity(Intent(this,MainActivity::class.java).putExtra("page","calls").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP));finish()}}
    private fun answer(){if(answering||valid()?.state()!="ringing")return;if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),41);return};answering=true;Engine.task{try{Engine.session?.answer()}catch(error:Exception){answering=false;Engine.log("接听失败 · ${error.javaClass.simpleName}")}}}
    override fun onRequestPermissionsResult(request:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(request,permissions,results);if(request==41&&results.firstOrNull()==PackageManager.PERMISSION_GRANTED)answer()}
    private fun render(){val call=valid();if(call==null){finish();return};val ui=UiKit(this);val box=ui.vertical().apply{gravity=Gravity.CENTER;setPadding(ui.dp(28),ui.dp(40),ui.dp(28),ui.dp(40));setBackgroundColor(Palette.bg)}
        box.addView(ui.icon(Symbol.PHONE,52,Palette.accent));box.addView(ui.space(28));box.addView(ui.text(call.number().removePrefix("sip:").substringBefore('@'),30f));box.addView(ui.space(12));box.addView(ui.text("VoDroid 来电",15f,Palette.secondary));box.addView(ui.space(56));val actions=ui.row().apply{gravity=Gravity.CENTER}
        actions.addView(ui.iconButton(Symbol.PHONE,"拒接",Color.WHITE,Palette.red,72){if(valid()?.state()=="ringing")Engine.task{Engine.session?.hangup()}});actions.addView(ui.space(48),LinearLayout.LayoutParams(ui.dp(48),1));actions.addView(ui.iconButton(Symbol.PHONE,"接听",Color.WHITE,Palette.success,72){answer()});box.addView(actions);setContentView(box)
    }
}
