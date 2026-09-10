#!/system/bin/sh
ui_print "- SD/OTG Bind Mount"

CONF="$MODPATH/mounts.conf"
if [ ! -f "$CONF" ]; then
    cat > "$CONF" << 'EOF'
# Formato: ORIGEN|DESTINO|HABILITADO(1/0)
# Ejemplo:
# /mnt/media_rw/1234-5678/Musica|/storage/emulated/0/Musica|1
EOF
fi

touch "$MODPATH/mount.log"
chmod 644 "$MODPATH/mounts.conf" "$MODPATH/mount.log" 2>/dev/null
chmod 755 "$MODPATH/webctl.sh" "$MODPATH/service.sh" "$MODPATH/post-fs-data.sh" "$MODPATH/uninstall.sh" "$MODPATH/common/functions.sh" 2>/dev/null

# Instala la app de gestión (Compose) si viene incluida en el zip del módulo
APK="$MODPATH/app/sdcard-bind-manager.apk"
if [ -f "$APK" ]; then
    ui_print "- Instalando la app SD Bind Manager..."
    if pm install -r "$APK" >> "$MODPATH/mount.log" 2>&1; then
        ui_print "- App instalada correctamente"
    else
        ui_print "- No se pudo instalar la app automáticamente (revisa mount.log)"
        ui_print "- Puedes seguir usando la WebUI del Manager sin problema"
    fi
else
    ui_print "- (Sin APK incluido: usa la WebUI del Manager para configurar)"
fi

ui_print "- Abre la app 'SD Bind Manager' o la WebUI del módulo para configurar las carpetas"
