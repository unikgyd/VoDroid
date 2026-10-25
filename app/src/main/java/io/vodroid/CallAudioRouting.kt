package io.vodroid

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper

/** Owns only this application's communication audio request, for one call. */
class CallAudioRouting(context:Context,private val focusChanged:(Boolean)->Unit={}) : AutoCloseable {
    private val manager=context.getSystemService(AudioManager::class.java)
    @Volatile private var request:AudioFocusRequest?=null
    private var previousMode=AudioManager.MODE_NORMAL
    @Volatile var focused=false
        private set
    @Synchronized fun start(){
        check(request==null){"通话音频已启动"}
        previousMode=manager.mode
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(listener@{change->if(request==null)return@listener;focused=change==AudioManager.AUDIOFOCUS_GAIN;focusChanged(focused)},Handler(Looper.getMainLooper())).build()
        if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){manager.abandonAudioFocusRequest(focus);error("其他通话正在占用音频")}
        request=focus;focused=true;manager.mode=AudioManager.MODE_IN_COMMUNICATION
    }
    fun speaker(enabled:Boolean):Boolean {
        val order=if(enabled)listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) else listOf(AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val available=manager.availableCommunicationDevices
        val device=order.firstNotNullOfOrNull{type->available.firstOrNull{it.type==type}} ?: return false
        return manager.setCommunicationDevice(device)
    }
    @Synchronized override fun close(){
        val focus=request ?: return;request=null;focused=false
        try{manager.clearCommunicationDevice()}finally{try{manager.mode=previousMode}finally{manager.abandonAudioFocusRequest(focus)}}
    }
}
