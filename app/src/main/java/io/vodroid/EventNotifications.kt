package io.vodroid

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import io.vodroid.core.ImsSession

object EventNotifications {
    const val SMS_CHANNEL="messages_v2"
    const val CALL_CHANNEL="incoming_calls_v2"
    private fun manager(context:Context)=context.getSystemService(NotificationManager::class.java)
    fun prepare(context:Context){
        val manager=manager(context);val old=manager.getNotificationChannel("events")
        fun channel(id:String,title:String,ring:Boolean){
            if(manager.getNotificationChannel(id)!=null)return
            val blocked=old?.importance==NotificationManager.IMPORTANCE_NONE
            val sound=if(old?.hasUserSetSound()==true)old.sound else if(ring)Settings.System.DEFAULT_RINGTONE_URI else Settings.System.DEFAULT_NOTIFICATION_URI
            manager.createNotificationChannel(NotificationChannel(id,title,if(blocked)NotificationManager.IMPORTANCE_NONE else NotificationManager.IMPORTANCE_HIGH).apply{
                setSound(sound,AudioAttributes.Builder().setUsage(if(ring)AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true);setVibrationPattern(longArrayOf(0,250,150,250));lockscreenVisibility=Notification.VISIBILITY_PRIVATE
            })
        }
        channel(SMS_CHANNEL,"短信",false);channel(CALL_CHANNEL,"来电",true)
    }
    private fun open(context:Context,page:String,thread:String?=null)=PendingIntent.getActivity(context,(page+thread).hashCode(),Intent(context,MainActivity::class.java).setAction("io.vodroid.OPEN_$page").putExtra("page",page).putExtra("thread",thread).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun sms(context:Context,id:String,number:String,text:String){
        runCatching{prepare(context);check(manager(context).areNotificationsEnabled()&&manager(context).getNotificationChannel(SMS_CHANNEL).importance>NotificationManager.IMPORTANCE_NONE){"短信通知未允许"}
            manager(context).notify("sms:$id",300,Notification.Builder(context,SMS_CHANNEL).setSmallIcon(R.drawable.ic_vodroid).setContentTitle(number).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open(context,"sms",number)).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).build())
        }.onFailure{Engine.log("短信通知失败 · ${it.javaClass.simpleName}")}
    }
    fun cancelSms(context:Context,id:String){manager(context).cancel("sms:$id",300)}
    fun call(context:Context,call:ImsSession.Call){
        if(!call.incoming()||call.state()!="ringing"){manager(context).cancel(200);return}
        runCatching{
            prepare(context);check(manager(context).areNotificationsEnabled()&&manager(context).getNotificationChannel(CALL_CHANNEL).importance>NotificationManager.IMPORTANCE_NONE){"来电通知未允许"}
            val show=PendingIntent.getActivity(context,200,Intent(context,IncomingCallActivity::class.java).putExtra("call_id",call.id()),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val answer=PendingIntent.getActivity(context,201,Intent(context,IncomingCallActivity::class.java).putExtra("call_id",call.id()).putExtra("answer",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val decline=PendingIntent.getService(context,202,Intent(context,VoWifiService::class.java).setAction("hangup").putExtra("call_id",call.id()),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val person=Person.Builder().setName(call.number().removePrefix("sip:").substringBefore('@').ifBlank{"VoDroid 来电"}).setImportant(true).build()
            val notification=Notification.Builder(context,CALL_CHANNEL).setSmallIcon(R.drawable.ic_vodroid).setContentTitle("VoDroid 来电").setContentText(person.name).setContentIntent(show).setFullScreenIntent(show,true).setStyle(Notification.CallStyle.forIncomingCall(person,decline,answer)).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_CALL).build()
            notification.flags=notification.flags or Notification.FLAG_INSISTENT;manager(context).notify(200,notification)
        }.onFailure{Engine.log("来电通知失败 · ${it.javaClass.simpleName}")}
    }
}
