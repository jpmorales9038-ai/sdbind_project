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

object Updater {

    const val DEFAULT_REPO = "jpmorales9038-ai/sdbind_project"
    private const val ZIP_NAME = "sdcard_bind_ui.zip"

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

    suspend fun checkAndDownload(context: Context, localVersion: String): UpdateOutcome =
        withContext(Dispatchers.IO) {
            val repo = configuredRepo()
            val body = httpGet("https://api.github.com/repos/$repo/releases/latest")
                ?: return@withContext UpdateOutcome.Info(context.getString(R.string.update_no_github))
            val json = JSONObject(body)
            if (json.has("message") && !json.has("tag_name")) {
                val msg = json.optString("message")
                return@withContext UpdateOutcome.Info(
                    if (msg.contains("Not Found", true))
                        context.getString(R.string.update_no_releases)
                    else msg
                )
            }
            val tag = json.optString("tag_name").ifBlank { json.optString("name") }
            if (!isNewer(tag, localVersion)) {
                return@withContext UpdateOutcome.Info(context.getString(R.string.update_up_to_date, localVersion))
            }
            val zipUrl = findModuleZip(json)
                ?: return@withContext UpdateOutcome.Info(context.getString(R.string.update_no_zip, tag))
            val dest = File(context.cacheDir, ZIP_NAME)
            httpDownload(zipUrl, dest)
            if (!dest.exists() || dest.length() < 1024) {
                return@withContext UpdateOutcome.Info(context.getString(R.string.update_empty))
            }
            Shell.cmd(
                "cp ${shQuote(dest.absolutePath)} /storage/emulated/0/Download/$ZIP_NAME && " +
                    "chmod 644 /storage/emulated/0/Download/$ZIP_NAME"
            ).exec()
            UpdateOutcome.Ready(tag, dest)
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
