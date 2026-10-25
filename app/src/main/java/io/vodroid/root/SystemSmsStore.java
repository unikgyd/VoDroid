package io.vodroid.root;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.IOException;
import java.lang.reflect.*;

/** Fixed SMS operations through an external provider lease, as used by Android's content command.
 * A standalone app_process has no registered ApplicationThread for Context's provider acquisition.
 */
final class SystemSmsStore implements AutoCloseable {
    static final String TEST_NUMBER="+9990000401",TEST_PREFIX="VoDroid 本机收件验证 · ";
    private final Object activity,provider;
    private final Class<?> activityType,providerType;
    private final IBinder token=new Binder();
    private final int user;
    private final AttributionSource attribution=new AttributionSource.Builder(1000).setPackageName("android").build();
    SystemSmsStore(int user)throws Exception{
        if(android.os.Process.myUid()!=0||android.system.Os.geteuid()!=0)throw new SecurityException("Root only");this.user=user;
        activityType=Class.forName("android.app.IActivityManager");providerType=Class.forName("android.content.IContentProvider");
        activity=Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);
        // OEM SMS AppOps can soft-deny uid 0 (returning URI /0). Borrow the
        // system effective UID for this fixed provider lease, with matching
        // Binder attribution; always restore root before the next RPC.
        android.system.Os.seteuid(1000);
        try{Object holder=activityType.getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class).invoke(activity,"sms",user,token,"VoDroid SMS");
            if(holder==null)throw new IOException("SMS provider unavailable");provider=holder.getClass().getField("provider").get(holder);
            if(provider==null){close();throw new IOException("SMS provider is not running");}
        }catch(Exception error){android.system.Os.seteuid(0);throw error;}
    }
    private static void validate(String number,String text,long time){if(number.isBlank()||number.length()>128||text.length()>65536||time<=0)throw new IllegalArgumentException("Invalid SMS fields");}
    private static Bundle selection(String number,String text,long time){Bundle query=new Bundle();query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,"address=? AND body=? AND date=? AND type=1");query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{number,text,Long.toString(time)});return query;}
    long find(String number,String text,long time)throws Exception{
        validate(number,text,time);try(Cursor cursor=(Cursor)providerType.getMethod("query",AttributionSource.class,Uri.class,String[].class,Bundle.class,Class.forName("android.os.ICancellationSignal")).invoke(provider,attribution,Uri.parse("content://sms"),new String[]{"_id"},selection(number,text,time),null)){
            if(cursor==null)throw new IOException("SMS provider query failed");return cursor.moveToFirst()?cursor.getLong(0):0;
        }
    }
    long insert(String number,String text,long time,boolean read,int subscription)throws Exception{
        long existing=find(number,text,time);if(existing!=0)return existing;
        ContentValues values=new ContentValues();values.put("address",number);values.put("body",text);values.put("date",time);values.put("date_sent",time);values.put("read",read?1:0);values.put("seen",read?1:0);values.put("type",1);values.put("sub_id",subscription);values.put("creator","io.vodroid");
        Uri result=(Uri)providerType.getMethod("insert",AttributionSource.class,Uri.class,ContentValues.class,Bundle.class).invoke(provider,attribution,Uri.parse("content://sms"),values,null);
        if(result==null)throw new IOException("SMS provider rejected insertion");long id=ContentUris.parseId(result);long found=find(number,text,time);if(id<=0||found!=id)throw new IOException("SMS provider did not retain row (id="+id+", found="+found+")");return id;
    }
    boolean read(long id)throws Exception{
        if(id<=0)throw new IllegalArgumentException("Invalid SMS row");try(Cursor cursor=(Cursor)providerType.getMethod("query",AttributionSource.class,Uri.class,String[].class,Bundle.class,Class.forName("android.os.ICancellationSignal")).invoke(provider,attribution,ContentUris.withAppendedId(Uri.parse("content://sms"),id),new String[]{"read"},null,null)){return cursor!=null&&cursor.moveToFirst()&&cursor.getInt(0)!=0;}
    }
    int cleanupTest(String nonce,long time)throws Exception{
        if(!nonce.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("Invalid test nonce");
        return (int)providerType.getMethod("delete",AttributionSource.class,Uri.class,Bundle.class).invoke(provider,attribution,Uri.parse("content://sms"),selection(TEST_NUMBER,TEST_PREFIX+nonce,time));
    }
    public void close()throws Exception{try{activityType.getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class).invoke(activity,"sms",token,user);}finally{android.system.Os.seteuid(0);}}
}
