package io.vodroid

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Read-only bridge for selected LSPosed apps whose mount namespace hides Magisk files. */
class IntegrationConfigProvider:ContentProvider(){
    private val reportedUnavailable=java.util.concurrent.atomic.AtomicBoolean()
    override fun onCreate():Boolean{Engine.init(requireNotNull(context));return true}
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle{
        require(method=="config"){"Unsupported integration request"}
        if(!Engine.prefs.getBoolean("root_enabled",false))return Bundle().apply{putString("app_compat","false")}
        // Never trust a caller-supplied UID. The authenticated root helper checks the
        // real Binder UID against this module's enabled LSPosed scope before returning EID.
        val caller=Binder.getCallingUid()
        val identity=Binder.clearCallingIdentity()
        val configuration=try{RootAccess.scopedConfiguration(requireNotNull(context),caller)}catch(error:Exception){if(reportedUnavailable.compareAndSet(false,true))android.util.Log.w("VoDroidIntegration","Scoped configuration unavailable: ${error.javaClass.simpleName}");throw SecurityException("VoDroid configuration access denied")}finally{Binder.restoreCallingIdentity(identity)}
        return Bundle().apply{for(key in listOf("root_enabled","slot","force_euicc","app_compat","carrier_access","sms_bridge","eid"))putString(key,configuration.optString(key,""))}
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=throw UnsupportedOperationException("Read-only config calls only")
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException("Read-only integration")
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read-only integration")
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read-only integration")
    override fun getType(uri:Uri):String?=null
}
