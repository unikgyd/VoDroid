package io.vodroid.hookprobe;

import android.content.*;

public final class ResultReceiver extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent){SharedPreferences results=context.getSharedPreferences("results",Context.MODE_PRIVATE);results.edit().putInt("count",results.getInt("count",0)+1).putInt("code",getResultCode()).commit();}
}
