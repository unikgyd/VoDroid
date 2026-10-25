package im.angry.openeuicc.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

fun ByteArray.encodeHex() = joinToString("") { "%02X".format(it.toInt() and 255) }
fun String.decodeHex(): ByteArray {
    require(length % 2 == 0 && matches(Regex("[0-9a-fA-F]*")))
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
class UsbPreferences {
    // Raw APDU logging is intentionally disabled: AKA responses contain CK and IK.
    val verboseLoggingFlow = MutableStateFlow(false)
    val forceTpduModeFlow = MutableStateFlow(false)
}
private val preferences = UsbPreferences()
val Context.preferenceRepository: UsbPreferences get() = preferences
