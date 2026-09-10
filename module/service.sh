#!/system/bin/sh
MODDIR="/data/adb/modules/sdcard_bind_ui"
. "$MODDIR/common/functions.sh"

# Espera a que el sistema termine de arrancar
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done

# Margen extra: el OTG/SD suele tardar unos segundos más en enumerarse
sleep 5

# Reintenta instalar la app si customize.sh no pudo (pm/SELinux/testOnly).
APK="$MODDIR/app/sdcard-bind-manager.apk"
if [ -f "$APK" ]; then
    if ! pm path com.sdcardbind.manager >/dev/null 2>&1; then
        log "== service: instalando app SD Bind Manager =="
        install_manager_apk "$APK"
    fi
fi

log "== service: intento de montaje tras boot_completed =="
apply_mounts
