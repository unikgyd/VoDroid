package io.vodroid

import io.vodroid.core.Bytes
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.impl.LocalProfileAssistantImpl
import net.typeblog.lpac_jni.impl.VerifiedHttp

/** Known removable-card management AIDs, matching the bundled OpenEUICC reference. */
object EuiccAccess {
    private val knownAids=listOf(
        "A0000005591010FFFFFFFF8900000100", // GSMA
        "A0000005591010000000008900000300", // eSIM.me
        "A0000005591010FFFFFFFF8900050500", // 5ber
        "A0000005591010FFFFFFFF8900000177", // Xesim
        "A000000559104C696E6B736669656C64", // LinksField
        "A06573746B6D65FFFF4953442D522030", // eSTK.me SE0
        "A06573746B6D65FFFF4953442D522031", // eSTK.me SE1
        "A06573746B6D65FFFFFFFF4953442D52"
    )
    fun open(card:CardAccess,preferred:String):LocalProfileAssistant {
        val failures=mutableListOf<Exception>()
        for(aid in (listOf(preferred.uppercase())+knownAids).distinct()){
            var assistant:LocalProfileAssistant?=null
            try {
                assistant=LocalProfileAssistantImpl(Bytes.hex(aid),card.apdu,VerifiedHttp())
                check(assistant.eID.matches(Regex("[0-9]{32}"))){"卡片未返回有效 EID"}
                Engine.log("eSIM 管理通道已打开"+(if(aid!=knownAids.first())"（兼容 AID）" else ""))
                return assistant
            } catch(e:Exception){runCatching{assistant?.close()};failures.add(e);Engine.log("eSIM AID $aid · ${e.javaClass.simpleName}: ${e.message ?: "无响应数据"}")}
        }
        val denied=failures.firstOrNull{error->generateSequence(error as Throwable){it.cause}.any{it is SecurityException||it.message.orEmpty().contains("SecurityException")||it.message.orEmpty().contains("not allowed")}}
        throw IllegalStateException(if(denied!=null)"eSIM 管理通道未获授权；可选择 Root 卡槽" else "此卡槽未发现可访问的 eSIM 管理通道；请检查卡槽或自定义 AID",denied ?: failures.firstOrNull())
    }
}
