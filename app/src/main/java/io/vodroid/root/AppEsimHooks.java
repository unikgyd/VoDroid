package io.vodroid.root;

import android.app.*;
import android.content.*;
import android.telephony.euicc.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.vodroid.EsimHandoff;
import java.util.Properties;

/** Runs only in applications explicitly selected in the user's LSPosed scope. */
final class AppEsimHooks {
    private static boolean installed;
    static synchronized void install(XC_LoadPackage.LoadPackageParam process)throws Exception{
        if(installed)return;installed=true;
        XposedBridge.hookAllMethods(Application.class,"attach",new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(p.args!=null&&p.args.length>0&&p.args[0] instanceof Context context)ScopedHooks.attach(context);}});
        try{XposedBridge.hookAllMethods(Class.forName("android.app.ApplicationPackageManager",false,process.classLoader),"hasSystemFeature",new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){if(enabled()&&p.args!=null&&p.args.length>0&&"android.hardware.telephony.euicc".equals(p.args[0]))p.setResult(true);}});}catch(ClassNotFoundException ignored){}
        XposedBridge.hookAllMethods(EuiccManager.class,"isEnabled",new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){if(enabled())p.setResult(true);}});
        XposedBridge.hookAllMethods(EuiccManager.class,"getEid",new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){Properties config=ScopedHooks.settings();String eid=config.getProperty("eid","");if(ScopedHooks.enabled(config,"app_compat")&&eid.matches("[0-9]{32}"))p.setResult(eid);}});
        XposedBridge.hookAllMethods(EuiccManager.class,"downloadSubscription",new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p)throws Throwable{
            if(!enabled()||p.args==null||p.args.length!=3||!(p.args[0] instanceof DownloadableSubscription subscription)||!(p.args[1] instanceof Boolean activate)||!(p.args[2] instanceof PendingIntent callback))return;
            p.setResult(null);
            try{Application app=application();app.startActivity(EsimHandoff.fromSubscription(app,subscription,activate,callback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
            catch(Exception e){XposedBridge.log("VoDroid: unable to open eSIM import UI");try{callback.send(EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_ERROR);}catch(PendingIntent.CanceledException ignored){}}
        }});
        XposedBridge.hookAllMethods(Instrumentation.class,"execStartActivity",new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){if(!enabled()||p.args==null)return;for(int i=0;i<p.args.length;i++)if(p.args[i] instanceof Intent original){try{Intent redirected=EsimHandoff.redirect(original);if(redirected!=null)p.args[i]=redirected;}catch(RuntimeException ignored){}}}});
        XposedBridge.log("VoDroid: scoped eSIM app compatibility installed");
    }
    private static boolean enabled(){return ScopedHooks.enabled(ScopedHooks.settings(),"app_compat");}
    private static Application application()throws Exception{return (Application)Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);}
}
