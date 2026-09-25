#!/system/bin/sh
MODDIR="/data/adb/modules/sdcard_bind_ui"
. "$MODDIR/common/functions.sh"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done

sleep 5

log "== service: intento de montaje tras boot_completed =="
_protect_media_fuse
apply_mounts

log "== service: vigilando desconexión de SD/OTG =="
tick=0
while true; do
    # Bajado de 2s a 1s: la foto de diagnóstico (ver _dump_media_procs) confirmó que
    # MediaProvider y vold seguían vivos y protegidos (adj=-1000) en las dos caídas de este
    # log — no es que el OOM killer se los lleve puestos, así que no hay proceso que proteger
    # mejor para evitar esto. Lo único que sí está en nuestras manos es achicar la ventana:
    # con 1s el peor caso de "el bind está caído y todavía no lo notamos" baja a la mitad.
    sleep 1
    _protect_media_fuse
    watch_and_prune
    # DIAGNÓSTICO (build de logs): timeline fijo cada ~10s, no depende de que pase algo.
    # Umbral x2 (10 en vez de 5) para mantener el mismo cadencia real ahora que el loop
    # corre cada 1s en lugar de 2s.
    tick=$((tick + 1))
    if [ "$tick" -ge 10 ]; then
        tick=0
        heartbeat_log
    fi
done
