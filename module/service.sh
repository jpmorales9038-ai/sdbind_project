#!/system/bin/sh
MODDIR="/data/adb/modules/sdcard_bind_ui"
. "$MODDIR/common/functions.sh"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done

sleep 5

log "== service: intento de montaje tras boot_completed =="
apply_mounts

log "== service: vigilando desconexión de SD/OTG =="
while true; do
    sleep 2
    watch_and_prune
done
