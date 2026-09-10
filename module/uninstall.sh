#!/system/bin/sh
MODDIR="/data/adb/modules/sdcard_bind_ui"
if [ -f "$MODDIR/common/functions.sh" ]; then
    . "$MODDIR/common/functions.sh"
    unmount_all
fi
