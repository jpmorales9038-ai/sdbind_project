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
        UNMOUNT_REASON="webctl apply (reintento/Guardar y montar, app o WebUI)"
        unmount_all
        unset UNMOUNT_REASON
        apply_mounts
        echo "DONE"
        ;;

    unmount)
        # "Desmontar todo" es intencional: mientras este archivo exista, _watch_cb (ver
        # functions.sh) no va a reintentar remontar nada solo, aunque el origen siga ahí.
        #
        # DIAGNÓSTICO: el log del 25/09 mostró un "Desmontado" en medio de una partida sin
        # que _watch_cb hubiera arrancado ninguna cuenta de gracia ni self-heal — o sea, no
        # fue este módulo desmontando por su cuenta. Este es justamente el botón que, tocado
        # sin querer (o con querer, sin darse cuenta de que iba a sacar del juego), produce
        # exactamente ese patrón: desmonta YA, sin pasar por _watch_cb, y además dejaba
        # marcado NOHEAL_MARKER, así que el self-heal no reintentaba después — coincide con
        # que ese log no muestre ningún "Bind caído solo" tras el desmontaje, pese a que el
        # origen seguía presente un rato más (src=1 en el HB siguiente). unmount_one ahora
        # deja registrada la cadena de procesos que pidió esto (ver _caller_chain en
        # functions.sh), así que la próxima vez el log dice solo quién lo tocó.
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
        # el loop de service.sh. "status" solo reporta lo que hay, sin tocar el marcador.
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
