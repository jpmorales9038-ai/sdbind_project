#!/system/bin/sh
MODDIR="/data/adb/modules/sdcard_bind_ui"
CONF="$MODDIR/mounts.conf"
. "$MODDIR/common/functions.sh"

case "$1" in
    apply)
        unmount_all
        apply_mounts
        echo "DONE"
        ;;

    unmount)
        unmount_all
        echo "DONE"
        ;;

    status)
        [ -f "$CONF" ] || exit 0
        while IFS='|' read -r SRC DEST ENABLED || [ -n "$SRC" ]; do
            [ -z "$SRC" ] && continue
            case "$SRC" in \#*) continue ;; esac
            if mountpoint -q "$DEST" 2>/dev/null; then
                ST="MOUNTED"
            elif [ -d "$SRC" ]; then
                ST="UNMOUNTED"
            else
                ST="SOURCE_MISSING"
            fi
            echo "${SRC%/}/|${DEST%/}/|$ENABLED|$ST"
        done < "$CONF"
        ;;

    detect)
        # Lista carpetas candidatas dentro de tarjetas SD / unidades OTG montadas
        for base in /mnt/media_rw /storage /mnt/runtime/default; do
            [ -d "$base" ] || continue
            for d in "$base"/*; do
                [ -d "$d" ] || continue
                case "$d" in
                    */emulated | */self) continue ;;
                esac
                # Evita listar el propio almacenamiento interno duplicado
                REAL=$(readlink -f "$d" 2>/dev/null)
                case "$REAL" in
                    /data/media*) continue ;;
                esac
                echo "$d"
            done
        done | sort -u
        ;;

    list_children)
        # $2 = ruta a listar (para navegar subcarpetas desde la UI)
        TARGET="$2"
        [ -d "$TARGET" ] || exit 1
        for d in "$TARGET"/*; do
            [ -d "$d" ] && echo "$d"
        done
        ;;

    log)
        tail -n 150 "$MODDIR/mount.log" 2>/dev/null
        ;;

    *)
        echo "Uso: webctl.sh {apply|unmount|status|detect|list_children <ruta>|log}"
        ;;
esac
