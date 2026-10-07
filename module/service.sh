#!/system/bin/sh
MODDIR="/data/adb/modules/sdcard_bind_ui"
. "$MODDIR/common/functions.sh"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done

sleep 5

log "== service: intento de montaje tras boot_completed =="
perf_load
_protect_media_fuse
apply_mounts

log "== service: vigilando desconexión de SD/OTG =="
while true; do
    # 1s: MediaProvider/vold no son el problema (quedan protegidos con adj=-1000), así que
    # lo único que reduce el impacto de una caída es achicar la ventana hasta notarla.
    # perf_load: solo builtins; relee perf.conf para que un cambio de la app surta efecto sin reiniciar.
    perf_load
    sleep "$PERF_INTERVAL"
    _protect_media_fuse
    watch_and_prune
done
