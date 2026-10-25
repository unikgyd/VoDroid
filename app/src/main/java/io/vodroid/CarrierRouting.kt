package io.vodroid

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import io.vodroid.core.CarrierProfile
import io.vodroid.core.SimCard
import io.vodroid.core.IkeTiming

/** Never borrow carrier data from the default data SIM or an unmatched MVNO. */
object CarrierRouting {
    // Compare with the selected card only; never persist or log subscriber IDs.
    @SuppressLint("HardwareIds")
    fun resolve(context: Context, identity: SimCard.Identity): CarrierProfile {
        try {
            val subscriptions=context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
            for(sub in subscriptions) {
                if(sub.mccString!=identity.mcc() || sub.mncString!=identity.mnc())continue
                val phone=context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub.subscriptionId)
                val iccid=try{sub.iccId.orEmpty()}catch(_:SecurityException){""}
                val matched=iccid.isNotEmpty() && iccid==identity.iccid() ||
                    try{phone.subscriberId==identity.imsi()}catch(_:SecurityException){false}
                if(!matched)continue
                val config=context.getSystemService(CarrierConfigManager::class.java).getConfigForSubId(sub.subscriptionId) ?: continue
                val home=config.getString(CarrierConfigManager.Iwlan.KEY_EPDG_STATIC_ADDRESS_STRING).orEmpty()
                val roaming=if(phone.isNetworkRoaming)config.getString(CarrierConfigManager.Iwlan.KEY_EPDG_STATIC_ADDRESS_ROAMING_STRING).orEmpty() else ""
                val groups=config.getIntArray(CarrierConfigManager.Iwlan.KEY_DIFFIE_HELLMAN_GROUPS_INT_ARRAY) ?: intArrayOf()
                val firstSupported=groups.firstOrNull{it==14 || it==2} ?: 14
                Engine.log("已匹配系统运营商配置 · ${identity.mcc()}/${identity.mnc()}")
                val defaults=IkeTiming.DEFAULT
                val timing=IkeTiming.hints(config.getInt(CarrierConfigManager.Iwlan.KEY_IKE_REKEY_SOFT_TIMER_SEC_INT,defaults.ikeSoft()),config.getInt(CarrierConfigManager.Iwlan.KEY_IKE_REKEY_HARD_TIMER_SEC_INT,defaults.ikeHard()),config.getInt(CarrierConfigManager.Iwlan.KEY_CHILD_SA_REKEY_SOFT_TIMER_SEC_INT,defaults.childSoft()),config.getInt(CarrierConfigManager.Iwlan.KEY_CHILD_SA_REKEY_HARD_TIMER_SEC_INT,defaults.childHard()),config.getBoolean(CarrierConfigManager.Iwlan.KEY_ADD_KE_TO_CHILD_SESSION_REKEY_BOOL,false))
                return CarrierProfile("android-carrier",listOf(roaming,home).filter{it.isNotBlank()}.joinToString(","),firstSupported==2,timing)
            }
        } catch(_:SecurityException) { Engine.log("系统运营商配置不可读，使用 3GPP 默认发现") }
          catch(error:RuntimeException) { Engine.log("系统运营商配置不可用（${error.javaClass.simpleName}），使用 3GPP 默认发现") }
        return CarrierProfile.STANDARD
    }
}
