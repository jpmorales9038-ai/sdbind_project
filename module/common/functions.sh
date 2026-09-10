#!/system/bin/sh
# Funciones compartidas: montar/desmontar carpetas SD/OTG -> almacenamiento interno
# Formato de mounts.conf: ORIGEN|DESTINO|HABILITADO(1/0)

[ -n "$MODDIR" ] || MODDIR="/data/adb/modules/sdcard_bind_ui"
[ -n "$CONF" ] || CONF="$MODDIR/mounts.conf"
[ -n "$LOG" ] || LOG="$MODDIR/mount.log"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >> "$LOG"
}

ensure_slash() {
    p="$1"
    case "$p" in
        ""|"/") echo "$p" ;;
        */) echo "$p" ;;
        *) echo "$p/" ;;
    esac
}

strip_slash() {
    p="$1"
    case "$p" in
        /) echo / ;;
        */) echo "${p%/}" ;;
        *) echo "$p" ;;
    esac
}

# La app (libsu) corre en un mount namespace aislado: el bind queda invisible
# para el resto de apps. La WebUI de KSU sí usa el namespace global (init).
# nsenter -t 1 -m hace el mount donde todo el sistema lo ve.
run_global() {
    if [ -r /proc/1/ns/mnt ]; then
        if command -v nsenter >/dev/null 2>&1; then
            nsenter -t 1 -m -- "$@"
            return $?
        fi
        if [ -x /system/bin/nsenter ]; then
            /system/bin/nsenter -t 1 -m -- "$@"
            return $?
        fi
        if [ -x /system/bin/toybox ]; then
            /system/bin/toybox nsenter -t 1 -m -- "$@"
            return $?
        fi
    fi
    "$@"
}

# Nunca bind/umount de la raíz del almacenamiento interno (rompe el teléfono).
is_protected() {
    p=$(strip_slash "$1")
    case "$p" in
        ""|"/"|"/storage"|"/storage/emulated"|"/storage/emulated/0"|"/sdcard"|"/mnt/sdcard"|"/data"|"/data/media"|"/data/media/0"|"/mnt"|"/mnt/user"|"/mnt/user/0"|"/mnt/runtime"|"/mnt/pass_through"|"/mnt/media_rw")
            return 0 ;;
    esac
    return 1
}

# Consulta la tabla de montaje de init (namespace global), no la del proceso actual.
is_mounted() {
    p=$(strip_slash "$1")
    [ -n "$p" ] || return 1
    if [ -r /proc/1/mounts ]; then
        awk -v p="$p" '$2 == p { found=1 } END { exit found ? 0 : 1 }' /proc/1/mounts
        return $?
    fi
    run_global mountpoint -q "$p" 2>/dev/null
}

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

    if is_protected "$DEST"; then
        log "FALLO (destino inseguro, elegí una subcarpeta): $DEST"
        return 1
    fi

    if ! wait_for_path "$SRC"; then
        log "FALLO (origen no encontrado): $SRC"
        return 1
    fi

    run_global mkdir -p "$DEST" 2>/dev/null

    if is_mounted "$DEST"; then
        log "Ya montado: $DEST"
        return 0
    fi

    if run_global mount -o bind "$(strip_slash "$SRC")" "$(strip_slash "$DEST")" 2>>"$LOG"; then
        run_global chcon -R u:object_r:media_rw_data_file:s0 "$(strip_slash "$DEST")" 2>/dev/null
        log "OK: $SRC -> $DEST"
        return 0
    else
        log "FALLO (mount): $SRC -> $DEST"
        return 1
    fi
}

unmount_one() {
    DEST=$(ensure_slash "$1")
    if is_protected "$DEST"; then
        log "Omitido (ruta protegida, no se desmonta): $DEST"
        return 0
    fi
    if is_mounted "$DEST"; then
        run_global umount -l "$(strip_slash "$DEST")" 2>>"$LOG" && log "Desmontado: $DEST"
    fi
}

each_entry() {
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

remove_entry() {
    SRC=$(ensure_slash "$1")
    DEST=$(ensure_slash "$2")
    unmount_one "$DEST"
    [ -f "$CONF" ] || return 0
    tmp="$CONF.tmp.$$"
    : > "$tmp"
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            ""|\#*) echo "$line" >> "$tmp"; continue ;;
        esac
        S=${line%%|*}
        rest=${line#*|}
        D=${rest%%|*}
        if [ "$(strip_slash "$S")" = "$(strip_slash "$SRC")" ] && [ "$(strip_slash "$D")" = "$(strip_slash "$DEST")" ]; then
            log "Eliminado: $S -> $D"
            continue
        fi
        echo "$line" >> "$tmp"
    done < "$CONF"
    mv "$tmp" "$CONF"
    chmod 644 "$CONF" 2>/dev/null
}

# SIZE|USED|AVAIL|PERCENT desde el namespace de init (no el de la app/WebUI)
df_stats() {
    mp=$(strip_slash "$1")
    [ -n "$mp" ] || return 1
    run_global test -d "$mp" || return 1
    line=$(run_global df -Ph "$mp" 2>/dev/null | awk 'NR==2 {print}')
    [ -n "$line" ] || line=$(run_global df -h "$mp" 2>/dev/null | awk 'NR==2 {print}')
    [ -n "$line" ] || return 1
    echo "$line" | awk '{
        gsub(/%/, "", $(NF-1))
        print $(NF-4) "|" $(NF-3) "|" $(NF-2) "|" $(NF-1)
    }'
}

# INTERNAL|/data|52G|46G|6.8G|88
# EXTERNAL|/mnt/media_rw/XXXX-XXXX|117G|49G|68G|42
dump_storage() {
    if stats=$(df_stats /data); then
        echo "INTERNAL|/data|$stats"
    elif stats=$(df_stats /storage/emulated); then
        echo "INTERNAL|/storage/emulated|$stats"
    elif stats=$(df_stats /storage/emulated/0); then
        echo "INTERNAL|/storage/emulated/0|$stats"
    fi

    seen="|"
    if [ -r /proc/1/mounts ]; then
        while IFS= read -r d; do
            [ -n "$d" ] || continue
            id=$(basename "$d")
            case "$seen" in *"|$id|"*) continue ;; esac
            stats=$(df_stats "$d") || continue
            echo "EXTERNAL|$d|$stats"
            seen="${seen}${id}|"
        done <<EOF
$(awk '$2 ~ /^\/mnt\/media_rw\/[^/]+$/ || $2 ~ /^\/mnt\/expand\/[^/]+$/ { print $2 }' /proc/1/mounts)
EOF
    fi
    for d in /mnt/media_rw/* /mnt/expand/*; do
        [ -d "$d" ] || continue
        id=$(basename "$d")
        case "$seen" in *"|$id|"*) continue ;; esac
        stats=$(df_stats "$d") || continue
        echo "EXTERNAL|$d|$stats"
        seen="${seen}${id}|"
    done
    for d in /storage/*; do
        [ -d "$d" ] || continue
        case "$d" in
            */emulated|*/self|*/sdcard) continue ;;
        esac
        REAL=$(readlink -f "$d" 2>/dev/null)
        case "$REAL" in
            /data/media*) continue ;;
        esac
        id=$(basename "$d")
        case "$seen" in *"|$id|"*) continue ;; esac
        stats=$(df_stats "$d") || continue
        echo "EXTERNAL|$d|$stats"
        seen="${seen}${id}|"
    done
}


theme_seed() {
    pkg=$(settings get secure theme_customization_overlay_packages 2>/dev/null)
    echo "$pkg" | tr ',{}' '\n' | grep -i system_palette | grep -oE '[0-9A-Fa-f]{6,8}' | head -1
}

find_gsr() {
    for f in \
        /system/fonts/GoogleSansRounded-Regular.ttf \
        /system/fonts/GoogleSansRounded-Medium.ttf \
        /system/fonts/GoogleSansRounded-VF.ttf \
        /product/fonts/GoogleSansRounded-Regular.ttf \
        /system_ext/fonts/GoogleSansRounded-Regular.ttf \
        /system/fonts/GoogleSansFlex-Variable.ttf \
        /system/fonts/GoogleSansFlex.ttf \
        /system/fonts/GoogleSansFlex-Regular.ttf \
        /system/fonts/GoogleSans-Regular.ttf
    do
        [ -f "$f" ] && echo "$f"
    done
    find /system/fonts /product/fonts /system_ext/fonts \
        \( -iname '*GoogleSansRound*' -o -iname '*GoogleSansFlex*' -o -iname 'GoogleSans-Regular*' \) \
        2>/dev/null
}
