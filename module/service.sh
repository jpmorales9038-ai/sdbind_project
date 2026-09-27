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
    # watch_and_prune sí necesita 1s (barato: solo mira los binds configurados, no todo
    # /proc). _protect_media_fuse es lo caro (recorre TODOS los procesos del sistema) y no
    # hace falta tan seguido — MediaProvider no se reinicia todo el tiempo. Corriéndolo cada
    # segundo se notaba como lag justo después de reiniciar el teléfono, cuando hay más
    # procesos arrancando/muriendo y menos margen de CPU libre que en régimen estable (por
    # eso el lag se sentía solo los primeros minutos). Cada ~10s alcanza de sobra.
    sleep 1
    tick=$((tick + 1))
    if [ "$tick" -ge 10 ]; then
        tick=0
        _protect_media_fuse
    fi
    watch_and_prune
done
