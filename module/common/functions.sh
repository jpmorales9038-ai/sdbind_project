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
    # SDBIND_FAST=1 lo pone webctl.sh cuando el "apply" viene de un tap interactivo del
    # usuario (app o WebUI), donde esperar no tiene sentido: o el origen ya está ahí, o no
    # va a aparecer por esperar. Sin esto, cada entrada de OTRA unidad que ya no está
    # conectada suma sus propios 15s de espera antes de seguir con la siguiente — con dos o
    # tres unidades desconectadas eso se siente como que la app se cuelga. La espera larga
    # sigue existiendo para post-fs-data.sh/service.sh al arrancar, que es donde hace falta
    # (el SD/OTG puede tardar un instante en aparecer recién booteado).
    if [ "$SDBIND_FAST" = "1" ]; then
        [ -d "$SRC" ]
        return $?
    fi
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

    # --rbind (no -o bind): si SRC contiene a su vez otro punto de montaje anidado adentro
    # (típico: la raíz de /mnt/media_rw/<id> con la SD montada por separado debajo, o
    # cualquier origen que el usuario elija que resulte "contener" otro mount), un bind
    # simple NO copia ese contenido anidado — queda como carpeta vacía del lado del destino,
    # aunque el resto sí se vea bien. --rbind sí lo arrastra.
    # (Antes había acá un "mount --make-rprivate" defensivo; el mount de Android/toybox no
    # soporta ese flag, cae a su modo de buscar en /etc/fstab -que no existe en Android- y
    # llenaba el log con "mount: bad /etc/fstab". Se saca: --rbind solo ya funciona bien.)
    if run_global mount --rbind "$(strip_slash "$SRC")" "$(strip_slash "$DEST")" 2>>"$LOG"; then
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
    D=$(strip_slash "$DEST")
    # Con --rbind, un mount anidado dentro del origen queda como una entrada de mount APARTE
    # bajo DEST (no solo una carpeta) — hay que desmontar de más anidado a menos anidado, no
    # solo el de arriba, o quedan mounts colgando.
    awk -v p="$D" '$2 == p || index($2, p "/") == 1 { print $2 }' /proc/1/mounts 2>/dev/null |
        awk '{ print length, $0 }' | sort -rn | cut -d' ' -f2- |
    while IFS= read -r mp; do
        [ -n "$mp" ] || continue
        run_global umount -l "$mp" 2>>"$LOG" && log "Desmontado: $mp"
    done
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

MISS_DIR="$MODDIR/.watch_grace"
# Con poca RAM, Android puede matar y reiniciar por un instante el proceso que sirve
# /mnt/media_rw (FUSE/MediaProvider): durante ese lapso "$SRC" da falso negativo aunque la
# SD/OTG siga físicamente conectada. GRACE_SECONDS es cuánto tiene que faltar el origen, de
# forma sostenida entre chequeos, antes de darlo por desconectado de verdad.
#
# BUG (v2.8.11 y anteriores): con 8s esto alcanzaba a dispararse en falso durante uso
# intensivo de una carpeta vinculada (un juego leyendo/escribiendo mucho rato seguido):
# bajo esa carga, el propio puente FUSE/media provider que sirve /mnt/media_rw puede
# saturarse o reiniciarse solo por un momento, y mientras tanto SU PROPIO punto de montaje
# puede faltar de /proc/1/mounts (lo que ve _device_present) más de esos 8s — no porque la
# SD/OTG se haya desconectado, sino porque el proveedor tarda en reaparecer bajo carga.
#
# BUG (v2.8.13 y anteriores): 45s tampoco alcanzaba en uso REALMENTE intensivo y sostenido
# (partidas largas con lecturas/escrituras pesadas todo el rato) en tarjetas SD lentas: el
# proveedor puede tardar más de eso en reponerse, y para cuando el siguiente watch_and_prune
# corre, la RAM ya se recuperó — _low_mem() ve "todo normal" y no llega a congelar el conteo
# aunque la caída de memoria que causó el corte haya sido hace instantes. 120s da margen para
# ese caso extremo sin dejar de detectar una desconexión real (que no se revierte sola) en un
# tiempo razonable.
GRACE_SECONDS=120

# Umbral de RAM disponible (KB) por debajo del cual el sistema puede estar matando procesos
# (low-memory killer) para liberar memoria — justo el escenario que GRACE_SECONDS por sí
# solo no cubre bien si la reconexión del proveedor tarda más de esos 8s. Con Android por
# debajo de esto, un "$SRC" ausente es mucho menos confiable.
LOW_MEM_KB=204800

# Cuánto tiempo (s) seguimos tratando la RAM como "poco confiable" DESPUÉS de haberla visto
# baja por última vez, no solo en el instante exacto del chequeo. Cubre el hueco de arriba:
# una caída breve que ya se recuperó para el siguiente watch_and_prune sigue congelando el
# conteo durante esta ventana, dándole tiempo al proveedor de FUSE/media a reponerse del todo.
LOW_MEM_STICKY_SECONDS=90
LOW_MEM_MARKER="$MODDIR/.watch_grace/.low_mem_seen"

# Poca RAM ahora mismo (según /proc/meminfo). Si no se puede leer, se asume que NO hay poca
# RAM (falso negativo aquí es más seguro que pausar el conteo sin motivo real). Además deja
# constancia de cuándo fue la última vez que se vio baja, para _low_mem_recently().
_mem_avail_kb() {
    awk '/^MemAvailable:/ {print $2; exit}' /proc/meminfo 2>/dev/null
}

_low_mem() {
    avail=$(_mem_avail_kb)
    case "$avail" in
        ''|*[!0-9]*) return 1 ;;
    esac
    if [ "$avail" -lt "$LOW_MEM_KB" ]; then
        mkdir -p "$MISS_DIR" 2>/dev/null
        date +%s > "$LOW_MEM_MARKER" 2>/dev/null
        return 0
    fi
    return 1
}

# La RAM estuvo baja hace poco (dentro de LOW_MEM_STICKY_SECONDS), aunque ahora mismo
# _low_mem ya no la vea baja. Ver el comentario de GRACE_SECONDS arriba: esto es lo que
# cubre una caída breve que ya se recuperó para cuando corre el siguiente chequeo.
_low_mem_recently() {
    [ -f "$LOW_MEM_MARKER" ] || return 1
    since=$(cat "$LOW_MEM_MARKER" 2>/dev/null)
    case "$since" in
        ''|*[!0-9]*) return 1 ;;
    esac
    now=$(date +%s 2>/dev/null || echo 0)
    [ $((now - since)) -lt "$LOW_MEM_STICKY_SECONDS" ]
}

# Log de una línea, como máximo una vez cada $3 segundos por $1 (clave arbitraria). Para
# eventos que pueden repetirse chequeo tras chequeo mientras dura un episodio (saturación de
# I/O, supresión por RAM baja) sin llenar mount.log de líneas idénticas durante minutos.
_log_throttled() {
    key="$1"; msg="$2"; interval="${3:-20}"
    mkdir -p "$MISS_DIR" 2>/dev/null
    f="$MISS_DIR/.throttle_$(echo "$key" | tr '/ ' '__')"
    now=$(date +%s 2>/dev/null || echo 0)
    last=0
    [ -f "$f" ] && last=$(cat "$f" 2>/dev/null)
    case "$last" in ''|*[!0-9]*) last=0 ;; esac
    if [ $((now - last)) -ge "$interval" ]; then
        echo "$now" > "$f" 2>/dev/null
        log "$msg"
    fi
}

# Punto de montaje del volumen (SD/OTG) que contiene a $1, si existe: /mnt/media_rw/<id>,
# /storage/<id>, /mnt/runtime/default/<id> o /mnt/expand/<id>. Se lee de /proc/1/mounts, que
# es tabla de montaje del kernel — no toca el filesystem del dispositivo en sí, así que
# responde al toque aunque ese filesystem esté saturado de I/O.
_media_root_for() {
    p=$(strip_slash "$1")
    [ -n "$p" ] || return 1
    [ -r /proc/1/mounts ] || return 1
    awk -v p="$p" '
        $2 ~ /^\/mnt\/media_rw\/[^\/]+$/ || $2 ~ /^\/storage\/[^\/]+$/ ||
        $2 ~ /^\/mnt\/runtime\/default\/[^\/]+$/ || $2 ~ /^\/mnt\/expand\/[^\/]+$/ {
            mp = $2
            if ((p == mp || index(p, mp "/") == 1) && length(mp) > best_len) {
                best = mp; best_len = length(mp)
            }
        }
        END { if (best_len > 0) { print best; exit 0 } exit 1 }
    ' /proc/1/mounts
}

# La SD/OTG en sí sigue conectada (su propio punto de montaje sigue en la tabla del kernel),
# más allá de que estat sobre una subcarpeta puntual esté fallando. Uso intensivo de la
# carpeta (un juego leyendo/escribiendo mucho) puede poner lento o devolver error transitorio
# al puente FUSE/MTP que sirve /mnt/media_rw bajo carga, sin que el volumen se haya ido —
# _watch_cb usa esto para no confundir esa saturación con una desconexión real.
_device_present() {
    root=$(_media_root_for "$1")
    [ -n "$root" ]
}

# Marcador por DEST con el instante (epoch) en que se lo vio faltar por primera vez. No se
# bloquea acá con un sleep/retry: esta función corre también dentro de "status"
# (webctl.sh), al que la app le pregunta cada ~1.5s para refrescar la pantalla, y una
# espera de varios segundos ahí congelaría la UI. En cambio, cada llamada (separada en el
# tiempo por el propio loop de service.sh o por el polling de la app) solo anota o revisa
# la marca, así el margen se cubre solo repitiendo chequeos rápidos.
_miss_marker() {
    mkdir -p "$MISS_DIR" 2>/dev/null
    echo "$MISS_DIR/$(echo "$1" | tr '/ ' '__')"
}

_watch_cb() {
    SRC="$1"; DEST="$2"; ENABLED="$3"
    [ "$ENABLED" = "1" ] || return 0
    is_mounted "$DEST" || return 0
    # Sin nsenter a propósito: el origen (SD/OTG) no pasa por el FUSE de storage por app,
    # así que cualquier proceso ve igual si sigue presente o no (mismo criterio que
    # wait_for_path, que tampoco usa run_global para esto).
    marker=$(_miss_marker "$DEST")
    if [ -d "$SRC" ]; then
        if [ -f "$marker" ]; then
            since=$(cat "$marker" 2>/dev/null)
            case "$since" in ''|*[!0-9]*) since=0 ;; esac
            now=$(date +%s 2>/dev/null || echo 0)
            # DIAGNÓSTICO (build de logs): esto confirma que la ausencia fue transitoria y
            # se resolvió sola, con la duración real de principio a fin.
            log "Origen reapareció tras $((now - since))s de ausencia: $SRC -> $DEST"
        fi
        rm -f "$marker" 2>/dev/null
        return 0
    fi
    if _low_mem || _low_mem_recently; then
        # No sumamos ni arrancamos el conteo de gracia mientras el sistema está (o estuvo
        # hace poco) justo de RAM: es el momento en que más probable es que esto sea un
        # falso negativo y no una desconexión real. El marcador (si ya existía de antes)
        # queda congelado tal cual, a la espera de que la memoria termine de recuperarse.
        # DIAGNÓSTICO: throttled porque puede repetirse chequeo tras chequeo mientras dura.
        _log_throttled "$DEST.lowmem" "Origen ausente + RAM baja/reciente (mem=$(_mem_avail_kb)KB), conteo congelado: $SRC -> $DEST" 20
        return 0
    fi
    if _device_present "$SRC"; then
        # El volumen sigue conectado (su punto de montaje sigue ahí); esto es la subcarpeta
        # puntual fallando por I/O intensa, no una desconexión. No se desmonta: si de verdad
        # se desconecta, el punto de montaje del volumen entero desaparece y ahí sí se cuenta
        # el margen de abajo. Se limpia el marcador en vez de congelarlo (a diferencia de
        # _low_mem): acá sabemos que el origen está bien, así que un miss suelto posterior no
        # debería heredar un conteo viejo de otra causa.
        # DIAGNÓSTICO: esta es la teoría de "saturación de I/O" en acción — si el problema
        # real es otra cosa, esta línea NO debería aparecer justo antes de un desmontaje.
        _log_throttled "$DEST.satlog" "Subcarpeta ausente pero el volumen SD/OTG sigue montado (I/O saturada, no se cuenta): $SRC" 15
        rm -f "$marker" 2>/dev/null
        return 0
    fi
    now=$(date +%s 2>/dev/null || echo 0)
    if [ ! -f "$marker" ]; then
        echo "$now" > "$marker" 2>/dev/null
        # DIAGNÓSTICO: arranque de un episodio real de ausencia — ni RAM baja ni volumen
        # presente lo explican, así que empieza a correr la cuenta de gracia de verdad.
        log "Origen ausente de verdad, iniciando cuenta de gracia (mem=$(_mem_avail_kb)KB): $SRC -> $DEST"
        return 0
    fi
    since=$(cat "$marker" 2>/dev/null)
    case "$since" in
        ''|*[!0-9]*) since="$now"; echo "$now" > "$marker" 2>/dev/null ;;
    esac
    elapsed=$((now - since))
    if [ "$elapsed" -ge "$GRACE_SECONDS" ]; then
        # Reconfirmación de último momento: barata (no toca el filesystem del origen) y
        # evita un desmontaje si el proveedor reapareció, o si la RAM volvió a caer, justo
        # entre el watch_and_prune anterior y este.
        if [ -d "$SRC" ] || _device_present "$SRC" || _low_mem || _low_mem_recently; then
            _log_throttled "$DEST.lastcheck" "Se cumplió el margen (${elapsed}s) pero la reconfirmación de último momento salvó a $SRC -> $DEST" 20
            rm -f "$marker" 2>/dev/null
            return 0
        fi
        log "Auto-desmontado (origen desconectado ${elapsed}s, mem=$(_mem_avail_kb)KB): $SRC -> $DEST"
        unmount_one "$DEST"
        rm -f "$marker" 2>/dev/null
    fi
}

# Diagnóstico temporal (build de logs, ver comentario en service.sh): deja un timeline fijo
# de RAM disponible + el estado real de cada bind (existe el origen / sigue el volumen
# montado / sigue is_mounted el destino) cada pocos segundos, sin depender de que algo
# "pase" para que quede constancia. Sirve para confirmar o descartar de una vez la teoría de
# saturación de I/O bajo uso intensivo, en vez de seguir ajustando GRACE_SECONDS a ciegas.
# Sacar (o dejar de llamar desde service.sh) una vez encontrada la causa real.
heartbeat_log() {
    avail=$(_mem_avail_kb)
    line="HB mem=${avail:-?}KB"
    if [ -f "$CONF" ]; then
        while IFS='|' read -r SRC DEST ENABLED || [ -n "$SRC" ]; do
            [ -z "$SRC" ] && continue
            case "$SRC" in \#*) continue ;; esac
            [ "$ENABLED" = "1" ] || continue
            d_src=0; [ -d "$SRC" ] && d_src=1
            d_vol=0; _device_present "$SRC" && d_vol=1
            d_mnt=0; is_mounted "$DEST" && d_mnt=1
            line="$line | $(basename "$DEST"): src=$d_src vol=$d_vol mounted=$d_mnt"
        done < "$CONF"
    fi
    log "$line"
}

# Recorre todos los binds habilitados y desmonta los que quedaron "colgando" porque su
# origen (tarjeta SD u OTG) ya no está conectado (tras confirmarlo durante GRACE_SECONDS).
watch_and_prune() {
    each_entry _watch_cb
}


remove_entry() {
    SRC=$(ensure_slash "$1")
    DEST=$(ensure_slash "$2")
    unmount_one "$DEST"
    rm -f "$(_miss_marker "$DEST")" 2>/dev/null
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
# motivo de run_global que list_subdirs. OJO: antes usaba `find -printf`, que es una
# extensión de GNU findutils — el find de Android (toybox) no la soporta, falla callado y
# devolvía siempre vacío (por eso "Explorar" mostraba "carpeta vacía" incluso ya montado,
# mientras que el selector de carpetas -que usa list_subdirs, sin -printf- sí funcionaba).
# Reemplazado por un loop portable con stat/wc, sin nada específico de GNU.
list_dir_entries() {
    DIR=$(strip_slash "$1")
    [ -n "$DIR" ] || return 1
    run_global sh -c '
        DIR="$1"
        for f in "$DIR"/* "$DIR"/.[!.]* "$DIR"/..?*; do
            [ -e "$f" ] || [ -L "$f" ] || continue
            name=$(basename "$f")
            if [ -d "$f" ]; then type=d; else type=f; fi
            size=$(stat -c "%s" "$f" 2>/dev/null)
            [ -n "$size" ] || size=$(wc -c < "$f" 2>/dev/null)
            [ -n "$size" ] || size=0
            echo "$type|$size|$name"
        done
    ' _ "$DIR" 2>/dev/null
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
