package io.vodroid.hookprobe;

import android.app.*;
import android.annotation.SuppressLint;
import android.content.*;
import android.os.Bundle;
import android.telephony.euicc.*;
import android.widget.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Normal app process: unlike instrumentation, LSPosed can apply the selected scope here. */
@SuppressLint("MissingPermission") // Intentionally unprivileged: denial is baseline evidence, not a request for system permissions.
public final class ProbeActivity extends Activity {
    private TextView status;
    private JSONObject evidence;
    public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(48,96,48,48);
        status=new TextView(this);status.setTextSize(18);page.addView(status);
        Button check=new Button(this);check.setText("检查 eSIM 识别");check.setOnClickListener(view->inspect());page.addView(check);
        Button handoff=new Button(this);handoff.setText("检查安装转接");handoff.setOnClickListener(view->handoff());page.addView(handoff);setContentView(page);inspect();
        if(getIntent().getBooleanExtra("handoff",false))handoff();
    }
    public void onResume(){super.onResume();if(status!=null)inspect();}
    private void inspect(){
        evidence=new JSONObject();EuiccManager manager=getSystemService(EuiccManager.class);
        try{
            evidence.put("euiccFeature",getPackageManager().hasSystemFeature("android.hardware.telephony.euicc"));
            evidence.put("unrelatedFeature",getPackageManager().hasSystemFeature("io.vodroid.hookprobe.absent"));
            evidence.put("managerPresent",manager!=null);evidence.put("enabled",manager!=null&&manager.isEnabled());
            if(manager!=null){evidence.put("managerClass",manager.getClass().getName());evidence.put("eidDeclaringClass",manager.getClass().getMethod("getEid").getDeclaringClass().getName());}
            try(java.io.InputStream input=new java.io.FileInputStream("/system/etc/vodroid.properties")){java.util.Properties configuration=new java.util.Properties();configuration.load(input);evidence.put("configuredCompat",configuration.getProperty("app_compat","false"));evidence.put("configuredEidDigits",configuration.getProperty("eid","").length());}catch(Exception error){evidence.put("configurationError",error.getClass().getSimpleName());}
            try{Bundle bridge=getContentResolver().call(android.net.Uri.parse("content://io.vodroid.integration"),"config",null,null);evidence.put("bridgePresent",bridge!=null);if(bridge!=null){evidence.put("bridgeCompat",bridge.getString("app_compat","false"));evidence.put("bridgeEidDigits",bridge.getString("eid","").length());}}catch(Exception error){evidence.put("bridgeError",error.getClass().getSimpleName());evidence.put("bridgeMessage",String.valueOf(error.getMessage()).replaceAll("[0-9]{7,}","[ID]"));}
            try{String eid=manager==null?null:manager.getEid();evidence.put("eidDigits",eid==null?0:eid.matches("[0-9]{32}")?32:-1);}catch(Exception error){evidence.put("eidError",error.getClass().getSimpleName());}
            android.content.SharedPreferences results=getSharedPreferences("results",MODE_PRIVATE);evidence.put("callbacks",results.getInt("count",0));evidence.put("result",results.getInt("code",-1));save();status.setText(evidence.toString(2));
        }catch(Exception error){status.setText(error.getClass().getSimpleName());}
    }
    private void handoff(){
        inspect();if(!evidence.optBoolean("euiccFeature")||!evidence.optBoolean("enabled")||evidence.optInt("eidDigits")!=32){Toast.makeText(this,"eSIM 兼容尚未生效",Toast.LENGTH_SHORT).show();return;}
        PendingIntent result=PendingIntent.getBroadcast(this,1,new Intent(this,ResultReceiver.class),PendingIntent.FLAG_MUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        try{getSystemService(EuiccManager.class).downloadSubscription(DownloadableSubscription.forActivationCode("1$smdp.example.invalid$vodroid-local-hook-probe"),false,result);}
        catch(Exception error){try{evidence.put("handoffError",error.getClass().getSimpleName());save();status.setText(evidence.toString(2));}catch(Exception ignored){}}
        // Do not press install in the receiving app: the reserved .invalid host is deliberately unusable.
    }
    private void save()throws Exception{try(java.io.OutputStream output=openFileOutput("probe.json",MODE_PRIVATE)){output.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));}}
}
