package io.vodroid

import android.os.Bundle
import android.service.euicc.*
import android.telephony.euicc.DownloadableSubscription
import android.telephony.euicc.EuiccInfo
import net.typeblog.lpac_jni.*
import io.vodroid.core.ActivationCode
import io.vodroid.core.CardTaskQueue

/** Platform LPA backend, enabled only by the optional system module. */
class SystemEuiccService : EuiccService() {
    private fun enabled(slot:Int)=Engine.prefs.getBoolean("root_enabled",false)&&Engine.prefs.getBoolean("system_lpa",false)&&slot==Engine.prefs.getInt("slot",0)
    private fun <T> withLpa(slot: Int,action: (LocalProfileAssistant)->T): T {
        check(enabled(slot)) { "System LPA is disabled or slot is unconfigured" }
        // Serialize with the app's operations; refuse while a VoWiFi channel is active.
        return CardTaskQueue.call(Engine.io,java.util.concurrent.Callable{check(enabled(slot)){"System LPA disabled before operation"};Engine.withLpa(action)},120,java.util.concurrent.TimeUnit.SECONDS)
    }
    private fun result(slot:Int,action:(LocalProfileAssistant)->Unit):Int=try{withLpa(slot,action);RESULT_OK}catch(e:Exception){Engine.log("系统 eSIM 操作失败: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}");RESULT_FIRST_USER}
    override fun onGetEid(slotId:Int):String?=try{
        check(enabled(slotId)){"System LPA is disabled or slot is unconfigured"}
        // The mapped card metadata contains the EID previously read from this
        // card. Identity queries must not compete with an active AKA channel.
        val known=getSystemService(android.telephony.TelephonyManager::class.java).uiccCardsInfo.firstOrNull{it.physicalSlotIndex==slotId&&it.isEuicc}?.eid
        known?.takeIf{it.matches(Regex("[0-9]{32}"))} ?: withLpa(slotId){it.eID}
    }catch(error:Exception){Engine.log("系统 eSIM 身份读取失败：${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName} · 卡槽 ${slotId+1}");null}
    override fun onGetOtaStatus(slotId:Int)=5 // EUICC_OTA_STATUS_UNAVAILABLE
    override fun onStartOtaIfNecessary(slotId:Int,statusChangedCallback:OtaStatusChangedCallback){statusChangedCallback.onOtaStatusChanged(5)}
    override fun onGetDownloadableSubscriptionMetadata(slotId:Int,subscription:DownloadableSubscription,forceDeactivateSim:Boolean)=GetDownloadableSubscriptionMetadataResult(if(enabled(slotId))RESULT_OK else RESULT_FIRST_USER,subscription)
    override fun onGetDefaultDownloadableSubscriptionList(slotId:Int,forceDeactivateSim:Boolean)=GetDefaultDownloadableSubscriptionListResult(if(enabled(slotId))RESULT_OK else RESULT_FIRST_USER,emptyArray())
    override fun onDownloadSubscription(slotId:Int,subscription:DownloadableSubscription,switchAfterDownload:Boolean,forceDeactivateSim:Boolean,resolvedBundle:Bundle?):DownloadSubscriptionResult {
        val code=result(slotId){lpa->val before=lpa.profiles.map{it.iccid}.toSet();val activation=ActivationCode.parse(subscription.encodedActivationCode ?: error("Missing activation code"));val confirmation=resolvedBundle?.getString("android.service.euicc.extra.RESOLUTION_CONFIRMATION_CODE") ?: subscription.confirmationCode;lpa.downloadProfile(ProfileDownloadInput(activation.address(),activation.matchingId().ifBlank{null},null,confirmation)){true};if(switchAfterDownload){val profile=lpa.profiles.firstOrNull{it.iccid !in before} ?: error("Downloaded profile not found");check(lpa.enableProfile(profile.iccid,true))}}
        return DownloadSubscriptionResult(code,0,slotId)
    }
    override fun onGetEuiccProfileInfoList(slotId:Int):GetEuiccProfileInfoListResult=try{withLpa(slotId){lpa->GetEuiccProfileInfoListResult(RESULT_OK,lpa.profiles.map{p->EuiccProfileInfo.Builder(p.iccid).setProfileName(p.name).setNickname(p.nickName.ifBlank{p.name}).setServiceProviderName(p.providerName).setState(if(p.state==LocalProfileInfo.State.Enabled)EuiccProfileInfo.PROFILE_STATE_ENABLED else EuiccProfileInfo.PROFILE_STATE_DISABLED).setProfileClass(when(p.profileClass){ProfileClass.Testing->0;ProfileClass.Provisioning->1;ProfileClass.Operational->2}).build()}.toTypedArray(),true)}}catch(e:Exception){GetEuiccProfileInfoListResult(RESULT_FIRST_USER,emptyArray(),true)}
    override fun onGetEuiccInfo(slotId:Int)=EuiccInfo("VoDroid/libeuicc")
    override fun onDeleteSubscription(slotId:Int,iccid:String)=result(slotId){lpa->check(lpa.profiles.none{it.iccid==iccid&&it.state==LocalProfileInfo.State.Enabled});check(lpa.deleteProfile(iccid))}
    override fun onSwitchToSubscription(slotId:Int,iccid:String?,forceDeactivateSim:Boolean)=result(slotId){lpa->if(iccid==null){lpa.profiles.filter{it.state==LocalProfileInfo.State.Enabled}.forEach{check(lpa.disableProfile(it.iccid,true))}}else check(lpa.enableProfile(iccid,true))}
    override fun onSwitchToSubscriptionWithPort(slotId:Int,portIndex:Int,iccid:String?,forceDeactivateSim:Boolean):Int {if(portIndex!=Engine.prefs.getInt("port",0))return RESULT_FIRST_USER;return onSwitchToSubscription(slotId,iccid,forceDeactivateSim)}
    override fun onUpdateSubscriptionNickname(slotId:Int,iccid:String,nickname:String?)=result(slotId){it.setNickname(iccid,nickname ?: "")}
    override fun onEraseSubscriptions(slotId:Int)=RESULT_FIRST_USER
    override fun onRetainSubscriptionsForFactoryReset(slotId:Int)=RESULT_FIRST_USER
}
