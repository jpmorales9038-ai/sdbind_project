package com.sdcardbind.manager

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Release publicada en GitHub: etiqueta, versión completa (con sufijo de pre-release si lo tiene,
 * p. ej. "2.9.3-preview.57") y URLs de los assets.
 */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val prerelease: Boolean,
    val apkUrl: String?,
    val zipUrl: String?
)

enum class FetchError { Network, NoReleases, Other }

sealed class FetchResult {
    data class Ok(val release: ReleaseInfo) : FetchResult()
    data class Failed(val kind: FetchError, val detail: String? = null) : FetchResult()
}

/** Versión del módulo leída de su module.prop. [pendingReboot]: hay una versión flasheada que aún no se aplicó. */
data class ModuleInfo(val version: String, val pendingReboot: Boolean)

object Updater {

    const val DEFAULT_REPO = "jpmorales9038-ai/sdbind_project"
    private const val ZIP_NAME = "sdcard_bind_ui.zip"
    private const val APK_NAME = "sdbind_update.apk"
    private const val APK_IN_ZIP = "sdcard-bind-manager.apk"
    private const val TMP_APK = "/data/local/tmp/sdbind_update.apk"
    private const val TMP_LOG = "/data/local/tmp/sdbind_install.log"

    private val managers = listOf(
        "com.rifsxd.ksunext" to "com.rifsxd.ksunext.ui.MainActivity",
        "me.weishu.kernelsu" to "me.weishu.kernelsu.ui.MainActivity",
        "com.sukisu.ultra" to "com.sukisu.ultra.ui.MainActivity",
        "com.topjohnwu.magisk" to "com.topjohnwu.magisk.ui.MainActivity",
        "io.github.huskydg.magisk" to "com.topjohnwu.magisk.ui.MainActivity",
        "me.bmax.apatch" to "me.bmax.apatch.ui.MainActivity"
    )


    // ---------------------------------------------------------------- versiones

    private val VERSION_RX = Regex("(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z][0-9A-Za-z.\\-]*))?")

    /** Parte numérica de una versión: "v2.9.1" -> "2.9.1", "2.9.1-preview.4" -> "2.9.1". */
    fun cleanVersion(s: String): String =
        VERSION_RX.find(s)?.groupValues?.get(1) ?: s.trim()

    /** Versión completa sin prefijo: "v2.9.3-preview.57" -> "2.9.3-preview.57". */
    fun fullVersion(s: String): String {
        val m = VERSION_RX.find(s) ?: return s.trim()
        val pre = m.groupValues[2]
        return if (pre.isEmpty()) m.groupValues[1] else m.groupValues[1] + "-" + pre
    }

    /** Solo la parte numérica ("v2.9.3"); para comparar app y módulo. */
    fun displayVersion(s: String): String = "v" + cleanVersion(s)

    /** Con sufijo de pre-release ("v2.9.3-preview.57"); para mostrar la build concreta. */
    fun displayFull(s: String): String = "v" + fullVersion(s)

    private fun parts(s: String): List<Int> =
        cleanVersion(s).split('.').mapNotNull { it.toIntOrNull() }

    private fun preParts(s: String): List<String> =
        VERSION_RX.find(s)?.groupValues?.get(2).orEmpty()
            .split('.', '-').filter { it.isNotEmpty() }

    /** Compara solo la parte numérica (ignora "-preview.N"). */
    fun compareBase(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p.compareTo(q)
        }
        return 0
    }

    /**
     * Compara versiones completas al estilo semver: primero los números; a igualdad, una versión
     * final es MAYOR que cualquiera de sus pre-releases ("2.9.3" > "2.9.3-preview.99") y entre
     * pre-releases se comparan los identificadores ("preview.58" > "preview.57").
     */
    fun compareVersions(a: String, b: String): Int {
        val base = compareBase(a, b)
        if (base != 0) return base
        val x = preParts(a)
        val y = preParts(b)
        if (x.isEmpty() && y.isEmpty()) return 0
        if (x.isEmpty()) return 1
        if (y.isEmpty()) return -1
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i) ?: return -1
            val q = y.getOrNull(i) ?: return 1
            val pn = p.toLongOrNull()
            val qn = q.toLongOrNull()
            val c = when {
                pn != null && qn != null -> pn.compareTo(qn)
                pn != null -> -1
                qn != null -> 1
                else -> p.compareTo(q)
            }
            if (c != 0) return c
        }
        return 0
    }

    /** ¿La release remota (final o pre-release) es más nueva que la instalada? */
    fun isNewer(remote: String, local: String): Boolean = compareVersions(remote, local) > 0

    /** Misma versión numérica (app vs. módulo: el módulo nunca lleva sufijo de pre-release). */
    fun sameVersion(a: String, b: String): Boolean = compareBase(a, b) == 0

    /** Misma build exacta, sufijo incluido. */
    fun sameBuild(a: String, b: String): Boolean = compareVersions(a, b) == 0

    @Suppress("DEPRECATION")
    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()

    // ---------------------------------------------------------------- módulo (root, solo lectura)

    private fun moduleId(): String = MODDIR.substringAfterLast('/')

    private fun readProp(path: String): String? {
        val out = Shell.cmd("cat ${shQuote(path)} 2>/dev/null").exec().out
        return out.firstOrNull { it.trim().startsWith("version=") }
            ?.substringAfter('=')?.trim()?.takeIf { it.isNotBlank() }
    }

    /** null si el módulo no está instalado (p. ej. la app se instaló suelta). */
    suspend fun readModuleInfo(): ModuleInfo? = withContext(Dispatchers.IO) {
        val installed = readProp("$MODDIR/module.prop")
        // Tras flashear, KernelSU/Magisk dejan el módulo nuevo aquí hasta el siguiente reinicio.
        val pending = readProp("/data/adb/modules_update/${moduleId()}/module.prop")
        when {
            pending != null -> ModuleInfo(pending, pendingReboot = true)
            installed != null -> ModuleInfo(installed, pendingReboot = false)
            else -> null
        }
    }

    // ---------------------------------------------------------------- GitHub

    suspend fun configuredRepo(): String = withContext(Dispatchers.IO) {
        val fromModule = Shell.cmd("cat $MODDIR/github.repo 2>/dev/null").exec().out
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            .orEmpty()
        if (fromModule.contains("/")) fromModule else DEFAULT_REPO
    }

    /**
     * Busca la release más nueva del repo, **incluidas las pre-releases** (`/releases`, no
     * `/releases/latest`, que las ignora). Se elige por versión (no por fecha de publicación) entre
     * las 30 más recientes que traigan algún asset descargable.
     */
    suspend fun fetchLatest(): FetchResult = withContext(Dispatchers.IO) {
        val repo = configuredRepo()
        val body = httpGet("https://api.github.com/repos/$repo/releases?per_page=30")
            ?: return@withContext FetchResult.Failed(FetchError.Network)
        val trimmed = body.trim()
        if (!trimmed.startsWith("[")) {
            // Un objeto en vez de una lista = error de la API (límite de peticiones, repo inexistente…).
            val json = try {
                JSONObject(trimmed)
            } catch (_: Exception) {
                return@withContext FetchResult.Failed(FetchError.Network)
            }
            val msg = json.optString("message")
            return@withContext when {
                msg.isBlank() -> FetchResult.Failed(FetchError.Network)
                msg.contains("Not Found", true) -> FetchResult.Failed(FetchError.NoReleases)
                else -> FetchResult.Failed(FetchError.Other, msg)
            }
        }
        val arr = try {
            JSONArray(trimmed)
        } catch (_: Exception) {
            return@withContext FetchResult.Failed(FetchError.Network)
        }
        var best: ReleaseInfo? = null
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            if (j.optBoolean("draft", false)) continue
            val tag = j.optString("tag_name").ifBlank { j.optString("name") }
            if (tag.isBlank() || !VERSION_RX.containsMatchIn(tag)) continue
            val apk = findAsset(j) { it.endsWith(".apk") }
            val zip = findModuleZip(j)
            if (apk == null && zip == null) continue
            val rel = ReleaseInfo(
                tag = tag,
                version = fullVersion(tag),
                prerelease = j.optBoolean("prerelease", false),
                apkUrl = apk,
                zipUrl = zip
            )
            val cur = best
            if (cur == null || compareVersions(rel.version, cur.version) > 0) best = rel
        }
        best?.let { FetchResult.Ok(it) } ?: FetchResult.Failed(FetchError.NoReleases)
    }

    // ---------------------------------------------------------------- descarga + instalación de la app

    /** Descarga el APK de la release (asset .apk; si no existe, lo extrae del zip del módulo). */
    suspend fun downloadApp(context: Context, rel: ReleaseInfo): File? = withContext(Dispatchers.IO) {
        val dest = File(context.cacheDir, APK_NAME)
        dest.delete()
        try {
            val apkUrl = rel.apkUrl
            val zipUrl = rel.zipUrl
            if (apkUrl != null) {
                httpDownload(apkUrl, dest)
            } else if (zipUrl != null) {
                val tmp = File(context.cacheDir, "$ZIP_NAME.tmp")
                httpDownload(zipUrl, tmp)
                val ok = extractApk(tmp, dest)
                tmp.delete()
                if (!ok) return@withContext null
            } else {
                return@withContext null
            }
        } catch (_: Exception) {
            return@withContext null
        }
        if (dest.length() < 100_000) null else dest
    }

    private fun extractApk(zip: File, dest: File): Boolean {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory && (e.name == "app/$APK_IN_ZIP" || e.name.endsWith(".apk"))) {
                    dest.outputStream().use { out -> zin.copyTo(out) }
                    return true
                }
                e = zin.nextEntry
            }
        }
        return false
    }

    /** El APK descargado es de esta misma app (mismo paquete); la firma la valida `pm`. */
    @Suppress("DEPRECATION")
    fun isOwnApk(context: Context, apk: File): Boolean =
        runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName == context.packageName
        }.getOrDefault(false)

    /**
     * Instala el APK con root (`pm install -r`, mismo certificado => actualiza sin desinstalar) en un
     * proceso desligado y relanza la app al terminar. Si tiene éxito el sistema mata esta app, así que
     * la función solo "vuelve" con false (fallo) o true (éxito y el proceso aún vivo tras unos segundos).
     */
    suspend fun installApkWithRoot(context: Context, apk: File): Boolean = withContext(Dispatchers.IO) {
        val launch = "${context.packageName}/${MainActivity::class.java.name}"
        Shell.cmd(
            "rm -f $TMP_LOG; cp ${shQuote(apk.absolutePath)} $TMP_APK && chmod 644 $TMP_APK"
        ).exec()
        val script = "pm install -r $TMP_APK > $TMP_LOG 2>&1; " +
            "if grep -q Success $TMP_LOG; then rm -f $TMP_APK; " +
            "am start --user 0 -n $launch >/dev/null 2>&1; fi"
        Shell.cmd(
            "S=''; command -v setsid >/dev/null 2>&1 && S=setsid; " +
                "nohup \$S sh -c ${shQuote(script)} >/dev/null 2>&1 &"
        ).exec()
        for (i in 0 until 60) {
            delay(1000)
            val out = Shell.cmd("cat $TMP_LOG 2>/dev/null").exec().out.joinToString("\n")
            if (out.contains("Success")) {
                delay(8000)
                return@withContext true
            }
            if (out.contains("Failure") || out.contains("Exception") || out.contains("Error")) {
                return@withContext false
            }
        }
        false
    }

    // ---------------------------------------------------------------- descarga del módulo

    suspend fun downloadModule(context: Context, rel: ReleaseInfo): File? = withContext(Dispatchers.IO) {
        val zipUrl = rel.zipUrl ?: return@withContext null
        val dest = File(context.cacheDir, ZIP_NAME)
        try {
            httpDownload(zipUrl, dest)
        } catch (_: Exception) {
            return@withContext null
        }
        if (!dest.exists() || dest.length() < 1024) return@withContext null
        Shell.cmd(
            "cp ${shQuote(dest.absolutePath)} /storage/emulated/0/Download/$ZIP_NAME && " +
                "chmod 644 /storage/emulated/0/Download/$ZIP_NAME"
        ).exec()
        dest
    }

    fun openForFlash(context: Context, zip: File) {
        val named = File(context.cacheDir, ZIP_NAME)
        if (zip.canonicalPath != named.canonicalPath) {
            zip.copyTo(named, overwrite = true)
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            named
        )
        val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION
        managers.forEach { (pkg, _) ->
            runCatching { context.grantUriPermission(pkg, uri, grant) }
        }

        for ((pkg, cls) in managers) {
            if (!installed(context, pkg)) continue
            val intent = Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(pkg, cls)
                setDataAndType(uri, "application/zip")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, ZIP_NAME, uri)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        grant
                )
            }
            runCatching {
                context.startActivity(intent)
                return
            }
        }

        val view = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, ZIP_NAME, uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or grant)
        }
        val skip = listOf("anykernel", "kernelflasher", "kernel flasher", "exkm", "franco")
        val pm = context.packageManager
        val matches = pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { ri ->
                val label = (ri.activityInfo.packageName + " " + ri.loadLabel(pm)).lowercase()
                skip.none { it in label }
            }
        matches.forEach { ri ->
            context.grantUriPermission(ri.activityInfo.packageName, uri, grant)
        }
        val chooser = Intent.createChooser(view, context.getString(R.string.flash_with)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or grant)
            putExtra(Intent.EXTRA_TITLE, context.getString(R.string.flash_with))
        }
        context.startActivity(chooser)
    }

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun findAsset(json: JSONObject, accept: (String) -> Boolean): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name").lowercase()
            val url = a.optString("browser_download_url")
            if (url.isNotBlank() && accept(name)) return url
        }
        return null
    }

    private fun findModuleZip(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        var fallback: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name").lowercase()
            if (!name.endsWith(".zip")) continue
            if (name.contains("anykernel")) continue
            val url = a.optString("browser_download_url")
            if (url.isBlank()) continue
            if (name.contains("sdcard_bind") || name.contains("sdbind") || name.contains("module")) {
                return url
            }
            fallback = url
        }
        return fallback
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SD-Bind-Manager")
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            try {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpDownload(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 120000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SD-Bind-Manager")
        }
        try {
            conn.inputStream.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        } finally {
            conn.disconnect()
        }
    }
}
