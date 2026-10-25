package io.vodroid

import android.app.*
import android.content.*
import android.graphics.Color
import android.os.Bundle
import android.telephony.euicc.EuiccManager
import android.text.InputType
import android.view.*
import android.widget.*
import io.vodroid.core.ActivationCode
import io.vodroid.ui.*
import net.typeblog.lpac_jni.LocalProfileInfo
import java.util.concurrent.atomic.AtomicBoolean

/** All external installation requests stop here for the user's selected OMAPI/USB/Root card. */
class EsimImportActivity:Activity(){
    private lateinit var ui:UiKit
    private lateinit var job:InstallJob
    private lateinit var code:EditText
    private lateinit var confirmation:EditText
    private lateinit var activate:Switch
    private lateinit var install:LinearLayout
    private lateinit var progress:ProgressBar
    private lateinit var status:TextView
    private lateinit var label:TextView
    private val listener:()->Unit={render()}
    private val back=android.window.OnBackInvokedCallback{close()}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);ui=UiKit(this)
        job=lastNonConfigurationInstance as? InstallJob ?: InstallJob(applicationContext,intent.getParcelableExtra(EsimHandoff.CALLBACK,PendingIntent::class.java))
        window.setDecorFitsSystemWindows(false);window.statusBarColor=Color.TRANSPARENT;window.navigationBarColor=Color.TRANSPARENT
        val root=ui.vertical().apply{setBackgroundColor(Palette.bg)}
        root.setOnApplyWindowInsetsListener{_,insets->val bars=insets.getInsets(WindowInsets.Type.systemBars());val keyboard=insets.getInsets(WindowInsets.Type.ime());root.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,keyboard.bottom));insets}
        val toolbar=ui.row().apply{setPadding(ui.dp(4),ui.dp(8),ui.dp(12),ui.dp(12))};toolbar.addView(ui.iconButton(Symbol.BACK,"取消安装"){close()});toolbar.addView(ui.text("安装 eSIM",23f),LinearLayout.LayoutParams(0,ui.dp(52),1f).apply{gravity=Gravity.CENTER_VERTICAL});(toolbar.getChildAt(1) as TextView).gravity=Gravity.CENTER_VERTICAL
        toolbar.addView(ui.iconButton(Symbol.COPY,"复制激活码"){runCatching{ActivationCode.parse(code.text.toString()).canonical()}.onSuccess{getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("eSIM 激活码",it));toast("已复制")}.onFailure{toast("激活码格式无效")}});root.addView(toolbar)
        val content=ui.vertical().apply{setPadding(ui.dp(20),ui.dp(6),ui.dp(20),ui.dp(24))};val scroll=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false;addView(content)};root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val target=ui.card(20);val targetRow=ui.row();targetRow.addView(ui.icon(Symbol.SIM,26,Palette.accent),LinearLayout.LayoutParams(ui.dp(26),ui.dp(26)).apply{marginEnd=ui.dp(14)});val targetText=ui.vertical();targetText.addView(ui.text("写入卡片",12f,Palette.secondary));targetText.addView(ui.space(6));targetText.addView(ui.text(cardName(),17f,weight=true));targetRow.addView(targetText);target.addView(targetRow);content.addView(target);content.addView(ui.space(24))
        val source=try{EsimHandoff.extract(intent)}catch(_:RuntimeException){""}
        val activation=ui.field("激活码 / 安装链接",state?.getString("code") ?: source,"LPA:1$…",multiline=true);code=activation.second;code.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;content.addView(activation.first)
        val paste=ui.action("粘贴",Symbol.COPY,Palette.soft,Palette.accent){val clipboard=getSystemService(android.content.ClipboardManager::class.java);val text=clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString();if(text.isNullOrBlank())toast("剪贴板为空") else code.setText(text)};content.addView(paste);content.addView(ui.space(24))
        val confirm=ui.field("确认码",state?.getString("confirmation") ?: intent.getStringExtra(EsimHandoff.CONFIRMATION).orEmpty(),"可选");confirmation=confirm.second;confirmation.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;content.addView(confirm.first)
        val options=ui.row().apply{setPadding(ui.dp(4),0,0,ui.dp(16))};options.addView(ui.text("安装后启用",15f),LinearLayout.LayoutParams(0,-2,1f));activate=Switch(this).apply{isChecked=state?.getBoolean("activate") ?: intent.getBooleanExtra(EsimHandoff.ACTIVATE,false);contentDescription="安装后启用"};options.addView(activate);content.addView(options)
        progress=ProgressBar(this).apply{visibility=View.GONE;indeterminateTintList=android.content.res.ColorStateList.valueOf(Palette.accent)};content.addView(progress,LinearLayout.LayoutParams(ui.dp(26),ui.dp(26)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=ui.dp(16)})
        status=ui.text(if(source.isBlank()&&intent.action?.contains("EUICC")==true)"此调用未包含激活码，可粘贴后安装" else "",13f,Palette.secondary);content.addView(status,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=ui.dp(16)})
        label=ui.text("下载安装",15f,Color.WHITE,true);install=ui.row().apply{gravity=Gravity.CENTER;minimumHeight=ui.dp(52);contentDescription="下载安装"};install.addView(ui.icon(Symbol.DOWNLOAD,19,Color.WHITE),LinearLayout.LayoutParams(ui.dp(19),ui.dp(19)).apply{marginEnd=ui.dp(9)});install.addView(label);ui.touch(install,Palette.accent,26){begin()};content.addView(install)
        setContentView(root);window.insetsController?.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,back)
        render()
    }
    override fun onResume(){super.onResume();job.listener=listener;render()}
    override fun onNewIntent(incoming:Intent){
        super.onNewIntent(incoming)
        if(job.running){incoming.getParcelableExtra(EsimHandoff.CALLBACK,PendingIntent::class.java)?.let{runCatching{it.send(this,EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,Intent())}};toast("正在安装，请稍候");return}
        job.reply(false);setIntent(incoming);job=InstallJob(applicationContext,incoming.getParcelableExtra(EsimHandoff.CALLBACK,PendingIntent::class.java));job.listener=listener
        code.setText(runCatching{EsimHandoff.extract(incoming)}.getOrDefault(""));confirmation.setText(incoming.getStringExtra(EsimHandoff.CONFIRMATION).orEmpty());activate.isChecked=incoming.getBooleanExtra(EsimHandoff.ACTIVATE,false);status.text="";render()
    }
    override fun onPause(){if(job.listener===listener)job.listener=null;super.onPause()}
    override fun onDestroy(){if(job.listener===listener)job.listener=null;onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back);if(isFinishing&&!job.running&&!job.done)job.reply(false);super.onDestroy()}
    @Deprecated("Retain the in-flight card operation across rotation") override fun onRetainNonConfigurationInstance():Any=job
    override fun onSaveInstanceState(out:Bundle){super.onSaveInstanceState(out);out.putString("code",code.text.toString());out.putString("confirmation",confirmation.text.toString());out.putBoolean("activate",activate.isChecked)}
    private fun cardName():String{val profile=Engine.profiles.firstOrNull{it.state==LocalProfileInfo.State.Enabled};return profile?.nickName?.ifBlank{profile.name}?.ifBlank{null} ?: when(Engine.prefs.getString("card_type","omapi")){"omapi"->"${Engine.prefs.getString("reader","SIM1")} · OMAPI";"root"->"卡槽 ${Engine.prefs.getInt("slot",0)+1} · Root";else->"USB CCID"}}
    private fun toast(value:String)=Toast.makeText(this,value,Toast.LENGTH_SHORT).show()
    private fun begin(){
        if(job.running)return
        val activation=runCatching{ActivationCode.parse(code.text.toString())}.getOrElse{code.error=it.message;return}
        if(VoWifiService.current!=null){toast("请先断开 VoWiFi");return};if(Engine.busy){toast("正在处理卡片，请稍候");return}
        currentFocus?.let{getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0)}
        job.start(activation,confirmation.text.toString(),activate.isChecked);render()
    }
    private fun render(){
        if(job.done){setResult(RESULT_OK);finish();return}
        progress.visibility=if(job.running)View.VISIBLE else View.GONE;code.isEnabled=!job.running;confirmation.isEnabled=!job.running;activate.isEnabled=!job.running;install.isEnabled=!job.running;install.alpha=if(job.running).55f else 1f
        if(job.running)status.text="正在安装" else if(job.error.isNotBlank())status.text=job.error
        status.setTextColor(if(job.error.isNotBlank())Palette.red else Palette.secondary);label.text=if(job.error.isNotBlank())"重试" else "下载安装"
    }
    private fun close(){if(job.running){toast("正在安装，请稍候");return};job.reply(false);setResult(RESULT_CANCELED);finish()}
    private class InstallJob(private val context:Context,private val callback:PendingIntent?){
        @Volatile var running=false;private set
        @Volatile var done=false;private set
        @Volatile var error="";private set
        var listener:(()->Unit)?=null
        private val replied=AtomicBoolean(false)
        fun reply(success:Boolean){if(replied.compareAndSet(false,true))try{callback?.send(context,if(success)EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK else EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR,Intent())}catch(_:PendingIntent.CanceledException){Engine.log("eSIM 调用方已取消结果回调")}}
        fun start(activation:ActivationCode,confirmation:String,activate:Boolean){
            running=true;error="";Engine.task{
                try{
                    if(activate)Engine.refreshProfiles()
                    val before=Engine.profiles.map{it.iccid}.toSet()
                    Engine.download(activation.canonical(),confirmation)
                    if(activate){val installed=Engine.profiles.filter{it.iccid !in before}.singleOrNull() ?: error("无法确定新增配置，请在配置页启用");Engine.profileAction(installed,"enable")}
                    Engine.profiles.firstOrNull{it.state==LocalProfileInfo.State.Enabled}?.let{Engine.prefs.edit().putString("profile_label",it.nickName.ifBlank{it.name}).apply()}
                    done=true;reply(true);Engine.log("eSIM 安装完成")
                }catch(e:Exception){
                    var message=e.message ?: "安装失败";message=message.replace(activation.canonical(),"[激活码]").replace(activation.payload(),"[激活码]");if(activation.matchingId().isNotBlank())message=message.replace(activation.matchingId(),"[匹配码]");if(confirmation.isNotBlank())message=message.replace(confirmation,"[确认码]");error=message.take(180);Engine.log("eSIM 安装失败: ${e.javaClass.simpleName}")
                }finally{running=false;context.mainExecutor.execute{listener?.invoke()}}
            }
        }
    }
}
