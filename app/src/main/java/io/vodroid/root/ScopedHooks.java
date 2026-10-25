package io.vodroid.root;

import android.content.pm.PackageInfo;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.*;
import java.util.Properties;

/** Explicitly enabled LSPosed module: grant only VoDroid local carrier API access. */
public final class ScopedHooks implements IXposedHookLoadPackage {
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam process) throws Throwable {
        if("io.vodroid".equals(process.processName)||process.processName!=null&&process.processName.startsWith("io.vodroid:"))return;
        if("com.android.phone".equals(process.processName)&&!process.packageName.equals("com.android.phone"))return;
        if(!process.packageName.equals("com.android.phone")){
            if(!process.packageName.equals("android")&&!process.packageName.equals("io.vodroid")&&!process.packageName.equals("com.android.systemui")){
                try{AppEsimHooks.install(process);}catch(Exception e){XposedBridge.log("VoDroid: eSIM app compatibility unavailable in this process");}
            }
            return;
        }
        try{SmsHooks.install(process);}catch(ClassNotFoundException e){XposedBridge.log("VoDroid: system SMS bridge unavailable on this ROM");}
        try{Class<?> rule=Class.forName("android.telephony.UiccAccessRule",false,process.classLoader);
            XposedBridge.hookAllMethods(rule,"getCarrierPrivilegeStatus",new XC_MethodHook(){
                protected void beforeHookedMethod(MethodHookParam p){
                    if(p.args==null||!enabled(settings(),"carrier_access"))return;
                    for(Object arg:p.args)if(arg instanceof PackageInfo info&&"io.vodroid".equals(info.packageName)){p.setResult(1);return;}
                }
            });
            XposedBridge.log("VoDroid: scoped carrier API hook enabled for io.vodroid");
        }catch(ClassNotFoundException e){XposedBridge.log("VoDroid: carrier API unavailable on this ROM");}
        try{SystemEsimHooks.install(process);}catch(Exception unavailable){XposedBridge.log("VoDroid: system eSIM metadata mapping unavailable on this ROM");}
    }
    private static volatile Properties cached;
    private static volatile long loadedAt;
    private static volatile android.content.Context appContext;
    private static boolean reportedUnavailable;
    static boolean enabled(Properties config,String key){return config.getProperty("root_enabled","true").equals("true")&&config.getProperty(key,"false").equals("true");}
    static void attach(android.content.Context context){appContext=context;cached=null;loadedAt=0;}
    static Properties settings(){
        long now=android.os.SystemClock.uptimeMillis();Properties value=cached;
        if(value!=null&&now-loadedAt<1000)return value;
        synchronized(ScopedHooks.class){
            value=cached;if(value!=null&&now-loadedAt<1000)return value;value=new Properties();boolean loaded=false;
            try(InputStream in=new FileInputStream("/system/etc/vodroid.properties")){value.load(in);loaded=true;}catch(IOException ignored){
                try{
                    android.content.Context context=appContext;
                    if(context==null)context=(android.app.Application)Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                    if(context!=null){android.os.Bundle result=context.getContentResolver().call(android.net.Uri.parse("content://io.vodroid.integration"),"config",null,null);if(result!=null){for(String key:result.keySet()){String item=result.getString(key);if(item!=null)value.setProperty(key,item);}loaded=true;}}
                }catch(Exception unavailable){if(!reportedUnavailable){reportedUnavailable=true;XposedBridge.log("VoDroid: scoped config unavailable: "+unavailable.getClass().getSimpleName());}}
            }
            // Queries can precede Application attachment. Do not retain an empty
            // result and incorrectly report unsupported eSIM during cold startup.
            cached=loaded?value:null;loadedAt=loaded?now:0;return value;
        }
    }
}
