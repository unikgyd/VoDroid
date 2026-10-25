package io.vodroid

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import io.vodroid.core.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Optional system TUN backend. Never started in application-internal mode. */
class TunnelVpnService : VpnService() {
    private var descriptor: ParcelFileDescriptor?=null
    private var output: FileOutputStream?=null
    private var network: PacketNetwork?=null
    companion object {
        @Volatile var current: TunnelVpnService?=null
        fun await(context: Context): TunnelVpnService {
            check(prepare(context)==null) { "请先允许 VoDroid 建立系统 VPN" }
            context.startForegroundService(Intent(context,TunnelVpnService::class.java))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(current==null&&System.nanoTime()<deadline)Thread.sleep(20)
            return current ?: error("VPN 服务启动超时")
        }
    }
    override fun onCreate(){super.onCreate();startForeground(101,Notification.Builder(this,"vowifi").setSmallIcon(R.drawable.ic_vodroid).setContentTitle("VoDroid 系统隧道").setContentText("可在设置切换为应用内隧道以释放 VPN 槽位").build(),if(android.os.Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0);current=this}
    fun attach(info: IkeSession.Tunnel,net: PacketNetwork,ike: IkeSession) {
        network=net
        // Only packets sourced from the assigned IMS address enter this route. App HTTP traffic uses the underlying network.
        val builder=Builder().setSession("VoDroid IMS").setMtu(1280).addAddress(info.address(),32).addAllowedApplication(packageName).setBlocking(true)
        // Cover IMS signaling and negotiated RTP destinations without changing routing for other apps.
        builder.addRoute("0.0.0.0",0)
        descriptor=builder.establish() ?: error("系统未建立 TUN")
        output=FileOutputStream(descriptor!!.fileDescriptor)
        val input=FileInputStream(descriptor!!.fileDescriptor)
        Thread({val buffer=ByteArray(32768);try{while(descriptor!=null){val count=input.read(buffer);if(count<0)break;if(count>0)ike.sendInner(net.transformOutbound(buffer.copyOf(count)))}}catch(e: Exception){if(descriptor!=null)Engine.log("TUN 已停止: ${e.javaClass.simpleName}")}},"vodroid-tun").start()
    }
    fun receive(packet: ByteArray){val net=network ?: return;output?.write(net.transformInbound(packet))}
    fun disconnect(){runCatching { descriptor?.close() };descriptor=null;output=null;network=null;stopSelf()}
    override fun onRevoke(){VoWifiService.current?.let { startService(Intent(this,VoWifiService::class.java).setAction("stop")) };disconnect()}
    override fun onDestroy(){runCatching { descriptor?.close() };descriptor=null;current=null;super.onDestroy()}
}
