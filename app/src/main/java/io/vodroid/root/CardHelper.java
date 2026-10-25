package io.vodroid.root;

import android.content.*;
import android.net.*;
import android.os.*;
import android.telephony.*;
import io.vodroid.core.Bytes;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Root child process, authenticated local IPC and a fixed command set. No shell-command RPC. */
public final class CardHelper {
    public static void main(String[] args) throws Exception {
        int status=0;
        try{run(args);}catch(Throwable error){error.printStackTrace();status=1;}
        // ActivityThread creates Binder threads; a returned main alone can leave
        // an orphan root process after the authenticated client disconnects.
        System.exit(status);
    }
    private static void run(String[] args) throws Exception {
        if(android.os.Process.myUid()!=0||args.length!=6)throw new SecurityException("Root helper requires explicit root launch");
        Looper.prepareMainLooper();Class<?> runtime=Class.forName("dalvik.system.VMRuntime");Object vm=runtime.getDeclaredMethod("getRuntime").invoke(null);runtime.getDeclaredMethod("setHiddenApiExemptions",String[].class).invoke(vm,(Object)new String[]{"L"});
        // app_process does not run ZygoteInit's telephony module initialization.
        Class<?> initializer=Class.forName("android.telephony.TelephonyFrameworkInitializer");
        if(initializer.getMethod("getTelephonyServiceManager").invoke(null)==null){
            Class<?> manager=Class.forName("android.os.TelephonyServiceManager");
            initializer.getMethod("setTelephonyServiceManager",manager).invoke(null,manager.getConstructor().newInstance());
        }
        Class<?> activity=Class.forName("android.app.ActivityThread");Object thread=activity.getDeclaredMethod("systemMain").invoke(null);Context context=(Context)activity.getDeclaredMethod("getSystemContext").invoke(thread);TelephonyManager tm=context.getSystemService(TelephonyManager.class);
        int expectedUid=Integer.parseInt(args[2]),slot=Integer.parseInt(args[3]),port=Integer.parseInt(args[4]),ownerPid=Integer.parseInt(args[5]);Set<Integer> channels=new HashSet<>();
        try(LocalServerSocket server=new LocalServerSocket(args[0])){
            java.util.concurrent.atomic.AtomicReference<LocalSocket> connection=new java.util.concurrent.atomic.AtomicReference<>();
            Thread ownerWatch=new Thread(()->{
                while(ownerAlive(ownerPid,expectedUid)){try{Thread.sleep(2000);}catch(InterruptedException done){return;}}
                try{server.close();}catch(IOException ignored){}
                try{LocalSocket active=connection.get();if(active!=null)active.close();}catch(IOException ignored){}
                try{Thread.sleep(10000);}catch(InterruptedException ignored){}
                System.exit(0);
            },"root-owner-watch");ownerWatch.setDaemon(true);ownerWatch.start();
            try(LocalSocket client=server.accept()){
            connection.set(client);
            if(client.getPeerCredentials().getUid()!=expectedUid||client.getPeerCredentials().getPid()!=ownerPid)throw new SecurityException("Unexpected IPC peer");DataInputStream input=new DataInputStream(client.getInputStream());DataOutputStream output=new DataOutputStream(client.getOutputStream());
            try{while(true){int len;try{len=input.readInt();}catch(EOFException e){break;}if(len<1||len>1048576)break;byte[] bytes=new byte[len];input.readFully(bytes);JSONObject result=new JSONObject();try{JSONObject request=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));if(!MessageDigest.isEqual(args[1].getBytes(),request.optString("token").getBytes()))throw new SecurityException("IPC authentication failed");String op=request.getString("op");switch(op){
                case "hello"->result.put("uid",android.os.Process.myUid()).put("euid",android.system.Os.geteuid());
                case "bye"->{result.put("ok",true);byte[] reply=result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);output.writeInt(reply.length);output.write(reply);output.flush();return;}
                case "reset_card"->{
                    if(slot<0||slot>=tm.getActiveModemCount()||!channels.isEmpty()||tm.getCallState()!=TelephonyManager.CALL_STATE_IDLE)throw new IllegalStateException("Card reset requires an idle selected slot with no owned channels");
                    Method power=tm.getClass().getMethod("setSimPowerStateForSlot",int.class,int.class);
                    try{power.invoke(tm,slot,0);Thread.sleep(1800);}finally{power.invoke(tm,slot,1);}
                }
                case "open"->{String aid=request.getString("aid");if(!aid.matches("(?i)([0-9a-f]{2}){5,32}"))throw new IllegalArgumentException("Invalid AID");
                    // Root-only compatibility: the modem consumes hex bytes, while
                    // some framework versions compare the ISD-R string case-sensitively.
                    // Preserve the exact AID bytes without changing global LPA policy.
                    aid=aid.toLowerCase(Locale.ROOT);
                    Method m=tm.getClass().getMethod("iccOpenLogicalChannelByPort",int.class,int.class,String.class,int.class);IccOpenLogicalChannelResponse response=(IccOpenLogicalChannelResponse)m.invoke(tm,slot,port,aid,0);if(response==null||response.getStatus()!=IccOpenLogicalChannelResponse.STATUS_NO_ERROR)throw new IOException("Logical channel denied; status="+(response==null?"null":response.getStatus()));channels.add(response.getChannel());result.put("channel",response.getChannel());}
                case "transmit"->{int handle=request.getInt("channel");if(!channels.contains(handle))throw new SecurityException("Channel is not owned by this client");io.vodroid.core.TelephonyApdu command=io.vodroid.core.TelephonyApdu.from(Bytes.hex(request.getString("data")));Method m=tm.getClass().getMethod("iccTransmitApduLogicalChannelByPort",int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class,String.class);String response=(String)m.invoke(tm,slot,port,handle,command.cla(),command.ins(),command.p1(),command.p2(),command.p3(),command.data());if(response==null)throw new IOException("Empty APDU response");result.put("data",response);}
                case "close"->{int handle=request.getInt("channel");if(channels.remove(handle))tm.getClass().getMethod("iccCloseLogicalChannelByPort",int.class,int.class,int.class).invoke(tm,slot,port,handle);}
                case "scoped_config"->{
                    int caller=request.getInt("callerUid");boolean allowed=caller==expectedUid;
                    String[] packages=context.getPackageManager().getPackagesForUid(caller);
                    if(!allowed&&caller>=0&&packages!=null){
                        // A standalone app_process has no registered ApplicationThread for
                        // Settings providers. Use SQLite's default process-local WAL flags;
                        // this database is opened read-only and is never reconfigured.
                        Class.forName("android.database.sqlite.SQLiteCompatibilityWalFlags").getMethod("init",String.class).invoke(null,(Object)null);
                        try(android.database.sqlite.SQLiteDatabase database=android.database.sqlite.SQLiteDatabase.openDatabase("/data/adb/lspd/config/modules_config.db",null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY|android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS,corrupt->{throw new IllegalStateException("Scope database is unreadable");})){
                            for(String name:packages){try(android.database.Cursor rows=database.rawQuery("SELECT 1 FROM scope s JOIN modules m ON s.mid=m.mid WHERE m.module_pkg_name=? AND m.enabled=1 AND s.app_pkg_name=? AND s.user_id=? LIMIT 1",new String[]{"io.vodroid",name,Integer.toString(caller/100000)})){if(rows.moveToFirst()){allowed=true;break;}}}
                        }
                    }
                    if(!allowed)throw new SecurityException("Caller is not in the enabled VoDroid scope");
                    Properties properties=new Properties();try(InputStream stream=new FileInputStream("/data/adb/modules/vodroid/system/etc/vodroid.properties")){properties.load(stream);}
                    for(String key:new String[]{"root_enabled","slot","force_euicc","app_compat","carrier_access","sms_bridge","eid"})result.put(key,properties.getProperty(key,key.equals("root_enabled")?"true":""));
                }
                case "disable_integration"->{
                    File file=new File("/data/adb/modules/vodroid/system/etc/vodroid.properties");
                    if(file.isFile()){
                        Properties properties=new Properties();try(InputStream stream=new FileInputStream(file)){properties.load(stream);}
                        for(String key:new String[]{"root_enabled","force_euicc","app_compat","carrier_access","sms_bridge"})properties.setProperty(key,"false");
                        // Keep the inode: it may already be mounted into /system.
                        try(OutputStream stream=new FileOutputStream(file)){properties.store(stream,"VoDroid integration disabled by user");}
                        android.system.Os.chown(file.getAbsolutePath(),0,1001);android.system.Os.chmod(file.getAbsolutePath(),0640);
                    }
                    context.getPackageManager().setComponentEnabledSetting(new ComponentName("io.vodroid","io.vodroid.SystemEuiccService"),android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,android.content.pm.PackageManager.DONT_KILL_APP);
                    result.put("modulePresent",file.isFile());
                }
                case "sms"->{try(SystemSmsStore store=new SystemSmsStore(expectedUid/100000)){long id=store.insert(request.getString("number"),request.getString("text"),request.getLong("time"),request.optBoolean("read",false),request.optInt("subscription",-1));result.put("row",id);}}
                case "sms_check"->{try(SystemSmsStore store=new SystemSmsStore(expectedUid/100000)){long id=store.find(request.getString("number"),request.getString("text"),request.getLong("time"));result.put("row",id);result.put("read",id>0&&store.read(id));}}
                case "sms_test_cleanup"->{try(SystemSmsStore store=new SystemSmsStore(expectedUid/100000)){result.put("deleted",store.cleanupTest(request.getString("nonce"),request.getLong("time")));}}
                case "call_account"->{android.telecom.TelecomManager telecom=context.getSystemService(android.telecom.TelecomManager.class);android.telecom.PhoneAccountHandle handle=new android.telecom.PhoneAccountHandle(new ComponentName("io.vodroid","io.vodroid.CallIntegrationService"),"vodroid",(android.os.UserHandle)android.os.UserHandle.class.getMethod("of",int.class).invoke(null,expectedUid/100000));telecom.getClass().getMethod("enablePhoneAccount",android.telecom.PhoneAccountHandle.class,boolean.class).invoke(telecom,handle,true);result.put("enabled",telecom.getPhoneAccount(handle)!=null&&telecom.getPhoneAccount(handle).isEnabled());}
                case "configure"->{boolean enabled=request.getBoolean("enabled");String eid=request.optString("eid");if(enabled&&!eid.matches("[0-9]{32}"))throw new IOException("Read the eUICC EID before enabling mapping");File file=new File("/data/adb/modules/vodroid/system/etc/vodroid.properties");if(!file.getParentFile().isDirectory())throw new IOException("Install the VoDroid Magisk module first");Properties properties=new Properties();properties.setProperty("root_enabled","true");properties.setProperty("slot",Integer.toString(slot));properties.setProperty("force_euicc",Boolean.toString(enabled));properties.setProperty("app_compat",Boolean.toString(request.optBoolean("appCompat",false)));properties.setProperty("carrier_access",Boolean.toString(request.getBoolean("carrierAccess")));properties.setProperty("sms_bridge",Boolean.toString(request.optBoolean("smsBridge",false)));properties.setProperty("eid",eid.matches("[0-9]{32}")?eid:"");try(OutputStream stream=new FileOutputStream(file)){properties.store(stream,"Explicit VoDroid local integration settings");}android.system.Os.chown(file.getAbsolutePath(),0,1001);android.system.Os.chmod(file.getAbsolutePath(),0640);context.getPackageManager().setComponentEnabledSetting(new ComponentName("io.vodroid","io.vodroid.SystemEuiccService"),enabled?android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED:android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,android.content.pm.PackageManager.DONT_KILL_APP);}
                default->throw new SecurityException("Unsupported operation");
            }result.put("ok",true);}catch(Exception e){Throwable cause=e instanceof InvocationTargetException?e.getCause():e;result.put("ok",false).put("error",cause.getClass().getSimpleName()+": "+Objects.toString(cause.getMessage(),"Root operation failed"));}byte[] response=result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);output.writeInt(response.length);output.write(response);output.flush();}}finally{for(int ch:channels)try{tm.getClass().getMethod("iccCloseLogicalChannelByPort",int.class,int.class,int.class).invoke(tm,slot,port,ch);}catch(Exception ignored){}}
            }finally{ownerWatch.interrupt();}
        }
    }
    private static boolean ownerAlive(int pid,int uid){
        if(pid<=0)return false;
        try(BufferedReader status=new BufferedReader(new FileReader("/proc/"+pid+"/status"))){String line;while((line=status.readLine())!=null){if(line.startsWith("State:")&&line.trim().split("\\s+")[1].equals("Z"))return false;if(line.startsWith("Uid:"))return Integer.parseInt(line.trim().split("\\s+")[1])==uid;}}catch(Exception ignored){}
        return false;
    }
}
