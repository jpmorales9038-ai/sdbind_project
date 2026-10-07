package com.sdcardbind.manager

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** [Ready]: APK descargado y verificado, a la espera de que el usuario confirme la instalación. */
enum class AppPhase { Idle, Checking, Downloading, Ready, Installing, UpToDate, Done, Failed }

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
 * - La app busca sola (al abrirse y cada ~30 min en primer plano, ver [autoCheck]). Si hay una
 *   versión más nueva (finales y pre-releases; gana la más alta) la descarga, verifica el paquete y
 *   pasa a [AppPhase.Ready]: tarjeta verde con un botón que solo confirma la instalación
 *   (`pm install -r` con root, [installReady]). Sin actualización no hay botón.
 * - [refresh] (deslizar en Ajustes) repite la búsqueda al momento por si la automática no la vio.
 * - Al sustituirse el APK el sistema mata este proceso, así que antes se guarda una marca
 *   ([KEY_UPDATED]) y, al reabrir, la tarjeta se muestra en verde una vez.
 * - En cada apertura (ON_START) se compara la versión de la app con la del módulo; si no coinciden
 *   la tarjeta pasa a ámbar y ofrece descargar y flashear el módulo desde la propia app.
 */
class UpdateController(private val context: Context) {

    private val prefs = context.getSharedPreferences("sdbind", Context.MODE_PRIVATE)
    private var release: ReleaseInfo? = null
    private var keepDoneOnce = false
    private var working = false
    private var lastAutoCheck = 0L
    private var readyApk: File? = null

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
    /** Últimas líneas del instalador mientras se flashea el módulo (vacío si no se está flasheando). */
    var flashLines by mutableStateOf<List<String>>(emptyList())
        private set
    /** Versión descargada y lista para instalar (solo con [AppPhase.Ready]). */
    var readyVersion by mutableStateOf<String?>(null)
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
        autoCheck()
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

    /** Búsqueda silenciosa: como mucho una vez cada [AUTO_INTERVAL] ms. No muestra errores. */
    suspend fun autoCheck() {
        val now = SystemClock.elapsedRealtime()
        if (lastAutoCheck != 0L && now - lastAutoCheck < AUTO_INTERVAL) return
        checkNow(manual = false)
        lastAutoCheck = now // si se cancela a medias (la app sale de primer plano) no cuenta
    }

    /** Búsqueda manual (deslizar para refrescar): módulo + app, con mensajes de estado. */
    suspend fun refresh() {
        refreshModule()
        checkNow(manual = true)
    }

    /**
     * Busca la última versión; si es más nueva la descarga y deja la tarjeta en [AppPhase.Ready].
     * Con [manual] = false los fallos y el "ya estás al día" no se muestran.
     */
    private suspend fun checkNow(manual: Boolean) {
        if (working || busy || moduleBusy) return
        if (phase == AppPhase.Done && !manual) return
        working = true
        val wasReady = phase == AppPhase.Ready
        try {
            if (manual && !wasReady) {
                phase = AppPhase.Checking
                setMsg(R.string.checking_updates)
            }
            val rel = when (val r = Updater.fetchLatest()) {
                is FetchResult.Failed -> {
                    if (manual && !wasReady) {
                        when (r.kind) {
                            FetchError.Network -> fail(R.string.update_no_github)
                            FetchError.NoReleases -> fail(R.string.update_no_releases)
                            FetchError.Other -> fail(R.string.upd_github_error, r.detail.orEmpty())
                        }
                    }
                    return
                }
                is FetchResult.Ok -> r.release
            }
            release = rel
            if (!Updater.isNewer(rel.version, appVersion)) {
                if (manual && !wasReady) {
                    // Si el módulo hay que actualizarlo (o reiniciar) no se dice "al día".
                    if (module is ModuleStatus.Mismatch || module is ModuleStatus.PendingReboot) {
                        phase = AppPhase.Idle
                        setMsg(null)
                    } else {
                        setMsg(R.string.update_up_to_date, Updater.displayFull(appVersion))
                        phase = AppPhase.UpToDate
                    }
                }
                return
            }
            if (rel.apkUrl == null && rel.zipUrl == null) {
                if (manual && !wasReady) fail(R.string.update_no_zip, rel.tag)
                return
            }
            val target = Updater.displayFull(rel.version)
            // Ya descargada esa misma versión: sigue lista, no se baja otra vez.
            val have = readyApk
            if (wasReady && have != null && have.isFile && readyVersion?.let { Updater.sameBuild(it, rel.version) } == true) {
                return
            }
            val cached = Updater.cachedApk(context)
                ?.takeIf { prefs.getString(KEY_READY, null) == rel.version && Updater.isOwnApk(context, it) }
            val apk = cached ?: run {
                // downloadApp pisa el mismo archivo: el APK "listo" anterior deja de valer.
                readyApk = null
                readyVersion = null
                phase = AppPhase.Downloading
                setMsg(R.string.upd_downloading, target)
                Updater.downloadApp(context, rel)
            }
            if (apk == null || !Updater.isOwnApk(context, apk)) {
                if (manual) fail(if (apk == null) R.string.upd_download_failed else R.string.upd_bad_apk)
                else { phase = AppPhase.Idle; setMsg(null) }
                return
            }
            prefs.edit().putString(KEY_READY, rel.version).apply()
            readyApk = apk
            readyVersion = rel.version
            setMsg(R.string.upd_ready, target)
            phase = AppPhase.Ready
        } finally {
            working = false
            // Cancelada a medias (la app salió de primer plano): no dejar la tarjeta "ocupada".
            if (phase == AppPhase.Checking || phase == AppPhase.Downloading) {
                if (readyApk != null) {
                    phase = AppPhase.Ready
                } else {
                    phase = AppPhase.Idle
                    setMsg(null)
                }
            }
        }
    }

    /** El usuario confirma: instala con root el APK ya descargado y verificado. */
    suspend fun installReady() {
        if (busy || moduleBusy) return
        val apk = readyApk
        val ver = readyVersion
        if (phase != AppPhase.Ready || apk == null || ver == null) return
        if (!apk.isFile) { // la caché se limpió: volver a buscar y descargar
            readyApk = null
            readyVersion = null
            phase = AppPhase.Idle
            setMsg(null)
            checkNow(manual = true)
            return
        }
        val target = Updater.displayFull(ver)
        phase = AppPhase.Installing
        setMsg(R.string.upd_installing, target)
        prefs.edit().putString(KEY_UPDATED, ver).commit()
        val ok = Updater.installApkWithRoot(context, apk)
        prefs.edit().remove(KEY_UPDATED).commit()
        if (ok) {
            readyApk = null
            readyVersion = null
            prefs.edit().remove(KEY_READY).apply()
            setMsg(R.string.upd_done, target)
            phase = AppPhase.Done
        } else {
            setMsg(R.string.upd_install_failed)
            phase = AppPhase.Ready // sigue lista: se puede reintentar
        }
    }

    /** Descarga el zip del módulo y lo flashea desde la propia app (sin gestor de módulos). */
    suspend fun flashModule() {
        if (busy || moduleBusy) return
        moduleBusy = true
        flashLines = emptyList()
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
            setMsg(R.string.upd_module_flashing)
            when (Updater.flashModuleZip(context, zip) { flashLines = it }) {
                FlashResult.Ok -> {
                    flashLines = emptyList()
                    val info = Updater.readModuleInfo()
                    refreshModule()
                    if (info != null && info.pendingReboot) {
                        setMsg(R.string.upd_pending_reboot, Updater.displayVersion(info.version))
                    } else {
                        setMsg(null)
                    }
                }
                FlashResult.NoInstaller -> setMsg(R.string.upd_module_no_installer)
                FlashResult.Failed -> setMsg(R.string.upd_module_flash_failed)
            }
        } finally {
            moduleBusy = false
        }
    }

    private companion object {
        const val KEY_UPDATED = "updated_to"
        const val KEY_READY = "ready_version"
        const val AUTO_INTERVAL = 10 * 60 * 1000L
    }
}
