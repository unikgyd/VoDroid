package io.vodroid;

import android.app.PendingIntent;
import android.content.*;
import android.telephony.euicc.DownloadableSubscription;
import io.vodroid.core.ActivationCode;

/** Shared payload contract for public links and the in-process LSPosed hook. */
public final class EsimHandoff {
    public static final String CODE="io.vodroid.extra.ACTIVATION_CODE";
    public static final String CONFIRMATION="io.vodroid.extra.CONFIRMATION_CODE";
    public static final String ACTIVATE="io.vodroid.extra.ACTIVATE_AFTER_DOWNLOAD";
    public static final String CALLBACK="io.vodroid.extra.DOWNLOAD_CALLBACK";
    private EsimHandoff(){}
    public static Intent create(Context context,String code,boolean activate,String confirmation,PendingIntent callback){
        return new Intent().setComponent(new ComponentName("io.vodroid","io.vodroid.EsimImportActivity"))
            .putExtra(CODE,code).putExtra(ACTIVATE,activate).putExtra(CONFIRMATION,confirmation).putExtra(CALLBACK,callback);
    }
    public static Intent fromSubscription(Context context,DownloadableSubscription subscription,boolean activate,PendingIntent callback){
        return create(context,subscription.getEncodedActivationCode(),activate,subscription.getConfirmationCode(),callback);
    }
    public static String extract(Intent intent){
        if(intent==null)return "";
        String explicit=intent.getStringExtra(CODE);if(explicit!=null)return explicit;
        for(String key:new String[]{"android.telephony.euicc.extra.ACTIVATION_CODE","android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_ACTIVATION_CODE","android.service.euicc.extra.ACTIVATION_CODE"}){String value=intent.getStringExtra(key);if(value!=null)return value;}
        DownloadableSubscription subscription=intent.getParcelableExtra("android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION",DownloadableSubscription.class);
        if(subscription!=null&&subscription.getEncodedActivationCode()!=null)return subscription.getEncodedActivationCode();
        if(Intent.ACTION_SEND.equals(intent.getAction())){CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);return text==null?"":text.toString();}
        return intent.getDataString()==null?"":intent.getDataString();
    }
    public static Intent redirect(Intent original){
        if(original==null)return null;
        String action=original.getAction();
        boolean install=action!=null&&(action.equals("android.telephony.euicc.action.START_EUICC_ACTIVATION")||action.equals("android.telephony.euicc.action.PROVISION_EMBEDDED_SUBSCRIPTION")||action.equals("android.service.euicc.action.PROVISION_EMBEDDED_SUBSCRIPTION")||action.equals("android.service.euicc.action.START_EUICC_ACTIVATION"));
        if(!install&&!Intent.ACTION_VIEW.equals(action))return null;
        String raw=install?extract(original):original.getDataString();
        if(!install){try{ActivationCode.parse(raw);}catch(IllegalArgumentException e){return null;}}
        // Preserve the launch and result semantics while changing only the installation destination.
        Intent result=new Intent(original);result.setComponent(new ComponentName("io.vodroid","io.vodroid.EsimImportActivity"));result.setPackage("io.vodroid");result.setAction("io.vodroid.action.IMPORT_ESIM");result.setData(null);result.setClipData(null);result.removeCategory(Intent.CATEGORY_BROWSABLE);result.putExtra(CODE,raw);
        DownloadableSubscription subscription=original.getParcelableExtra("android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION",DownloadableSubscription.class);
        if(!original.hasExtra(CONFIRMATION)&&subscription!=null)result.putExtra(CONFIRMATION,subscription.getConfirmationCode());
        return result;
    }
}
