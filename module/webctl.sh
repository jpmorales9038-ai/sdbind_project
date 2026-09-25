#!/system/bin/sh
MODDIR="/data/adb/modules/sdcard_bind_ui"
CONF="$MODDIR/mounts.conf"
. "$MODDIR/common/functions.sh"

case "$1" in
    apply)
        # SDBIND_FAST=1: esto es un tap interactivo (usuario esperando en pantalla), no el
        # arranque — que wait_for_path no se ponga a esperar 15s por cada entrada de una
        # unidad que ya no está conectada. Ver el comentario en wait_for_path.
        SDBIND_FAST=1
        export SDBIND_FAST
        # El usuario está pidiendo montar a propósito: si "Desmontar todo" había dejado la
        # autocuración pausada, la reactivamos.
        rm -f "$NOHEAL_MARKER" 2>/dev/null
        # Y si alguna entrada había quedado marcada como "desconexión real" (ver _disc_marker
        # en functions.sh), se limpia: el usuario mismo está pidiendo montar ahora, así que a
        # partir de este punto el self-heal puede volver a actuar solo sobre ella.
        rm -f "$MISS_DIR"/.disc_* 2>/dev/null
        UNMOUNT_REASON="webctl apply (reintento/Guardar y montar, app o WebUI)"
        unmount_all
        unset UNMOUNT_REASON
        apply_mounts
        echo "DONE"
        ;;

    unmount)
        # "Desmontar todo" es intencional: mientras este archivo exista, _watch_cb (ver
        # functions.sh) no va a reintentar remontar nada solo, aunque el origen siga ahí.
        touch "$NOHEAL_MARKER" 2>/dev/null
        UNMOUNT_REASON="webctl unmount (botón Desmontar todo, app o WebUI)"
        unmount_all
        unset UNMOUNT_REASON
        echo "DONE"
        ;;

    remove)
        remove_entry "$2" "$3"
        echo "DONE"
        ;;

    status)
        # OJO: acá ANTES se llamaba a watch_and_prune "para reflejar la realidad al toque" —
        # ese era el bug real detrás de las caídas en uso intensivo, confirmado con el log
        # diagnóstico: la app llama a "status" cada ~1.5s (y la WebUI también) mientras
        # service.sh corre su propio watch_and_prune cada 2s en paralelo, SIEMPRE, tenga la
        # app abierta o no. Dos-tres procesos root sueltos leyendo y escribiendo el mismo
        # marcador de gracia en $MISS_DIR sin ningún lock entre ellos: un salto de "recién
        # empezado" a "ya se cumplió el margen" en 3 segundos con GRACE_SECONDS=120 (visto en
        # el log) no tiene otra explicación sensata que esa carrera. Ajustar el número del
        # margen nunca lo iba a arreglar porque el problema no era el número, era que dos
        # procesos pisaban el mismo archivo a la vez. Ahora hay un solo dueño de esa lógica:
        # el loop de service.sh. "status" solo reporta lo que hay, sin tocar el marcador
        # POR DEST (_miss_marker/GRACE_SECONDS) ni llamar a watch_and_prune.
        #
        # OJO 2: lo de arriba no significa que "status" tenga que mentir mientras dura el
        # margen de gracia del auto-desmontado. Si sacás la SD/OTG sin desmontar, el bind
        # sigue figurando "montado" para el kernel (un bind no se cae solo porque el origen
        # desaparezca) hasta que watch_and_prune lo desmonte de verdad, ~2 min después. Antes
        # este bloque mostraba "montado" todo ese rato. Ahora, si el bind sigue montado pero
        # ya podemos confirmar con los MISMOS chequeos que usa _watch_cb para decidir si vale
        # la pena arrancar la cuenta de gracia (RAM baja reciente, volumen SD/OTG todavía
        # presente por I/O saturada) que el origen se fue de verdad, lo mostramos como
        # "origen ausente" al toque — sin tocar el marcador por DEST ni el mount en sí, así
        # que el auto-desmontado real sigue esperando su propio margen sin cambios.
        [ -f "$CONF" ] || exit 0
        while IFS='|' read -r SRC DEST ENABLED || [ -n "$SRC" ]; do
            [ -z "$SRC" ] && continue
            case "$SRC" in \#*) continue ;; esac
            if is_mounted "$DEST"; then
                if [ -d "$SRC" ]; then
                    ST="MOUNTED"
                elif _low_mem || _low_mem_recently; then
                    ST="MOUNTED"
                elif _device_present "$SRC"; then
                    ST="MOUNTED"
                else
                    ST="SOURCE_MISSING"
                fi
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
        TARGET=$(strip_slash "$2")
        [ -n "$TARGET" ] || exit 1
        list_subdirs "$TARGET"
        ;;

    subdirs)
        list_subdirs "$2"
        ;;

    entries)
        list_dir_entries "$2"
        ;;

    rm)
        [ "$4" = "dir" ] && delete_path "$2" dir || delete_path "$2" file
        ;;

    prune)
        watch_and_prune
        echo "DONE"
        ;;

    anyvolpresent)
        # Lo usa la app antes de reaccionar a ACTION_MEDIA_UNMOUNTED/REMOVED/BAD_REMOVAL: esos
        # broadcasts de Android pueden dispararse igual aunque la SD/OTG siga físicamente
        # puesta (mismo tipo de falso positivo por I/O saturada + RAM baja que ya conocemos
        # del lado shell — ver _device_present). Antes de desmontar TODO en el acto sin pasar
        # por el margen de gracia ni el self-heal de _watch_cb, se confirma acá contra
        # /proc/1/mounts (no toca el filesystem, responde igual aunque el origen esté
        # saturado de I/O) si al menos un volumen configurado sigue en la tabla de montaje.
        any=0
        if [ -f "$CONF" ]; then
            while IFS='|' read -r SRC DEST ENABLED || [ -n "$SRC" ]; do
                [ -z "$SRC" ] && continue
                case "$SRC" in \#*) continue ;; esac
                [ "$ENABLED" = "1" ] || continue
                _device_present "$SRC" && any=1
            done < "$CONF"
        fi
        if [ "$any" = "1" ]; then
            echo "PRESENT"
        else
            echo "GONE"
        fi
        ;;

    clearlog)
        : > "$LOG"
        log "Registro borrado a mano"
        echo "DONE"
        ;;

    log)
        tail -n 150 "$MODDIR/mount.log" 2>/dev/null
        ;;

    fulllog)
        # Sin límite de líneas — usado por el botón "Compartir registro completo" de la app
        # para juntar una sesión de diagnóstico entera, no solo lo último que entra en pantalla.
        cat "$MODDIR/mount.log" 2>/dev/null
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
        echo "Uso: webctl.sh {apply|unmount|remove <origen> <destino>|status|detect|list_children <ruta>|subdirs <ruta>|entries <ruta>|rm <ruta> [dir]|prune|log|storage|theme}"
        ;;
esac
