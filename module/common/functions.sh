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
            nsenter -t 1 -m -- "$@" && return $?
            nsenter --mount=/proc/1/ns/mnt -- "$@" && return $?
        fi
        if [ -x /system/bin/nsenter ]; then
            /system/bin/nsenter -t 1 -m -- "$@" && return $?
            /system/bin/nsenter --mount=/proc/1/ns/mnt -- "$@" && return $?
        fi
        if [ -x /system/bin/toybox ]; then
            /system/bin/toybox nsenter -t 1 -m -- "$@" && return $?
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

_watch_cb() {
    SRC="$1"; DEST="$2"; ENABLED="$3"
    [ "$ENABLED" = "1" ] || return 0
    is_mounted "$DEST" || return 0
    # Sin nsenter a propósito: el origen (SD/OTG) no pasa por el FUSE de storage por app,
    # así que cualquier proceso ve igual si sigue presente o no (mismo criterio que
    # wait_for_path, que tampoco usa run_global para esto).
    if [ ! -d "$SRC" ]; then
        log "Auto-desmontado (origen desconectado): $SRC -> $DEST"
        unmount_one "$DEST"
    fi
}

# Recorre todos los binds habilitados y desmonta los que quedaron "colgando" porque su
# origen (tarjeta SD u OTG) ya no está conectado.
watch_and_prune() {
    each_entry _watch_cb
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

# SIZE|USED|AVAIL|PERCENT
df_stats() {
    mp=$(strip_slash "$1")
    [ -n "$mp" ] || return 1
    line=$(run_global df -Ph "$mp" 2>/dev/null | awk 'NR==2 {print}')
    [ -n "$line" ] || line=$(run_global df -h "$mp" 2>/dev/null | awk 'NR==2 {print}')
    [ -n "$line" ] || line=$(df -Ph "$mp" 2>/dev/null | awk 'NR==2 {print}')
    [ -n "$line" ] || return 1
    echo "$line" | awk '{
        gsub(/%/, "", $(NF-1))
        print $(NF-4) "|" $(NF-3) "|" $(NF-2) "|" $(NF-1)
    }'
}

# Subcarpetas de primer nivel dentro de DIR (una ruta por línea). Con run_global: un bind
# hecho en el namespace de init (ver mount_one) puede no verse desde el namespace propio del
# llamador si no se entra a ese mismo namespace para leerlo también.
list_subdirs() {
    DIR=$(strip_slash "$1")
    [ -n "$DIR" ] || return 1
    case "$DIR" in
        /mnt/media_rw|/mnt/expand)
            awk -v p="$DIR" '$2 ~ "^" p "/[^/]+$" { print $2 }' /proc/1/mounts 2>/dev/null | sort -u
            ;;
        *)
            run_global find "$DIR" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort
            ;;
    esac
}

# Archivos y carpetas de primer nivel dentro de DIR: "tipo|tamaño|nombre" por línea. Mismo
# motivo de run_global que list_subdirs.
list_dir_entries() {
    DIR=$(strip_slash "$1")
    [ -n "$DIR" ] || return 1
    run_global find "$DIR" -mindepth 1 -maxdepth 1 -printf '%y|%s|%f\n' 2>/dev/null
}

# Borra archivo o carpeta (con contenido). Mismo motivo de run_global: si PATH cuelga de una
# carpeta con bind, un rm sin esto actúa sobre la vista vacía del namespace del llamador.
delete_path() {
    P=$(strip_slash "$1")
    [ -n "$P" ] || return 1
    is_protected "$P" && return 1
    if [ "$2" = "dir" ]; then
        run_global rm -rf "$P"
    else
        run_global rm -f "$P"
    fi
}

dump_storage() {
    df_out=$(run_global df -aPh 2>/dev/null)
    [ -n "$df_out" ] || df_out=$(run_global df -Ph 2>/dev/null)
    [ -n "$df_out" ] || df_out=$(df -Ph 2>/dev/null)

    echo "$df_out" | awk '
        NR == 1 { next }
        {
            m = $NF
            sub(/\/$/, "", m)
            gsub(/%/, "", $(NF-1))
            stats = $(NF-4) "|" $(NF-3) "|" $(NF-2) "|" $(NF-1)
            if (m == "/data" || m == "/data/media" || m == "/storage/emulated/0" || m == "/storage/emulated") {
                if (!got_int) {
                    print "INTERNAL|" m "|" stats
                    got_int = 1
                }
            }
        }
    '

    seen="|"
    while IFS= read -r d; do
        [ -n "$d" ] || continue
        id=$(basename "$d")
        case "$id" in emulated|self|sdcard|media_rw|user|runtime|pass_through|"" ) continue ;; esac
        case "$seen" in *"|$id|"*) continue ;; esac

        stats=$(echo "$df_out" | awk -v id="$id" '
            NR == 1 { next }
            {
                m = $NF
                sub(/\/$/, "", m)
                n = m
                sub(/^.*\//, "", n)
                if (n == id && m !~ /emulated/ && m !~ /^\/data/) {
                    gsub(/%/, "", $(NF-1))
                    print $(NF-4) "|" $(NF-3) "|" $(NF-2) "|" $(NF-1)
                    exit
                }
            }
        ')
        if [ -z "$stats" ]; then
            stats=$(df_stats "$d")
        fi
        if [ -z "$stats" ]; then
            stats=$(df_stats "/mnt/media_rw/$id")
        fi
        if [ -z "$stats" ]; then
            stats=$(df_stats "/storage/$id")
        fi
        [ -n "$stats" ] || continue

        path="$d"
        [ -d "/mnt/media_rw/$id" ] && path="/mnt/media_rw/$id"
        echo "EXTERNAL|$path|$stats"
        seen="${seen}${id}|"
    done <<EOF
$( {
    [ -r /proc/1/mounts ] && awk '
        $2 ~ /^\/mnt\/media_rw\/[^/]+$/ { print $2 }
        $2 ~ /^\/mnt\/expand\/[^/]+$/ { print $2 }
        $2 ~ /^\/storage\/[^/]+$/ && $2 !~ /emulated|self|sdcard/ { print $2 }
        $2 ~ /^\/mnt\/runtime\/default\/[^/]+$/ && $2 !~ /emulated/ { print $2 }
    ' /proc/1/mounts
    echo "$df_out" | awk '
        NR == 1 { next }
        {
            m = $NF
            sub(/\/$/, "", m)
            if (m ~ /^\/mnt\/media_rw\/[^/]+$/) print m
            else if (m ~ /^\/mnt\/expand\/[^/]+$/) print m
            else if (m ~ /^\/storage\/[^/]+$/ && m !~ /emulated|self|sdcard/) print m
        }
    '
} | sort -u )
EOF
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
