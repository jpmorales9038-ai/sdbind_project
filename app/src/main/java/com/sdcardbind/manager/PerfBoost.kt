package com.sdcardbind.manager

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compilación anticipada (AOT) de la propia app. Un APK instalado con `pm install -r` queda solo
 * "verificado": el código se ejecuta interpretado/JIT hasta que el sistema lo compila en su
 * mantenimiento nocturno (horas o días después). De ahí el síntoma de arranque pesado, animaciones
 * a tirones y scroll trabado que luego "se arreglan solos" y vuelven tras cada actualización.
 * Con root se fuerza `cmd package compile -m speed`: todo el código (app + Compose) pasa a nativo.
 * Se hace una vez por versión instalada, con un shell propio para no bloquear el principal.
 */
object PerfBoost {
    private const val KEY = "aot_done"

    suspend fun ensureCompiled(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.getSharedPreferences("sdbind", Context.MODE_PRIVATE)
            val ver = Updater.installedVersion(context)
            if (ver.isEmpty() || prefs.getString(KEY, null) == ver) return@runCatching
            val pkg = context.packageName
            val shell = Shell.Builder.create().setTimeout(240).build()
            try {
                val out = shell.newJob()
                    .add("cmd package compile -m speed -f $pkg || pm compile -m speed -f $pkg")
                    .to(ArrayList<String>())
                    .exec()
                if (out.out.any { it.contains("Success", ignoreCase = true) }) {
                    prefs.edit().putString(KEY, ver).apply()
                }
            } finally {
                shell.close()
            }
        }
    }
}
