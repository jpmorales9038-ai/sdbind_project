package com.sdcardbind.manager

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val MODDIR = "/data/adb/modules/sdcard_bind_ui"
const val CONF = "$MODDIR/mounts.conf"
const val WEBCTL = "$MODDIR/webctl.sh"

data class MountEntry(
    val source: String,
    val dest: String,
    val enabled: Boolean,
    val status: String = ""
)

data class StorageVolume(
    val path: String,
    val totalHuman: String,
    val usedHuman: String,
    val availHuman: String,
    val usePercent: Int
)

fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

fun normalizeDir(path: String): String {
    val p = path.trim()
    if (p.isEmpty() || p == "/") return p
    return if (p.endsWith("/")) p else "$p/"
}

fun dirBaseName(path: String): String = path.trim().trimEnd('/').substringAfterLast('/')

/** Destinos que romperían el almacenamiento interno si se hace bind/umount. */
fun isUnsafeDest(path: String): Boolean {
    val p = path.trim().trimEnd('/')
    return p in setOf(
        "", "/",
        "/storage", "/storage/emulated", "/storage/emulated/0",
        "/sdcard", "/mnt/sdcard",
        "/data", "/data/media", "/data/media/0",
        "/mnt", "/mnt/user", "/mnt/user/0",
        "/mnt/runtime", "/mnt/pass_through", "/mnt/media_rw"
    )
}

object RootOps {

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        Shell.getShell().isRoot
    }

    private suspend fun exec(cmd: String): List<String> = withContext(Dispatchers.IO) {
        Shell.cmd(cmd).exec().out
    }

    suspend fun listSubdirectories(path: String): List<String> {
        val cmd = "find " + shQuote(path.trimEnd('/').ifBlank { "/" }) +
            " -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort"
        return exec(cmd).filter { it.isNotBlank() }.map { normalizeDir(it) }
    }

    suspend fun loadMounts(): List<MountEntry> {
        val lines = exec("sh $WEBCTL status")
        return lines.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 4) return@mapNotNull null
            MountEntry(
                source = normalizeDir(parts[0]),
                dest = normalizeDir(parts[1]),
                enabled = parts[2] == "1",
                status = parts[3]
            )
        }
    }

    suspend fun detectCandidates(): List<String> {
        return exec("sh $WEBCTL detect").filter { it.isNotBlank() }.map { normalizeDir(it) }
    }

    suspend fun saveAndApply(entries: List<MountEntry>): Boolean {
        val body = buildString {
            appendLine("# Formato: ORIGEN|DESTINO|HABILITADO(1/0)")
            entries.forEach {
                if (it.source.isBlank() || it.dest.isBlank()) return@forEach
                if (isUnsafeDest(it.dest)) return@forEach
                append(normalizeDir(it.source)).append("|")
                append(normalizeDir(it.dest)).append("|")
                append(if (it.enabled) "1" else "0").appendLine()
            }
        }
        val cmd = buildString {
            append("cat > ").append(CONF).append(" << 'SDBIND_EOF'\n")
            append(body)
            if (!body.endsWith("\n")) append('\n')
            append("SDBIND_EOF\n")
            append("sh ").append(WEBCTL).append(" apply")
        }
        val result = withContext(Dispatchers.IO) { Shell.cmd(cmd).exec() }
        return result.isSuccess
    }

    suspend fun unmountAll(): Boolean {
        val result = withContext(Dispatchers.IO) { Shell.cmd("sh $WEBCTL unmount").exec() }
        return result.isSuccess
    }

    suspend fun tailLog(): String {
        return exec("sh $WEBCTL log").joinToString("\n")
    }

    suspend fun storageVolumes(): List<StorageVolume> {
        val lines = exec("df -h 2>/dev/null")
        val result = mutableListOf<StorageVolume>()
        for (line in lines) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 6) continue
            val mountPoint = cols[5]
            val isRelevant = mountPoint == "/storage/emulated" ||
                mountPoint.startsWith("/storage/emulated/0") ||
                mountPoint.startsWith("/mnt/media_rw")
            if (!isRelevant) continue
            val usePercentStr = cols[4].removeSuffix("%")
            val usePercent = usePercentStr.toIntOrNull() ?: 0
            result.add(
                StorageVolume(
                    path = mountPoint,
                    totalHuman = cols[1],
                    usedHuman = cols[2],
                    availHuman = cols[3],
                    usePercent = usePercent
                )
            )
        }
        return result.distinctBy { it.path }
    }
}
