package io.vodroid.root;

import android.telephony.UiccCardInfo;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.*;

/** Maps public card metadata before the phone service applies caller permissions. */
final class SystemEsimHooks {
    private static boolean reportedUnavailable;
    static void install(XC_LoadPackage.LoadPackageParam process)throws Exception{
        Class<?> controller=Class.forName("com.android.internal.telephony.uicc.UiccController",false,process.classLoader);
        java.lang.reflect.Constructor<UiccCardInfo> constructor=UiccCardInfo.class.getDeclaredConstructor(boolean.class,int.class,String.class,int.class,boolean.class,boolean.class,List.class);
        constructor.setAccessible(true);
        XposedBridge.hookAllMethods(controller,"getAllUiccCardInfos",new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){
            Properties config=ScopedHooks.settings();if(!enabled(config)||!(p.getResult() instanceof List<?> cards))return;
            try{
                int slot=Integer.parseInt(config.getProperty("slot","-1"));ArrayList<Object> mapped=new ArrayList<>(cards.size());
                for(Object item:cards){
                    if(item instanceof UiccCardInfo card&&card.getPhysicalSlotIndex()==slot&&card.getCardId()>=0)
                        mapped.add(constructor.newInstance(true,card.getCardId(),config.getProperty("eid"),slot,card.isRemovable(),card.isMultipleEnabledProfilesSupported(),new ArrayList<>(card.getPorts())));
                    else mapped.add(item);
                }
                p.setResult(mapped);
            }catch(Exception error){unavailable();}
        }});
        XposedBridge.hookAllMethods(controller,"getCardIdForDefaultEuicc",new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){
            Properties config=ScopedHooks.settings();if(!enabled(config))return;
            try{
                int slot=Integer.parseInt(config.getProperty("slot","-1"));Object infos=controller.getMethod("getAllUiccCardInfos").invoke(p.thisObject);
                if(infos instanceof List<?> cards)for(Object item:cards)if(item instanceof UiccCardInfo card&&card.getPhysicalSlotIndex()==slot&&card.getCardId()>=0){p.setResult(card.getCardId());break;}
            }catch(Exception error){unavailable();}
        }});
        // Some ROMs cannot match removable-card IDs against UiccSlotInfo.
        // Route only the verified, selected public card ID to its physical slot.
        for(String name:new String[]{"com.android.internal.telephony.euicc.EuiccConnector","com.android.internal.telephony.euicc.EuiccController"}){
            Class<?> routing=Class.forName(name,false,process.classLoader);
            XposedBridge.hookAllMethods(routing,name.endsWith("EuiccConnector")?"getSlotIdFromCardId":"getSlotIndexFromCardId",new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){
                Properties config=ScopedHooks.settings();if(!enabled(config)||p.args==null||p.args.length!=1||!(p.args[0] instanceof Integer requested))return;
                try{
                    int slot=Integer.parseInt(config.getProperty("slot","-1"));Object instance=controller.getMethod("getInstance").invoke(null);
                    Object infos=controller.getMethod("getAllUiccCardInfos").invoke(instance);
                    if(infos instanceof List<?> cards)for(Object item:cards)if(item instanceof UiccCardInfo card&&card.getPhysicalSlotIndex()==slot&&card.getCardId()>=0&&card.getCardId()==requested){p.setResult(slot);break;}
                }catch(Exception error){unavailable();}
            }});
        }
        // Do not hook UiccSlot.isEuicc: framework callers cast real modem cards
        // to EuiccCard when it is true. Removable cards keep their modem objects.
        XposedBridge.log("VoDroid: system eSIM metadata mapping installed");
    }
    private static boolean enabled(Properties config){return ScopedHooks.enabled(config,"force_euicc")&&config.getProperty("eid","").matches("[0-9]{32}");}
    private static synchronized void unavailable(){if(!reportedUnavailable){reportedUnavailable=true;XposedBridge.log("VoDroid: system eSIM metadata mapping could not be applied");}}
}
