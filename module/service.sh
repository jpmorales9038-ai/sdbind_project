#!/system/bin/sh
MODDIR="/data/adb/modules/sdcard_bind_ui"
. "$MODDIR/common/functions.sh"

# Espera a que el sistema termine de arrancar
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done

# Margen extra: el OTG/SD suele tardar unos segundos más en enumerarse
sleep 5

# Si customize.sh no pudo instalar la app (pm no suele estar en el install),
# se intenta una sola vez acá, ya con el sistema arriba.
APK="$MODDIR/app/sdcard-bind-manager.apk"
MARKER="$MODDIR/.apk_installed"
if [ -f "$APK" ] && [ ! -f "$MARKER" ]; then
    if pm path com.sdcardbind.manager >/dev/null 2>&1; then
        touch "$MARKER"
    elif pm install -r "$APK" >> "$MODDIR/mount.log" 2>&1; then
        log "App SD Bind Manager instalada tras el boot"
        touch "$MARKER"
    else
        log "No se pudo instalar la app automáticamente (pm install falló)"
    fi
fi

log "== service: intento de montaje tras boot_completed =="
apply_mounts
