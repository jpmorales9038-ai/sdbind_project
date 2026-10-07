package com.sdcardbind.manager

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Ruta del archivo que lee el módulo (`perf_load` en functions.sh). Formato `clave=número`. */
const val PERF_CONF = "$MODDIR/perf.conf"

/** Valores válidos; el módulo ignora cualquier otro y usa el predeterminado. */
val PERF_READAHEAD_OPTIONS = listOf(0, 512, 1024, 2048)
val PERF_INTERVAL_OPTIONS = listOf(1, 2, 5)

/**
 * Ajustes de rendimiento de los vínculos. Los predeterminados equivalen al comportamiento de
 * siempre (sin perf.conf el módulo no cambia nada).
 */
data class PerfConfig(
    val readAheadKb: Int = 0,
    val intervalSec: Int = 1,
    val lightGuard: Boolean = false,
    val fastLabel: Boolean = false
) {
    internal fun toConf(): String = buildString {
        appendLine("readahead_kb=$readAheadKb")
        appendLine("watch_interval=$intervalSec")
        appendLine("light_guard=${if (lightGuard) 1 else 0}")
        appendLine("fast_label=${if (fastLabel) 1 else 0}")
    }

    companion object {
        internal fun parse(lines: List<String>): PerfConfig {
            val kv = lines.mapNotNull { l ->
                val i = l.indexOf('=')
                if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
            }.toMap()
            val ra = kv["readahead_kb"]?.toIntOrNull()?.takeIf { it in PERF_READAHEAD_OPTIONS } ?: 0
            val iv = kv["watch_interval"]?.toIntOrNull()?.takeIf { it in PERF_INTERVAL_OPTIONS } ?: 1
            return PerfConfig(ra, iv, kv["light_guard"] == "1", kv["fast_label"] == "1")
        }
    }
}

/**
 * Lee/guarda perf.conf y le pide al módulo que lo aplique (`webctl.sh perf`). Vive aparte de
 * [RootOps] (que no se toca): usa libsu igual que él y no depende de ningún composable.
 */
class PerfController {
    var config by mutableStateOf(PerfConfig())
        private set
    var loaded by mutableStateOf(false)
        private set
    var saveFailed by mutableStateOf(false)
        private set

    private val lock = Mutex()

    suspend fun load() = lock.withLock {
        val lines = withContext(Dispatchers.IO) {
            Shell.cmd("cat $PERF_CONF 2>/dev/null").exec().out
        }
        config = PerfConfig.parse(lines)
        loaded = true
    }

    /** Guarda [next], lo aplica al momento y devuelve si el módulo lo aceptó. */
    suspend fun update(next: PerfConfig): Boolean = lock.withLock {
        val previous = config
        config = next // respuesta inmediata en la UI
        val ok = withContext(Dispatchers.IO) {
            val write = Shell.cmd("cat > $PERF_CONF << 'SDBIND_EOF'\n${next.toConf()}SDBIND_EOF").exec()
            if (!write.isSuccess) return@withContext false
            Shell.cmd("chmod 644 $PERF_CONF 2>/dev/null; sh $WEBCTL perf").exec().isSuccess
        }
        saveFailed = !ok
        if (!ok) config = previous
        ok
    }
}
