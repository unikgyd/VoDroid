package io.vodroid

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import io.vodroid.core.Bytes
import io.vodroid.core.SimReadProbe
import net.typeblog.lpac_jni.ApduInterface
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

object RootAccess {
    private var bridge: Bridge?=null
    private val cardBridges=mutableSetOf<Bridge>()
    data class CardProbe(val slot:Int,val port:Int,val aid:String,val passed:Boolean,val statusWord:Int=0,val bytes:Int=0,val detail:String="")
    @Volatile var cardProbe:CardProbe?=null
        private set
    @Volatile var status="未授权"
        private set
    private var executable: String?=null
    /** App PATH can omit /product/bin even when adb shell finds Magisk there. */
    @Synchronized fun check(): Boolean {
        status="等待授权";Engine.update()
        val candidates=(listOfNotNull(executable)+listOf("su","/product/bin/su","/system/bin/su","/system/xbin/su","/sbin/su","/debug_ramdisk/su")).distinct()
        for(path in candidates){
            val process=try{ProcessBuilder(path,"-c","id -u").redirectErrorStream(true).start()}catch(_:java.io.IOException){continue}
            if(!process.waitFor(45,TimeUnit.SECONDS)){process.destroy();status="授权超时";Engine.update();return false}
            val granted=process.exitValue()==0&&process.inputStream.bufferedReader().readText().lineSequence().any{it.trim()=="0"}
            if(granted){executable=path;status="已授权";Engine.prefs.edit().putBoolean("root_enabled",true).apply();Engine.log("Root 授权成功");return true}
            status="授权被拒绝";Engine.update();return false
        }
        status="su 不可见";Engine.update();return false
    }
    fun disable(context:Context){
        val cleared=runCatching{connect(context,Engine.prefs.getInt("slot",0),Engine.prefs.getInt("port",0)).request(JSONObject().put("op","disable_integration"))}
        disconnect();executable=null;cardProbe=null
        Engine.prefs.edit().putBoolean("root_enabled",false).putBoolean("system_lpa",false).putBoolean("esim_app_compat",false).putBoolean("carrier_access",false).putBoolean("system_sms_send",false).apply()
        context.packageManager.setComponentEnabledSetting(android.content.ComponentName(context,SystemEuiccService::class.java),android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,android.content.pm.PackageManager.DONT_KILL_APP)
        status=if(cleared.isSuccess)"已停用" else "已停用 · 模块待关闭";Engine.update()
        if(cleared.isFailure)throw IllegalStateException("应用 Root 已停用，未能关闭模块配置；请在 Magisk 停用 VoDroid 模块",cleared.exceptionOrNull())
    }
    fun verifyCard(context:Context,slot:Int,port:Int,aid:String):CardProbe=Engine.cardOperation{
        require(slot in 0..7&&port in 0..7){"卡槽和端口应为 0–7"}
        require(aid.matches(Regex("(?i)([0-9a-f]{2}){5,16}"))){"AID 格式无效"}
        check(VoWifiService.current==null&&Engine.session==null&&Engine.call==null){"请先断开 VoWiFi"}
        val report=try{
            check(context.getSystemService(android.telephony.TelephonyManager::class.java).callState==android.telephony.TelephonyManager.CALL_STATE_IDLE){"通话中，暂不检测"}
            open(context,slot,port).use{card->val result=SimReadProbe.run(card,aid);CardProbe(slot,port,aid.uppercase(),true,result.status(),result.bytes())}
        }catch(error:Exception){CardProbe(slot,port,aid.uppercase(),false,detail=error.message.orEmpty().take(140))}
        cardProbe=report;val target="SIM${slot+1} / 端口 $port";Engine.log(if(report.passed)"Root $target 只读 APDU 通过 · SW=9000 · ${report.bytes} 字节" else "Root $target 读卡未通过 · ${report.detail}");Engine.update();report
    }
    private fun quote(s: String)="'"+s.replace("'","'\\''")+"'"
    @Synchronized private fun connect(context: Context,slot: Int,port: Int): Bridge {
        bridge?.let{if(it.slot==slot&&it.port==port&&it.valid)return it;it.close();bridge=null}
        return launch(context,slot,port).also{bridge=it}
    }
    @Synchronized private fun launch(context:Context,slot:Int,port:Int):Bridge {
        check(Engine.prefs.getBoolean("root_enabled",false)){"请在设置中开启 Root 授权"}
        require(slot in 0..7&&port in 0..7){"卡槽或端口无效"}
        if(executable==null)check(check()){"Root 不可用：$status"}
        val socketName="vodroid-"+UUID.randomUUID().toString();val token=Bytes.hex(io.vodroid.core.Crypto.random(32))
        val command="CLASSPATH=${quote(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin --nice-name=io.vodroid.root io.vodroid.root.CardHelper ${quote(socketName)} ${quote(token)} ${context.applicationInfo.uid} $slot $port ${android.os.Process.myPid()}"
        val process=ProcessBuilder(executable!!,"-c",command).redirectErrorStream(true).start()
        val errors=StringBuilder()
        Thread({runCatching{process.inputStream.bufferedReader().useLines{lines->lines.forEach{line->synchronized(errors){if(errors.length<4000)errors.append(line).append('\n')}}}}},"root-helper-output").apply{isDaemon=true;start()}
        val socket=LocalSocket();val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);var connected=false
        while(System.nanoTime()<deadline&&process.isAlive&&!connected){try{socket.connect(LocalSocketAddress(socketName));connected=true}catch(_: Exception){Thread.sleep(80)}}
        if(!connected){socket.close();process.destroy();val detail=synchronized(errors){errors.lineSequence().firstOrNull{it.contains("Exception")||it.contains("Error")}};error("Root 卡片服务启动失败"+(detail?.let{": $it"} ?: "；请检查授权"))}
        socket.soTimeout=20000
        val result=Bridge(socket,process,token,slot,port)
        try{check(result.request(JSONObject().put("op","hello")).getInt("uid")==0){"Root 服务身份校验失败"};return result}catch(e:Exception){result.close();throw e}
    }
    @Synchronized private fun cardBridge(context:Context,slot:Int,port:Int)=launch(context,slot,port).also{cardBridges.add(it)}
    @Synchronized private fun release(b:Bridge){cardBridges.remove(b);b.close()}
    fun open(context: Context,slot: Int,port: Int): CardAccess {val b=cardBridge(context,slot,port);val apdu=object:ApduInterface{
        override val valid get()=b.valid
        override fun connect(){}
        override fun disconnect(){}
        override fun logicalChannelOpen(aid: ByteArray)=b.request(JSONObject().put("op","open").put("aid",Bytes.hex(aid))).getInt("channel")
        override fun logicalChannelClose(handle: Int){b.request(JSONObject().put("op","close").put("channel",handle))}
        override fun transmit(handle: Int,tx: ByteArray)=Bytes.hex(b.request(JSONObject().put("op","transmit").put("channel",handle).put("data",Bytes.hex(tx))).getString("data"))
    };return CardAccess(apdu){release(b)}}
    fun insertSms(context:Context,number:String,text:String,time:Long,read:Boolean=false,subscription:Int=-1):Long=connect(context,Engine.prefs.getInt("slot",0),Engine.prefs.getInt("port",0)).request(JSONObject().put("op","sms").put("number",number).put("text",text).put("time",time).put("read",read).put("subscription",subscription)).getLong("row")
    fun checkSms(context:Context,number:String,text:String,time:Long):JSONObject=connect(context,Engine.prefs.getInt("slot",0),Engine.prefs.getInt("port",0)).request(JSONObject().put("op","sms_check").put("number",number).put("text",text).put("time",time))
    fun cleanupTestSms(context:Context,nonce:String,time:Long):Int=connect(context,Engine.prefs.getInt("slot",0),Engine.prefs.getInt("port",0)).request(JSONObject().put("op","sms_test_cleanup").put("nonce",nonce).put("time",time)).getInt("deleted")
    fun utilityIdentity(context:Context):JSONObject=connect(context,Engine.prefs.getInt("slot",0),Engine.prefs.getInt("port",0)).request(JSONObject().put("op","hello"))
    fun enableCallAccount(context:Context):Boolean=connect(context,Engine.prefs.getInt("slot",0),Engine.prefs.getInt("port",0)).request(JSONObject().put("op","call_account")).getBoolean("enabled")
    fun configure(context: Context,enabled: Boolean,carrierAccess: Boolean,smsBridge: Boolean=false)=Engine.cardOperation {
        check(VoWifiService.current==null&&Engine.session==null){"请先断开 VoWiFi"}
        val slot=Engine.prefs.getInt("slot",0);val port=Engine.prefs.getInt("port",0)
        if(enabled){
            check(Engine.eid.matches(Regex("[0-9]{32}"))){"请先读取真实 EID"}
            open(context,slot,port).use{card->val lpa=EuiccAccess.open(card,Engine.prefs.getString("isdr","A0000005591010FFFFFFFF8900000100")!!);try{check(lpa.eID==Engine.eid){"所选 Root 卡槽与已读取的 eSIM 不一致"}}finally{lpa.close()}}
        }
        connect(context,slot,port).request(JSONObject().put("op","configure").put("enabled",enabled).put("carrierAccess",carrierAccess).put("smsBridge",smsBridge).put("appCompat",Engine.prefs.getBoolean("esim_app_compat",false)).put("eid",Engine.eid));Unit
    }
    fun scopedConfiguration(context:Context,callerUid:Int):JSONObject=connect(context,Engine.prefs.getInt("slot",0),Engine.prefs.getInt("port",0)).request(JSONObject().put("op","scoped_config").put("callerUid",callerUid))
    @Synchronized fun resetCard(context:Context){check(VoWifiService.current==null&&cardBridges.none{it.valid}){"请先释放读卡连接"};connect(context,Engine.prefs.getInt("slot",0),Engine.prefs.getInt("port",0)).request(JSONObject().put("op","reset_card"))}
    @Synchronized fun disconnect(){bridge?.close();bridge=null;val cards=cardBridges.toList();cardBridges.clear();cards.forEach{it.close()}}
    private class Bridge(val socket: LocalSocket,val process: Process,val token: String,val slot:Int,val port:Int){val input=DataInputStream(socket.inputStream);val output=DataOutputStream(socket.outputStream)
        @Volatile private var closed=false
        val valid get()=!closed&&process.isAlive
        @Synchronized fun request(json: JSONObject): JSONObject{check(valid){"Root 连接已关闭"};val bytes=json.put("token",token).toString().toByteArray();output.writeInt(bytes.size);output.write(bytes);output.flush();val len=input.readInt();check(len in 1..1048576);val data=ByteArray(len);input.readFully(data);val result=JSONObject(String(data));check(result.optBoolean("ok")){result.optString("error","Root 操作失败")};return result}
        @Synchronized fun close(){if(closed)return;runCatching{request(JSONObject().put("op","bye"))};closed=true;runCatching{socket.close()};runCatching{process.waitFor(5,TimeUnit.SECONDS)};process.destroy()}
    }
}
