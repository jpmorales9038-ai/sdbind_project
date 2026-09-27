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

# Si "customize.sh" no pudo compilar el baseline profile en el momento de flashear (ese
# contexto no siempre tiene el framework de Android 100% disponible), se reintenta acá: a
# esta altura, tras boot_completed + los 5s de arriba, el sistema seguro está arriba del
# todo. Guardado con marcador para no repetir esto en cada reinicio una vez que ya funcionó.
if [ ! -f "$MODDIR/.aot_ok" ]; then
    if cmd package compile -m speed-profile -f com.sdcardbind.manager > "$MODDIR/.aot_log" 2>&1 \
        || pm compile -m speed-profile -f com.sdcardbind.manager >> "$MODDIR/.aot_log" 2>&1; then
        touch "$MODDIR/.aot_ok" 2>/dev/null
        log "== service: compilación AOT del baseline profile OK (reintento post-boot) =="
    else
        log "== service: compilación AOT SIGUE fallando tras boot_completed — ver .aot_log =="
    fi
fi

log "== service: vigilando desconexión de SD/OTG =="
while true; do
    # 1s: MediaProvider/vold no son el problema (quedan protegidos con adj=-1000), así que
    # lo único que reduce el impacto de una caída es achicar la ventana hasta notarla.
    sleep 1
    _protect_media_fuse
    watch_and_prune
done
