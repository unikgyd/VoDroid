package io.vodroid;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Emulator fixtures and explicitly selected, non-destructive physical-device diagnostics. */
public final class SmokeInstrumentation extends Instrumentation {
    private boolean testTun;
    private Activity screen;
    private String layout="";
    private String cardProbe="";
    private String networkProbe="";
    private boolean audioProbe;
    private boolean liveProbe,telecomProbe;
    private boolean deviceUi;
    private String configureCard="";
    private String integrationProbe="";
    private boolean nativeLpaProbe;
    private boolean recoverCard,cardLifecycle,rootOwnerProbe;
    private boolean registrationProbe,compatibilityUi;
    private boolean rootAudit,rekeyProbe;
    public void onCreate(Bundle args){super.onCreate(args);rekeyProbe=args!=null&&"yes".equals(args.getString("rekey_probe"));testTun=args!=null&&"yes".equals(args.getString("tun"));layout=args==null?"":args.getString("layout","");cardProbe=args==null?"":args.getString("card_probe","");networkProbe=args==null?"":args.getString("network_probe","");audioProbe=args!=null&&"yes".equals(args.getString("audio_probe"));liveProbe=args!=null&&"yes".equals(args.getString("live_probe"));telecomProbe=args!=null&&"yes".equals(args.getString("telecom_probe"));deviceUi=args!=null&&"yes".equals(args.getString("device_ui"));configureCard=args==null?"":args.getString("configure_card","");integrationProbe=args==null?"":args.getString("integration_probe","");nativeLpaProbe=args!=null&&"yes".equals(args.getString("native_lpa_probe"));recoverCard=args!=null&&"yes".equals(args.getString("recover_card"));cardLifecycle=args!=null&&"yes".equals(args.getString("card_lifecycle"));rootOwnerProbe=args!=null&&"yes".equals(args.getString("root_owner_probe"));registrationProbe=args!=null&&"yes".equals(args.getString("registration_probe"));compatibilityUi=args!=null&&"yes".equals(args.getString("compatibility_ui"));rootAudit=args!=null&&"yes".equals(args.getString("root_audit"));start();}
    public void onStart(){Bundle result=new Bundle();Activity activity=null;try{
        if(layout.equals("events-inspect")){eventsInspect(result);finish(Activity.RESULT_OK,result);return;}
        if(layout.equals("events-receipt")){eventsReceipt(result);finish(Activity.RESULT_OK,result);return;}
        if(layout.equals("enhancements")){enhancementsUi(result);finish(Activity.RESULT_OK,result);return;}
        if(layout.equals("root-read-check")){rootReadCheck(result);finish(Activity.RESULT_OK,result);return;}
        if(layout.equals("home")){homeUi(result);finish(Activity.RESULT_OK,result);return;}
        if(layout.equals("donation")){donationUi(result);finish(Activity.RESULT_OK,result);return;}
        if(layout.equals("publication")){publicationUi(result);finish(Activity.RESULT_OK,result);return;}
        if(layout.equals("events-connect")){require(Engine.INSTANCE.getCall()==null&&getTargetContext().getSystemService(android.telephony.TelephonyManager.class).getCallState()==0,"Active call");getTargetContext().startForegroundService(new Intent(getTargetContext(),VoWifiService.class));long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(100);while(System.nanoTime()<deadline&&(Engine.INSTANCE.getSession()==null||!Engine.INSTANCE.getSession().registered()))Thread.sleep(150);require(Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered(),"Registration failed: "+Engine.INSTANCE.getPhase());SystemIntegration.INSTANCE.register(getTargetContext());android.telecom.PhoneAccount account=inspectOwnedAccount(getTargetContext().getSystemService(android.telecom.TelecomManager.class),SystemIntegration.INSTANCE.handle(getTargetContext()));require(account!=null&&account.isEnabled(),"Owned Telecom account is disabled: "+SystemIntegration.INSTANCE.getCallAccountState());result.putString("stream","IMS restored; owned Telecom account enabled; no call/SMS sent.\n");finish(Activity.RESULT_OK,result);return;}
        if(layout.equals("events-check")){eventsCheck(result);finish(Activity.RESULT_OK,result);return;}
        if(layout.equals("events-write-probe")){String nonce=UUID.randomUUID().toString().replace("-",""),number="+9990000401",text="VoDroid 本机收件验证 · "+nonce;long time=System.currentTimeMillis();try{long row=RootAccess.INSTANCE.insertSms(getTargetContext(),number,text,time,false,-1);result.putString("stream","SMS store probe row="+row+"\n");}catch(Exception error){result.putString("stream","SMS fixture probe failure: "+error.getClass().getSimpleName()+": "+error.getMessage()+"\n");}finally{try{RootAccess.INSTANCE.cleanupTestSms(getTargetContext(),nonce,time);}catch(Exception cleanup){result.putString("stream",result.getString("stream","")+"Cleanup: "+cleanup.getMessage()+"\n");}}finish(Activity.RESULT_OK,result);return;}
        if(layout.equals("events-stop")){require(Engine.INSTANCE.getCall()==null&&getTargetContext().getSystemService(android.telephony.TelephonyManager.class).getCallState()==0,"Active call; do not update");boolean wasRegistered=Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered();getTargetContext().startService(new Intent(getTargetContext(),VoWifiService.class).setAction("stop"));long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<deadline&&VoWifiService.Companion.getCurrent()!=null)Thread.sleep(100);require(VoWifiService.Companion.getCurrent()==null,"Service did not stop");result.putString("stream","Idle IMS stopped for authorized app update; previouslyRegistered="+wasRegistered+"; no active call.\n");finish(Activity.RESULT_OK,result);return;}

        if(rootAudit){rootAudit(result);finish(Activity.RESULT_OK,result);return;}
        if(rootOwnerProbe){
            require(VoWifiService.Companion.getCurrent()==null,"Disconnect VoWiFi before owner diagnostics");
            require(RootAccess.INSTANCE.check(),"Root not authorized");
            org.json.JSONObject config=RootAccess.INSTANCE.scopedConfiguration(getTargetContext(),android.os.Process.myUid());
            require(config.optString("eid").matches("[0-9]{32}"),"Verified EID configuration unavailable");
            result.putString("stream","Authenticated Root helper retained for owner-exit diagnostic; no card channels opened.\n");finish(Activity.RESULT_OK,result);return;
        }
        if(recoverCard){
            require(VoWifiService.Companion.getCurrent()==null&&Engine.INSTANCE.getCall()==null,"Disconnect VoWiFi before card recovery");
            require(RootAccess.INSTANCE.check(),"Root not authorized");Engine.INSTANCE.closeCard();RootAccess.INSTANCE.disconnect();
            try{RootAccess.INSTANCE.resetCard(getTargetContext());}finally{RootAccess.INSTANCE.disconnect();}
            result.putString("stream","Selected SIM slot power-cycled; no profile switch, download or deletion performed.\n");finish(Activity.RESULT_OK,result);return;
        }
        if(cardLifecycle){
            require(VoWifiService.Companion.getCurrent()==null,"Disconnect VoWiFi before card lifecycle diagnostics");
            Engine.INSTANCE.refreshProfiles();String eid=Engine.INSTANCE.getEid();int profiles=Engine.INSTANCE.getProfiles().size();
            require(eid.matches("[0-9]{32}")&&profiles>0,"Real eUICC snapshot missing");
            require(Engine.INSTANCE.getCard()==null&&Engine.INSTANCE.getSim()==null,"Snapshot retained card transport");
            CardAccess independent=CardAccess.Companion.omapi(getTargetContext(),Engine.INSTANCE.getPrefs().getString("reader","SIM2"));
            try{net.typeblog.lpac_jni.LocalProfileAssistant assistant=EuiccAccess.INSTANCE.open(independent,Engine.INSTANCE.getPrefs().getString("isdr","A0000005591010FFFFFFFF8900000100"));try{require(assistant.getEID().equals(eid)&&assistant.getProfiles().size()==profiles,"Independent reader snapshot differs");}finally{assistant.close();}}finally{independent.close();}
            boolean expected=false;try{Engine.INSTANCE.withLpa(assistant->{throw new IllegalStateException("Intentional diagnostic failure");});}catch(IllegalStateException failure){expected="Intentional diagnostic failure".equals(failure.getMessage());}
            require(expected&&Engine.INSTANCE.getCard()==null&&Engine.INSTANCE.getSim()==null,"Failure retained card resources");
            Engine.INSTANCE.refreshProfiles();require(Engine.INSTANCE.getEid().equals(eid)&&Engine.INSTANCE.getProfiles().size()==profiles&&Engine.INSTANCE.getCard()==null,"Reopen after failure failed");
            result.putString("stream","Card lifecycle passed: real EID and "+profiles+" profiles; transport released after success and failure; independent OMAPI reader and repeated reopen passed. No SIM writes.\n");finish(Activity.RESULT_OK,result);return;
        }
        if(nativeLpaProbe){
            StringBuilder report=new StringBuilder("Native LPA uid="+android.os.Process.myUid()+"\n");
            android.telephony.TelephonyManager telephony=getTargetContext().getSystemService(android.telephony.TelephonyManager.class);
            report.append("feature=").append(getTargetContext().getPackageManager().hasSystemFeature("android.hardware.telephony.euicc")).append(" defaultCardId=").append(telephony.getCardIdForDefaultEuicc()).append('\n');
            for(android.telephony.UiccCardInfo card:telephony.getUiccCardsInfo()){
                report.append("slot=").append(card.getPhysicalSlotIndex()).append(" euicc=").append(card.isEuicc()).append(" cardId=").append(card.getCardId()).append(" EIDdigits=").append(card.getEid()==null?0:card.getEid().length()).append('\n');
                if(card.getPhysicalSlotIndex()==Engine.INSTANCE.getPrefs().getInt("slot",0)){
                    android.telephony.euicc.EuiccManager manager=getTargetContext().getSystemService(android.telephony.euicc.EuiccManager.class).createForCardId(card.getCardId());
                    report.append("selectedEnabled=").append(manager.isEnabled()).append('\n');
                    if(!Engine.INSTANCE.getPrefs().getBoolean("system_lpa",false)){report.append("selectedBackend=disabled; no backend request\n");continue;}
                    String eid=manager.getEid();report.append("selectedEidDigits=").append(eid==null?0:eid.length()).append('\n');require(eid!=null&&eid.matches("[0-9]{32}"),"Native LPA did not return real EID");
                    try{android.telephony.euicc.EuiccInfo info=manager.getEuiccInfo();report.append("selectedBackend=").append(info==null?"null":info.getOsVersion()).append('\n');}catch(Exception error){report.append("selectedBackendError=").append(error.getClass().getSimpleName()).append('\n');}
                }
            }
            result.putString("stream",report.toString());finish(Activity.RESULT_OK,result);return;
        }
        if(liveProbe||telecomProbe){if(telecomProbe)telecomProbe(result);else result.putString("stream","Live process uid="+android.os.Process.myUid()+" registered="+(Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered())+" activeCall="+(Engine.INSTANCE.getCall()!=null)+" foreground="+(VoWifiService.Companion.getCurrent()!=null)+" phase="+Engine.INSTANCE.getPhase()+"\n");finish(Activity.RESULT_OK,result);return;}
        if(!cardProbe.isEmpty()){cardProbe(result);finish(Activity.RESULT_OK,result);return;}
        if(!networkProbe.isEmpty()){networkProbe(result);finish(Activity.RESULT_OK,result);return;}
        if(audioProbe){audioProbe(result);finish(Activity.RESULT_OK,result);return;}
        if(!integrationProbe.isEmpty()){
            require(Set.of("app","system","off").contains(integrationProbe),"Unknown integration diagnostic");
            screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));require(VoWifiService.Companion.getCurrent()==null,"Disconnect VoWiFi before changing integration");require(RootAccess.INSTANCE.check(),"Root not authorized");Engine.INSTANCE.closeCard();
            Engine.INSTANCE.getPrefs().edit().putString("card_type","omapi").putString("reader","SIM2").putInt("slot",1).putInt("port",0).commit();Engine.INSTANCE.refreshProfiles();require(Engine.INSTANCE.getEid().matches("[0-9]{32}"),"Real EID unavailable");
            boolean system=integrationProbe.equals("system"),enabled=!integrationProbe.equals("off");
            Engine.INSTANCE.getPrefs().edit().putBoolean("esim_app_compat",enabled).putBoolean("system_lpa",system).commit();
            RootAccess.INSTANCE.configure(getTargetContext(),system,Engine.INSTANCE.getPrefs().getBoolean("carrier_access",false),Engine.INSTANCE.getPrefs().getBoolean("system_sms_send",false));
            Engine.INSTANCE.closeCard();RootAccess.INSTANCE.disconnect();
            result.putString("stream","Integration configured: "+integrationProbe+"; OMAPI SIM2, real EID and profiles read; no profile download or write performed.\n");finish(Activity.RESULT_OK,result);return;
        }
        if(configureCard.equals("root:1")){
            screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));require(RootAccess.INSTANCE.check(),"Root not authorized");Engine.INSTANCE.closeCard();
            Engine.INSTANCE.getPrefs().edit().putString("card_type","root").putString("reader","SIM2").putInt("slot",1).putInt("port",0).commit();Engine.INSTANCE.refreshProfiles();
            for(net.typeblog.lpac_jni.LocalProfileInfo profile:Engine.INSTANCE.getProfiles()){if(profile.getState()==net.typeblog.lpac_jni.LocalProfileInfo.State.Enabled){Engine.INSTANCE.getPrefs().edit().putString("profile_label",profile.getNickName().isBlank()?profile.getName():profile.getNickName()).commit();break;}}
            click("设置");capture("device-settings");click("Root 与模块");require(find(screen,"启用 Root")!=null,"Root switch missing");capture("device-root");click("返回");click("SIM 与 eSIM");require(Engine.INSTANCE.getEid().length()==32&&Engine.INSTANCE.getProfiles().size()>0,"eSIM profiles unavailable");capture("device-cards-real");
            result.putString("stream","Configured Root SIM2; valid EID and "+Engine.INSTANCE.getProfiles().size()+" real profiles; Root switch and card page verified. No profile operations performed.\n");Engine.INSTANCE.closeCard();RootAccess.INSTANCE.disconnect();finish(Activity.RESULT_OK,result);return;
        }
        if(compatibilityUi){
            screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));click("设置");click("运营商网络");
            require(find(screen,"IKE 策略")!=null,"IKE policy setting missing");click("IKE 策略");
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            require(root!=null&&"io.vodroid".contentEquals(root.getPackageName()),"Carrier dialog is not foreground");
            require(!root.findAccessibilityNodeInfosByText("Modern").isEmpty()&&!root.findAccessibilityNodeInfosByText("Legacy").isEmpty(),"IKE presets missing");
            capture("device-ike-policy");result.putString("stream","Carrier UI: Auto / Modern / Legacy picker reachable; settings unchanged.\n");finish(Activity.RESULT_OK,result);return;
        }
        if(registrationProbe||rekeyProbe){
            require(VoWifiService.Companion.getCurrent()==null&&Engine.INSTANCE.getCall()==null,"Disconnect existing VoWiFi before registration probe");
            require(getTargetContext().getSystemService(android.telephony.TelephonyManager.class).getCallState()==0,"Native call is active");
            screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try{
                Engine.INSTANCE.setFailureStage("");
                getTargetContext().startForegroundService(new Intent(getTargetContext(),VoWifiService.class));
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
                boolean registered=false;
                while(System.nanoTime()<deadline){io.vodroid.core.ImsSession session=Engine.INSTANCE.getSession();if(session!=null&&session.registered()){registered=true;break;}if(!Engine.INSTANCE.getFailureStage().isEmpty())break;Thread.sleep(200);}
                require(registered,"Registration failed: "+Engine.INSTANCE.getPhase());
                if(rekeyProbe){
                    for(boolean ikeSa:new boolean[]{false,true}){
                        VoWifiService service=VoWifiService.Companion.getCurrent();require(service!=null,"Tunnel stopped before rekey");int before=service.health().rekeys();
                        deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);boolean started=false;
                        while(System.nanoTime()<deadline&&!(started=service.requestRekey(ikeSa)))Thread.sleep(100);
                        require(started,"Rekey request remained busy");
                        deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(28);
                        while(System.nanoTime()<deadline&&service.health().rekeys()==before){require(Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered(),"Registration lost during rekey: "+Engine.INSTANCE.getPhase());Thread.sleep(100);}
                        require(service.health().rekeys()>before,(ikeSa?"IKE_SA":"CHILD_SA")+" rekey not accepted by carrier");
                        Engine.INSTANCE.getSession().register();require(Engine.INSTANCE.getSession().registered(),"Fresh IMS REGISTER failed after rekey");
                    }
                    click("状态");capture("device-040-connected");click("连接详情");capture("device-040-health");getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);settle();
                }
                deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
                while(System.nanoTime()<deadline){require(Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered(),"Registration lost: "+Engine.INSTANCE.getPhase());Thread.sleep(250);}
                StringBuilder report=new StringBuilder("Live IMS registered and remained available for 45 seconds; no call, SMS or profile write. Policy="+Engine.INSTANCE.getPrefs().getString("ike_policy","auto")+"; rekeyProbe="+rekeyProbe+(rekeyProbe?"; fresh REGISTER passed after both CHILD_SA and IKE_SA rekey":"")+"\n");
                for(String line:Engine.INSTANCE.getLogs())if(line.contains("运营商配置")||line.contains("IKE 套件")||line.contains("尝试兼容套件")||line.contains("存活探测")||line.contains("存活确认")||line.contains("分片")||line.contains("换钥")||line.contains("EAP-"))report.append(line).append('\n');
                result.putString("stream",report.toString());
            }finally{
                getTargetContext().startService(new Intent(getTargetContext(),VoWifiService.class).setAction("stop"));
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while(System.nanoTime()<deadline&&(VoWifiService.Companion.getCurrent()!=null||Engine.INSTANCE.getSim()!=null))Thread.sleep(100);
            }
            finish(Activity.RESULT_OK,result);return;
        }
        if(deviceUi){screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));click("设置");require(find(screen,"Root 与模块")!=null,"Root entry missing from settings");capture("device-settings");click("Root 与模块");require(find(screen,"启用 Root")!=null,"Root switch missing");capture("device-root");click("返回");click("SIM 与 eSIM");require(find(screen,"检测卡片")!=null,"Card diagnostics missing");capture("device-cards");result.putString("stream","Physical UI: settings Root entry, Root switch and card diagnostics reachable; no user data reset.\n");finish(Activity.RESULT_OK,result);return;}
        if(layout.equals("protocol")){protocolUi(result);finish(Activity.RESULT_OK,result);return;}
        if(layout.equals("handoff-readonly")){handoffReadonly(result);finish(Activity.RESULT_OK,result);return;}
        require(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("Emulator")||android.os.Build.HARDWARE.contains("ranchu")||android.os.Build.HARDWARE.contains("goldfish"),"Fixture tests require an emulator; use card_probe / network_probe / device_ui on a physical phone");
        getTargetContext().getSharedPreferences("settings",Context.MODE_PRIVATE).edit().remove("vpn_backend").remove("card_type").commit();
        getTargetContext().deleteFile("messages.json");
        activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));screen=activity;
        if(layout.equals("handoff")){handoffChecks();result.putString("stream","VoDroid eSIM handoff: subscription payload and callback preserved; installation intent redirected; LPA/share/Android link import; no automatic install or false success; code retained across Activity recreation; missing-card failure and cancellation callbacks passed.\n");finish(Activity.RESULT_OK,result);return;}
        if(!layout.isEmpty()){layoutChecks();result.putString("stream","VoDroid layout: "+layout+"; reachable dial controls, incoming-call layout, retained draft after Activity recreation and Android back navigation passed.\n");finish(Activity.RESULT_OK,result);return;}
        require(find(screen,"系统状态")!=null,"Status did not render");
        require(!Engine.INSTANCE.getPrefs().getBoolean("vpn_backend",false),"Internal mode should be default");
        require(Engine.INSTANCE.getPrefs().getString("card_type","omapi").equals("omapi"),"OMAPI must be the primary card path");
        capture("ui-status");click("拨号");
        click("1");click("3");click("8");
        final EditText[] number=new EditText[1];ui(()->{number[0]=(EditText)find(screen,"电话号码");number[0].setSelection(2);});
        Engine.INSTANCE.log("UI validation: preserve dial input");settle();
        require(find(screen,"电话号码")==number[0],"Log update rebuilt dial input");
        require(number[0].getText().toString().equals("138")&&number[0].getSelectionStart()==2,"Dial number/cursor changed during log update");
        capture("ui-dial");click("短信");require(find(screen,"暂无短信")!=null,"SMS empty state missing");capture("ui-messages-empty");click("新建短信");
        final EditText[] draft=new EditText[1];ui(()->{draft[0]=(EditText)find(screen,"短信内容");draft[0].setText("今晚见，到了给我发短信。");draft[0].setSelection(3);});
        Engine.INSTANCE.log("UI validation: preserve SMS draft");settle();
        require(find(screen,"短信内容")==draft[0]&&draft[0].getSelectionStart()==3,"Log update rebuilt composer or moved its cursor");
        ui(()->{draft[0].requestFocus();screen.getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(draft[0],android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});settle();capture("ui-keyboard");
        ui(()->screen.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(draft[0].getWindowToken(),0));settle();
        require(!find(screen,"发送短信").isEnabled(),"Disconnected SMS send must be disabled");capture("ui-compose");click("返回");click("新建短信");
        require(((EditText)find(screen,"短信内容")).getText().toString().equals("今晚见，到了给我发短信。"),"Draft lost when navigating");click("返回");
        // In-memory fixtures exist only in this instrumentation process, never in the APK's normal UI.
        long now=System.currentTimeMillis();ui(()->{synchronized(Engine.INSTANCE.getMessages()){
            Engine.INSTANCE.getMessages().add(new Engine.Message("ui-1","10086","您的业务已办理成功。",true,now-480000,"已接收",false));
            Engine.INSTANCE.getMessages().add(new Engine.Message("ui-2","+8613800138000","周末一起喝咖啡？",true,now-360000,"已接收",true));
            Engine.INSTANCE.getMessages().add(new Engine.Message("ui-3","+8613800138000","好，下午三点见。",false,now-240000,"网络已确认",true));
            Engine.INSTANCE.getMessages().add(new Engine.Message("ui-4","10010","本月套餐余量已更新。",true,now-180000,"已接收",true));
        }});Engine.INSTANCE.update();settle();capture("ui-messages");click("+8613800138000");capture("ui-thread");click("返回");click("10086");settle();
        java.util.concurrent.CountDownLatch read=new java.util.concurrent.CountDownLatch(1);Engine.INSTANCE.getIo().execute(read::countDown);require(read.await(3,java.util.concurrent.TimeUnit.SECONDS),"Read state stalled");
        require(Engine.INSTANCE.getMessages().get(0).getRead(),"Opening thread did not mark it read");click("返回");click("设置");capture("ui-settings");
        click("隧道模式");capture("ui-network");click("系统 VPN");require(Engine.INSTANCE.getPrefs().getBoolean("vpn_backend",false),"VPN option did not persist");
        click("隧道模式");click("应用内隧道");require(!Engine.INSTANCE.getPrefs().getBoolean("vpn_backend",true),"Internal option did not persist");
        click("SIM 与 eSIM");require(find(screen,"读取配置")!=null,"SIM management missing");capture("ui-cards-empty");
        ui(()->Engine.INSTANCE.setProfiles(List.of(
            new net.typeblog.lpac_jni.LocalProfileInfo("8900000000000000001",net.typeblog.lpac_jni.LocalProfileInfo.State.Enabled,"个人","个人","测试运营商","",net.typeblog.lpac_jni.ProfileClass.Operational),
            new net.typeblog.lpac_jni.LocalProfileInfo("8900000000000000002",net.typeblog.lpac_jni.LocalProfileInfo.State.Disabled,"工作","工作","测试运营商","",net.typeblog.lpac_jni.ProfileClass.Operational)
        )));Engine.INSTANCE.update();settle();capture("ui-cards");click("卡片通道");require(find(screen,"手机卡槽")!=null,"OMAPI slot picker missing");capture("ui-card-access");click("手机卡槽");Thread.sleep(4500);capture("ui-readers");click("手动输入");capture("ui-reader-manual");click("选择");click("返回");click("返回");click("日志");capture("ui-logs");click("异常");click("全部");click("返回");
        click("运营商网络");capture("ui-carrier");click("MNC 位数");click("2");require(Engine.INSTANCE.getPrefs().getInt("mnc_length",0)==2,"MNC setting has wrong storage type");click("返回");click("Root 与模块");capture("ui-integration");click("返回");click("状态");
        require(VoWifiService.Companion.getCurrent()==null,"Navigation started a carrier service");
        ui(()->{synchronized(Engine.INSTANCE.getMessages()){Engine.INSTANCE.getMessages().clear();}Engine.INSTANCE.setProfiles(List.of());Engine.INSTANCE.getPrefs().edit().remove("mnc_length").apply();});getTargetContext().deleteFile("messages.json");
        // Native loading must succeed on this ABI; actual LPA creation requires a real eUICC.
        Class.forName("net.typeblog.lpac_jni.LpacJni");
        audioLoopback("PCMA",8000);
        audioLoopback("AMR",8000);
        audioLoopback("AMR-WB",16000);
        // Deterministic missing-hardware check; no OMAPI or carrier APDU is issued by this test.
        Engine.INSTANCE.getPrefs().edit().putString("card_type","usb").commit();
        click("连接 VoWiFi");
        long deadline=System.currentTimeMillis()+5000;
        while(System.currentTimeMillis()<deadline&&!Engine.INSTANCE.getPhase().startsWith("连接失败")){Thread.sleep(50);waitForIdleSync();}
        require(Engine.INSTANCE.getPhase().startsWith("连接失败"),"Missing-card failure was not surfaced");
        require(TunnelVpnService.Companion.getCurrent()==null,"Internal mode unexpectedly claimed the system VPN service");
        if(testTun)tunBackend();
        capture("ui-failure");
        Engine.INSTANCE.getPrefs().edit().remove("card_type").commit();
        result.putString("stream","VoDroid smoke: four tabs, settings destinations, both network options, stable dial/composer focus, retained draft, thread read state, log filter, MNC storage, native LPA load, PCMA/AMR/AMR-WB audio loopback, missing-card failure and no-VPN internal mode passed."+(testTun?" Android VPN/TUN establish, assigned-address socket bind and protect passed.":"")+"\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAILED: "+e+"\n"+Arrays.toString(e.getStackTrace()));finish(Activity.RESULT_CANCELED,result);}}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    /** Explicit physical test: only SELECT/READ APDUs; restore all integration selections. */
    private void rootAudit(Bundle output)throws Exception{
        require(VoWifiService.Companion.getCurrent()==null&&Engine.INSTANCE.getCall()==null,"Disconnect calls before Root audit");
        android.content.SharedPreferences prefs=Engine.INSTANCE.getPrefs();
        require(!prefs.getBoolean("system_lpa",false),"Keep experimental system mapping off during audit");
        String type=prefs.getString("card_type","omapi"),reader=prefs.getString("reader","SIM1");int slot=prefs.getInt("slot",0),port=prefs.getInt("port",0);
        boolean app=prefs.getBoolean("esim_app_compat",false),carrier=prefs.getBoolean("carrier_access",false),sms=prefs.getBoolean("system_sms_send",false);
        require(RootAccess.INSTANCE.check(),"Root authorization failed");Engine.INSTANCE.refreshProfiles();
        String eid=Engine.INSTANCE.getEid();require(eid.matches("[0-9]{32}"),"Expected real eUICC EID snapshot");
        RootAccess.CardProbe report=RootAccess.INSTANCE.verifyCard(getTargetContext(),slot,port,prefs.getString("usim","A0000000871002"));require(report.getPassed()&&report.getStatusWord()==0x9000&&report.getBytes()>0,"Selected Root card read failed");
        try(CardAccess first=RootAccess.INSTANCE.open(getTargetContext(),0,0);CardAccess second=RootAccess.INSTANCE.open(getTargetContext(),1,0)){
            io.vodroid.core.SimReadProbe.run(first,"A0000000871002");
            RootAccess.INSTANCE.scopedConfiguration(getTargetContext(),android.os.Process.myUid());
            io.vodroid.core.SimReadProbe.run(first,"A0000000871002");first.close();
            io.vodroid.core.SimReadProbe.run(second,"A0000000871002");
        }
        try{
            RootAccess.INSTANCE.disable(getTargetContext());
            require(!prefs.getBoolean("root_enabled",true)&&!prefs.getBoolean("carrier_access",true)&&!prefs.getBoolean("esim_app_compat",true)&&!prefs.getBoolean("system_sms_send",true),"Root master left app features enabled");
            require(getTargetContext().getPackageManager().getComponentEnabledSetting(new ComponentName(getTargetContext(),SystemEuiccService.class))==android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,"Native LPA still enabled");
            require(new SystemEuiccService().onGetEid(slot)==null,"Disabled LPA exposed an identity");
            android.telephony.euicc.DownloadableSubscription subscription=android.telephony.euicc.DownloadableSubscription.forActivationCode("1$smdp.example.invalid$disabled-root-probe");
            require(new SystemEuiccService().onDownloadSubscription(slot,subscription,false,false,null).getResult()!=0,"Disabled LPA accepted a download");
            require(RootAccess.INSTANCE.check(),"Cannot reauthorize for restoring integration");
            org.json.JSONObject disabled=RootAccess.INSTANCE.scopedConfiguration(getTargetContext(),android.os.Process.myUid());
            for(String key:new String[]{"root_enabled","force_euicc","app_compat","carrier_access","sms_bridge"})require("false".equals(disabled.optString(key)),"Module flag still enabled: "+key);
        }finally{
            require(RootAccess.INSTANCE.check(),"Cannot restore Root configuration");
            prefs.edit().putBoolean("system_lpa",false).putBoolean("esim_app_compat",app).putBoolean("carrier_access",carrier).putBoolean("system_sms_send",sms).commit();
            RootAccess.INSTANCE.configure(getTargetContext(),false,carrier,sms);RootAccess.INSTANCE.disconnect();
        }
        require(type.equals(prefs.getString("card_type",""))&&reader.equals(prefs.getString("reader",""))&&slot==prefs.getInt("slot",-1)&&port==prefs.getInt("port",-1),"Root probe changed selected card");
        screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));click("设置");click("Root 与模块");click("授权状态");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<deadline&&Engine.INSTANCE.getBusy())Thread.sleep(100);settle();
        RootAccess.CardProbe prior=RootAccess.INSTANCE.getCardProbe();click("读卡检测");click("开始检测");
        deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<deadline&&(Engine.INSTANCE.getBusy()||RootAccess.INSTANCE.getCardProbe()==prior))Thread.sleep(100);settle();
        require(RootAccess.INSTANCE.getCardProbe()!=null&&RootAccess.INSTANCE.getCardProbe().getPassed()&&RootAccess.INSTANCE.getCardProbe().getSlot()==slot,"Root UI did not test selected slot");capture("device-root-apdu");
        output.putString("stream","Root UID verified; selected slot "+slot+" port "+port+" SELECT/READ returned SW=9000 and "+report.getBytes()+" bytes; card/config helpers isolated; closing SIM1 preserved SIM2; Root OFF cleared module/app flags and disabled LPA; disabled native download rejected without APDU; original integration settings and selected card restored; optional Root UI read probe passed. No authentication, profile writes, calls or SMS.\n");
    }
    /** Local audio only: microphone input is muted before encoding, nothing is persisted or sent. */
    private void audioProbe(Bundle output)throws Exception{
        require(VoWifiService.Companion.getCurrent()==null,"Stop VoWiFi before local audio diagnostics");
        screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));settle();
        require(getTargetContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED,"Microphone permission missing");
        android.media.AudioManager manager=getTargetContext().getSystemService(android.media.AudioManager.class);int previousMode=manager.getMode();
        try(CallAudioRouting routing=new CallAudioRouting(getTargetContext(),focused->kotlin.Unit.INSTANCE)){
            routing.start();require(routing.getFocused(),"Audio focus denied");require(manager.getMode()==android.media.AudioManager.MODE_IN_COMMUNICATION,"Communication mode missing");
            require(routing.speaker(true),"Speaker unavailable");long deadline=System.currentTimeMillis()+3000;
            while(System.currentTimeMillis()<deadline&&(manager.getCommunicationDevice()==null||manager.getCommunicationDevice().getType()!=android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))Thread.sleep(30);
            require(manager.getCommunicationDevice()!=null&&manager.getCommunicationDevice().getType()==android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,"Speaker route not applied");
            boolean earpiece=manager.getAvailableCommunicationDevices().stream().anyMatch(device->device.getType()==android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
            if(earpiece)require(routing.speaker(false),"Earpiece route unavailable");
            android.media.AudioFocusRequest temporary=new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION).build()).setOnAudioFocusChangeListener(change->{}).build();
            try{
                require(manager.requestAudioFocus(temporary)==android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,"Temporary focus request failed");deadline=System.currentTimeMillis()+3000;while(routing.getFocused()&&System.currentTimeMillis()<deadline)Thread.sleep(30);require(!routing.getFocused(),"Focus loss was not observed");
            }finally{manager.abandonAudioFocusRequest(temporary);}
            deadline=System.currentTimeMillis()+3000;while(!routing.getFocused()&&System.currentTimeMillis()<deadline)Thread.sleep(30);require(routing.getFocused(),"Focus did not recover");
        }
        require(manager.getMode()==previousMode,"Audio mode leaked after routing close");
        for(io.vodroid.core.RtpEndpoint.Format format:List.of(new io.vodroid.core.RtpEndpoint.Format("PCMA",8000,8,true),new io.vodroid.core.RtpEndpoint.Format("AMR",8000,96,true),new io.vodroid.core.RtpEndpoint.Format("AMR-WB",16000,97,true))){
            java.util.concurrent.atomic.AtomicInteger frames=new java.util.concurrent.atomic.AtomicInteger();final AudioBridge[] bridge=new AudioBridge[1];
            bridge[0]=new AudioBridge(getTargetContext(),format,encoded->{frames.incrementAndGet();bridge[0].downlink(encoded);return kotlin.Unit.INSTANCE;});
            try{bridge[0].mute(true);bridge[0].start();long deadline=System.currentTimeMillis()+4000;while(frames.get()<15&&System.currentTimeMillis()<deadline)Thread.sleep(30);require(frames.get()>=15,"No local encoded audio: "+format.name());}finally{bridge[0].close();}
            require(manager.getMode()==previousMode,"Audio mode leaked after "+format.name());
        }
        for(String log:Engine.INSTANCE.getLogs())require(!log.contains("音频上行失败")&&!log.contains("音频下行失败"),"Audio worker failed");
        io.vodroid.core.ImsSession.Call originalCall=Engine.INSTANCE.getCall();boolean originalMute=Engine.INSTANCE.getMicrophoneMuted();android.telecom.Connection connection=null;
        try{
            Engine.INSTANCE.setCall(new io.vodroid.core.ImsSession.Call("local-audio-controls","本地音频检查","ringing",true,"PCMA"));
            Bundle extras=new Bundle();extras.putString("vodroid_call_id","local-audio-controls");connection=new CallIntegrationService().onCreateIncomingConnection(null,new android.telecom.ConnectionRequest(SystemIntegration.INSTANCE.handle(getTargetContext()),android.net.Uri.parse("tel:+9990000401"),extras));
            connection.onCallAudioStateChanged(new android.telecom.CallAudioState(true,android.telecom.CallAudioState.ROUTE_EARPIECE,android.telecom.CallAudioState.ROUTE_EARPIECE));require(Engine.INSTANCE.getMicrophoneMuted(),"Telecom mute did not reach app audio");
            connection.onCallAudioStateChanged(new android.telecom.CallAudioState(false,android.telecom.CallAudioState.ROUTE_EARPIECE,android.telecom.CallAudioState.ROUTE_EARPIECE));require(!Engine.INSTANCE.getMicrophoneMuted(),"Telecom unmute did not reach app audio");
        }finally{if(connection!=null)connection.destroy();CallIntegrationService.Companion.getConnections().remove("local-audio-controls");Engine.INSTANCE.setCall(originalCall);Engine.INSTANCE.muteMicrophone(originalMute);}
        output.putString("stream","Physical audio: focus loss/recovery, speaker/earpiece selection, mode restoration, muted PCMA / AMR / AMR-WB encode/playback and synthetic Telecom mute callback passed. No carrier calls, SMS, recording files or user-data reset.\n");
    }
    private void telecomProbe(Bundle output)throws Exception{
        require(Engine.INSTANCE.getCall()==null,"An active call must not be interrupted");
        Context context=getTargetContext();android.telecom.TelecomManager manager=context.getSystemService(android.telecom.TelecomManager.class);
        android.telecom.PhoneAccountHandle handle=SystemIntegration.INSTANCE.handle(context);android.telecom.PhoneAccount original=inspectOwnedAccount(manager,handle);
        SharedPreferences preferences=Engine.INSTANCE.getPrefs();boolean had=preferences.contains("system_calls"),enabled=preferences.getBoolean("system_calls",false);
        try{
            preferences.edit().putBoolean("system_calls",true).commit();SystemIntegration.INSTANCE.register(context);
            android.telecom.PhoneAccount account=inspectOwnedAccount(manager,handle);require(account!=null,"System Telecom rejected the VoDroid account");
            require(account.hasCapabilities(android.telecom.PhoneAccount.CAPABILITY_CALL_PROVIDER)&&account.supportsUriScheme("tel"),"System account has wrong capabilities");
            output.putString("stream","System Telecom account registration verified as app uid="+android.os.Process.myUid()+"; existing="+(original!=null)+"; enabled="+account.isEnabled()+"; no calls placed; original settings and owned account restored.\n");
        }finally{
            if(original==null)manager.unregisterPhoneAccount(handle);
            SharedPreferences.Editor editor=preferences.edit();if(had)editor.putBoolean("system_calls",enabled);else editor.remove("system_calls");editor.commit();
        }
    }
    private android.telecom.PhoneAccount inspectOwnedAccount(android.telecom.TelecomManager manager,android.telecom.PhoneAccountHandle handle){
        // The ROM gates this read behind READ_PHONE_NUMBERS even for our own account.
        // Adopt only for this diagnostic read; registration still runs as the normal app UID.
        getUiAutomation().adoptShellPermissionIdentity(android.Manifest.permission.READ_PHONE_NUMBERS);
        try{return manager.getPhoneAccount(handle);}finally{getUiAutomation().dropShellPermissionIdentity();}
    }
    /** Carrier registration diagnostics: uses the current default network, never sends calls or SMS. */
    private void networkProbe(Bundle output)throws Exception{
        screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));StringBuilder report=new StringBuilder();
        String[] parts=networkProbe.split(":");boolean root=parts[0].equals("root");String target=parts[1];String transport=parts.length>2?parts[2]:"default";
        android.net.Network selected=null;
        if(!transport.equals("default")){
            android.net.ConnectivityManager manager=getTargetContext().getSystemService(android.net.ConnectivityManager.class);
            int kind=transport.equals("cellular")?android.net.NetworkCapabilities.TRANSPORT_CELLULAR:android.net.NetworkCapabilities.TRANSPORT_WIFI;
            for(android.net.Network candidate:manager.getAllNetworks()){android.net.NetworkCapabilities capabilities=manager.getNetworkCapabilities(candidate);if(capabilities!=null&&capabilities.hasTransport(kind)&&capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)&&!capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)){selected=candidate;break;}}
            require(selected!=null,"Requested network unavailable: "+transport);
        }
        final android.net.Network network=selected;report.append("Network=").append(transport).append('\n');
        try(CardAccess card=root?RootAccess.INSTANCE.open(getTargetContext(),Integer.parseInt(target),0):CardAccess.Companion.omapi(getTargetContext(),target);io.vodroid.core.SimCard sim=new io.vodroid.core.SimCard(card);
            io.vodroid.core.IkeSession ike=new io.vodroid.core.IkeSession(socket->{if(network!=null)network.bindSocket(socket);},line->{report.append(line).append('\n');Bundle progress=new Bundle();progress.putString("stream",line+"\n");sendStatus(0,progress);},host->network==null?java.net.InetAddress.getAllByName(host):network.getAllByName(host))){
            sim.connect(Engine.INSTANCE.getPrefs().getString("usim","A0000000871002"));io.vodroid.core.SimCard.Identity identity=sim.identity(Engine.INSTANCE.getPrefs().getInt("mnc_length",0));report.append("USIM ready; MCC=").append(identity.mcc()).append(" MNC=").append(identity.mnc()).append('\n');
            io.vodroid.core.IkeSession.Tunnel tunnel=ike.connect("",identity,sim);report.append("IKE authenticated; P-CSCF count=").append(tunnel.pcscf().size()).append('\n');
        }catch(Exception e){report.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');}finally{RootAccess.INSTANCE.disconnect();}
        for(String line:Engine.INSTANCE.getLogs()){if(line.contains("eSIM AID"))report.append(line).append('\n');}
        output.putString("stream",report.toString());
    }
    /** Physical-device diagnostics: no profile changes, authentication challenges, or preference resets. */
    private void cardProbe(Bundle output)throws Exception{
        screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));StringBuilder report=new StringBuilder();
        try{report.append("readers=").append(CardAccess.Companion.simReaders(getTargetContext())).append('\n');}catch(Exception e){report.append("readers: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');}
        boolean root=cardProbe.startsWith("root");
        if(root){try{report.append("appRoot=").append(RootAccess.INSTANCE.check()).append(" status=").append(RootAccess.INSTANCE.getStatus()).append('\n');}catch(Exception e){report.append("appRoot: ").append(e.getMessage()).append('\n');}
        }
        String reader=cardProbe.contains(":")?cardProbe.substring(cardProbe.indexOf(':')+1):Engine.INSTANCE.getPrefs().getString("reader","SIM1");
        int slot=root&&cardProbe.contains(":")?Integer.parseInt(reader):Engine.INSTANCE.getPrefs().getInt("slot",0);
        try(CardAccess card=root?RootAccess.INSTANCE.open(getTargetContext(),slot,Engine.INSTANCE.getPrefs().getInt("port",0)):CardAccess.Companion.omapi(getTargetContext(),reader)){
            report.append("transport opened\n");try(io.vodroid.core.SimCard sim=new io.vodroid.core.SimCard(card)){sim.connect(Engine.INSTANCE.getPrefs().getString("usim","A0000000871002"));report.append("USIM channel opened\n");io.vodroid.core.SimCard.Identity id=sim.identity(Engine.INSTANCE.getPrefs().getInt("mnc_length",0));report.append("identity valid; MCC=").append(id.mcc()).append(" MNC=").append(id.mnc()).append('\n');}catch(Exception e){report.append("USIM: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');}
            try{net.typeblog.lpac_jni.LocalProfileAssistant lpa=EuiccAccess.INSTANCE.open(card,Engine.INSTANCE.getPrefs().getString("isdr","A0000005591010FFFFFFFF8900000100"));try{report.append("eUICC EID digits=").append(lpa.getEID().length()).append(" profiles=").append(lpa.getProfiles().size()).append('\n');}finally{lpa.close();}}catch(Exception e){report.append("eUICC: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');}
        }catch(Exception e){report.append("transport: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');}finally{if(root)RootAccess.INSTANCE.disconnect();}
        for(String line:Engine.INSTANCE.getLogs()){if(line.contains("eSIM AID"))report.append(line).append('\n');}
        output.putString("stream",report.toString());
    }
    private void handoffChecks()throws Exception{
        final String activation="LPA:1$smdp.example$instrumentation-only-token";final Activity main=screen;
        java.util.concurrent.atomic.AtomicInteger count=new java.util.concurrent.atomic.AtomicInteger(),resultCode=new java.util.concurrent.atomic.AtomicInteger(-999);
        BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){resultCode.set(getResultCode());count.incrementAndGet();}};
        getTargetContext().registerReceiver(receiver,new IntentFilter("io.vodroid.TEST_ESIM_RESULT"),Context.RECEIVER_NOT_EXPORTED);
        PendingIntent callback=PendingIntent.getBroadcast(getTargetContext(),909,new Intent("io.vodroid.TEST_ESIM_RESULT").setPackage("io.vodroid"),PendingIntent.FLAG_MUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        try{
            android.telephony.euicc.DownloadableSubscription sub=android.telephony.euicc.DownloadableSubscription.forActivationCode(activation.substring(4));Intent captured=EsimHandoff.fromSubscription(getTargetContext(),sub,true,callback);
            require(captured.getStringExtra(EsimHandoff.CODE).equals(activation.substring(4)),"Subscription activation code changed");require(captured.getBooleanExtra(EsimHandoff.ACTIVATE,false),"Switch-after-download lost");require(callback.equals(captured.getParcelableExtra(EsimHandoff.CALLBACK,PendingIntent.class)),"Original callback lost");
            Intent source=new Intent("android.telephony.euicc.action.PROVISION_EMBEDDED_SUBSCRIPTION").putExtra("android.telephony.euicc.extra.ACTIVATION_CODE",activation);Intent redirected=EsimHandoff.redirect(source);require(redirected!=null&&redirected.getComponent().getClassName().equals("io.vodroid.EsimImportActivity"),"System installation was not redirected");require(EsimHandoff.extract(redirected).equals(activation),"Installation intent lost activation code");require(EsimHandoff.redirect(new Intent("unrelated.action"))==null,"Unrelated activity launch was modified");
            for(String scheme:new String[]{"lpa:","LPA:"}){Intent link=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(scheme+activation.substring(4))).setPackage("io.vodroid");require(getTargetContext().getPackageManager().resolveActivity(link,0)!=null,"LPA scheme not registered: "+scheme);}
            screen=startActivitySync(captured.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));settle();require(find(screen,"安装 eSIM")!=null,"Import UI missing");require(editor(screen.getWindow().getDecorView(),"激活码 / 安装链接").getText().toString().equals(activation.substring(4)),"Captured code absent from import UI");require(count.get()==0&&Engine.INSTANCE.getCard()==null,"Opening import triggered installation or callback");capture("ui-esim-import");
            ActivityMonitor monitor=addMonitor(EsimImportActivity.class.getName(),null,false);ui(()->screen.recreate());Activity replacement=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);require(replacement!=null,"Import recreation failed");screen=replacement;settle();require(editor(screen.getWindow().getDecorView(),"激活码 / 安装链接").getText().toString().equals(activation.substring(4)),"Recreation lost captured credential");require(count.get()==0,"Recreation prematurely completed callback");click("取消安装");settle();require(count.get()==1&&resultCode.get()==2,"Cancel did not return eUICC error exactly once");screen=main;
            Intent shared=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,activation);require(EsimHandoff.extract(shared).equals(activation),"Shared text code not accepted");
            Intent link=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://esimsetup.android.com/esim_qrcode_provisioning?carddata=LPA%3A1%24smdp.example%24instrumentation-only-token"));require(io.vodroid.core.ActivationCode.parse(EsimHandoff.extract(link)).canonical().equals(activation),"Android link not decoded");require(EsimHandoff.redirect(link)!=null,"Android install link did not redirect");
            Engine.INSTANCE.getPrefs().edit().putString("card_type","usb").commit();screen=startActivitySync(EsimHandoff.create(getTargetContext(),activation,false,null,callback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));click("下载安装");long deadline=System.currentTimeMillis()+5000;while(System.currentTimeMillis()<deadline&&find(screen,"重试")==null){Thread.sleep(80);waitForIdleSync();}require(find(screen,"重试")!=null,"Missing-card error not shown");require(count.get()==1,"Failed installation reported premature success");capture("ui-esim-import-failure");click("取消安装");settle();require(count.get()==2&&resultCode.get()==2,"Failure/cancel callback mismatch");screen=main;require(VoWifiService.Companion.getCurrent()==null,"eSIM import started VoWiFi");
        }finally{getTargetContext().unregisterReceiver(receiver);callback.cancel();Engine.INSTANCE.getPrefs().edit().remove("card_type").commit();}
    }
    private void layoutChecks()throws Exception{
        click("拨号");ui(()->find(screen,"拨打").requestRectangleOnScreen(new android.graphics.Rect(0,0,find(screen,"拨打").getWidth(),find(screen,"拨打").getHeight()),true));settle();
        require(find(screen,"拨打").getGlobalVisibleRect(new android.graphics.Rect()),"Call action is unreachable");capture("ui-"+layout+"-dial");
        ui(()->Engine.INSTANCE.setCall(new io.vodroid.core.ImsSession.Call("layout-call","+8613800138000","ringing",true,"PCMA")));Engine.INSTANCE.update();settle();require(find(screen,"接听")!=null&&find(screen,"拒接")!=null,"Incoming call actions missing");capture("ui-"+layout+"-incoming");
        ui(()->Engine.INSTANCE.setCall(null));Engine.INSTANCE.update();settle();click("短信");click("新建短信");ui(()->((EditText)find(screen,"短信内容")).setText("布局验证草稿"));
        if(layout.equals("compact")){ui(()->{EditText input=(EditText)find(screen,"短信内容");input.requestFocus();input.postDelayed(()->screen.getWindow().getInsetsController().show(WindowInsets.Type.ime()),250);});Thread.sleep(1200);settle();require(find(screen,"短信内容").getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom>0,"IME did not open");require(find(screen,"短信内容").getGlobalVisibleRect(new android.graphics.Rect()),"IME obscured composer");capture("ui-compact-keyboard");ui(()->screen.getWindow().getInsetsController().hide(WindowInsets.Type.ime()));settle();}
        ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);ui(()->screen.recreate());Activity replacement=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);require(replacement!=null,"Activity recreation timed out");screen=replacement;settle();require(((EditText)find(screen,"短信内容")).getText().toString().equals("布局验证草稿"),"Recreation lost draft");capture("ui-"+layout+"-compose");getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);settle();require(find(screen,"新建短信")!=null&&find(screen,"短信内容")==null,"Android back did not return to messages");
    }
    private void protocolUi(Bundle result)throws Exception{
        require(VoWifiService.Companion.getCurrent()==null&&Engine.INSTANCE.getCall()==null,"UI layout test requires idle VoWiFi");
        screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));int original=screen.getRequestedOrientation();
        String eap=Engine.INSTANCE.getPrefs().getString("eap_policy","auto"),ike=Engine.INSTANCE.getPrefs().getString("ike_policy","auto");
        try{
            orient(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);require(find(screen,"连接 VoWiFi")!=null,"Portrait connect action missing");capture("device-040-status");
            orient(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);capture("device-040-landscape");require(find(screen,"连接 VoWiFi").getGlobalVisibleRect(new android.graphics.Rect()),"Landscape connect action unreachable");
            orient(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);click("设置");click("运营商网络");require(find(screen,"SIM 认证")!=null,"SIM authentication setting missing");capture("device-040-carrier");click("SIM 认证");
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();require(root!=null&&"io.vodroid".contentEquals(root.getPackageName()),"Authentication dialog not foreground");require(!root.findAccessibilityNodeInfosByText("AKA′").isEmpty()&&!root.findAccessibilityNodeInfosByText("AKA").isEmpty(),"Authentication methods missing");capture("device-040-eap");
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);settle();
            require(eap.equals(Engine.INSTANCE.getPrefs().getString("eap_policy","auto"))&&ike.equals(Engine.INSTANCE.getPrefs().getString("ike_policy","auto")),"Read-only UI test changed protocol settings");
            result.putString("stream","Physical UI: portrait and adaptive landscape status rendered; connect action reachable; SIM authentication Auto / AKA / AKA-prime picker visible; preferences unchanged; no card, voice or SMS operations.\n");
        }finally{orient(original);}
    }
    private void donationUi(Bundle output)throws Exception{
        require(Engine.INSTANCE.getCall()==null&&getTargetContext().getSystemService(android.telephony.TelephonyManager.class).getCallState()==0,"Donation UI check requires no active call");
        SharedPreferences p=Engine.INSTANCE.getPrefs();Map<String,?> before=new HashMap<>(p.getAll());boolean registered=Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered();
        screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));click("设置");click("关于 VoDroid");require(find(screen,"支持项目")!=null,"Donation entry missing");capture("device-045-about");click("支持项目");
        ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);ui(()->screen.recreate());Activity replacement=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);require(replacement!=null,"Donation Activity recreation timed out");screen=replacement;settle();capture("device-045-donation");
        android.content.ClipboardManager clipboard=getTargetContext().getSystemService(android.content.ClipboardManager.class);boolean hadClip=clipboard.hasPrimaryClip();ClipData previous=clipboard.getPrimaryClip();require(!hadClip||previous!=null,"Clipboard unavailable for restoration");
        try{
            for(DonationInfo.Destination destination:DonationInfo.INSTANCE.getDestinations()){
                TextView address=screen.getWindow().getDecorView().findViewWithTag("donation-address-"+destination.getId());require(address!=null&&destination.getAddress().contentEquals(address.getText()),"Displayed donation address differs");
                String copy="复制 "+destination.getChain()+" 地址";reveal(copy);click(copy);ClipData copied=clipboard.getPrimaryClip();require(copied!=null&&copied.getItemCount()==1&&destination.getAddress().contentEquals(copied.getItemAt(0).getText()),"Clipboard address differs");
                require(copied.getDescription().getLabel().toString().contains(destination.getNetwork()),"Copied address network missing");
            }
            click("返回");require(find(screen,"关于 VoDroid")!=null,"Donation back did not return to About");click("返回");require(find(screen,"可选增强")!=null,"About back did not return to settings");
            require(before.equals(p.getAll()),"Donation UI changed operational settings");require(!registered||(Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered()),"Donation UI interrupted IMS");
            output.putString("stream","Donation UI: About entry, three network-labelled full addresses and exact clipboard copies passed; Activity recreation and back navigation passed; original clipboard restored; operational settings unchanged. No browser, wallet, transfer, card or network call/SMS action.\n");
        }finally{if(previous!=null)clipboard.setPrimaryClip(previous);else clipboard.clearPrimaryClip();}
    }
    private void publicationUi(Bundle output)throws Exception{
        SharedPreferences preferences=Engine.INSTANCE.getPrefs();Map<String,?> before=new HashMap<>(preferences.getAll());
        screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        List<String> links=new ArrayList<>();
        ActivityMonitor browser=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){
            if(Intent.ACTION_VIEW.equals(intent.getAction())){links.add(intent.getDataString());return new ActivityResult(Activity.RESULT_CANCELED,null);}return null;
        }};
        addMonitor(browser);
        try{
            capture("publication-home");click("拨号");capture("publication-dial");click("短信");capture("publication-messages");click("设置");capture("publication-settings");
            reveal("关于 VoDroid");click("关于 VoDroid");
            require(find(screen,ProjectInfo.AUTHOR)!=null&&find(screen,"GitHub 仓库")!=null&&find(screen,"问题反馈")!=null,"Author/repository entries missing");
            capture("publication-about");
            click("作者");click("GitHub 仓库");click("问题反馈");
            require(links.equals(List.of(ProjectInfo.AUTHOR_URL,ProjectInfo.REPOSITORY_URL,ProjectInfo.ISSUES_URL)),"Project links differ from public metadata");
            reveal("支持项目");click("支持项目");require(find(screen,"USDT · TRON")!=null,"Donation navigation missing");
            require(before.equals(preferences.getAll()),"Project UI changed operational settings");
            output.putString("stream","Publication UI: author, repository and issue links match public metadata; external launches intercepted; donation navigation reachable; operational settings unchanged.\n");
        }finally{removeMonitor(browser);}
    }
    private void homeUi(Bundle output)throws Exception{
        require(Engine.INSTANCE.getCall()==null&&getTargetContext().getSystemService(android.telephony.TelephonyManager.class).getCallState()==0,"Home UI check requires no active call");
        SharedPreferences p=Engine.INSTANCE.getPrefs();Map<String,?> before=new HashMap<>(p.getAll());boolean registered=Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered();
        screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));int original=screen.getRequestedOrientation();
        try{
            orient(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            require(find(screen,"系统状态")!=null&&find(screen,"当前 SIM")!=null&&find(screen,"隧道模式")!=null,"Home summary missing");
            require(find(screen,"VPN 已连接")==null&&find(screen,"Wi-Fi 已连接")==null&&find(screen,"网络")==null,"Network/VPN state remains on home");capture("device-044-home");
            click("隧道模式");require(find(screen,"应用内隧道")!=null&&find(screen,"系统 VPN")!=null,"Home tunnel link did not open mode settings");capture("device-044-tunnel");
            ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);ui(()->screen.recreate());Activity replacement=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);require(replacement!=null,"Tunnel settings recreation timed out");screen=replacement;settle();
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);settle();require(find(screen,"系统状态")!=null,"Tunnel back did not return home after recreation");
            orient(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);View connect=find(screen,"连接 VoWiFi");if(connect==null)connect=find(screen,"断开连接");require(connect!=null&&connect.getGlobalVisibleRect(new android.graphics.Rect()),"Landscape connect action unreachable");capture("device-044-home-landscape");
            orient(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);click("设置");click("隧道模式");click("返回");require(find(screen,"可选增强")!=null,"Settings tunnel back returned to wrong origin");click("状态");
            require(before.equals(p.getAll()),"Read-only home navigation changed settings");require(!registered||(Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered()),"Home navigation interrupted IMS");
            output.putString("stream","Home UI: network/VPN state removed; unified SIM/tunnel summary and status pipeline rendered; tunnel page reachable from home/settings; correct back origins retained through Activity recreation; portrait/landscape connect action reachable; preferences unchanged. Originally registered="+registered+". No card, module, call or SMS action.\n");
        }finally{orient(original);}
    }
    private void enhancementsUi(Bundle output)throws Exception{
        SharedPreferences p=Engine.INSTANCE.getPrefs();Map<String,Object> before=new HashMap<>();
        List<String> keys=List.of("root_enabled","system_calls","system_sms","esim_app_compat","system_lpa","carrier_access","system_sms_send","card_type","slot","port","reader","usim","isdr");
        for(String key:keys)before.put(key,p.getAll().get(key));
        boolean registered=Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered();
        RootAccess.CardProbe previousProbe=RootAccess.INSTANCE.getCardProbe();
        screen=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));click("设置");
        require(find(screen,"可选增强")!=null&&find(screen,"Root 与模块")!=null,"Optional enhancement destination missing");
        require(find(screen,"Root 授权")==null&&find(screen,"系统集成")==null,"Enhanced controls remain in standard settings");
        reveal("Root 与模块");capture("device-042-settings");click("Root 与模块");
        for(String label:List.of("启用 Root","授权状态","读卡检测","Root 卡槽","系统通话","系统短信","应用 eSIM 兼容","系统 LPA","运营商卡访问","系统短信发送","应用模块配置"))require(find(screen,label)!=null,"Missing enhancement: "+label);
        click("读卡检测");capture("device-043-card-check");click("检测卡槽");capture("device-043-check-targets");
        int alternate=p.getInt("slot",0)>0?0:(getTargetContext().getSystemService(android.telephony.TelephonyManager.class).getActiveModemCount()>1?1:0);click("SIM"+(alternate+1));
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);settle();
        capture("device-042-enhancements");reveal("应用模块配置");require(find(screen,"应用模块配置").getGlobalVisibleRect(new android.graphics.Rect()),"Module apply action unreachable");capture("device-042-module-settings");
        click("Root 卡槽");require(find(screen,"卡槽")!=null&&find(screen,"端口")!=null&&find(screen,"OMAPI")==null,"Root-only card destination incorrect");capture("device-042-root-card");
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);settle();require(find(screen,"启用 Root")!=null,"Root card back did not return to enhancements");
        click("返回");click("SIM 与 eSIM");click("卡片通道");
        require(find(screen,"OMAPI")!=null&&find(screen,"USB CCID")!=null&&find(screen,"Root 卡槽")==null&&find(screen,"启用 Root")==null,"Standard card choices include enhancement controls");capture("device-042-standard-card");
        click("返回");click("返回");
        for(String key:keys)require(Objects.equals(before.get(key),p.getAll().get(key)),"Navigation changed preference: "+key);
        require(RootAccess.INSTANCE.getCardProbe()==previousProbe,"Target selection unexpectedly ran a probe");
        require(!registered||(Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered()),"Navigation interrupted IMS");
        output.putString("stream","Optional Root/module settings grouped; standard settings and card choices separated; Root card back navigation and module apply reachability passed; existing card and enhancement preferences unchanged. IMS retained="+registered+". No authorization, APDU, module apply, call or SMS triggered.\n");
    }
    private void reveal(String label)throws Exception{
        ui(()->{View view=find(screen,label);require(view!=null,"Missing: "+label);if(!view.isClickable()&&view.getParent() instanceof View)view=(View)view.getParent();view.requestRectangleOnScreen(new android.graphics.Rect(0,0,view.getWidth(),view.getHeight()),true);});settle();
    }
    private void rootReadCheck(Bundle output)throws Exception{
        Context context=getTargetContext();SharedPreferences p=Engine.INSTANCE.getPrefs();
        require(VoWifiService.Companion.getCurrent()==null&&Engine.INSTANCE.getSession()==null&&Engine.INSTANCE.getCall()==null,"Root read check requires idle IMS");
        require(p.getBoolean("root_enabled",false),"Root read check requires existing user authorization");
        Map<String,?> before=new HashMap<>(p.getAll());int slot=p.getInt("slot",0),port=p.getInt("port",0);String aid=p.getString("usim","A0000000871002");
        RootAccess.CardProbe prior=RootAccess.INSTANCE.getCardProbe();
        screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));click("设置");click("Root 与模块");click("授权状态");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<deadline&&Engine.INSTANCE.getBusy())Thread.sleep(100);settle();
        require("已授权".equals(RootAccess.INSTANCE.getStatus())&&RootAccess.INSTANCE.getCardProbe()==prior,"Authorization unexpectedly performed a card probe");
        RootAccess.CardProbe report=RootAccess.INSTANCE.verifyCard(context,slot,port,aid);
        require(report.getPassed()&&report.getStatusWord()==0x9000&&report.getBytes()>0&&report.getSlot()==slot&&report.getPort()==port&&report.getAid().equalsIgnoreCase(aid),"Configured target read failed");
        settle();require(find(screen,"SIM"+(slot+1)+" · 通过")!=null,"UI attributed the receipt to a different slot");capture("device-043-selected-read");
        boolean rejected=false;try{RootAccess.INSTANCE.verifyCard(context,-1,port,aid);}catch(IllegalArgumentException expected){rejected=true;}require(rejected&&RootAccess.INSTANCE.getCardProbe()==report,"Invalid target touched the last receipt");
        require(before.equals(p.getAll()),"Probe changed operational preferences");require("已授权".equals(RootAccess.INSTANCE.getStatus()),"Probe changed authorization verdict");
        output.putString("stream","UID-only authorization did not read a card; explicit read-only probe used configured slot="+slot+" port="+port+" and configured AID; SW9000 bytes="+report.getBytes()+". Invalid target rejected before access; operational preferences unchanged. Card handles released; no module apply, authentication, profile writes, call or SMS.\n");
    }
    private void eventsInspect(Bundle output){
        Context context=getTargetContext();SharedPreferences p=Engine.INSTANCE.getPrefs();android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);StringBuilder r=new StringBuilder();
        r.append("registered=").append(Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered()).append(" activeCall=").append(Engine.INSTANCE.getCall()!=null).append(" notificationsEnabled=").append(manager.areNotificationsEnabled()).append('\n');
        for(String key:List.of("root_enabled","system_sms","system_calls","system_sms_send","system_lpa"))r.append(key).append('=').append(p.getBoolean(key,false)).append('\n');
        for(NotificationChannel channel:manager.getNotificationChannels())r.append("channel=").append(channel.getId()).append(" importance=").append(channel.getImportance()).append(" sound=").append(channel.getSound()!=null).append(" userSound=").append(channel.hasUserSetSound()).append('\n');
        r.append("activeOwnNotifications=").append(manager.getActiveNotifications().length).append('\n');
        synchronized(Engine.INSTANCE.getMessages()){r.append("localMessages=").append(Engine.INSTANCE.getMessages().size()).append(" unread=").append(Engine.INSTANCE.getMessages().stream().filter(m->m.getIncoming()&&!m.getRead()).count()).append(" mirrored=").append(Engine.INSTANCE.getMessages().stream().filter(m->m.getIncoming()&&m.getSystemRow()>0).count()).append('\n');}
        synchronized(Engine.INSTANCE){for(String line:Engine.INSTANCE.getLogs())if(line.contains("RP-ACK")||line.contains("系统短信写入失败")||line.contains("系统来电界面不可用"))r.append(line).append('\n');}
        output.putString("stream",r.toString());
    }
    /** Read-only receipt metadata; never returns sender, body or message identifiers. */
    private void eventsReceipt(Bundle output)throws Exception{
        Context context=getTargetContext();android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);
        List<Engine.Message> messages; synchronized(Engine.INSTANCE.getMessages()){messages=new ArrayList<>(Engine.INSTANCE.getMessages());}
        Engine.Message latest=messages.stream().filter(m->m.getIncoming()&&m.getId().startsWith("ims:")).max(Comparator.comparingLong(Engine.Message::getTime)).orElseThrow(()->new AssertionError("No network receipt"));
        org.json.JSONObject check=RootAccess.INSTANCE.checkSms(context,latest.getNumber(),latest.getText(),latest.getTime());
        require(latest.getSystemRow()>0&&check.getLong("row")==latest.getSystemRow(),"Latest receipt system readback failed");
        boolean notified=false;int notices=0;for(android.service.notification.StatusBarNotification notice:manager.getActiveNotifications())if(EventNotifications.SMS_CHANNEL.equals(notice.getNotification().getChannelId())){notices++;if(("sms:"+latest.getId()).equals(notice.getTag()))notified=true;}
        StringBuilder r=new StringBuilder("latestNetworkReceipt ageSeconds=").append((System.currentTimeMillis()-latest.getTime())/1000).append(" localCopies=").append(messages.stream().filter(m->m.getId().equals(latest.getId())).count()).append(" systemReadback=true systemRead=").append(check.getBoolean("read")).append(" ownNotificationPosted=").append(notified).append(" smsChannelNotifications=").append(notices).append('\n');
        getUiAutomation().adoptShellPermissionIdentity("android.permission.READ_SMS");
        try(android.database.Cursor rows=context.getContentResolver().query(android.net.Uri.parse("content://sms"),new String[]{"_id"},"address=? AND body=? AND date=? AND type=1",new String[]{latest.getNumber(),latest.getText(),Long.toString(latest.getTime())},null)){
            require(rows!=null,"System receipt count unavailable");r.append("exactSystemCopies=").append(rows.getCount()).append('\n');
        }catch(SecurityException denied){r.append("exactSystemCopies=permission-denied; authenticated Root readback passed\n");}
        finally{getUiAutomation().dropShellPermissionIdentity();}
        r.append("rootEffectiveUidRestored=").append(RootAccess.INSTANCE.utilityIdentity(context).getInt("euid")==0).append(" registered=").append(Engine.INSTANCE.getSession()!=null&&Engine.INSTANCE.getSession().registered()).append('\n');
        output.putString("stream",r.toString());
    }
    private void eventsCheck(Bundle output)throws Exception{
        Context context=getTargetContext();require(Engine.INSTANCE.getSession()==null&&Engine.INSTANCE.getCall()==null&&VoWifiService.Companion.getCurrent()==null,"Event checks require idle IMS");require(context.getSystemService(android.telephony.TelephonyManager.class).getCallState()==0,"Native call active");
        SharedPreferences preferences=Engine.INSTANCE.getPrefs();require(preferences.getBoolean("root_enabled",false)&&preferences.getBoolean("system_sms",false),"Root/system inbox must be enabled");
        android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);require(manager.areNotificationsEnabled(),"Notifications blocked");
        String nonce=UUID.randomUUID().toString().replace("-",""),id="local-event-test:"+nonce,number="+9990000401",body="VoDroid 本机收件验证 · "+nonce;long time=0,row=0;
        boolean calls=preferences.getBoolean("system_calls",false);io.vodroid.core.ImsSession.Call fixture=new io.vodroid.core.ImsSession.Call("local-ring:"+nonce,number,"ringing",true,"PCMA");
        try{
            screen=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Engine.INSTANCE.sms(id,number,body);Engine.INSTANCE.sms(id,number,body);capture("device-041-sms-notice");
            java.util.concurrent.CountDownLatch sync=new java.util.concurrent.CountDownLatch(1);Engine.INSTANCE.getIo().execute(sync::countDown);require(sync.await(25,java.util.concurrent.TimeUnit.SECONDS),"System SMS write did not finish");
            Engine.Message message; synchronized(Engine.INSTANCE.getMessages()){require(Engine.INSTANCE.getMessages().stream().filter(m->id.equals(m.getId())).count()==1,"Duplicate SMS stored locally");message=Engine.INSTANCE.getMessages().stream().filter(m->id.equals(m.getId())).findFirst().orElseThrow();}
            time=message.getTime();row=message.getSystemRow();require(row>0,"SMS row missing: "+message.getSystemError());
            org.json.JSONObject check=RootAccess.INSTANCE.checkSms(context,number,body,time);require(check.getLong("row")==row&&!check.getBoolean("read"),"System inbox readback differs");
            long repeated=RootAccess.INSTANCE.insertSms(context,number,body,time,false,-1);require(repeated==row,"Retry inserted a duplicate system SMS");
            require(RootAccess.INSTANCE.utilityIdentity(context).getInt("euid")==0,"SMS provider lease did not restore Root identity");
            android.service.notification.StatusBarNotification sms=null;for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())if(("sms:"+id).equals(n.getTag()))sms=n;
            require(sms!=null&&EventNotifications.SMS_CHANNEL.equals(sms.getNotification().getChannelId()),"SMS notification was not posted");NotificationChannel smsChannel=manager.getNotificationChannel(EventNotifications.SMS_CHANNEL);require(smsChannel.getImportance()==android.app.NotificationManager.IMPORTANCE_HIGH,"SMS channel not high importance");
            sms.getNotification().contentIntent.send();settle();require(find(screen,"短信内容")!=null,"Notification did not open SMS thread");capture("device-041-sms-thread");
            preferences.edit().putBoolean("system_calls",false).commit();Engine.INSTANCE.call(fixture);Thread.sleep(350);
            android.service.notification.StatusBarNotification ringing=null;for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())if(n.getId()==200)ringing=n;
            require(ringing!=null&&EventNotifications.CALL_CHANNEL.equals(ringing.getNotification().getChannelId()),"Incoming call notification missing");require(ringing.getNotification().fullScreenIntent!=null&&Notification.CallStyle.class.getName().equals(ringing.getNotification().extras.getString("android.template")),"CallStyle/full-screen intent missing");
            ActivityMonitor monitor=addMonitor(IncomingCallActivity.class.getName(),null,false);ringing.getNotification().contentIntent.send();Activity incoming=waitForMonitorWithTimeout(monitor,4000);removeMonitor(monitor);require(incoming!=null,"Incoming-call screen did not open");screen=incoming;settle();require(find(screen,"接听")!=null&&find(screen,"拒接")!=null,"Incoming call actions missing");capture("device-041-incoming");
            Engine.INSTANCE.call(new io.vodroid.core.ImsSession.Call(fixture.id(),number,"ended",true,"PCMA"));settle();for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())require(n.getId()!=200,"Ended call left ringing notification");
            output.putString("stream","Local incoming event checks: stable SMS identity stored once; system inbox row inserted, read back unread, retry reused same row; SMS notification and thread link verified; CallStyle with full-screen intent and answer/decline screen verified, ended call notification removed. SMS sound="+(smsChannel.getSound()!=null)+". Test row/notification/local message cleaned; original call setting restored. No network calls or SMS sent.\n");
        }finally{
            preferences.edit().putBoolean("system_calls",calls).commit();if(Engine.INSTANCE.getCall()!=null&&fixture.id().equals(Engine.INSTANCE.getCall().id()))Engine.INSTANCE.call(new io.vodroid.core.ImsSession.Call(fixture.id(),number,"ended",true,"PCMA"));
            EventNotifications.INSTANCE.cancelSms(context,id);if(time>0)RootAccess.INSTANCE.cleanupTestSms(context,nonce,time);Engine.INSTANCE.removeLocalMessage(id);
        }
    }
    private void handoffReadonly(Bundle result)throws Exception{
        require(VoWifiService.Companion.getCurrent()==null&&Engine.INSTANCE.getCard()==null,"Installation handoff check requires idle card");
        java.util.concurrent.atomic.AtomicInteger count=new java.util.concurrent.atomic.AtomicInteger(),code=new java.util.concurrent.atomic.AtomicInteger(-1);
        BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){code.set(getResultCode());count.incrementAndGet();}};
        getTargetContext().registerReceiver(receiver,new IntentFilter("io.vodroid.TEST_READONLY_HANDOFF"),Context.RECEIVER_NOT_EXPORTED);
        PendingIntent callback=PendingIntent.getBroadcast(getTargetContext(),940,new Intent("io.vodroid.TEST_READONLY_HANDOFF").setPackage("io.vodroid"),PendingIntent.FLAG_MUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        try{
            String activation="1$smdp.test.invalid$readonly-test";android.telephony.euicc.DownloadableSubscription sub=new android.telephony.euicc.DownloadableSubscription.Builder(activation).setConfirmationCode("readonly-confirmation").build();
            Intent captured=EsimHandoff.fromSubscription(getTargetContext(),sub,true,callback);require(activation.equals(captured.getStringExtra(EsimHandoff.CODE))&&"readonly-confirmation".equals(captured.getStringExtra(EsimHandoff.CONFIRMATION)),"Subscription code or confirmation lost");
            Intent original=new Intent("android.telephony.euicc.action.PROVISION_EMBEDDED_SUBSCRIPTION").putExtra("android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION",sub).putExtra(EsimHandoff.CALLBACK,callback).putExtra(EsimHandoff.ACTIVATE,true);
            Intent redirected=EsimHandoff.redirect(original);require(redirected!=null&&activation.equals(EsimHandoff.extract(redirected))&&"readonly-confirmation".equals(redirected.getStringExtra(EsimHandoff.CONFIRMATION)),"Intent handoff lost subscription confirmation");
            require(redirected.getBooleanExtra(EsimHandoff.ACTIVATE,false)&&callback.equals(redirected.getParcelableExtra(EsimHandoff.CALLBACK,PendingIntent.class)),"Activation option or callback lost");
            original.putExtra(EsimHandoff.CONFIRMATION,"explicit-confirmation");require("explicit-confirmation".equals(EsimHandoff.redirect(original).getStringExtra(EsimHandoff.CONFIRMATION)),"Explicit confirmation was overwritten");
            screen=startActivitySync(captured.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));settle();require(find(screen,"安装 eSIM")!=null,"Installer missing");require(Engine.INSTANCE.getCard()==null&&count.get()==0,"Opening installer started a card operation");click("取消安装");require(count.get()==1&&code.get()==2,"Cancellation must return exactly one error callback");
            result.putString("stream","Read-only eSIM handoff: activation code, confirmation, explicit confirmation precedence, activation option and callback preserved; opening installer did not access card; cancellation returned one ERROR. Reserved .invalid input; no installation or profile write.\n");
        }finally{getTargetContext().unregisterReceiver(receiver);callback.cancel();}
    }
    private void orient(int value)throws Exception{
        ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);ui(()->screen.setRequestedOrientation(value));Activity replacement=waitForMonitorWithTimeout(monitor,3000);removeMonitor(monitor);if(replacement!=null)screen=replacement;settle();
    }
    private void audioLoopback(String name,int rate)throws Exception{
        java.util.concurrent.CountDownLatch frames=new java.util.concurrent.CountDownLatch(3);java.util.concurrent.atomic.AtomicReference<byte[]> encoded=new java.util.concurrent.atomic.AtomicReference<>();
        AudioBridge bridge=new AudioBridge(getTargetContext(),new io.vodroid.core.RtpEndpoint.Format(name,rate,96,true),data->{if(data.length>0){encoded.set(data.clone());frames.countDown();}return kotlin.Unit.INSTANCE;});
        try{bridge.start();require(frames.await(5,java.util.concurrent.TimeUnit.SECONDS),name+" microphone/encoder emitted no frames");byte[] frame=encoded.get();require(frame!=null&&frame.length>1,name+" empty codec frame");if(name.startsWith("AMR")){int ft=(frame[0]>>>3)&15;require(ft<=9,name+" invalid AMR storage header");}bridge.downlink(frame);Thread.sleep(80);}finally{bridge.close();}
    }
    private void tunBackend()throws Exception{
        require(android.net.VpnService.prepare(getTargetContext())==null,"Test emulator must grant ACTIVATE_VPN before TUN check");
        TunnelVpnService vpn=TunnelVpnService.Companion.await(getTargetContext());
        io.vodroid.core.IkeSession idle=new io.vodroid.core.IkeSession(s->{},s->{});
        io.vodroid.core.PacketNetwork network=new io.vodroid.core.PacketNetwork("10.77.0.2",p->{},true);
        try{vpn.attach(new io.vodroid.core.IkeSession.Tunnel("10.77.0.2",32,List.of("10.77.0.1"),"test.invalid",null),network,idle);try(io.vodroid.core.PacketNetwork.Port port=network.openUdp(0);java.net.DatagramSocket socket=new java.net.DatagramSocket()){require(port.number>0,"Could not bind a system socket to the assigned IMS address");require(vpn.protect(socket),"ePDG outer socket protection failed");}}finally{network.close();vpn.disconnect();idle.close();}
    }
    private static EditText editor(View root,String label){if(root instanceof EditText value&&label.equals(String.valueOf(root.getContentDescription())))return value;if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){EditText value=editor(group.getChildAt(i),label);if(value!=null)return value;}return null;}
    private static View find(Activity activity,String text){return find(activity.getWindow().getDecorView(),text);}
    private static View find(View root,String text){if(root.getVisibility()!=View.VISIBLE)return null;if(text.equals(String.valueOf(root.getContentDescription())))return root;if(root instanceof TextView view&&view.getText().toString().equals(text))return view;if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View v=find(group.getChildAt(i),text);if(v!=null)return v;}return null;}
    private void ui(Runnable action){java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();runOnMainSync(()->{try{action.run();}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new AssertionError("UI action failed: "+error.get(),error.get());}
    private void settle()throws Exception{waitForIdleSync();Thread.sleep(230);waitForIdleSync();}
    private void click(String text)throws Exception{
        settle();android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();boolean clicked=false;
        if(root!=null){for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)){if(!(text.equals(String.valueOf(node.getText()))||text.equals(String.valueOf(node.getContentDescription()))))continue;while(node!=null&&!node.isClickable())node=node.getParent();if(node!=null){clicked=node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);if(clicked)break;}}}
        if(!clicked)ui(()->{View v=find(screen,text);require(v!=null,"Missing: "+text);while(!v.isClickable()&&v.getParent() instanceof View)v=(View)v.getParent();require(v.isClickable(),"Not clickable: "+text);v.performClick();});settle();
    }
    private void capture(String name)throws Exception{settle();android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();require(bitmap!=null,"Screenshot unavailable");try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{bitmap.recycle();}}
}
