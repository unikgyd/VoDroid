package io.vodroid

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.vodroid.core.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VoWifiService : Service() {
    private val executor=Executors.newSingleThreadExecutor()
    private val stopped=AtomicBoolean(false)
    private val connecting=AtomicBoolean(false)
    @Volatile private var ike: IkeSession?=null
    fun health():IkeSession.Health?=ike?.health()
    fun requestRekey(ikeSa:Boolean):Boolean {check(Engine.call==null){"通话中不能手动换钥"};return ike?.requestRekey(ikeSa) ?: false}
    private var network: PacketNetwork?=null
    @Volatile private var ownedCard:CardAccess?=null
    companion object { @Volatile var current: VoWifiService?=null }
    private fun foregroundType()=if(android.os.Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    override fun onCreate() { super.onCreate();current=this;notifications();startForeground(100,notification("准备连接"),foregroundType()) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="answer"||intent?.action=="hangup"){if(Engine.call?.id()==intent.getStringExtra("call_id")&&Engine.session!=null){Engine.task{if(Engine.call?.id()==intent.getStringExtra("call_id")){if(intent.action=="answer")Engine.session?.answer() else Engine.session?.hangup()}}}else if(ike==null)stopSelf();return START_NOT_STICKY}
        if(intent?.action=="stop"){shutdown();Engine.state("已断开");stopSelf();return START_NOT_STICKY}
        if(ike==null&&connecting.compareAndSet(false,true))executor.execute { connect() }
        return START_NOT_STICKY
    }
    private fun connect() {
        var stage="SIM 读取"
        try {
            check(Engine.session==null) { "已有活动会话" };Engine.failureStage="";Engine.simReady=false;Engine.tunnelReady=false;Engine.epdg="";Engine.pcscf="";Engine.state("正在读取 SIM")
            val identity=Engine.cardOperation {
                check(!stopped.get()){"连接已取消"}
                Engine.releaseCard()
                val card=Engine.connectCard();ownedCard=card
                val sim=SimCard(card);Engine.sim=sim;sim.connect(Engine.prefs.getString("usim","A0000000871002")!!)
                sim.identity(Engine.prefs.getInt("mnc_length",0)).also { Engine.verifyProfileIdentity(it) }
            }
            val sim=Engine.sim ?: error("SIM 读取已取消")
            Engine.simReady=true;Engine.update()
            Engine.log("USIM 身份读取成功")
            if(stopped.get()){releaseOwnedCard();return}
            val useVpn=Engine.prefs.getBoolean("vpn_backend",false)
            val vpn=if(useVpn)TunnelVpnService.await(this) else null
            stage="ePDG 连接";Engine.state("正在建立 ePDG 隧道")
            val policy=IkePolicy.parse(Engine.prefs.getString("ike_policy","auto"))
            val profile=CarrierRouting.resolve(this,identity)
            val tunnel=IkeSession({ socket -> if(vpn!=null)check(vpn.protect(socket)) { "无法保护 ePDG 套接字" } },Engine::log,policy,profile).also { it.setEapPolicy(EapPolicy.parse(Engine.prefs.getString("eap_policy","auto")));ike=it }
            val info=tunnel.connect(Engine.prefs.getString("epdg","")!!,identity,sim)
            Engine.tunnelReady=true;Engine.epdg=info.epdg();Engine.update()
            if(stopped.get()){tunnel.close();releaseOwnedCard();return}
            val net=PacketNetwork(info.address(),tunnel::sendInner,useVpn).also { network=it }
            if(vpn!=null)vpn.attach(info,net,tunnel)
            Thread({try{tunnel.runDataplane { packet -> if(vpn!=null)vpn.receive(packet) else net.receive(packet) }}catch(e: Exception){if(!stopped.get()){Engine.failureStage="ePDG 链路";Engine.state("隧道断开: ${e.message}");shutdown();stopSelf()}}},"vodroid-ipsec").start()
            stage="IMS 注册"
            val override=Engine.prefs.getString("pcscf","").orEmpty()
            val candidates=(if(override.isBlank())info.pcscf() else override.split(Regex("[,;\\s]+"))).filter{it.isNotBlank()}.distinct().take(8)
            check(candidates.isNotEmpty()){"ePDG 未提供 P-CSCF，请在设置中填写"}
            for((index,pcscf) in candidates.withIndex()){
                check(!stopped.get()){"连接已取消"};Engine.pcscf=pcscf;Engine.update()
                var session:ImsSession?=null
                try{
                    session=ImsSession(ImsSession.Config(pcscf,Engine.prefs.getInt("sip_port",5060),Engine.prefs.getBoolean("sip_tcp",false),Engine.prefs.getString("smsc","")!!,Engine.prefs.getBoolean("ims_ipsec",false),""),net,sim,identity,Engine)
                    Engine.session=session;check(!stopped.get()){"连接已取消"};session.register();break
                }catch(error:Exception){
                    session?.close();if(Engine.session===session)Engine.session=null
                    if(stopped.get() || index==candidates.lastIndex || !ImsSession.retryableRegistrationFailure(error))throw error
                    Engine.log("P-CSCF 暂不可用，尝试备用地址")
                }
            }
            check(!stopped.get()){"连接已取消"};SystemIntegration.register(this)
            getSystemService(NotificationManager::class.java).notify(100,notification("IMS 已注册 · ${if(useVpn)"Android VPN/TUN" else "应用内隧道"}"))
        } catch(e: Exception) { if(!stopped.get()){Engine.failureStage=stage;Engine.state("连接失败 · $stage: ${e.message ?: e.javaClass.simpleName}")};shutdown();stopSelf() } finally {connecting.set(false)}
    }
    fun microphoneActive() { startForeground(100,notification("VoWiFi 通话中"),foregroundType() or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
    private fun shutdown() {
        if(!stopped.compareAndSet(false,true))return
        Engine.simReady=false;Engine.tunnelReady=false;Engine.epdg="";Engine.pcscf=""
        // Close the outer socket immediately to interrupt any pending IKE exchange.
        ike?.close();ike=null
        val session=Engine.session;Engine.session=null
        session?.close();network?.close();network=null
        Engine.audio?.close();Engine.audio=null
        TunnelVpnService.current?.disconnect()
        // Closing a logical channel may wait on a reader or the phone service; keep it off the UI thread.
        Engine.io.execute{releaseOwnedCard()}
        Engine.call=null;Engine.microphoneMuted=false;Engine.update()
    }
    private fun releaseOwnedCard()=Engine.cardOperation{val owned=ownedCard;if(owned!=null&&Engine.card===owned)Engine.closeCard();ownedCard=null}
    override fun onDestroy(){shutdown();executor.shutdownNow();if(current===this)current=null;super.onDestroy();Engine.update()}
    private fun notifications(){getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("vowifi","VoWiFi 连接",NotificationManager.IMPORTANCE_LOW))}
    private fun notification(text: String): Notification {val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE);val stop=PendingIntent.getService(this,1,Intent(this,VoWifiService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);return Notification.Builder(this,"vowifi").setSmallIcon(R.drawable.ic_vodroid).setContentTitle("VoDroid").setContentText(text).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"断开",stop).build()).build()}
}
