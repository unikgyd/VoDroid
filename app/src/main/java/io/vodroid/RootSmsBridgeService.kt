package io.vodroid

import android.app.*
import android.content.Intent
import android.os.*
import android.telephony.SmsManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bound only by the local privileged phone process. No network listener or exported general RPC. */
class RootSmsBridgeService : Service() {
    companion object {
        const val DESCRIPTOR="io.vodroid.RootSmsBridge"
        private val sent=ConcurrentHashMap<String,List<PendingIntent>>()
        private val timer=Executors.newSingleThreadScheduledExecutor()
        fun status(id:String,status:String){val code=when{status.startsWith("网络已确认")->Activity.RESULT_OK;status.startsWith("失败")||status=="网络拒绝"->SmsManager.RESULT_ERROR_GENERIC_FAILURE;else->return};sent.remove(id)?.forEach{runCatching{it.send(code)}}}
    }
    private val binder=object:Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean{
        if(code==INTERFACE_TRANSACTION){reply?.writeString(DESCRIPTOR);return true}
        check(Binder.getCallingUid()==1001||Binder.getCallingUid()==1000) { "Phone process only" }
        data.enforceInterface(DESCRIPTOR)
        val active=Engine.prefs.getBoolean("root_enabled",false)&&Engine.prefs.getBoolean("system_sms_send",false)&&Engine.session?.registered()==true
        if(code==1){reply?.writeNoException();reply?.writeInt(if(active)1 else 0);return true}
        if(code!=2&&code!=3)return super.onTransact(code,data,reply,flags)
        val number=data.readString() ?: ""
        val text=if(code==3)data.createStringArrayList()?.joinToString("") ?: "" else data.readString() ?: ""
        val callbacks=if(code==3)data.createTypedArrayList(PendingIntent.CREATOR)?.filterNotNull() ?: emptyList() else listOfNotNull(if(data.readInt()!=0)PendingIntent.CREATOR.createFromParcel(data) else null)
        if(!active||!number.matches(Regex("\\+?[0-9]{1,20}"))||text.isEmpty()){reply?.writeNoException();reply?.writeInt(0);return true}
        val id=UUID.randomUUID().toString();if(callbacks.isNotEmpty())sent[id]=callbacks
        synchronized(Engine.messages){Engine.messages.add(Engine.Message(id,number,text,false,System.currentTimeMillis(),"系统短信 · 待发送"))};Engine.update()
        Engine.task{try{(Engine.session ?: error("IMS 未注册")).sendSms(id,number,text)}catch(e:Exception){Engine.smsStatus(id,"失败: ${e.message}")}}
        timer.schedule({sent.remove(id)?.forEach{runCatching{it.send(SmsManager.RESULT_ERROR_GENERIC_FAILURE)}}},90,TimeUnit.SECONDS)
        reply?.writeNoException();reply?.writeInt(1);return true
    }}
    override fun onBind(intent:Intent?):IBinder=binder
}
