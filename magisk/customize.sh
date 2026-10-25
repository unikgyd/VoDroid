#!/system/bin/sh
SKIPMOUNT=false
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=true
ui_print "VoDroid: Android 13+ system integration"
[ "$API" -ge 33 ] || abort "Android 13 or newer is required"
CURRENT_CONFIG=/data/adb/modules/vodroid/system/etc/vodroid.properties
if [ -f "$CURRENT_CONFIG" ] && [ "$MODPATH" != /data/adb/modules/vodroid ]; then
    cp "$CURRENT_CONFIG" "$MODPATH/system/etc/vodroid.properties" || abort "Cannot preserve VoDroid configuration"
fi
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm "$MODPATH/system/etc/vodroid.properties" 0 1001 0640
set_perm "$MODPATH/service.sh" 0 0 0755
ui_print "App compatibility: enable in VoDroid, select target apps in LSPosed."
ui_print "Phone integration: optional com.android.phone scope. Reboot after configuration."
