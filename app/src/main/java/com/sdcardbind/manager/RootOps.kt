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

enum class VolumeKind { INTERNAL, EXTERNAL }

data class StorageVolume(
    val kind: VolumeKind,
    val path: String,
    val totalHuman: String,
    val usedHuman: String,
    val availHuman: String,
    val usePercent: Int
) {
    val label: String
        get() = when (kind) {
            VolumeKind.INTERNAL -> "Interno"
            VolumeKind.EXTERNAL -> {
                val id = path.trimEnd('/').substringAfterLast('/')
                if (id.isBlank() || id == "media_rw") "SD / OTG" else "SD $id"
            }
        }
}

fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

fun normalizeDir(path: String): String {
    val p = path.trim()
    if (p.isEmpty() || p == "/") return p
    return if (p.endsWith("/")) p else "$p/"
}

fun volId(path: String): String = path.trimEnd('/').substringAfterLast('/')

fun dirBaseName(path: String): String = volId(path)

fun humanToBytes(s: String): Long {
    val t = s.trim().uppercase().replace(",", ".")
    val num = t.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0L
    val unit = t.dropWhile { it.isDigit() || it == '.' }
    val mul = when {
        unit.startsWith("T") -> 1024.0 * 1024 * 1024 * 1024
        unit.startsWith("G") -> 1024.0 * 1024 * 1024
        unit.startsWith("M") -> 1024.0 * 1024
        unit.startsWith("K") -> 1024.0
        else -> 1.0
    }
    return (num * mul).toLong()
}

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
        val raw = path.trimEnd('/').ifBlank { "/" }
        if (raw == "/mnt/media_rw" || raw == "/mnt/expand") {
            val awk = "awk -v p=" + shQuote(raw) + " '\$2 ~ \"^\" p \"/[^/]+\$\" { print \$2 }' /proc/1/mounts 2>/dev/null | sort -u"
            return exec(awk).filter { it.isNotBlank() }.map { normalizeDir(it) }
        }
        val cmd = "find " + shQuote(raw) + " -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort"
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

    private fun confBody(entries: List<MountEntry>): String = buildString {
        appendLine("# Formato: ORIGEN|DESTINO|HABILITADO(1/0)")
        entries.forEach {
            if (it.source.isBlank() || it.dest.isBlank()) return@forEach
            if (isUnsafeDest(it.dest)) return@forEach
            append(normalizeDir(it.source)).append("|")
            append(normalizeDir(it.dest)).append("|")
            append(if (it.enabled) "1" else "0").appendLine()
        }
    }

    private suspend fun writeConf(entries: List<MountEntry>): Boolean {
        val body = confBody(entries)
        val cmd = "cat > $CONF << 'SDBIND_EOF'\n$body\nSDBIND_EOF"
        val result = withContext(Dispatchers.IO) { Shell.cmd(cmd).exec() }
        return result.isSuccess
    }

    suspend fun saveAndApply(entries: List<MountEntry>): Boolean {
        if (!writeConf(entries)) return false
        val result = withContext(Dispatchers.IO) { Shell.cmd("sh $WEBCTL apply").exec() }
        return result.isSuccess
    }

    suspend fun unmountAll(): Boolean {
        val result = withContext(Dispatchers.IO) { Shell.cmd("sh $WEBCTL unmount").exec() }
        return result.isSuccess
    }

    suspend fun removeMount(source: String, dest: String): Boolean = withContext(Dispatchers.IO) {
        val src = normalizeDir(source)
        val dst = normalizeDir(dest)
        Shell.cmd("nsenter -t 1 -m -- umount -l ${shQuote(dst.trimEnd('/'))} 2>/dev/null; true").exec()
        val remaining = loadMounts().filterNot {
            normalizeDir(it.source).trimEnd('/') == src.trimEnd('/') &&
                normalizeDir(it.dest).trimEnd('/') == dst.trimEnd('/')
        }
        writeConf(remaining)
    }

    suspend fun tailLog(): String {
        return exec("sh $WEBCTL log").joinToString("\n")
    }

    suspend fun storageVolumes(): List<StorageVolume> {
        val fromCtl = exec("sh $WEBCTL storage").mapNotNull { parseStorageLine(it) }
        val fromDf = fallbackDf()
        val internals = (fromCtl.filter { it.kind == VolumeKind.INTERNAL } +
            fromDf.filter { it.kind == VolumeKind.INTERNAL }).distinctBy { volId(it.path) }
        val externals = (fromCtl.filter { it.kind == VolumeKind.EXTERNAL } +
            fromDf.filter { it.kind == VolumeKind.EXTERNAL }).distinctBy { volId(it.path) }
        val merged = internals.take(1) + externals
        return merged
    }

    private fun parseStorageLine(line: String): StorageVolume? {
        val p = line.trim().split("|")
        if (p.size < 6) return null
        val kind = when (p[0]) {
            "INTERNAL" -> VolumeKind.INTERNAL
            "EXTERNAL" -> VolumeKind.EXTERNAL
            else -> return null
        }
        return StorageVolume(
            kind = kind,
            path = p[1],
            totalHuman = p[2],
            usedHuman = p[3],
            availHuman = p[4],
            usePercent = p[5].toIntOrNull() ?: 0
        )
    }

    private suspend fun fallbackDf(): List<StorageVolume> {
        val lines = exec("nsenter -t 1 -m -- df -Ph 2>/dev/null || df -Ph 2>/dev/null")
        val result = mutableListOf<StorageVolume>()
        for (line in lines) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 6) continue
            val mp = cols.last()
            val kind = when {
                mp == "/data" || mp == "/data/media" || mp.startsWith("/storage/emulated") -> VolumeKind.INTERNAL
                mp.startsWith("/mnt/media_rw/") || mp.startsWith("/mnt/expand/") -> VolumeKind.EXTERNAL
                mp.startsWith("/mnt/runtime/default/") && !mp.contains("emulated") -> VolumeKind.EXTERNAL
                mp.startsWith("/storage/") && !mp.contains("emulated") && !mp.endsWith("/self") -> VolumeKind.EXTERNAL
                else -> continue
            }
            val use = cols[cols.size - 2]
            result.add(
                StorageVolume(
                    kind = kind,
                    path = mp,
                    totalHuman = cols[cols.size - 5],
                    usedHuman = cols[cols.size - 4],
                    availHuman = cols[cols.size - 3],
                    usePercent = use.removeSuffix("%").toIntOrNull() ?: 0
                )
            )
        }
        return result.distinctBy { it.kind to it.path.trimEnd('/').substringAfterLast('/') }
    }
}
