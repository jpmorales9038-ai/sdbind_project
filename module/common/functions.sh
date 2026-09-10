#!/system/bin/sh
# Funciones compartidas: montar/desmontar carpetas SD/OTG -> almacenamiento interno
# Formato de mounts.conf: ORIGEN|DESTINO|HABILITADO(1/0)

[ -n "$MODDIR" ] || MODDIR="/data/adb/modules/sdcard_bind_ui"
[ -n "$CONF" ] || CONF="$MODDIR/mounts.conf"
[ -n "$LOG" ] || LOG="$MODDIR/mount.log"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >> "$LOG"
}

# La WebUI escribe las rutas con '/' final; el picker de la app no lo hacía.
ensure_slash() {
    p="$1"
    case "$p" in
        ""|"/") echo "$p" ;;
        */) echo "$p" ;;
        *) echo "$p/" ;;
    esac
}

# Espera hasta ~15s a que aparezca una ruta (la SD/OTG puede montarse tarde)
wait_for_path() {
    SRC="$1"
    i=0
    while [ ! -d "$SRC" ] && [ "$i" -lt 30 ]; do
        sleep 0.5
        i=$((i + 1))
    done
    [ -d "$SRC" ]
}

mount_one() {
    SRC=$(ensure_slash "$1")
    DEST=$(ensure_slash "$2")

    if ! wait_for_path "$SRC"; then
        log "FALLO (origen no encontrado): $SRC"
        return 1
    fi

    mkdir -p "$DEST" 2>/dev/null

    if mountpoint -q "$DEST" 2>/dev/null; then
        log "Ya montado: $DEST"
        return 0
    fi

    if mount -o bind "$SRC" "$DEST" 2>>"$LOG"; then
        chcon -R u:object_r:media_rw_data_file:s0 "$DEST" 2>/dev/null
        log "OK: $SRC -> $DEST"
        return 0
    else
        log "FALLO (mount): $SRC -> $DEST"
        return 1
    fi
}

unmount_one() {
    DEST=$(ensure_slash "$1")
    if mountpoint -q "$DEST" 2>/dev/null; then
        umount -l "$DEST" 2>>"$LOG" && log "Desmontado: $DEST"
    fi
}

# Recorre mounts.conf ignorando comentarios (#) y líneas vacías
each_entry() {
    # $1 = nombre de la función a llamar con SRC DEST ENABLED
    [ -f "$CONF" ] || return 0
    while IFS='|' read -r SRC DEST ENABLED || [ -n "$SRC" ]; do
        [ -z "$SRC" ] && continue
        case "$SRC" in
            \#*) continue ;;
        esac
        "$1" "$SRC" "$DEST" "$ENABLED"
    done < "$CONF"
}

_apply_cb() {
    SRC="$1"; DEST="$2"; ENABLED="$3"
    if [ "$ENABLED" = "1" ]; then
        mount_one "$SRC" "$DEST"
    else
        log "Omitido (deshabilitado): $SRC -> $DEST"
    fi
}

_unmount_cb() {
    DEST="$2"
    unmount_one "$DEST"
}

apply_mounts() {
    each_entry _apply_cb
}

unmount_all() {
    each_entry _unmount_cb
}

# Copia el APK a /data/local/tmp (SELinux) e instala con -t (APK debug/testOnly).
install_manager_apk() {
    APKFILE="$1"
    [ -f "$APKFILE" ] || return 1

    if pm path com.sdcardbind.manager >/dev/null 2>&1; then
        log "App ya instalada"
        return 0
    fi

    TMPAPK="/data/local/tmp/sdcard-bind-manager.apk"
    mkdir -p /data/local/tmp 2>/dev/null
    cp -f "$APKFILE" "$TMPAPK" || return 1
    chmod 644 "$TMPAPK"
    chcon u:object_r:apk_data_file:s0 "$TMPAPK" 2>/dev/null || \
        chcon u:object_r:shell_data_file:s0 "$TMPAPK" 2>/dev/null || true

    i=0
    while ! pm list packages >/dev/null 2>&1 && [ "$i" -lt 40 ]; do
        sleep 1
        i=$((i + 1))
    done

    rc=1
    if pm install -r -t -g -d --user 0 "$TMPAPK" >> "$LOG" 2>&1; then
        rc=0
    elif pm install -r -t -g -d "$TMPAPK" >> "$LOG" 2>&1; then
        rc=0
    elif cmd package install -r -t --user 0 "$TMPAPK" >> "$LOG" 2>&1; then
        rc=0
    fi

    rm -f "$TMPAPK"
    if [ "$rc" -eq 0 ]; then
        log "App SD Bind Manager instalada"
    else
        log "FALLO pm install de $APKFILE"
    fi
    return $rc
}
