#!/system/bin/sh
MODDIR="/data/adb/modules/sdcard_bind_ui"
. "$MODDIR/common/functions.sh"

# Espera a que el sistema termine de arrancar
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done

# Margen extra: el OTG/SD suele tardar unos segundos más en enumerarse
sleep 5

log "== service: intento de montaje tras boot_completed =="
apply_mounts
