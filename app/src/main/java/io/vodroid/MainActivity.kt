package io.vodroid

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.*
import android.os.*
import android.text.*
import android.view.*
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import io.vodroid.ui.*
import net.typeblog.lpac_jni.LocalProfileInfo
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    internal lateinit var ui: UiKit
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var navigation: LinearLayout
    private lateinit var callBanner: LinearLayout
    private val handler=Handler(Looper.getMainLooper())
    private var route="status"
    private var backRoute="status"
    private var updatePage:()->Unit={}
    private var number=""
    private var peer=""
    private var search=""
    private val drafts=mutableMapOf<String,String>()
    private var pendingPermission:(()->Unit)?=null
    private var seenCall=""
    private var activeSince=0L
    private var muted: Boolean get()=Engine.microphoneMuted;set(value){Engine.microphoneMuted=value}
    private var speaker=false
    private var resumed=false
    private var initialized=false
    private val backCallback=android.window.OnBackInvokedCallback { if(route=="status")finish() else go(backRoute) }
    private val settings by lazy { SettingsPages(this) }
    private val observer:()->Unit={ updatePage();updateCallBanner() }
    private val tick=object:Runnable{override fun run(){if(resumed){if(route=="dial"||route=="status")updatePage();handler.postDelayed(this,1000)}}}
    private val usbAction="io.vodroid.USB_PERMISSION"
    private val usbReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){
        if(intent?.action!=usbAction)return
        val device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice::class.java)
        if(device!=null&&intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)){
            Engine.prefs.edit().putString("usb",device.deviceName).putString("card_type","usb").apply()
            notice("读卡器已连接");if(route=="card-access")go(route,false) else updatePage()
        }else notice("未获得 USB 授权")
    }}

    override fun onCreate(state:Bundle?){
        super.onCreate(state);ui=UiKit(this)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor=Color.TRANSPARENT;window.navigationBarColor=Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        registerReceiver(usbReceiver,IntentFilter(usbAction),RECEIVER_NOT_EXPORTED)
        Engine.observers.add(observer)
        number=state?.getString("number") ?: "";peer=state?.getString("peer") ?: ""
        state?.getBundle("drafts")?.let{bundle->bundle.keySet().forEach{drafts[it]=bundle.getString(it,"")}}
        root=ui.vertical().apply{setBackgroundColor(Palette.bg)}
        val wide=resources.configuration.screenWidthDp>=600
        toolbar=ui.row();body=FrameLayout(this);callBanner=ui.row();navigation=if(wide)ui.vertical() else ui.row()
        root.addView(toolbar)
        if(wide){val workspace=ui.row();workspace.addView(navigation,LinearLayout.LayoutParams(ui.dp(80),-1));workspace.addView(body,LinearLayout.LayoutParams(0,-1,1f));root.addView(workspace,LinearLayout.LayoutParams(-1,0,1f));root.addView(callBanner)}else{root.addView(body,LinearLayout.LayoutParams(-1,0,1f));root.addView(callBanner);root.addView(navigation)}
        root.setOnApplyWindowInsetsListener{_,insets->val bars=insets.getInsets(WindowInsets.Type.systemBars());val keyboard=insets.getInsets(WindowInsets.Type.ime());root.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,keyboard.bottom));navigation.visibility=if(route=="thread"||route=="compose"||keyboard.bottom>bars.bottom)View.GONE else View.VISIBLE;insets}
        setContentView(root);buildNavigation()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback)
        window.insetsController?.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        val first=state?.getString("route") ?: when(intent?.getStringExtra("page")){"sms"->"sms";"calls"->"dial";else->"status"}
        if(state==null&&first=="sms"&&!intent.getStringExtra("thread").isNullOrBlank()){peer=intent.getStringExtra("thread")!!;go("thread",false)}else go(first,false);initialized=true
        if(intent?.data?.scheme.equals("lpa",true)){go("cards",false);settings.downloadSheet(intent.data.toString())}
    }
    override fun onResume(){super.onResume();resumed=true;handler.post(tick);if(initialized)observer()}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);when(intent.getStringExtra("page")){"sms"->{peer=intent.getStringExtra("thread").orEmpty();go(if(peer.isBlank())"sms" else "thread")};"calls"->go("dial");else->go("status")}}
    override fun onPause(){resumed=false;handler.removeCallbacks(tick);super.onPause()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);Engine.observers.remove(observer);unregisterReceiver(usbReceiver);onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback);super.onDestroy()}
    override fun onSaveInstanceState(out:Bundle){super.onSaveInstanceState(out);out.putString("route",route);out.putString("number",number);out.putString("peer",peer);out.putBundle("drafts",Bundle().apply{drafts.forEach{(key,value)->putString(key,value)}})}
    @Deprecated("Legacy back callback") override fun onBackPressed(){if(route=="status")super.onBackPressed() else go(backRoute)}

    internal fun go(destination:String,animate:Boolean=true){
        currentFocus?.let{getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0)}
        route=destination;updatePage={};body.animate().cancel();body.removeAllViews()
        navigation.visibility=if(route=="thread"||route=="compose")View.GONE else View.VISIBLE
        when(route){"status"->statusPage();"dial"->dialPage();"sms"->messagesPage();"thread","compose"->threadPage();else->settings.show(route)}
        selectNavigation();updateCallBanner();updatePage()
        if(animate){body.alpha=0f;body.translationY=ui.dp(6).toFloat();body.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}else{body.alpha=1f;body.translationY=0f}
        root.requestApplyInsets()
    }
    internal fun updater(action:()->Unit){updatePage=action}
    internal fun page(title:String,back:String?=null,actions:List<View> = emptyList(),scroll:Boolean=true):LinearLayout{
        backRoute=back ?: if(route=="status")"status" else "status"
        toolbar.removeAllViews();toolbar.setPadding(ui.dp(if(back!=null)4 else 22),ui.dp(8),ui.dp(10),ui.dp(12))
        if(back!=null)toolbar.addView(ui.iconButton(Symbol.BACK,"返回"){go(back)})
        toolbar.addView(ui.text(title,if(back!=null)22f else 28f,weight=false).apply{minimumHeight=ui.dp(52);maxLines=1;ellipsize=TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f).apply{gravity=Gravity.CENTER_VERTICAL}.also{})
        (toolbar.getChildAt(if(back!=null)1 else 0) as TextView).gravity=Gravity.CENTER_VERTICAL
        actions.forEach{toolbar.addView(it)}
        val content=ui.vertical().apply{setPadding(ui.dp(20),ui.dp(4),ui.dp(20),ui.dp(20))}
        if(scroll){val scroller=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS};scroller.addView(content);body.addView(scroller,FrameLayout.LayoutParams(-1,-1))}
        else body.addView(content,FrameLayout.LayoutParams(-1,-1))
        return content
    }
    internal fun addCard(parent:LinearLayout,card:View,gap:Int=12){parent.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=ui.dp(gap)})}
    internal fun notice(text:String){runOnUiThread{Toast.makeText(this,text,Toast.LENGTH_SHORT).show()}}
    internal fun copy(label:String,text:String){getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label,text))}
    internal fun confirm(message:String,action:()->Unit){AlertDialog.Builder(this).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确认"){_,_->action()}.show()}
    internal fun sheet(title:String,build:(LinearLayout,Dialog)->Unit):Dialog{
        val dialog=Dialog(this);val box=ui.vertical().apply{setPadding(ui.dp(22),ui.dp(10),ui.dp(22),ui.dp(28));background=ui.shape(Palette.surface,28)}
        box.addView(View(this).apply{background=ui.shape(Palette.line,3)},LinearLayout.LayoutParams(ui.dp(32),ui.dp(4)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=ui.dp(16)})
        val header=ui.row();header.addView(ui.text(title,23f),LinearLayout.LayoutParams(0,-2,1f));header.addView(ui.iconButton(Symbol.CLOSE,"关闭"){dialog.dismiss()});box.addView(header);box.addView(ui.space(12));build(box,dialog)
        val scroll=ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(box)};dialog.setContentView(scroll)
        dialog.window?.apply{setBackgroundDrawableResource(android.R.color.transparent);setLayout(-1,-2);setGravity(Gravity.BOTTOM);setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)}
        dialog.show();dialog.window?.setLayout(-1,minOf((resources.displayMetrics.heightPixels*.86f).toInt(),box.measuredHeight.takeIf{it>0} ?: -2));return dialog
    }
    internal fun profileName():String=Engine.profiles.firstOrNull{it.state==LocalProfileInfo.State.Enabled}?.let{it.nickName.ifBlank{it.name.ifBlank{it.providerName}}}?.ifBlank{"eSIM"} ?: Engine.prefs.getString("profile_label","")!!.ifBlank{when(Engine.prefs.getString("card_type","omapi")){"omapi"->Engine.prefs.getString("reader","SIM1")!!;"root"->"SIM${Engine.prefs.getInt("slot",0)+1}";else->if(Engine.simReady)"SIM" else "未选择 SIM"}}
    internal fun accessName():String=when(Engine.prefs.getString("card_type","omapi")){"omapi"->Engine.prefs.getString("reader","SIM1")!!;"root"->"卡槽 ${Engine.prefs.getInt("slot",0)+1}";else->"USB CCID"}
    internal fun connected()=Engine.session?.registered()==true
    internal fun stopConnection(){startService(Intent(this,VoWifiService::class.java).setAction("stop"))}
    internal fun startConnection(){
        if(Engine.busy){notice("正在处理卡片，请稍候");return}
        val launch={if(Engine.prefs.getBoolean("vpn_backend",false)){val consent=VpnService.prepare(this);if(consent!=null)startActivityForResult(consent,20) else startForegroundService(Intent(this,VoWifiService::class.java))}else startForegroundService(Intent(this,VoWifiService::class.java));Unit}
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!Engine.prefs.getBoolean("notifications_asked",false)){
            Engine.prefs.edit().putBoolean("notifications_asked",true).apply();pendingPermission=launch;requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),30)
        }else launch()
    }
    private fun withMicrophone(action:()->Unit){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)action() else{pendingPermission={if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)action()};requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),31)}}
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);val continuation=pendingPermission;pendingPermission=null;continuation?.invoke();updatePage()}
    override fun onActivityResult(code:Int,result:Int,data:Intent?){super.onActivityResult(code,result,data);if(code==20&&result==RESULT_OK)startConnection()}
    internal fun selectUsb(){
        if(VoWifiService.current!=null){notice("请先断开连接");return}
        val manager=getSystemService(UsbManager::class.java);val devices=manager.deviceList.values.filter{d->(0 until d.interfaceCount).any{d.getInterface(it).interfaceClass==11}}
        sheet("读卡器"){box,dialog->if(devices.isEmpty()){box.addView(ui.empty(Symbol.USB,"未发现读卡器"))}else devices.forEach{device->box.addView(ui.setting(Symbol.USB,device.productName ?: "USB CCID",device.deviceName.substringAfterLast('/')){dialog.dismiss();Engine.task{Engine.closeCard();runOnUiThread{val request=PendingIntent.getBroadcast(this,0,Intent(usbAction).setPackage(packageName),PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);manager.requestPermission(device,request)}}})}}
    }
    private fun buildNavigation(){
        val wide=resources.configuration.screenWidthDp>=600
        navigation.setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(6));navigation.setBackgroundColor(0xFFF0F3FA.toInt())
        listOf(Triple("status","状态",Symbol.STATUS),Triple("dial","拨号",Symbol.DIAL),Triple("sms","短信",Symbol.MESSAGE),Triple("settings","设置",Symbol.SETTINGS)).forEach{(id,label,symbol)->
            val item=ui.vertical().apply{gravity=Gravity.CENTER;tag=id;contentDescription=label;setPadding(0,ui.dp(4),0,ui.dp(4))}
            val pill=FrameLayout(this).apply{tag="pill"};pill.addView(ui.icon(symbol,23),FrameLayout.LayoutParams(ui.dp(23),ui.dp(23),Gravity.CENTER));item.addView(pill,LinearLayout.LayoutParams(ui.dp(62),ui.dp(32)));item.addView(ui.space(4));item.addView(ui.text(label,12f).apply{tag="label"});ui.touch(item,radius=22){if(route!=id)go(id)};navigation.addView(item,if(wide)LinearLayout.LayoutParams(-1,0,1f) else LinearLayout.LayoutParams(0,ui.dp(maxOf(64,(44+resources.configuration.fontScale*16).toInt())),1f))
        }
    }
    private fun selectNavigation(){val selected=when(route){"status","dial"->route;"sms","thread","compose"->"sms";else->"settings"};for(i in 0 until navigation.childCount){val item=navigation.getChildAt(i) as LinearLayout;val active=item.tag==selected;item.isSelected=active;val pill=item.findViewWithTag<FrameLayout>("pill");pill.background=ui.shape(if(active)Palette.mint else Color.TRANSPARENT,24);(pill.getChildAt(0) as IconView).color=if(active)Palette.accent else Palette.secondary;item.findViewWithTag<TextView>("label").setTextColor(if(active)Palette.ink else Palette.secondary)}}
    private fun updateCallBanner(){
        val call=Engine.call;callBanner.removeAllViews();callBanner.visibility=if(call!=null&&route!="dial")View.VISIBLE else View.GONE
        if(call==null||route=="dial")return
        callBanner.setPadding(ui.dp(20),ui.dp(12),ui.dp(16),ui.dp(12));callBanner.setBackgroundColor(Palette.soft);callBanner.addView(ui.icon(Symbol.PHONE,18,Palette.accent));callBanner.addView(ui.text(if(call.incoming()&&call.state()=="ringing")"来电" else "返回通话",14f,Palette.accent,true),LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=ui.dp(10)});callBanner.addView(ui.icon(Symbol.CHEVRON,17,Palette.accent));callBanner.setOnClickListener{go("dial")}
    }

    private fun statusPage(){
        val page=page("系统状态",actions=listOf(ui.iconButton(Symbol.INFO,"连接详情"){settings.connectionDetails()}))
        val main=ui.vertical();val details=ui.vertical()
        val compact=resources.configuration.screenWidthDp>=600&&resources.configuration.screenHeightDp<480
        val hero=ui.card(if(compact)20 else 24,Palette.hero)
        val top=ui.row();val dot=ui.dot(Palette.muted);val label=ui.text("VoWiFi",14f,Palette.secondary,true);top.addView(dot);top.addView(label,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=ui.dp(8)})
        val emblem=FrameLayout(this).apply{background=ui.shape(0x66FFFFFF,12)};emblem.addView(ui.icon(Symbol.WIFI,22,Palette.accent),FrameLayout.LayoutParams(ui.dp(22),ui.dp(22),Gravity.CENTER));top.addView(emblem,LinearLayout.LayoutParams(ui.dp(36),ui.dp(36)));top.visibility=if(compact)View.GONE else View.VISIBLE;hero.addView(top);hero.addView(ui.space(if(compact)0 else 16))
        val state=ui.text("未连接",if(compact)28f else 32f);hero.addView(state);hero.addView(ui.space(if(compact)4 else 6));val hint=ui.text("",13f,Palette.secondary).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END};hero.addView(hint,LinearLayout.LayoutParams(-1,-2));hero.addView(ui.space(if(compact)16 else 20))
        val connectLabel=ui.text("连接",15f,Color.WHITE,true);val connect=ui.row().apply{gravity=Gravity.CENTER;minimumHeight=ui.dp(if(compact)48 else 52);contentDescription="连接 VoWiFi"};val connectIcon=ui.icon(Symbol.POWER,19,Color.WHITE);connect.addView(connectIcon,LinearLayout.LayoutParams(ui.dp(19),ui.dp(19)).apply{marginEnd=ui.dp(9)})
        val progress=ProgressBar(this).apply{indeterminateTintList=android.content.res.ColorStateList.valueOf(Color.WHITE);visibility=View.GONE};connect.addView(progress,LinearLayout.LayoutParams(ui.dp(18),ui.dp(18)).apply{marginEnd=ui.dp(9)})
        connect.addView(connectLabel);ui.touch(connect,Palette.accent,28){if(VoWifiService.current!=null)stopConnection() else startConnection()};hero.addView(connect);hero.addView(ui.space(if(compact)14 else 20))
        val pipeline=ui.row();val lamps=mutableListOf<View>();val steps=listOf("SIM","ePDG","IMS")
        steps.forEachIndexed{i,title->
            val step=ui.row().apply{gravity=Gravity.CENTER;minimumHeight=ui.dp(24)};val lamp=ui.dot(Palette.muted,7);lamps.add(lamp);step.addView(lamp);step.addView(ui.text(title,12f,Palette.secondary),LinearLayout.LayoutParams(-2,-2).apply{marginStart=ui.dp(7)});pipeline.addView(step,LinearLayout.LayoutParams(0,-2,1f))
            if(i<2)pipeline.addView(View(this).apply{setBackgroundColor(0x220B57D0)},LinearLayout.LayoutParams(ui.dp(1),ui.dp(14)))
        };hero.addView(pipeline);addCard(main,hero,16)
        val summary=ui.card(4);val sim=ui.setting(Symbol.SIM,"当前 SIM",""){go("cards")};val mode=ui.setting(Symbol.SHIELD,"隧道模式",""){go("tunnel-status")};summary.addView(sim);summary.addView(ui.divider(66));summary.addView(mode);addCard(details,summary)
        val capabilities=ui.card(16);val cap=ui.row();val voice=capability(Symbol.PHONE,"通话");val sms=capability(Symbol.MESSAGE,"短信");cap.addView(voice.first,LinearLayout.LayoutParams(0,-2,1f));cap.addView(ui.divider(),LinearLayout.LayoutParams(ui.dp(1),ui.dp(32)));cap.addView(sms.first,LinearLayout.LayoutParams(0,-2,1f));capabilities.addView(cap);addCard(details,capabilities)
        hint.contentDescription="连接安全状态";hint.setOnClickListener{if(connected())settings.connectionDetails()}
        val errorRow=ui.setting(Symbol.ALERT,"连接异常","查看日志"){go("logs")};errorRow.visibility=View.GONE;details.addView(errorRow)
        if(resources.configuration.screenWidthDp>=600){val columns=ui.row().apply{gravity=Gravity.TOP};columns.addView(main,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=ui.dp(10)});columns.addView(details,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=ui.dp(10)});page.addView(columns)}else{page.addView(main);page.addView(details)}
        updater {
            val registered=connected();val active=VoWifiService.current!=null;val failed=Engine.phase.contains("失败")||Engine.phase.contains("中断")||Engine.phase.startsWith("隧道断开")
            val health=VoWifiService.current?.health()
            val heading=when{registered->"已连接";active&&Engine.phase.contains("读取")->"读取 SIM";active&&Engine.phase.contains("注册")->"注册中";active->"连接中";failed->"连接失败";else->"未连接"}
            state.text=heading;ui.tintDot(dot,if(registered)Palette.success else if(failed)Palette.red else Palette.muted)
            progress.visibility=if(active&&!registered)View.VISIBLE else View.GONE;connectIcon.visibility=if(active&&!registered)View.GONE else View.VISIBLE
            hint.text=if(registered)health?.let{listOf(it.eap(),if(it.fragmentation())"分片" else null,if(it.rekeys()>0)"已换钥 ${it.rekeys()}" else "加密连接").filterNotNull().joinToString(" · ")} ?: profileName() else if(active)when{Engine.tunnelReady->"IMS";Engine.simReady->"ePDG";else->accessName()} else if(failed)Engine.failureStage.ifBlank{"查看日志"} else profileName()
            connectLabel.text=if(registered)"断开" else if(active)"取消连接" else if(failed)"重新连接" else "连接";connect.contentDescription=if(active)"断开连接" else "连接 VoWiFi"
            val readiness=listOf(Engine.simReady,Engine.tunnelReady,registered);lamps.forEachIndexed{i,lamp->ui.tintDot(lamp,if(readiness[i])Palette.success else if(active&&i==readiness.count{it})Palette.accent else Palette.muted);lamp.contentDescription="${steps[i]} ${if(readiness[i])"已就绪" else "未就绪"}"}
            sim.findViewWithTag<TextView>("value").text=profileName();mode.findViewWithTag<TextView>("value").text=if(Engine.prefs.getBoolean("vpn_backend",false))"系统 VPN" else "应用内隧道"
            voice.second.text=if(registered)"可用" else "未注册";sms.second.text=if(registered)"可用" else "未注册";voice.second.setTextColor(if(registered)Palette.success else Palette.muted);sms.second.setTextColor(if(registered)Palette.success else Palette.muted);errorRow.visibility=if(failed&&!active)View.VISIBLE else View.GONE
        }
    }
    private fun capability(symbol:Symbol,label:String):Pair<LinearLayout,TextView>{val box=ui.row().apply{minimumHeight=ui.dp(42);setPadding(ui.dp(10),0,ui.dp(10),0)};box.addView(ui.icon(symbol,20,Palette.secondary),LinearLayout.LayoutParams(ui.dp(20),ui.dp(20)));val texts=ui.vertical();texts.addView(ui.text(label,14f,weight=true));val status=ui.text("未注册",12f,Palette.muted);texts.addView(ui.space(4));texts.addView(status);box.addView(texts,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=ui.dp(12)});return box to status}

    private fun dialPage(){
        val page=page("拨号",actions=listOf(ui.iconButton(Symbol.SIM,"选择 SIM"){go("cards")} ),scroll=resources.configuration.screenHeightDp<660)
        val statusLine=ui.row().apply{gravity=Gravity.CENTER};val simName=ui.text(profileName(),12f,Palette.secondary);statusLine.addView(ui.icon(Symbol.SIM,15,Palette.secondary));statusLine.addView(simName,LinearLayout.LayoutParams(-2,-2).apply{marginStart=ui.dp(6)});page.addView(statusLine)
        val dialPanel=ui.vertical();val callPanel=ui.vertical();page.addView(dialPanel,LinearLayout.LayoutParams(-1,0,1f));page.addView(callPanel,LinearLayout.LayoutParams(-1,0,1f));callPanel.visibility=View.GONE
        dialPanel.addView(ui.space(14));val numberRow=ui.row();numberRow.addView(View(this),LinearLayout.LayoutParams(ui.dp(48),1));val numberField=EditText(this).apply{setText(number);setSelection(number.length);hint="输入号码";setHintTextColor(Palette.muted);setTextColor(Palette.ink);textSize=32f;gravity=Gravity.CENTER;isSingleLine=true;showSoftInputOnFocus=false;background=null;inputType=android.text.InputType.TYPE_CLASS_PHONE;contentDescription="电话号码";setPadding(0,ui.dp(14),0,ui.dp(14))};numberField.afterChange{number=it};numberRow.addView(numberField,LinearLayout.LayoutParams(0,ui.dp(82),1f));val erase=ui.iconButton(Symbol.BACKSPACE,"删除号码",Palette.secondary){val p=numberField.selectionStart.coerceAtLeast(0);if(p>0)numberField.text.delete(p-1,p)};erase.setOnLongClickListener{numberField.setText("");true};numberRow.addView(erase);dialPanel.addView(numberRow)
        val available=ui.text("未连接",12f,Palette.muted).apply{gravity=Gravity.CENTER;setPadding(0,0,0,ui.dp(12));setOnClickListener{go("status")}};dialPanel.addView(available,LinearLayout.LayoutParams(-1,-2))
        val pad=ui.vertical();val keys=listOf("1" to "","2" to "ABC","3" to "DEF","4" to "GHI","5" to "JKL","6" to "MNO","7" to "PQRS","8" to "TUV","9" to "WXYZ","*" to "","0" to "+","#" to "")
        keys.chunked(3).forEach{rowKeys->val row=ui.row();rowKeys.forEach{(digit,letters)->val key=ui.vertical().apply{gravity=Gravity.CENTER;contentDescription=digit;minimumHeight=ui.dp(68)};key.addView(ui.text(digit,30f));key.addView(ui.text(letters,9f,Palette.secondary).apply{letterSpacing=.18f});ui.touch(key,radius=40){val p=numberField.selectionStart.coerceAtLeast(0);numberField.text.insert(p,digit)};if(digit=="0")key.setOnLongClickListener{val p=numberField.selectionStart.coerceAtLeast(0);numberField.text.insert(p,"+");true};row.addView(key,LinearLayout.LayoutParams(0,0,1f).apply{height=ui.dp(72)})};pad.addView(row,LinearLayout.LayoutParams(-1,0,1f))};dialPanel.addView(pad,LinearLayout.LayoutParams(-1,0,1f));dialPanel.addView(ui.space(14));val dial=ui.iconButton(Symbol.PHONE,"拨打",Color.WHITE,Palette.accent,70){if(number.isBlank())return@iconButton;if(!connected()){notice("请先连接");return@iconButton};withMicrophone{Engine.task{Engine.session?.dial(number)}}};dialPanel.addView(dial,LinearLayout.LayoutParams(ui.dp(70),ui.dp(70)).apply{gravity=Gravity.CENTER_HORIZONTAL});dialPanel.addView(ui.space(12))
        var lastCall=""
        updater {
            simName.text=profileName();available.text=if(connected())"可呼叫" else "未连接";available.setTextColor(if(connected())Palette.success else Palette.muted)
            val call=Engine.call;speaker=getSystemService(AudioManager::class.java).communicationDevice?.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;dialPanel.visibility=if(call==null)View.VISIBLE else View.GONE;callPanel.visibility=if(call==null)View.GONE else View.VISIBLE
            if(call==null){lastCall="";muted=false;speaker=false;return@updater}
            if(seenCall!=call.id()){seenCall=call.id();activeSince=0L}
            if(call.state()=="active"&&activeSince==0L)activeSince=SystemClock.elapsedRealtime()
            val signature="${call.id()}/${call.state()}/${muted}/${speaker}"
            if(signature!=lastCall){lastCall=signature;callPanel.removeAllViews();callPanel.gravity=Gravity.CENTER;callPanel.addView(ui.space(36));val avatar=FrameLayout(this).apply{background=ui.shape(Palette.soft,50)};avatar.addView(ui.icon(Symbol.PHONE,34,Palette.accent),FrameLayout.LayoutParams(ui.dp(34),ui.dp(34),Gravity.CENTER));callPanel.addView(avatar,LinearLayout.LayoutParams(ui.dp(88),ui.dp(88)));callPanel.addView(ui.space(26));callPanel.addView(ui.text(call.number().removePrefix("sip:").substringBefore('@'),29f).apply{gravity=Gravity.CENTER});callPanel.addView(ui.space(10));callPanel.addView(ui.text("",15f,Palette.secondary).apply{tag="call-state"});callPanel.addView(ui.space(40));val controls=ui.row().apply{gravity=Gravity.CENTER}
                if(call.incoming()&&call.state()=="ringing"){val reject=ui.iconButton(Symbol.PHONE,"拒接",Color.WHITE,Palette.red,70){Engine.task{Engine.session?.hangup()}};reject.getChildAt(0).rotation=135f;controls.addView(reject);controls.addView(View(this),LinearLayout.LayoutParams(ui.dp(48),1));controls.addView(ui.iconButton(Symbol.PHONE,"接听",Color.WHITE,Palette.success,70){withMicrophone{Engine.task{Engine.session?.answer()}}})}
                else{controls.addView(callControl(Symbol.MUTE,"静音",muted){Engine.muteMicrophone(!Engine.microphoneMuted)});controls.addView(ui.space(24),LinearLayout.LayoutParams(ui.dp(24),1));controls.addView(callControl(Symbol.SPEAKER,"扬声器",speaker){if(Engine.audio?.speaker(!speaker)!=true)notice("音频设备暂不可用");updatePage()})}
                callPanel.addView(controls);if(!(call.incoming()&&call.state()=="ringing")){callPanel.addView(ui.space(42));val hangup=ui.iconButton(Symbol.PHONE,if(call.incoming()&&call.state()=="ringing")"拒接" else "挂断",Color.WHITE,Palette.red,70){Engine.task{Engine.session?.hangup()}};hangup.getChildAt(0).rotation=135f;callPanel.addView(hangup)}
            }
            callPanel.findViewWithTag<TextView>("call-state")?.text=when(call.state()){ "active"->{val seconds=(SystemClock.elapsedRealtime()-activeSince)/1000;"%02d:%02d".format(seconds/60,seconds%60)};"ringing"->if(call.incoming())"来电" else "正在响铃";"connecting"->"正在接听";"dialing"->"正在呼叫";else->"连接中"}
        }
    }
    private fun callControl(symbol:Symbol,label:String,selected:Boolean,action:()->Unit):LinearLayout{val col=ui.vertical().apply{gravity=Gravity.CENTER};col.addView(ui.iconButton(symbol,label,if(selected)Palette.accent else Palette.secondary,if(selected)Palette.mint else Palette.surface,58,action));col.addView(ui.space(8));col.addView(ui.text(label,12f,Palette.secondary));return col}

    private fun messagesPage(){
        val page=page("短信",actions=listOf(ui.iconButton(Symbol.PLUS,"新建短信",Palette.accent,Palette.soft){peer="";go("compose")}),scroll=false)
        val searchRow=ui.row().apply{background=ui.shape(0xFFEBEEF4.toInt(),28);setPadding(ui.dp(15),0,ui.dp(12),0)};searchRow.addView(ui.icon(Symbol.SEARCH,20,Palette.secondary));val field=EditText(this).apply{setText(search);hint="搜索短信";textSize=15f;setSingleLine();background=null;setPadding(ui.dp(12),ui.dp(14),0,ui.dp(14));setTextColor(Palette.ink);setHintTextColor(Palette.secondary)};searchRow.addView(field,LinearLayout.LayoutParams(0,ui.dp(52),1f));page.addView(searchRow);page.addView(ui.space(12))
        val list=ui.vertical();val scroll=ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(list)};page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        var signature=""
        fun refresh(force:Boolean=false){val messages=synchronized(Engine.messages){Engine.messages.toList()};val key=messages.joinToString("|"){"${it.id}/${it.status}/${it.read}"}+search;if(key==signature&&!force)return;signature=key;list.removeAllViews();val threads=messages.groupBy{it.number}.values.sortedByDescending{it.last().time}.filter{thread->search.isBlank()||thread.any{it.number.contains(search,true)||it.text.contains(search,true)}}
            if(threads.isEmpty()){list.addView(ui.empty(Symbol.MESSAGE,if(search.isBlank())"暂无短信" else "没有匹配的短信"));return}
            threads.forEach{thread->val last=thread.last();val unread=thread.count{it.incoming&&!it.read};val row=ui.row().apply{setPadding(ui.dp(2),ui.dp(17),ui.dp(4),ui.dp(17))};row.addView(avatar(last.number),LinearLayout.LayoutParams(ui.dp(46),ui.dp(46)).apply{marginEnd=ui.dp(14)});val texts=ui.vertical();val top=ui.row();top.addView(ui.text(last.number,16f,weight=unread>0),LinearLayout.LayoutParams(0,-2,1f));top.addView(ui.text(shortTime(last.time),11f,if(unread>0)Palette.accent else Palette.muted));texts.addView(top);texts.addView(ui.space(6));val preview=ui.row();preview.addView(ui.text(last.text.replace('\n',' '),13f,Palette.secondary).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f));if(unread>0)preview.addView(ui.badge(if(unread>99)"99+" else unread.toString(),Palette.accent,Palette.soft),LinearLayout.LayoutParams(-2,-2).apply{marginStart=ui.dp(8)});texts.addView(preview);row.addView(texts,LinearLayout.LayoutParams(0,-2,1f));ui.touch(row,radius=18){peer=last.number;Engine.markThreadRead(peer);go("thread")};list.addView(row);list.addView(ui.divider(60))}
        }
        field.afterChange{search=it;refresh()};updater{refresh()};refresh(true)
    }
    private fun threadPage(){
        val composing=route=="compose";val page=page(if(composing)"新建短信" else peer,back="sms",actions=if(composing)emptyList() else listOf(ui.iconButton(Symbol.MORE,"会话选项"){viewThreadMenu()}),scroll=false)
        val recipient=if(composing)ui.field("收件人",peer,"电话号码",phone=true).let{(group,edit)->page.addView(group);edit.afterChange{peer=it};edit} else null
        val offline=ui.row().apply{setPadding(ui.dp(14),ui.dp(10),ui.dp(14),ui.dp(10));background=ui.shape(Palette.soft,16)};offline.addView(ui.icon(Symbol.INFO,16,Palette.accent));offline.addView(ui.text("未连接",12f,Palette.accent),LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=ui.dp(8)});offline.addView(ui.text("连接",12f,Palette.accent,true));offline.setOnClickListener{go("status")};page.addView(offline);page.addView(ui.space(8))
        val bubbles=ui.vertical();val scroll=ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(bubbles)};page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val composer=ui.row().apply{gravity=Gravity.BOTTOM;setPadding(0,ui.dp(10),0,0)};val draftKey=if(composing)"@new" else peer;val input=EditText(this).apply{hint="短信";setText(drafts[draftKey] ?: "");textSize=16f;setTextColor(Palette.ink);setHintTextColor(Palette.secondary);maxLines=5;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;setPadding(ui.dp(18),ui.dp(14),ui.dp(18),ui.dp(14));background=ui.shape(0xFFEBEEF4.toInt(),26);contentDescription="短信内容"};composer.addView(input,LinearLayout.LayoutParams(0,-2,1f))
        val send=ui.iconButton(Symbol.SEND,"发送短信",Color.WHITE,Palette.accent,48){val target=recipient?.text?.toString()?.trim() ?: peer;if(!target.matches(Regex("\\+?[0-9]{1,20}"))){recipient?.error="号码无效";return@iconButton};if(!connected()){notice("请先连接");return@iconButton};val text=input.text.toString().trim();if(text.isNotEmpty()){Engine.sendSms(target,text);drafts.remove(draftKey);input.setText("");if(composing){peer=target;go("thread")}}};composer.addView(send,LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)).apply{marginStart=ui.dp(10);bottomMargin=ui.dp(2)});page.addView(composer)
        fun updateSend(){send.isEnabled=connected()&&input.text.isNotBlank()&&(!composing||recipient?.text?.isNotBlank()==true);send.alpha=if(send.isEnabled)1f else .38f}
        input.afterChange{drafts[draftKey]=it;updateSend()};recipient?.afterChange{peer=it;updateSend()}
        var signature=""
        updater{offline.visibility=if(connected())View.GONE else View.VISIBLE;updateSend();if(!composing){val messages=synchronized(Engine.messages){Engine.messages.filter{it.number==peer}};val key=messages.joinToString("|"){it.id+it.status};if(key!=signature){signature=key;val nearBottom=scroll.getChildAt(0).height-scroll.height-scroll.scrollY<ui.dp(100);bubbles.removeAllViews();var date="";messages.forEach{message->val day=dayLabel(message.time);if(day!=date){date=day;bubbles.addView(ui.text(day,11f,Palette.muted).apply{gravity=Gravity.CENTER;setPadding(0,ui.dp(16),0,ui.dp(16))},LinearLayout.LayoutParams(-1,-2))};bubbles.addView(messageBubble(message))};if(nearBottom||scroll.scrollY==0)scroll.post{scroll.fullScroll(View.FOCUS_DOWN)};Engine.markThreadRead(peer)}}}
    }
    private fun messageBubble(message:Engine.Message):LinearLayout{
        val wrapper=ui.vertical().apply{gravity=if(message.incoming)Gravity.START else Gravity.END;setPadding(0,ui.dp(3),0,ui.dp(12))}
        val bubble=ui.vertical().apply{setPadding(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(12));background=ui.shape(if(message.incoming)Palette.surface else Palette.hero,20)}
        bubble.addView(ui.text(message.text,16f).apply{maxWidth=(resources.displayMetrics.widthPixels*.68f).toInt();setTextIsSelectable(true)})
        wrapper.addView(bubble,LinearLayout.LayoutParams(-2,-2));val meta=ui.row().apply{setPadding(ui.dp(6),ui.dp(5),ui.dp(6),0)};meta.addView(ui.text(SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(message.time)),10f,Palette.muted))
        if(!message.incoming){val failed=message.status.contains("失败")||message.status.contains("拒绝");val sent=message.status.startsWith("网络已确认")||message.status.startsWith("IMS 已接受");meta.addView(ui.icon(if(failed)Symbol.ALERT else if(sent)Symbol.CHECK else Symbol.CLOCK,12,if(failed)Palette.red else Palette.muted),LinearLayout.LayoutParams(ui.dp(12),ui.dp(12)).apply{marginStart=ui.dp(5)});if(failed)ui.touch(bubble,Palette.hero,20){sheet("发送失败"){box,dialog->box.addView(ui.text(message.status,14f,Palette.secondary));box.addView(ui.space(20));box.addView(ui.action("重试",Symbol.REFRESH){dialog.dismiss();Engine.retrySms(message.id)})}}}
        wrapper.addView(meta,LinearLayout.LayoutParams(-2,-2));return wrapper
    }
    private fun viewThreadMenu(){sheet("会话"){box,dialog->box.addView(ui.setting(Symbol.COPY,"复制号码"){copy("电话号码",peer);dialog.dismiss()});box.addView(ui.setting(Symbol.PHONE,"拨号"){number=peer;dialog.dismiss();go("dial")});box.addView(ui.setting(Symbol.TRASH,"删除会话"){dialog.dismiss();confirm("删除此会话？"){Engine.deleteThread(peer);go("sms")}})}}
    private fun avatar(number:String):FrameLayout{val frame=FrameLayout(this).apply{background=ui.shape(if(number.hashCode() and 1==0)Palette.hero else 0xFFE9E7F8.toInt(),25)};frame.addView(ui.icon(Symbol.MESSAGE,20,if(number.hashCode() and 1==0)Palette.accent else 0xFF66589A.toInt()),FrameLayout.LayoutParams(ui.dp(20),ui.dp(20),Gravity.CENTER));return frame}
    private fun shortTime(time:Long):String{val now=Calendar.getInstance();val then=Calendar.getInstance().apply{timeInMillis=time};return if(now.get(Calendar.YEAR)==then.get(Calendar.YEAR)&&now.get(Calendar.DAY_OF_YEAR)==then.get(Calendar.DAY_OF_YEAR))SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(time)) else SimpleDateFormat("M/d",Locale.getDefault()).format(Date(time))}
    private fun dayLabel(time:Long):String{val now=Calendar.getInstance();val then=Calendar.getInstance().apply{timeInMillis=time};if(now.get(Calendar.YEAR)==then.get(Calendar.YEAR)&&now.get(Calendar.DAY_OF_YEAR)==then.get(Calendar.DAY_OF_YEAR))return "今天";now.add(Calendar.DAY_OF_YEAR,-1);return if(now.get(Calendar.YEAR)==then.get(Calendar.YEAR)&&now.get(Calendar.DAY_OF_YEAR)==then.get(Calendar.DAY_OF_YEAR))"昨天" else SimpleDateFormat("M月d日",Locale.getDefault()).format(Date(time))}
    internal fun EditText.afterChange(action:(String)->Unit){addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){action(s.toString())};override fun afterTextChanged(s:Editable?){} })}
}
