package com.sdcardbind.manager

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppPhase { Idle, Checking, Downloading, Installing, UpToDate, Done, Failed }

/** Relación entre la versión de la app y la del módulo instalado. */
sealed interface ModuleStatus {
    data object Unknown : ModuleStatus
    data object NotFound : ModuleStatus
    data class Synced(val version: String) : ModuleStatus
    /** Misma versión que la app, ya flasheada pero a falta de reiniciar. */
    data class PendingReboot(val version: String) : ModuleStatus
    data class Mismatch(val moduleVersion: String) : ModuleStatus
}

/**
 * Estado y acciones de la tarjeta "Actualizaciones" (Ajustes).
 *
 * - La app se actualiza sola al pulsar el botón: GitHub -> descarga -> `pm install -r` con root.
 *   Se consideran también las pre-releases (p. ej. `v2.9.3-preview.57`): gana la versión más alta.
 *   Al sustituirse el APK el sistema mata este proceso, así que antes se guarda una marca
 *   ([KEY_UPDATED]) y, al reabrir, la tarjeta se muestra en verde una vez.
 * - En cada apertura (ON_START) se compara la versión de la app con la del módulo; si no coinciden
 *   la tarjeta pasa a ámbar y ofrece descargar y flashear el módulo.
 */
class UpdateController(private val context: Context) {

    private val prefs = context.getSharedPreferences("sdbind", Context.MODE_PRIVATE)
    private var release: ReleaseInfo? = null
    private var keepDoneOnce = false

    val appVersion: String = Updater.installedVersion(context)

    var phase by mutableStateOf(AppPhase.Idle)
        private set
    var msgRes by mutableStateOf<Int?>(null)
        private set
    var msgArg by mutableStateOf("")
        private set
    var module by mutableStateOf<ModuleStatus>(ModuleStatus.Unknown)
        private set
    var moduleBusy by mutableStateOf(false)
        private set

    val busy: Boolean
        get() = phase == AppPhase.Checking || phase == AppPhase.Downloading || phase == AppPhase.Installing

    init {
        val marker = prefs.getString(KEY_UPDATED, null)
        if (marker != null) {
            prefs.edit().remove(KEY_UPDATED).apply()
            if (Updater.sameBuild(marker, appVersion)) {
                setMsg(R.string.upd_done, Updater.displayFull(appVersion))
                phase = AppPhase.Done
                keepDoneOnce = true
            }
        }
    }

    private fun setMsg(res: Int?, arg: String = "") {
        msgRes = res
        msgArg = arg
    }

    private fun fail(res: Int, arg: String = "") {
        setMsg(res, arg)
        phase = AppPhase.Failed
    }

    /** Se llama cada vez que la app pasa a primer plano (con root disponible). */
    suspend fun onForeground() {
        if (keepDoneOnce) {
            // Primera apertura tras actualizarse: se deja el verde; la siguiente ya avisa del módulo.
            keepDoneOnce = false
        } else if (!busy && !moduleBusy &&
            (phase == AppPhase.Done || phase == AppPhase.UpToDate || phase == AppPhase.Failed)
        ) {
            phase = AppPhase.Idle
            setMsg(null)
        }
        refreshModule()
    }

    suspend fun refreshModule() {
        val info = Updater.readModuleInfo()
        module = when {
            info == null -> ModuleStatus.NotFound
            !Updater.sameVersion(info.version, appVersion) -> ModuleStatus.Mismatch(info.version)
            info.pendingReboot -> ModuleStatus.PendingReboot(info.version)
            else -> ModuleStatus.Synced(info.version)
        }
    }

    /** Busca, descarga e instala la última versión de la app (finales y pre-releases). */
    suspend fun updateApp() {
        if (busy || moduleBusy) return
        phase = AppPhase.Checking
        setMsg(R.string.checking_updates)
        val rel = when (val r = Updater.fetchLatest()) {
            is FetchResult.Failed -> {
                when (r.kind) {
                    FetchError.Network -> fail(R.string.update_no_github)
                    FetchError.NoReleases -> fail(R.string.update_no_releases)
                    FetchError.Other -> fail(R.string.upd_github_error, r.detail.orEmpty())
                }
                return
            }
            is FetchResult.Ok -> r.release
        }
        release = rel
        if (!Updater.isNewer(rel.version, appVersion)) {
            setMsg(R.string.update_up_to_date, Updater.displayFull(appVersion))
            phase = AppPhase.UpToDate
            return
        }
        if (rel.apkUrl == null && rel.zipUrl == null) {
            fail(R.string.update_no_zip, rel.tag)
            return
        }
        val target = Updater.displayFull(rel.version)
        phase = AppPhase.Downloading
        setMsg(R.string.upd_downloading, target)
        val apk = Updater.downloadApp(context, rel)
        if (apk == null) {
            fail(R.string.upd_download_failed)
            return
        }
        if (!Updater.isOwnApk(context, apk)) {
            fail(R.string.upd_bad_apk)
            return
        }
        phase = AppPhase.Installing
        setMsg(R.string.upd_installing, target)
        prefs.edit().putString(KEY_UPDATED, rel.version).commit()
        val ok = Updater.installApkWithRoot(context, apk)
        prefs.edit().remove(KEY_UPDATED).commit()
        if (ok) {
            setMsg(R.string.upd_done, target)
            phase = AppPhase.Done
        } else {
            fail(R.string.upd_install_failed)
        }
    }

    /** Descarga el zip del módulo y lo abre en el gestor de root para flashearlo. */
    suspend fun flashModule() {
        if (busy || moduleBusy) return
        moduleBusy = true
        try {
            setMsg(R.string.upd_module_downloading)
            val rel = release ?: (Updater.fetchLatest() as? FetchResult.Ok)?.release
            if (rel == null) {
                setMsg(R.string.update_no_github)
                return
            }
            release = rel
            if (rel.zipUrl == null) {
                setMsg(R.string.update_no_zip, rel.tag)
                return
            }
            val zip = Updater.downloadModule(context, rel)
            if (zip == null) {
                setMsg(R.string.upd_download_failed)
                return
            }
            setMsg(R.string.update_ready, rel.tag)
            runCatching { Updater.openForFlash(context, zip) }
                .onFailure { setMsg(R.string.update_saved_downloads) }
        } finally {
            moduleBusy = false
        }
    }

    private companion object {
        const val KEY_UPDATED = "updated_to"
    }
}
