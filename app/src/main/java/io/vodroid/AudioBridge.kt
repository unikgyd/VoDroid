package io.vodroid

import android.content.Context
import android.media.*
import io.vodroid.core.RtpEndpoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class AudioBridge(private val context: Context,private val format: RtpEndpoint.Format,private val send: (ByteArray)->Unit) : AutoCloseable {
    private val running=AtomicBoolean(false)
    private val incoming=ArrayBlockingQueue<ByteArray>(100)
    private var recorder: AudioRecord?=null
    private var track: AudioTrack?=null
    private var encoder: MediaCodec?=null
    private var decoder: MediaCodec?=null
    private val routing=CallAudioRouting(context){focused->runCatching{track?.setVolume(if(focused)1f else 0f)}}
    private val amr=format.name().startsWith("AMR")
    @Volatile private var muted=false
    fun mute(enabled:Boolean){muted=enabled}
    fun speaker(enabled:Boolean)=routing.speaker(enabled)
    fun start() {
        check(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED) { "请授予麦克风权限后通话" }
        check(!running.getAndSet(true)) { "音频已启动" }
        try {
        VoWifiService.current?.microphoneActive()
        routing.start()
        val rate=format.rate();val samples=rate/50
        val inputSize=maxOf(AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT),samples*8)
        recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,inputSize)
        check(recorder!!.state==AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
        track=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(samples*12,AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT))).setTransferMode(AudioTrack.MODE_STREAM).build()
        if(amr){val mime=if(rate==16000)MediaFormat.MIMETYPE_AUDIO_AMR_WB else MediaFormat.MIMETYPE_AUDIO_AMR_NB;val config=MediaFormat.createAudioFormat(mime,rate,1).apply{setInteger(MediaFormat.KEY_BIT_RATE,if(rate==16000)23850 else 12200)};encoder=MediaCodec.createEncoderByType(mime).apply{configure(config,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);start()};decoder=MediaCodec.createDecoderByType(mime).apply{configure(MediaFormat.createAudioFormat(mime,rate,1),null,null,0);start()}}
        recorder!!.startRecording();track!!.setVolume(if(routing.focused)1f else 0f);track!!.play()
        Thread({val pcm=ShortArray(samples);var pts=0L;try{while(running.get()){val n=recorder!!.read(pcm,0,samples,AudioRecord.READ_BLOCKING);if(n<=0)continue;if(muted||!routing.focused)pcm.fill(0,0,n);if(amr){val codec=encoder!!;val index=codec.dequeueInputBuffer(10000);if(index>=0){val b=codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN);b.clear();for(i in 0 until n)b.putShort(pcm[i]);codec.queueInputBuffer(index,0,n*2,pts,0);pts+=n*1000000L/rate};drain(codec){send(it)}}else send(RtpEndpoint.encodeG711(pcm.copyOf(n),format.name()=="PCMA"))}}catch(e: Exception){if(running.get())Engine.log("音频上行失败: ${e.javaClass.simpleName}");close()}},"vodroid-audio-up").start()
        Thread({var pts=0L;try{while(running.get()){val data=incoming.poll(200,java.util.concurrent.TimeUnit.MILLISECONDS);if(amr){val codec=decoder!!;if(data!=null){val index=codec.dequeueInputBuffer(10000);if(index>=0){codec.getInputBuffer(index)!!.apply{clear();put(data)};codec.queueInputBuffer(index,0,data.size,pts,0);pts+=20000}};drain(codec){track?.write(it,0,it.size,AudioTrack.WRITE_BLOCKING)}}else if(data!=null){val pcm=RtpEndpoint.decodeG711(data,format.name()=="PCMA");track?.write(pcm,0,pcm.size,AudioTrack.WRITE_BLOCKING)}}}catch(e: Exception){if(running.get())Engine.log("音频下行失败: ${e.javaClass.simpleName}");close()}},"vodroid-audio-down").start()
        } catch(e:Exception) { close();throw e }
    }
    private fun drain(codec: MediaCodec,consume: (ByteArray)->Unit) {val info=MediaCodec.BufferInfo();while(running.get()){val index=codec.dequeueOutputBuffer(info,0);if(index<0)break;try{if(info.size>0&&info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0){val b=codec.getOutputBuffer(index)!!;b.position(info.offset);b.limit(info.offset+info.size);val data=ByteArray(info.size);b.get(data);consume(data)}}finally{codec.releaseOutputBuffer(index,false)}}}
    fun downlink(data: ByteArray){incoming.offer(data.clone())}
    override fun close(){if(!running.getAndSet(false))return;runCatching{recorder?.stop()};runCatching{track?.stop()};runCatching{recorder?.release()};runCatching{track?.release()};runCatching{encoder?.stop();encoder?.release()};runCatching{decoder?.stop();decoder?.release()};runCatching{routing.close()}}
}
