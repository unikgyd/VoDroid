#!/system/bin/sh
MODDIR=${0%/*}
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
# The service remains disabled until the user enables system LPA in the app.
# No global package signature verification or SELinux setting is modified.
exit 0
