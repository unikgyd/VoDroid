package io.vodroid

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import io.vodroid.core.*
import net.typeblog.lpac_jni.*
import net.typeblog.lpac_jni.impl.LocalProfileAssistantImpl
import net.typeblog.lpac_jni.impl.VerifiedHttp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

class VoDroidApp : Application() { override fun onCreate() { super.onCreate(); Engine.init(this) } }

object Engine : ImsSession.Listener {
    lateinit var context: Context
    val io = Executors.newSingleThreadExecutor()
    val observers = CopyOnWriteArraySet<() -> Unit>()
    val logs = ArrayDeque<String>()
    val messages = mutableListOf<Message>()
    data class Message @JvmOverloads constructor(val id: String, val number: String, val text: String, val incoming: Boolean, val time: Long, var status: String, var read: Boolean = !incoming,var systemRow:Long=0,var systemError:String="")
    @Volatile var phase = "未连接"
    @Volatile var call: ImsSession.Call? = null
    @Volatile var session: ImsSession? = null
    @Volatile var card: CardAccess? = null
    @Volatile var sim: SimCard? = null
    @Volatile var profiles: List<LocalProfileInfo> = emptyList()
    @Volatile var eid = ""
    @Volatile var busy = false
    @Volatile var simReady = false
    @Volatile var failureStage = ""
    @Volatile var tunnelReady = false
    @Volatile var epdg = ""
    @Volatile var pcscf = ""
    @Volatile var microphoneMuted = false
    fun muteMicrophone(muted:Boolean){microphoneMuted=muted;audio?.mute(muted);update()}
    private var lpa: LocalProfileAssistant? = null
    private val cardOperationLock=Any()
    fun <T> cardOperation(action:()->T):T=synchronized(cardOperationLock){action()}
    var audio: AudioBridge? = null
    val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    @Synchronized fun init(context: Context) { if(this::context.isInitialized)return;this.context = context.applicationContext; loadMessages();SystemIntegration.syncInbox(this.context) }
    fun update() { context.mainExecutor.execute { observers.forEach { it() } } }
    @Synchronized fun log(text: String) { logs.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}  $text"); while(logs.size>160)logs.removeFirst(); update() }
    fun task(action: () -> Unit) { io.execute { busy=true;update();try { action() } catch(e: Exception) { log(e.message ?: e.javaClass.simpleName) } finally { busy=false;update() } } }
    fun connectCard(): CardAccess = cardOperation {
        card?.let { return@cardOperation it }
        val type = prefs.getString("card_type", "omapi")
        val connected = when(type) {
            "omapi" -> CardAccess.omapi(context, prefs.getString("reader", "SIM1")!!)
            "root" -> RootAccess.open(context, prefs.getInt("slot", 0), prefs.getInt("port", 0))
            else -> { val manager=context.getSystemService(UsbManager::class.java); val name=prefs.getString("usb", ""); val device=manager.deviceList.values.firstOrNull { it.deviceName==name } ?: error("请选择并授权 USB 读卡器");CardAccess.usb(context,device) }
        }
        card=connected; log("卡片通道已打开");connected
    }
    private fun profileHash(iccid:String)=Bytes.hex(Crypto.digest("SHA-256",iccid.toByteArray(Charsets.US_ASCII)))
    fun verifyProfileIdentity(identity:SimCard.Identity) {
        if(prefs.getString("profile_identity","")!=profileHash(identity.iccid())){
            prefs.edit().remove("profile_label").remove("profile_identity").apply();profiles=emptyList()
        }
    }
    fun <T> withLpa(action:(LocalProfileAssistant)->T):T=cardOperation {
        check(session==null&&VoWifiService.current==null){"请先断开 VoWiFi 再管理 eSIM"}
        val wasBusy=busy;busy=true;update()
        try {
            val assistant=EuiccAccess.open(connectCard(),prefs.getString("isdr","A0000005591010FFFFFFFF8900000100")!!)
            lpa=assistant;action(assistant)
        }finally{releaseCard();busy=wasBusy;update()}
    }
    fun refreshProfiles() {
        val snapshot=withLpa { it.eID to it.profiles }
        eid=snapshot.first;profiles=snapshot.second
        val active=profiles.firstOrNull{it.state==LocalProfileInfo.State.Enabled}
        prefs.edit().putString("profile_identity",active?.let{profileHash(it.iccid)} ?: "").putString("profile_label",active?.let{it.nickName.ifBlank{it.name.ifBlank{it.providerName}}} ?: "").apply()
        log("已读取 ${profiles.size} 个 eSIM 配置");update() }
    fun profileAction(profile: LocalProfileInfo, operation: String, nickname: String="") {
        withLpa { assistant->when(operation) { "enable"->check(assistant.enableProfile(profile.iccid,false));"disable"->check(assistant.disableProfile(profile.iccid,false));"delete"->check(assistant.deleteProfile(profile.iccid));"rename"->assistant.setNickname(profile.iccid,nickname);else->error("未知的 eSIM 操作") } }
        log("eSIM 操作完成"); refreshProfiles()
    }
    fun download(code: String, confirmation: String) { val activation=ActivationCode.parse(code);withLpa { assistant->assistant.downloadProfile(ProfileDownloadInput(activation.address(),activation.matchingId().ifEmpty{null},null,confirmation.ifEmpty{null})) { log("eSIM 下载: $it");true } };refreshProfiles() }
    fun releaseCard()=cardOperation {runCatching { lpa?.close() };lpa=null;runCatching { sim?.close() };sim=null;runCatching { card?.close() };card=null}
    fun closeCard()=cardOperation {releaseCard();profiles=emptyList();eid=""}
    fun sendSms(number: String, text: String) { val msg=Message(UUID.randomUUID().toString(),number,text,false,System.currentTimeMillis(),"待发送");synchronized(messages){messages.add(msg);saveMessages()};update();task { (session ?: error("请先连接 VoWiFi")).sendSms(msg.id,number,text) } }
    fun markThreadRead(number: String) { io.execute { synchronized(messages) { var changed=false;messages.filter { it.number==number&&!it.read }.forEach { it.read=true;changed=true };if(changed){saveMessages();update()} } } }
    fun deleteThread(number: String) { task { synchronized(messages){messages.removeAll { it.number==number };saveMessages()};update() } }
    fun retrySms(id: String) { task { val message=synchronized(messages){messages.firstOrNull { it.id==id }} ?: return@task;(session ?: error("请先连接")).sendSms(id,message.number,message.text) } }
    override fun state(state: String) { phase=state;log(state) }
    override fun event(event:String){log(event)}
    override fun sms(from:String,text:String)=sms(UUID.randomUUID().toString(),from,text)
    override fun sms(id:String,from:String,text:String){
        val message:Message;var fresh=false
        synchronized(messages){message=messages.firstOrNull{it.id==id} ?: Message(id,from,text,true,System.currentTimeMillis(),"已接收").also{m->messages.add(m);try{saveMessages();fresh=true}catch(error:Exception){messages.remove(m);throw error}}}
        SystemIntegration.receivedSms(context,message)
        if(fresh)EventNotifications.sms(context,message.id,from,text)
        update()
    }
    fun mirrorResult(id:String,row:Long,error:String=""){synchronized(messages){messages.firstOrNull{it.id==id}?.let{it.systemRow=row;it.systemError=error.take(160);saveMessages()}};update()}
    fun removeLocalMessage(id:String){synchronized(messages){messages.removeAll{it.id==id};saveMessages()};update()}
    override fun smsStatus(id: String, status: String) { synchronized(messages){messages.firstOrNull { it.id==id }?.status=status;saveMessages()};RootSmsBridgeService.status(id,status);update() }
    override fun call(call: ImsSession.Call) { this.call=if(call.state()=="ended"||call.state().startsWith("failed"))null else call;log("通话: ${call.state()}");SystemIntegration.call(context,call);EventNotifications.call(context,call);if(call.state()=="active")startAudio() else if(call.state()=="ended"||call.state().startsWith("failed")) { audio?.close();audio=null;microphoneMuted=false };update() }
    override fun audio(encoded: ByteArray, format: RtpEndpoint.Format) { audio?.downlink(encoded) }
    fun startAudio() { val active=session ?: return;val format=active.format() ?: return;if(audio!=null)return;try { audio=AudioBridge(context,format) { frame -> active.sendAudio(frame) }.also { it.mute(microphoneMuted);it.start() } } catch(e: Exception) { log("音频启动失败: ${e.message}") } }
    private fun loadMessages() { runCatching { val file=java.io.File(context.filesDir,"messages.json");if(!file.exists())return;val a=JSONArray(file.readText());for(i in 0 until a.length()){val m=a.getJSONObject(i);messages.add(Message(m.getString("id"),m.getString("number"),m.getString("text"),m.getBoolean("incoming"),m.getLong("time"),m.getString("status"),m.optBoolean("read",true),m.optLong("systemRow",0),m.optString("systemError",""))) } } }
    private fun saveMessages() { val a=JSONArray();messages.takeLast(2000).forEach { m->a.put(JSONObject().put("id",m.id).put("number",m.number).put("text",m.text).put("incoming",m.incoming).put("time",m.time).put("status",m.status).put("read",m.read).put("systemRow",m.systemRow).put("systemError",m.systemError)) };val file=java.io.File(context.filesDir,"messages.json");val temp=java.io.File(context.filesDir,"messages.tmp");temp.writeText(a.toString());check(temp.renameTo(file)) }
}
