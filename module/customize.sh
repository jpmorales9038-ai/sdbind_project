#!/system/bin/sh
ui_print "- SD/OTG Bind Mount"

MODDIR="$MODPATH"
CONF="$MODPATH/mounts.conf"
LOG="$MODPATH/mount.log"

if [ ! -f "$CONF" ]; then
    cat > "$CONF" << 'EOF'
# Formato: ORIGEN|DESTINO|HABILITADO(1/0)
# Ejemplo:
# /mnt/media_rw/1234-5678/Musica/|/storage/emulated/0/Musica/|1
EOF
fi

touch "$LOG"
chmod 644 "$CONF" "$LOG" 2>/dev/null
chmod 755 "$MODPATH/webctl.sh" "$MODPATH/service.sh" "$MODPATH/post-fs-data.sh" "$MODPATH/uninstall.sh" "$MODPATH/common/functions.sh" 2>/dev/null

# Carga helpers (install_manager_apk, log, …)
if [ -f "$MODPATH/common/functions.sh" ]; then
    . "$MODPATH/common/functions.sh"
fi

# Instala la app. El APK debug es testOnly: hace falta -t.
# Además pm no puede leer desde el directorio del módulo (SELinux),
# así que se copia a /data/local/tmp.
APK="$MODPATH/app/sdcard-bind-manager.apk"
if [ -f "$APK" ]; then
    ui_print "- Instalando la app SD Bind Manager..."
    if install_manager_apk "$APK"; then
        ui_print "- App instalada correctamente"
    else
        ui_print "- No se pudo instalar ahora: se reintentará al reiniciar"
        ui_print "- Si no aparece, instala module/app/sdcard-bind-manager.apk a mano"
    fi
else
    ui_print "- (Sin APK incluido: usa la WebUI del Manager para configurar)"
fi

ui_print "- Abre la app 'SD Bind Manager' o la WebUI del módulo para configurar las carpetas"
