package io.vodroid.root;

import android.app.*;
import android.content.*;
import android.os.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.*;
import java.util.Properties;
import java.util.ArrayList;
import java.util.List;

/** Sends system SMS through a ready IMS session only when explicitly selected by the user. */
final class SmsHooks {
    private static volatile IBinder service;
    private static boolean binding;
    private static final String DESCRIPTOR="io.vodroid.RootSmsBridge";
    static void install(XC_LoadPackage.LoadPackageParam process) throws Exception {
        Class<?> controller=Class.forName("com.android.internal.telephony.SmsDispatchersController",false,process.classLoader);
        XposedBridge.hookAllMethods(controller,"sendText",new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p)throws Throwable{
            Properties config=new Properties();try(InputStream in=new FileInputStream("/system/etc/vodroid.properties")){config.load(in);}catch(IOException e){return;}
            if(!ScopedHooks.enabled(config,"sms_bridge")||!selectedSlot(p.thisObject,config))return;
            // Only the verified leading argument layout is handled; unknown OEM overloads use their normal path.
            if(p.args==null||p.args.length<5||!(p.args[0] instanceof String)||!(p.args[2] instanceof String)||p.args[3]!=null&&!(p.args[3] instanceof PendingIntent)||p.args[4]!=null&&!(p.args[4] instanceof PendingIntent))return;
            if(service==null||!service.isBinderAlive()){bind();return;}
            // A delivery report request uses the platform path until TP-STATUS-REPORT bridging is implemented.
            if(p.args[4]!=null)return;
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(DESCRIPTOR);data.writeString((String)p.args[0]);data.writeString((String)p.args[2]);PendingIntent sent=(PendingIntent)p.args[3];data.writeInt(sent==null?0:1);if(sent!=null)sent.writeToParcel(data,0);if(service.transact(2,data,reply,0)){reply.readException();if(reply.readInt()==1)p.setResult(null);}}catch(RemoteException e){service=null;}finally{data.recycle();reply.recycle();}
        }});
        XposedBridge.hookAllMethods(controller,"sendMultipartText",new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p)throws Throwable{
            Properties config=new Properties();try(InputStream in=new FileInputStream("/system/etc/vodroid.properties")){config.load(in);}catch(IOException e){return;}
            if(!ScopedHooks.enabled(config,"sms_bridge")||!selectedSlot(p.thisObject,config)||p.args==null||p.args.length<5||!(p.args[0] instanceof String)||!(p.args[2] instanceof ArrayList<?> parts))return;
            if(parts.isEmpty()||parts.stream().anyMatch(v->!(v instanceof String)))return;
            if(p.args[4] instanceof List<?> delivery&&delivery.stream().anyMatch(v->v!=null))return;
            if(p.args[3]!=null&&!(p.args[3] instanceof ArrayList<?>))return;
            if(service==null||!service.isBinderAlive()){bind();return;}
            ArrayList<String> texts=new ArrayList<>();for(Object part:parts)texts.add((String)part);ArrayList<PendingIntent> sent=new ArrayList<>();if(p.args[3] instanceof ArrayList<?> callbacks){for(Object value:callbacks){if(value!=null&&!(value instanceof PendingIntent))return;sent.add((PendingIntent)value);}}
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(DESCRIPTOR);data.writeString((String)p.args[0]);data.writeStringList(texts);data.writeTypedList(sent);if(service.transact(3,data,reply,0)){reply.readException();if(reply.readInt()==1)p.setResult(null);}}catch(RemoteException e){service=null;}finally{data.recycle();reply.recycle();}
        }});
        // Bind after the phone application is ready, before the first outbound SMS.
        new Handler(Looper.getMainLooper()).postDelayed(SmsHooks::bind,10000);
    }
    private static synchronized void bind(){if(binding||service!=null)return;try{Application app=(Application)Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);if(app==null)return;binding=app.bindService(new Intent().setComponent(new ComponentName("io.vodroid","io.vodroid.RootSmsBridgeService")),new ServiceConnection(){public void onServiceConnected(ComponentName name,IBinder binder){service=binder;binding=false;}public void onServiceDisconnected(ComponentName name){service=null;binding=false;}},Context.BIND_AUTO_CREATE);}catch(Exception e){binding=false;}}
    private static boolean selectedSlot(Object controller,Properties config){try{Class<?> clazz=controller.getClass();java.lang.reflect.Field field=null;while(clazz!=null&&field==null){try{field=clazz.getDeclaredField("mPhone");}catch(NoSuchFieldException e){clazz=clazz.getSuperclass();}}if(field==null)return false;field.setAccessible(true);Object phone=field.get(controller);int phoneId=(int)phone.getClass().getMethod("getPhoneId").invoke(phone);Class<?> uicc=Class.forName("com.android.internal.telephony.uicc.UiccController",false,controller.getClass().getClassLoader());Object instance=uicc.getMethod("getInstance").invoke(null);int physical=(int)uicc.getMethod("getSlotIdFromPhoneId",int.class).invoke(instance,phoneId);return physical==Integer.parseInt(config.getProperty("slot","-1"));}catch(Exception e){return false;}}
}
