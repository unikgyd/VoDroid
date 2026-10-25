package io.vodroid

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.se.omapi.Channel
import android.se.omapi.SEService
import android.se.omapi.Session
import im.angry.openeuicc.core.usb.UsbApduInterface
import im.angry.openeuicc.core.usb.UsbCcidContext
import io.vodroid.core.SimCard
import net.typeblog.lpac_jni.ApduInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CardAccess(private val rawApdu: ApduInterface, private val release: () -> Unit) : SimCard.Transport {
    private val lock = Any()
    // LPA and AKA own logical channels; only CardAccess owns the physical transport.
    val apdu: ApduInterface = object: ApduInterface {
        override val valid get()=rawApdu.valid
        override fun connect()=synchronized(lock){ rawApdu.connect() }
        override fun disconnect() {}
        override fun logicalChannelOpen(aid:ByteArray)=synchronized(lock){ rawApdu.logicalChannelOpen(aid) }
        override fun logicalChannelClose(handle:Int)=synchronized(lock){ rawApdu.logicalChannelClose(handle) }
        override fun transmit(handle:Int,tx:ByteArray)=synchronized(lock){ rawApdu.transmit(handle,tx) }
    }
    override fun open(aid: ByteArray): Int = synchronized(lock) { apdu.logicalChannelOpen(aid) }
    override fun transmit(channel: Int, command: ByteArray): ByteArray = synchronized(lock) { apdu.transmit(channel, command) }
    override fun closeChannel(channel: Int) = synchronized(lock) { apdu.logicalChannelClose(channel) }
    override fun close() = synchronized(lock) { try { rawApdu.disconnect() } finally { release() } }
    companion object {
        data class SimReader(val name: String, val present: Boolean)
        fun simReaders(context: Context): List<SimReader> {
            val ready=CountDownLatch(1)
            val service=SEService(context,context.mainExecutor){ready.countDown()}
            try {
                check(ready.await(4,TimeUnit.SECONDS)){"OMAPI 服务不可用"}
                return service.readers.filter{it.name.startsWith("SIM",true)}.map{SimReader(it.name,it.isSecureElementPresent)}
            } finally { service.shutdown() }
        }
        fun usb(context: Context, device: UsbDevice): CardAccess {
            val manager = context.getSystemService(UsbManager::class.java)
            check(manager.hasPermission(device)) { "请先授权 USB 读卡器" }
            val usbInterface = (0 until device.interfaceCount).map(device::getInterface).firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CSCID } ?: error("设备没有 CCID 接口")
            val ccid = UsbCcidContext.createFromUsbDevice(context, device, usbInterface) ?: error("无法打开 USB 读卡器")
            ccid.allowDisconnect = false
            val apdu = UsbApduInterface(ccid)
            try { apdu.connect() } catch (e: Exception) { ccid.allowDisconnect=true;ccid.disconnect(); throw e }
            return CardAccess(apdu) { ccid.allowDisconnect=true;ccid.disconnect() }
        }
        fun omapi(context: Context, name: String): CardAccess {
            val ready = CountDownLatch(1)
            val service = SEService(context, context.mainExecutor) { ready.countDown() }
            try {
                check(ready.await(10, TimeUnit.SECONDS)) { "OMAPI 服务连接超时" }
                val reader = service.readers.firstOrNull { it.name == name } ?: error("找不到 OMAPI Reader: $name")
                check(reader.isSecureElementPresent){"$name 未检测到卡片"}
                val session = reader.openSession()
                return CardAccess(Omapi(session)) { session.close(); service.shutdown() }
            } catch (e: Exception) { service.shutdown(); throw e }
        }
    }
    private class Omapi(private val session: Session) : ApduInterface {
        private val channels = mutableMapOf<Int, Channel>()
        private val sequence = AtomicInteger()
        override val valid get() = !session.isClosed
        override fun connect() {}
        override fun disconnect() { channels.values.forEach { runCatching { it.close() } }; channels.clear(); session.close() }
        override fun logicalChannelOpen(aid: ByteArray): Int { val channel = session.openLogicalChannel(aid) ?: error("卡片拒绝逻辑通道；请检查 ARA-M 授权或选择 Root 访问") ; val id = sequence.incrementAndGet(); channels[id] = channel; return id }
        override fun logicalChannelClose(handle: Int) { channels.remove(handle)?.close() }
        override fun transmit(handle: Int, tx: ByteArray) = (channels[handle] ?: error("逻辑通道已关闭")).transmit(tx)
    }
}
