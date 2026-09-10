package com.sdcardbind.manager

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Repo de GitHub para releases. Una línea `usuario/repo` en
 * /data/adb/modules/sdcard_bind_ui/github.repo (se instala con el módulo).
 */
object Updater {

    const val DEFAULT_REPO = "jpmorales9038-ai/sdbind_project"

    suspend fun configuredRepo(): String = withContext(Dispatchers.IO) {
        val fromModule = Shell.cmd("cat $MODDIR/github.repo 2>/dev/null").exec().out
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            .orEmpty()
        if (fromModule.contains("/")) fromModule else DEFAULT_REPO
    }

    suspend fun checkAndInstall(context: Context, localVersion: String): String = withContext(Dispatchers.IO) {
        val repo = configuredRepo()
        val body = httpGet("https://api.github.com/repos/$repo/releases/latest")
            ?: return@withContext "No se pudo hablar con GitHub"
        val json = JSONObject(body)
        if (json.has("message") && !json.has("tag_name")) {
            val msg = json.optString("message")
            return@withContext if (msg.contains("Not Found", true))
                "Todavía no hay releases. Subí este código a GitHub y esperá a que Actions publique uno."
            else msg
        }
        val tag = json.optString("tag_name").ifBlank { json.optString("name") }
        if (!isNewer(tag, localVersion)) {
            return@withContext "Ya estás al día ($localVersion)"
        }
        val zipUrl = findAsset(json, ".zip")
            ?: return@withContext "La release $tag no trae un .zip"
        val dest = File(context.cacheDir, "sdbind_update.zip")
        httpDownload(zipUrl, dest)
        if (!dest.exists() || dest.length() < 1024) {
            return@withContext "La descarga quedó vacía"
        }
        val tmp = "/data/local/tmp/sdbind_update.zip"
        Shell.cmd("cp ${shQuote(dest.absolutePath)} $tmp").exec()
        val install = Shell.cmd(
            "ksud module install $tmp 2>/dev/null || magisk --install-module $tmp 2>/dev/null"
        ).exec()
        Shell.cmd(
            "mkdir -p /data/local/tmp/sdbind_up && " +
                "unzip -o $tmp 'app/*.apk' -d /data/local/tmp/sdbind_up >/dev/null 2>&1 && " +
                "pm install -r /data/local/tmp/sdbind_up/app/*.apk >/dev/null 2>&1; true"
        ).exec()
        if (install.isSuccess) "Actualizado a $tag. Si el módulo no carga, reiniciá."
        else "Descargado $tag pero falló la instalación del módulo"
    }

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

    private fun findAsset(json: JSONObject, suffix: String): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(suffix, ignoreCase = true)) {
                return a.optString("browser_download_url").ifBlank { null }
            }
        }
        return json.optString("zipball_url").ifBlank { null }
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
