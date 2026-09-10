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

    remove)
        remove_entry "$2" "$3"
        echo "DONE"
        ;;

    status)
        [ -f "$CONF" ] || exit 0
        while IFS='|' read -r SRC DEST ENABLED || [ -n "$SRC" ]; do
            [ -z "$SRC" ] && continue
            case "$SRC" in \#*) continue ;; esac
            if is_mounted "$DEST"; then
                ST="MOUNTED"
            elif [ -d "$SRC" ]; then
                ST="UNMOUNTED"
            else
                ST="SOURCE_MISSING"
            fi
            echo "$(ensure_slash "$SRC")|$(ensure_slash "$DEST")|$ENABLED|$ST"
        done < "$CONF"
        ;;

    detect)
        for base in /mnt/media_rw /storage /mnt/runtime/default; do
            [ -d "$base" ] || continue
            for d in "$base"/*; do
                [ -d "$d" ] || continue
                case "$d" in
                    */emulated | */self) continue ;;
                esac
                REAL=$(readlink -f "$d" 2>/dev/null)
                case "$REAL" in
                    /data/media*) continue ;;
                esac
                echo "$d"
            done
        done | sort -u
        ;;

    list_children)
        TARGET="$2"
        [ -d "$TARGET" ] || exit 1
        for d in "$TARGET"/*; do
            [ -d "$d" ] && echo "$d"
        done
        ;;

    log)
        tail -n 150 "$MODDIR/mount.log" 2>/dev/null
        ;;

    storage)
        dump_storage
        ;;

    theme)
        seed=$(theme_seed)
        [ -n "$seed" ] && echo "SEED|$seed"
        if [ -f "$MODDIR/webroot/theme.css" ] && grep -q -- "--primary" "$MODDIR/webroot/theme.css" 2>/dev/null; then
            echo "CSS|1"
        else
            echo "CSS|0"
        fi
        find_gsr | sort -u | while read -r f; do
            [ -n "$f" ] && echo "FONT|$f"
        done
        ;;

    *)
        echo "Uso: webctl.sh {apply|unmount|remove <origen> <destino>|status|detect|list_children <ruta>|log|storage|theme}"
        ;;
esac
