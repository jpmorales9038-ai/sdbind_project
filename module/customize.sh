#!/system/bin/sh
ui_print "- SD/OTG Bind Mount"

CONF="$MODPATH/mounts.conf"
LOG="$MODPATH/mount.log"

# customize.sh corre siempre en el directorio NUEVO de la actualización, donde mounts.conf
# todavía no existe — sin esto, cada actualización del módulo pisaba la config real del
# usuario con la plantilla vacía de abajo (los binds ya montados seguían andando hasta el
# próximo reinicio, pero ese reinicio los perdía porque apply_mounts ya no tenía nada que
# aplicar). La instalación anterior sigue viva en /data/adb/modules/<id> hasta que el
# arranque completa el swap, así que su mounts.conf real todavía se puede leer de ahí.
OLD="/data/adb/modules/sdcard_bind_ui/mounts.conf"
if [ ! -f "$CONF" ] && [ -f "$OLD" ]; then
    cp -f "$OLD" "$CONF" 2>/dev/null
    ui_print "- Vínculos de la instalación anterior preservados"
fi

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
    ui_print "- Instalando SD Bind Manager"
    if pm install -r "$APK" >/dev/null 2>&1; then
        ui_print "- App instalada / actualizada"
    else
        ui_print "- No se pudo actualizar la app (firma distinta a la instalada)."
        ui_print "  Desinstalá SD Bind una vez y volvé a flashear el módulo."
    fi
fi

ui_print "- Abrí la app SD Bind, configurá las carpetas y tocá 'Guardar y montar'"
