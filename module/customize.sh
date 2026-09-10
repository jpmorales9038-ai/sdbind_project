#!/system/bin/sh
ui_print "- SD/OTG Bind Mount"

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

APK="$MODPATH/app/sdcard-bind-manager.apk"
if [ -f "$APK" ]; then
    ui_print "- APK incluido: si la app no está, instalala a mano (sdcard-bind-manager.apk)"
fi

ui_print "- Configurá las carpetas en la app o en la WebUI, luego 'Guardar y montar'"
