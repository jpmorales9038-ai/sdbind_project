#!/system/bin/sh
MODDIR="/data/adb/modules/sdcard_bind_ui"
. "$MODDIR/common/functions.sh"

log "== post-fs-data: intento temprano de montaje =="
# Se ejecuta en segundo plano para no retrasar el arranque si la SD/OTG tarda
apply_mounts &
