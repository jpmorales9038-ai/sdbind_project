#!/system/bin/sh
MODDIR="/data/adb/modules/sdcard_bind_ui"
. "$MODDIR/common/functions.sh"

# Arranque nuevo: si quedó colgado de antes de reiniciar (el usuario había pedido
# "Desmontar todo" y apagó el teléfono sin volver a montar), no tiene sentido heredarlo —
# al boot, todo lo habilitado en mounts.conf se intenta montar igual, así que la
# autocuración también debería estar activa desde el vamos.
# Arranque nuevo: si quedó colgado de antes de reiniciar (el usuario había pedido
# "Desmontar todo" y apagó el teléfono sin volver a montar), no tiene sentido heredarlo —
# al boot, todo lo habilitado en mounts.conf se intenta montar igual, así que la
# autocuración también debería estar activa desde el vamos.
rm -f "$NOHEAL_MARKER" 2>/dev/null
_protect_media_fuse

log "== post-fs-data: intento temprano de montaje =="
# Se ejecuta en segundo plano para no retrasar el arranque si la SD/OTG tarda
apply_mounts &
