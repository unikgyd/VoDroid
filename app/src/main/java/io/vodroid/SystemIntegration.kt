package io.vodroid

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.*
import io.vodroid.core.ImsSession
import java.util.concurrent.ConcurrentHashMap

object SystemIntegration {
    @Volatile var callAccountState="未启用"
        private set
    fun handle(context: Context)=PhoneAccountHandle(ComponentName(context,CallIntegrationService::class.java),"vodroid")
    fun register(context: Context) {
        if(!Engine.prefs.getBoolean("system_calls",false))return
        runCatching { context.getSystemService(TelecomManager::class.java).registerPhoneAccount(PhoneAccount.builder(handle(context),"VoDroid Wi‑Fi 通话").setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER).setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL)).build());callAccountState="需启用账户";if(Engine.prefs.getBoolean("root_enabled",false)&&RootAccess.enableCallAccount(context))callAccountState="已启用" }.onFailure{callAccountState="需检查账户";Engine.log("系统通话账户不可用: ${it.javaClass.simpleName}")};Engine.update()
    }
    fun call(context: Context,call: ImsSession.Call){
        val connection=CallIntegrationService.connections[call.id()]
        if(connection!=null){if(call.state()=="ended"||call.state().startsWith("failed")){connection.setDisconnected(DisconnectCause(DisconnectCause.LOCAL));connection.destroy();CallIntegrationService.connections.remove(call.id())}else when(call.state()){ "active"->connection.setActive();"ringing"->if(call.incoming())connection.setRinging() else connection.setDialing() };return}
        if(call.incoming()&&call.state()=="ringing"&&Engine.prefs.getBoolean("system_calls",false))runCatching { val extras=Bundle().apply{putString("vodroid_call_id",call.id());putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,Uri.parse(call.number()))};context.getSystemService(TelecomManager::class.java).addNewIncomingCall(handle(context),extras) }.onFailure{Engine.log("系统来电界面不可用，请在应用中接听")}
    }
    fun receivedSms(context:Context,message:Engine.Message){if(!Engine.prefs.getBoolean("system_sms",false)||!message.incoming||message.systemRow>0)return;Engine.io.execute{
        if(!Engine.prefs.getBoolean("system_sms",false)||message.systemRow>0)return@execute
        runCatching{check(Engine.prefs.getBoolean("root_enabled",false)){"请启用 Root"};RootAccess.insertSms(context,message.number,message.text,message.time,message.read,subscription(context))}.onSuccess{Engine.mirrorResult(message.id,it);Engine.log("短信已写入系统收件箱")}.onFailure{Engine.mirrorResult(message.id,0,it.javaClass.simpleName);Engine.log("系统短信写入失败 · ${it.javaClass.simpleName}")}
    }}
    fun syncInbox(context:Context){val pending=synchronized(Engine.messages){Engine.messages.filter{it.incoming&&it.systemRow==0L}};pending.forEach{receivedSms(context,it)}}
    private fun subscription(context:Context):Int=try{context.getSystemService(android.telephony.SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().firstOrNull{it.simSlotIndex==Engine.prefs.getInt("slot",0)}?.subscriptionId ?: -1}catch(_:SecurityException){-1}catch(_:RuntimeException){-1}
}

class CallIntegrationService : ConnectionService() {
    companion object { val connections=ConcurrentHashMap<String,Connection>() }
    private class CallConnection:Connection(){
        init{connectionCapabilities=CAPABILITY_MUTE}
        override fun onAnswer(){Engine.task{Engine.session?.answer()}}
        override fun onReject(){Engine.task{Engine.session?.hangup()}}
        override fun onDisconnect(){Engine.task{Engine.session?.hangup()}}
        override fun onAbort(){onDisconnect()}
        override fun onCallAudioStateChanged(state:CallAudioState?){super.onCallAudioStateChanged(state);state?.let{Engine.muteMicrophone(it.isMuted)}}
    }
    private fun connection(call: ImsSession.Call): Connection {
        val conn=CallConnection()
        conn.setAddress(Uri.parse(if(call.number().startsWith("sip:")||call.number().startsWith("tel:"))call.number() else "tel:${call.number()}"),TelecomManager.PRESENTATION_ALLOWED)
        conn.setAudioModeIsVoip(true);conn.connectionCapabilities=Connection.CAPABILITY_MUTE;conn.setInitializing();connections[call.id()]=conn
        return conn
    }
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?,request: ConnectionRequest?): Connection {val call=Engine.call?.takeIf{it.incoming()&&it.state()=="ringing"&&it.id()==request?.extras?.getString("vodroid_call_id")} ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR));return connection(call).also{it.setRinging()}}
    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?,request: ConnectionRequest?): Connection {
        val conn=CallConnection()
        conn.setAddress(request?.address,TelecomManager.PRESENTATION_ALLOWED);conn.setAudioModeIsVoip(true);conn.setInitializing()
        Engine.task{try{val number=request?.address?.schemeSpecificPart ?: error("无号码");val call=(Engine.session ?: error("IMS 未注册")).dial(number);connections[call.id()]=conn;conn.setDialing()}catch(e:Exception){conn.setDisconnected(DisconnectCause(DisconnectCause.ERROR,e.message));conn.destroy()}}
        return conn
    }
}
