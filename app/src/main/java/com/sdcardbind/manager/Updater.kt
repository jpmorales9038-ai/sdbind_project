package com.sdcardbind.manager

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed class UpdateOutcome {
    data class Ready(val tag: String, val zip: File) : UpdateOutcome()
    data class Info(val message: String) : UpdateOutcome()
}

sealed class AppUpdateOutcome {
    data class UpToDate(val version: String) : AppUpdateOutcome()
    /** El instalador root ya arrancó: Android reinicia la app al terminar. */
    data class Installing(val tag: String) : AppUpdateOutcome()
    data class Failed(val message: String) : AppUpdateOutcome()
}

object Updater {

    const val DEFAULT_REPO = "jpmorales9038-ai/sdbind_project"
    private const val ZIP_NAME = "sdcard_bind_ui.zip"
    private const val APK_NAME = "sdbind-app.apk"
    private const val APK_IN_ZIP = "app/sdcard-bind-manager.apk"
    private const val INSTALL_LOG = "/data/local/tmp/sdbind_install.log"

    private val managers = listOf(
        "com.rifsxd.ksunext" to "com.rifsxd.ksunext.ui.MainActivity",
        "me.weishu.kernelsu" to "me.weishu.kernelsu.ui.MainActivity",
        "com.sukisu.ultra" to "com.sukisu.ultra.ui.MainActivity",
        "com.topjohnwu.magisk" to "com.topjohnwu.magisk.ui.MainActivity",
        "io.github.huskydg.magisk" to "com.topjohnwu.magisk.ui.MainActivity",
        "me.bmax.apatch" to "me.bmax.apatch.ui.MainActivity"
    )

    suspend fun configuredRepo(): String = withContext(Dispatchers.IO) {
        val fromModule = Shell.cmd("cat $MODDIR/github.repo 2>/dev/null").exec().out
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            .orEmpty()
        if (fromModule.contains("/")) fromModule else DEFAULT_REPO
    }

    /** Versión del módulo instalado (module.prop), o null si no se puede leer. */
    suspend fun moduleVersion(): String? = withContext(Dispatchers.IO) {
        Shell.cmd("grep -m1 '^version=' $MODDIR/module.prop 2>/dev/null").exec().out
            .firstOrNull()
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /** Compara solo los números (v2.9.1 == 2.9.1 == 2.9.1.0). */
    fun sameVersion(a: String, b: String): Boolean {
        fun norm(s: String) = verParts(s).dropLastWhile { it == 0 }
        return norm(a) == norm(b)
    }

    private fun latestRelease(context: Context, repo: String): Result<JSONObject> {
        val body = httpGet("https://api.github.com/repos/$repo/releases/latest")
            ?: return Result.failure(Exception(context.getString(R.string.update_no_github)))
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result.failure(Exception(context.getString(R.string.update_no_github)))
        if (json.has("message") && !json.has("tag_name")) {
            val msg = json.optString("message")
            return Result.failure(
                Exception(
                    if (msg.contains("Not Found", true)) context.getString(R.string.update_no_releases)
                    else msg
                )
            )
        }
        return Result.success(json)
    }

    private fun tagOf(json: JSONObject): String =
        json.optString("tag_name").ifBlank { json.optString("name") }

    /**
     * Actualiza la APP (no el módulo): busca el último release, descarga el APK y lo instala
     * con root (`pm install -r`, misma firma). Android mata la app al reemplazarse, así que el
     * instalador corre desacoplado y vuelve a abrirla al terminar.
     */
    suspend fun updateApp(
        context: Context,
        localVersion: String,
        onInstalling: (String) -> Unit = {}
    ): AppUpdateOutcome =
        withContext(Dispatchers.IO) {
            val release = latestRelease(context, configuredRepo())
                .getOrElse { return@withContext AppUpdateOutcome.Failed(it.message.orEmpty()) }
            val tag = tagOf(release)
            if (!isNewer(tag, localVersion)) {
                return@withContext AppUpdateOutcome.UpToDate(localVersion)
            }
            val apk = File(context.cacheDir, APK_NAME)
            apk.delete()
            try {
                val apkUrl = findAsset(release) { it.endsWith(".apk") }
                if (apkUrl != null) {
                    httpDownload(apkUrl, apk)
                } else {
                    // Releases antiguas sin APK suelto: el APK va dentro del zip del módulo.
                    val zipUrl = findModuleZip(release)
                        ?: return@withContext AppUpdateOutcome.Failed(
                            context.getString(R.string.update_no_zip, tag)
                        )
                    val tmp = File(context.cacheDir, ZIP_NAME)
                    httpDownload(zipUrl, tmp)
                    extractFromZip(tmp, APK_IN_ZIP, apk)
                }
            } catch (_: Exception) {
                return@withContext AppUpdateOutcome.Failed(context.getString(R.string.update_no_github))
            }
            if (!apk.exists() || apk.length() < 1024) {
                return@withContext AppUpdateOutcome.Failed(context.getString(R.string.update_empty))
            }
            onInstalling(tag)
            Shell.cmd("rm -f $INSTALL_LOG").exec()
            val path = shQuote(apk.absolutePath)
            val script = "pm install -r -S \$(stat -c %s $path) < $path > $INSTALL_LOG 2>&1; " +
                "am start --user 0 -n ${context.packageName}/.MainActivity >/dev/null 2>&1"
            val launched = Shell.cmd("(nohup sh -c ${shQuote(script)} >/dev/null 2>&1 &)").exec()
            if (!launched.isSuccess) {
                return@withContext AppUpdateOutcome.Failed(context.getString(R.string.update_install_start))
            }
            // Si la instalación funciona, este proceso muere antes de agotar el bucle.
            repeat(30) {
                kotlinx.coroutines.delay(1000)
                val log = Shell.cmd("cat $INSTALL_LOG 2>/dev/null").exec().out.joinToString(" ").trim()
                if (log.contains("Failure", true) || log.contains("Exception", true)) {
                    return@withContext AppUpdateOutcome.Failed(log.take(160))
                }
            }
            AppUpdateOutcome.Installing(tag)
        }

    /** Descarga el zip del módulo del último release (sin exigir que sea más nuevo). */
    suspend fun downloadModule(context: Context): UpdateOutcome =
        withContext(Dispatchers.IO) {
            val release = latestRelease(context, configuredRepo())
                .getOrElse { return@withContext UpdateOutcome.Info(it.message.orEmpty()) }
            val tag = tagOf(release)
            val zipUrl = findModuleZip(release)
                ?: return@withContext UpdateOutcome.Info(context.getString(R.string.update_no_zip, tag))
            val dest = File(context.cacheDir, ZIP_NAME)
            try {
                httpDownload(zipUrl, dest)
            } catch (_: Exception) {
                return@withContext UpdateOutcome.Info(context.getString(R.string.update_no_github))
            }
            if (!dest.exists() || dest.length() < 1024) {
                return@withContext UpdateOutcome.Info(context.getString(R.string.update_empty))
            }
            Shell.cmd(
                "cp ${shQuote(dest.absolutePath)} /storage/emulated/0/Download/$ZIP_NAME && " +
                    "chmod 644 /storage/emulated/0/Download/$ZIP_NAME"
            ).exec()
            UpdateOutcome.Ready(tag, dest)
        }

    private fun extractFromZip(zip: File, entry: String, dest: File) {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.name == entry) {
                    dest.outputStream().use { zin.copyTo(it) }
                    return
                }
            }
        }
    }

    private fun findAsset(json: JSONObject, match: (String) -> Boolean): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (match(a.optString("name").lowercase())) {
                val url = a.optString("browser_download_url")
                if (url.isNotBlank()) return url
            }
        }
        return null
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

    private fun isNewer(remote: String, local: String): Boolean {
        val a = verParts(remote)
        val b = verParts(local)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun verParts(s: String): List<Int> =
        s.trim().removePrefix("v").removePrefix("V")
            .split(Regex("[^0-9]+"))
            .mapNotNull { it.toIntOrNull() }

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
