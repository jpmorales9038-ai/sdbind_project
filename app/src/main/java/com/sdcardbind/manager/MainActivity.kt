package com.sdcardbind.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sdcardbind.manager.ui.AppTheme
import com.sdcardbind.manager.ui.UiBottomFade
import com.sdcardbind.manager.ui.UiCard
import com.sdcardbind.manager.ui.UiHeader
import com.sdcardbind.manager.ui.UiHeroCard
import com.sdcardbind.manager.ui.UiNavBar
import com.sdcardbind.manager.ui.UiNavItem
import com.sdcardbind.manager.ui.UiNavClearance
import com.sdcardbind.manager.ui.UiScreenPadding
import com.sdcardbind.manager.ui.uiInnerColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    // La config del shell root vive en BindApplication (registrada en el manifest): se
    // arranca con el proceso, antes de que exista esta Activity.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Un único enableEdgeToEdge: barras transparentes y íconos claros/oscuros según el
        // tema del sistema. La barra de navegación Expressive pinta su propio fondo.
        enableEdgeToEdge()
        setContent {
            AppTheme { BindApp() }
        }
    }
}

private enum class Tab { Home, Log, About }
private enum class Flow { Home, PickSource, PickDest, Browse }

/** Animación de tamaño con el esquema de movimiento Expressive del tema. */
@Composable
private fun sizeSpec() = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()

@Composable
fun BindApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val slideSpec = motion.defaultSpatialSpec<IntOffset>()
    val fadeInSpec = motion.defaultEffectsSpec<Float>()
    val fadeOutSpec = motion.fastEffectsSpec<Float>()
    val fabSpec = motion.fastSpatialSpec<Float>()
    val pageSpec = motion.defaultEffectsSpec<Float>()
    val snackbarHost = remember { SnackbarHostState() }
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var entries by remember { mutableStateOf(listOf<MountEntry>()) }
    var volumes by remember { mutableStateOf(listOf<StorageVolume>()) }
    var storageGen by remember { mutableIntStateOf(0) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val goTab: (Tab) -> Unit = { t ->
        scope.launch {
            pagerState.animateScrollToPage(
                t.ordinal,
                animationSpec = pageSpec
            )
        }
    }
    var flow by remember { mutableStateOf(Flow.Home) }
    var pendingSource by remember { mutableStateOf("") }
    var browsePath by remember { mutableStateOf("") }
    var snack by remember { mutableStateOf<String?>(null) }
    var otgPopup by remember { mutableStateOf<StorageVolume?>(null) }
    var pendingDelete by remember { mutableStateOf<MountEntry?>(null) }
    var volumesPrimed by remember { mutableStateOf(false) }
    var popupQuietUntil by remember { mutableStateOf(0L) }

    fun applyVolumes(next: List<StorageVolume>) {
        val oldIds = volumes.filter { it.kind == VolumeKind.EXTERNAL }.map { volId(it.path) }.toSet()
        val oldKey = volumes.joinToString("|") { "${it.kind}:${volId(it.path)}" }
        val newKey = next.joinToString("|") { "${it.kind}:${volId(it.path)}" }
        volumes = next
        if (oldKey != newKey) storageGen++
        if (volumesPrimed && System.currentTimeMillis() > popupQuietUntil) {
            val fresh = next.filter { it.kind == VolumeKind.EXTERNAL && volId(it.path) !in oldIds }
                .filter { humanToBytes(it.totalHuman) >= 8L * 1024 * 1024 }
                .maxByOrNull { humanToBytes(it.totalHuman) }
            if (fresh != null) otgPopup = fresh
        }
        volumesPrimed = true
    }

    fun onHardwareAttach() {
        if (!volumesPrimed) return
        val known = volumes.filter { it.kind == VolumeKind.EXTERNAL }.map { volId(it.path) }.toSet()
        scope.launch {
            var best: StorageVolume? = null
            repeat(8) {
                delay(400)
                val latest = RootOps.storageVolumes()
                applyVolumes(latest)
                // Al conectar hardware, además de los anillos, refrescamos el estado de los
                // vínculos ahora mismo (en vez de esperar hasta 1.5s al próximo ciclo del
                // loop de abajo) para que "ausente" -> "presente" se sienta instantáneo.
                entries = RootOps.loadMounts()
                best = latest.filter { it.kind == VolumeKind.EXTERNAL && volId(it.path) !in known }
                    .sortedWith(
                        compareByDescending<StorageVolume> { it.path.contains("media_rw") }
                            .thenByDescending { humanToBytes(it.totalHuman) }
                    )
                    .firstOrNull()
                if (best != null && humanToBytes(best!!.totalHuman) >= 8L * 1024 * 1024) {
                    otgPopup = best
                    return@launch
                }
            }
        }
    }

    fun refresh(showSnack: Boolean = false, forceAnim: Boolean = showSnack) {
        scope.launch {
            RootOps.pruneStaleMounts()
            entries = RootOps.loadMounts()
            applyVolumes(RootOps.storageVolumes())
            log = RootOps.tailLog()
            if (forceAnim) storageGen++
            if (showSnack) snack = context.getString(R.string.storage_updated)
        }
    }

    fun unmountAllNow(auto: Boolean = false) {
        scope.launch {
            busy = true
            popupQuietUntil = System.currentTimeMillis() + 8000
            RootOps.unmountAll()
            refresh()
            if (auto) snack = context.getString(R.string.auto_unmounted)
            busy = false
        }
    }

    // FIX: antes, ACTION_MEDIA_UNMOUNTED/REMOVED/BAD_REMOVAL llamaban unmountAllNow()
    // directo, sin confirmar nada — un log real mostró un desmontaje a mitad de una partida
    // (GTAV) sin que el margen de gracia ni el self-heal del lado shell (_watch_cb) hubieran
    // llegado siquiera a mirarlo: la app ya había desmontado todo por su cuenta antes. Android
    // puede emitir estos broadcasts (sobre todo BAD_REMOVAL) igual aunque la SD/OTG siga
    // físicamente puesta, bajo la misma combinación de I/O intensa + RAM baja que ya
    // confundía al watch loop antes de agregarle _device_present. Acá se hace la misma
    // verificación (rápida, contra /proc/1/mounts, sin tocar el filesystem) antes de
    // desmontar todo en el acto.
    fun onPossibleMediaGone() {
        scope.launch {
            if (RootOps.anyVolumePresent()) {
                // Probable falso positivo: el volumen sigue en la tabla de montaje del
                // kernel. No desmontamos nada de golpe — se deja que el loop normal
                // (_watch_cb, con su propio margen de gracia) siga vigilando, igual que con
                // cualquier otro hipo transitorio.
                RootOps.pruneStaleMounts()
                refresh()
            } else {
                unmountAllNow(auto = true)
            }
        }
    }

    fun clearLogNow() {
        scope.launch {
            RootOps.clearLog()
            log = RootOps.tailLog()
            snack = context.getString(R.string.log_cleared)
        }
    }

    LaunchedEffect(Unit) {
        rootOk = RootOps.isRootAvailable()
        if (rootOk == true) refresh()
    }
    LaunchedEffect(snack) {
        snack?.let {
            snackbarHost.showSnackbar(it)
            snack = null
        }
    }

    // El sondeo (cada 1.5s: volúmenes + estado de cada vínculo) solo corre con la app visible.
    // Antes seguía lanzando comandos root en segundo plano aunque la pantalla estuviera
    // apagada; ahora se pausa solo y, al volver, refresca de inmediato.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(rootOk) {
        if (rootOk != true) return@LaunchedEffect
        var firstStart = true
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!firstStart) {
                applyVolumes(RootOps.storageVolumes())
                entries = RootOps.loadMounts()
            }
            firstStart = false
            while (true) {
                delay(1500)
                applyVolumes(RootOps.storageVolumes())
                // El estado de cada vínculo (presente/ausente) se recalcula en el mismo
                // ciclo, sin pruneStaleMounts ni tailLog (eso sigue solo en refresh()).
                entries = RootOps.loadMounts()
            }
        }
    }

    // Actualizaciones: estado de la tarjeta de Ajustes. Cada vez que la app vuelve a primer plano
    // se compara la versión de la app con la del módulo (ámbar si no coinciden).
    val updates = remember { UpdateController(context) }
    LaunchedEffect(rootOk) {
        if (rootOk != true) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            updates.onForeground()
            // Con la app abierta, vuelve a buscar de vez en cuando (autoCheck limita la frecuencia).
            while (true) {
                delay(30 * 60 * 1000L)
                updates.autoCheck()
            }
        }
    }

    DisposableEffect(rootOk) {
        if (rootOk != true) return@DisposableEffect onDispose { }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED,
                    Intent.ACTION_MEDIA_MOUNTED -> onHardwareAttach()
                    UsbManager.ACTION_USB_DEVICE_DETACHED,
                    Intent.ACTION_MEDIA_UNMOUNTED,
                    Intent.ACTION_MEDIA_REMOVED,
                    Intent.ACTION_MEDIA_BAD_REMOVAL -> onPossibleMediaGone()
                }
            }
        }
        val usb = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val media = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, usb, Context.RECEIVER_EXPORTED)
            context.registerReceiver(receiver, media, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, usb)
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, media)
        }
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val currentTab = Tab.entries.getOrElse(pagerState.currentPage) { Tab.Home }
    val screen = when {
        rootOk == false -> "noroot"
        flow == Flow.PickSource -> "src"
        flow == Flow.PickDest -> "dst"
        flow == Flow.Browse -> "browse"
        else -> "tabs"
    }

    val showChrome = screen == "tabs" && rootOk == true
    val hasExternal = volumes.any { it.kind == VolumeKind.EXTERNAL }
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = cs.background,
        snackbarHost = {
            SnackbarHost(snackbarHost, Modifier.padding(bottom = if (showChrome) 96.dp else 0.dp))
        }
    ) { pad ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            AnimatedContent(
                targetState = screen,
                modifier = Modifier.weight(1f),
            transitionSpec = {
                val forward = targetState == "src" || (initialState == "src" && targetState == "dst")
                if (forward) {
                    (slideInHorizontally(slideSpec) { it } + fadeIn(fadeInSpec)) togetherWith
                        (slideOutHorizontally(slideSpec) { -it / 5 } + fadeOut(fadeOutSpec))
                } else {
                    (slideInHorizontally(slideSpec) { -it / 5 } + fadeIn(fadeInSpec)) togetherWith
                        (slideOutHorizontally(slideSpec) { it } + fadeOut(fadeOutSpec))
                }.using(SizeTransform(clip = false))
            },
            label = "screen"
        ) { s ->
            when (s) {
                "noroot" -> NoRoot()
                "src" -> FolderPickerScreen(
                    title = stringResource(R.string.source_title),
                    hint = stringResource(R.string.source_hint),
                    startPath = "/mnt/media_rw",
                    confirmLabel = stringResource(R.string.next),
                    onBack = { flow = Flow.Home },
                    onPicked = { path ->
                        pendingSource = normalizeDir(path)
                        flow = Flow.PickDest
                    }
                )
                "dst" -> FolderPickerScreen(
                    title = stringResource(R.string.dest_title),
                    hint = stringResource(R.string.dest_hint),
                    startPath = "/storage/emulated/0",
                    confirmLabel = stringResource(R.string.link_and_mount),
                    rejectRoot = true,
                    onBack = { flow = Flow.PickSource },
                    onPicked = { path ->
                        val dest = normalizeDir(path)
                        if (isUnsafeDest(dest)) {
                            snack = context.getString(R.string.pick_subfolder)
                        } else {
                            scope.launch {
                                busy = true
                                val next = entries + MountEntry(pendingSource, dest, true)
                                val ok = RootOps.saveAndApply(next)
                                snack = if (ok) context.getString(R.string.mounted_in, dest)
                                else context.getString(R.string.mount_error)
                                refresh()
                                busy = false
                                flow = Flow.Home
                            }
                        }
                    }
                )
                "browse" -> FileBrowserScreen(
                    startPath = browsePath,
                    onBack = { flow = Flow.Home }
                )
                else -> HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = true
                ) { page ->
                    when (page) {
                        1 -> LogPane(log, onClear = { clearLogNow() }, onRefresh = { refresh() })
                        2 -> AboutPane(
                            updates = updates,
                            onInstallUpdate = { scope.launch { updates.installReady() } },
                            onRefresh = { updates.refresh() },
                            onFlashModule = { scope.launch { updates.flashModule() } }
                        )
                        else -> HomePane(
                            volumes = volumes,
                            entries = entries,
                            busy = busy,
                            landscape = landscape,
                            playToken = storageGen,
                            onRefreshStorage = { refresh(true) },
                            onDelete = { i -> pendingDelete = entries.getOrNull(i) },
                            onOpen = { path -> browsePath = path; flow = Flow.Browse },
                            onAdd = {
                                if (hasExternal) {
                                    flow = Flow.PickSource; pendingSource = ""
                                } else {
                                    snack = context.getString(R.string.no_external_hint)
                                }
                            },
                            onToggle = { on ->
                                if (!on) {
                                    unmountAllNow()
                                } else if (!hasExternal) {
                                    snack = context.getString(R.string.no_external_hint)
                                } else if (entries.isEmpty()) {
                                    snack = context.getString(R.string.nothing_bound)
                                } else {
                                    scope.launch {
                                        busy = true
                                        popupQuietUntil = System.currentTimeMillis() + 8000
                                        val ok = RootOps.saveAndApply(entries)
                                        snack = if (ok) context.getString(R.string.binds_applied)
                                        else context.getString(R.string.mount_failed)
                                        refresh()
                                        busy = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
        }
    }
    // Difuminado inferior (detrás de la barra flotante y de la zona de gestos).
    AnimatedVisibility(
        visible = showChrome,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = fadeIn(fadeInSpec),
        exit = fadeOut(fadeOutSpec)
    ) { UiBottomFade() }
    // Barra flotante: superpuesta al contenido, centrada abajo.
    AnimatedVisibility(
        visible = showChrome,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(slideSpec) { it } + fadeIn(fadeInSpec),
        exit = slideOutVertically(slideSpec) { it } + fadeOut(fadeOutSpec)
    ) {
        AppNavBar(
            selected = pagerState.currentPage,
            onTab = goTab,
            modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp)
        )
    }
    OtgConnectPopup(vol = otgPopup, onDismiss = { otgPopup = null })
    pendingDelete?.let { entry ->
        val name = dirBaseName(entry.dest).ifBlank { dirBaseName(entry.source).ifBlank { context.getString(R.string.this_bind) } }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_bind)) },
            text = { Text(stringResource(R.string.delete_bind_body, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            busy = true
                            val remaining = entries.filterNot {
                                it.source.trimEnd('/') == entry.source.trimEnd('/') &&
                                    it.dest.trimEnd('/') == entry.dest.trimEnd('/')
                            }
                            entries = remaining
                            val ok = RootOps.removeMount(entry.source, entry.dest)
                            snack = if (ok) context.getString(R.string.bind_deleted)
                            else context.getString(R.string.bind_delete_failed)
                            refresh()
                            busy = false
                        }
                    }
                ) { Text(stringResource(R.string.delete), color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    }
}


@Composable
private fun OtgConnectPopup(vol: StorageVolume?, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(vol?.path) {
        if (vol != null) {
            kotlinx.coroutines.delay(8000)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = vol != null,
        enter = fadeIn(tween(320, easing = FastOutSlowInEasing)) +
            slideInVertically(tween(520, easing = FastOutSlowInEasing)) { it / 3 },
        exit = fadeOut(tween(260, easing = FastOutSlowInEasing)) +
            slideOutVertically(tween(360, easing = FastOutSlowInEasing)) { it / 3 }
    ) {
        val shown = vol ?: return@AnimatedVisibility
        val id = volId(shown.path)
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (landscape) 0.38f else 0.45f))
                    .clickable(onClick = onDismiss)
            )
            Column(
                Modifier
                    .then(
                        if (landscape) {
                            Modifier
                                .align(Alignment.Center)
                                .widthIn(max = 400.dp)
                                .fillMaxWidth(0.46f)
                        } else {
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        }
                    )
                    .clip(RoundedCornerShape(32.dp))
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        if (id.isBlank()) "OTG" else id,
                        color = cs.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterEnd).size(36.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = cs.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.usb_connected), color = cs.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .size(if (landscape) 140.dp else 200.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF141414)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.usb_otg),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Barra de navegación flotante (ver ui/UiKit.kt). */
@Composable
private fun AppNavBar(selected: Int, onTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(
        UiNavItem(stringResource(R.string.tab_home), Icons.Filled.Home),
        UiNavItem(stringResource(R.string.tab_log), Icons.Filled.Notes),
        UiNavItem(stringResource(R.string.tab_settings), Icons.Filled.Info)
    )
    UiNavBar(items = items, selected = selected, onSelect = { onTab(Tab.entries[it]) }, modifier = modifier)
}

@Composable
private fun NoRoot() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Lock, null, tint = cs.error, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.no_root), color = cs.onBackground, style = MaterialTheme.typography.headlineMediumEmphasized)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.no_root_hint), color = cs.onSurfaceVariant)
    }
}

/** Contenedor "deslizar para refrescar": ejecuta [onRefresh] y mantiene el indicador un instante. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullRefresh(onRefresh: () -> Unit, content: @Composable () -> Unit) =
    PullRefreshAwait(onRefresh = { onRefresh() }, minMillis = 1200, content = content)

/** Como [PullRefresh] pero espera a que termine [onRefresh] (indicador visible al menos [minMillis]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullRefreshAwait(onRefresh: suspend () -> Unit, minMillis: Long = 600, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) scope.launch {
                refreshing = true
                try {
                    val t0 = System.currentTimeMillis()
                    onRefresh()
                    val left = minMillis - (System.currentTimeMillis() - t0)
                    if (left > 0) delay(left)
                } finally {
                    refreshing = false
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { content() }
}

@Composable
private fun HomePane(
    volumes: List<StorageVolume>,
    entries: List<MountEntry>,
    busy: Boolean,
    landscape: Boolean,
    playToken: Int,
    onRefreshStorage: () -> Unit,
    onDelete: (Int) -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val active = entries.any { it.status == "MOUNTED" }
    val hasExternal = volumes.any { it.kind == VolumeKind.EXTERNAL }
    val header: @Composable () -> Unit = {
        UiHeader(stringResource(R.string.tab_home))
    }
    val hero: @Composable () -> Unit = {
        UiHeroCard(
            title = stringResource(R.string.use_binds),
            subtitle = stringResource(if (active) R.string.state_on else R.string.state_off),
            checked = active,
            enabled = !busy,
            onCheckedChange = onToggle
        )
    }
    val storage: @Composable () -> Unit = {
        StorageCard(volumes, playToken, onRefreshStorage)
    }
    val binds: @Composable () -> Unit = {
        UiCard(icon = Icons.Filled.Link, title = stringResource(R.string.binds)) {
            BindList(entries, onDelete, onOpen)
            Spacer(Modifier.height(12.dp))
            AddBindButton(hasExternal, onAdd)
        }
    }
    PullRefresh(onRefresh = onRefreshStorage) {
    if (landscape) {
        Row(Modifier.fillMaxSize().padding(horizontal = UiScreenPadding)) {
            Column(
                Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 8.dp)
            ) {
                header()
                hero()
                Spacer(Modifier.height(16.dp))
                storage()
                Spacer(Modifier.height(UiNavClearance))
            }
            Column(
                Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 8.dp, top = 16.dp)
            ) {
                binds()
                Spacer(Modifier.height(UiNavClearance))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            header()
            Column(Modifier.padding(horizontal = UiScreenPadding)) {
                hero()
                Spacer(Modifier.height(16.dp))
                storage()
                Spacer(Modifier.height(16.dp))
                binds()
            }
            Spacer(Modifier.height(UiNavClearance))
        }
    }
    }
}

@Composable
private fun BindList(
    entries: List<MountEntry>,
    onDelete: (Int) -> Unit,
    onOpen: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    if (entries.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(uiInnerColor()).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Folder, null, tint = cs.primary, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.nothing_bound), color = cs.onSurface, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.nothing_bound_hint), color = cs.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    } else {
        entries.forEachIndexed { i, e ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            BindCard(e, onDelete = { onDelete(i) }, onOpen = { onOpen(e.dest) })
        }
    }
}

/** Botón "Añadir vínculo": apagado (pero tocable, para avisar por qué) si no hay SD/OTG. */
@Composable
private fun AddBindButton(hasExternal: Boolean, onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onAdd,
        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
        shapes = ButtonDefaults.shapes(),
        colors = if (hasExternal) ButtonDefaults.buttonColors()
        else ButtonDefaults.buttonColors(
            containerColor = uiInnerColor(),
            contentColor = cs.onSurfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.add_bind), style = MaterialTheme.typography.titleMediumEmphasized)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageCard(volumes: List<StorageVolume>, playToken: Int, onRefresh: () -> Unit) {
    UiCard(
        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onRefresh() }) },
        icon = Icons.Filled.Storage,
        title = stringResource(R.string.storage)
    ) {
        val cs = MaterialTheme.colorScheme
        if (volumes.isEmpty()) {
            Text(stringResource(R.string.long_press_refresh), color = cs.onSurfaceVariant)
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                maxItemsInEachRow = 4
            ) {
                volumes.forEach { vol ->
                    StorageCell(
                        vol,
                        Modifier.width(120.dp),
                        secondary = vol.kind == VolumeKind.EXTERNAL,
                        playToken = playToken
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageCell(
    vol: StorageVolume,
    modifier: Modifier = Modifier,
    secondary: Boolean,
    empty: Boolean = false,
    playToken: Int = 0
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        StorageRing(if (empty) 0 else vol.usePercent, Modifier.size(88.dp), secondary = secondary, playToken = playToken)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (vol.kind == VolumeKind.INTERNAL) Icons.Outlined.Smartphone else Icons.Outlined.SdCard,
                null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            val volLabel = when {
                empty -> stringResource(R.string.sd_otg)
                vol.kind == VolumeKind.INTERNAL -> stringResource(R.string.internal)
                else -> {
                    val id = vol.path.trimEnd('/').substringAfterLast('/')
                    if (id.isBlank() || id == "media_rw") stringResource(R.string.sd_otg)
                    else stringResource(R.string.sd_named, id)
                }
            }
            Text(volLabel, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (empty) {
            Text(stringResource(R.string.no_drive), color = cs.onSurfaceVariant, fontSize = 13.sp)
        } else {
            Text(stringResource(R.string.free_fmt, vol.availHuman), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("${vol.usedHuman} / ${vol.totalHuman}", color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StorageRing(percent: Int, modifier: Modifier = Modifier, secondary: Boolean = false, playToken: Int = 0) {
    val cs = MaterialTheme.colorScheme
    // Llenado lento y suave (antes: slowEffectsSpec del motionScheme).
    val effects = tween<Float>(durationMillis = 2200, easing = EaseInOutCubic)
    val anim = remember { Animatable(0f) }
    var lastToken by remember { mutableIntStateOf(playToken) }
    LaunchedEffect(playToken, percent) {
        val t = percent.coerceIn(0, 100) / 100f
        if (playToken != lastToken) {
            lastToken = playToken
            anim.snapTo(0f)
        }
        anim.animateTo(t, effects)
    }
    val track = if (secondary) cs.secondaryContainer else cs.primaryContainer
    val arc = if (secondary) cs.secondary else cs.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        // Anillo plano (el ondulado tenía una animación infinita). La única animación es el
        // llenado de 0 -> valor al refrescar (mantener presionado) o al cambiar el porcentaje.
        CircularProgressIndicator(
            progress = { anim.value },
            modifier = Modifier.fillMaxSize(),
            color = arc,
            trackColor = track,
            strokeWidth = 8.dp,
            strokeCap = StrokeCap.Round
        )
        Text(
            "${(anim.value * 100).toInt()}%",
            color = cs.onSurface,
            style = MaterialTheme.typography.titleMediumEmphasized
        )
    }
}

/** Junta el log completo (root, sin recorte) en un archivo del caché propio de la app y lo
 *  manda al selector de apps para compartir — mismo mecanismo de FileProvider que
 *  openFileExternally, pero para ACTION_SEND en vez de ACTION_VIEW. */
private fun shareLogFile(context: Context, content: String) {
    runCatching {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "sdbind_log_${System.currentTimeMillis()}.txt")
        file.writeText(content.ifBlank { "(log vacío)" })
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.open_file_failed), Toast.LENGTH_SHORT).show()
    }
}

/** Abre un archivo con la app externa que corresponda según su tipo (imagen, video, apk...). */
private fun openFileExternally(context: Context, absolutePath: String) {
    val file = File(absolutePath)
    val ext = file.extension.lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
    if (uri == null) {
        Toast.makeText(context, context.getString(R.string.open_file_failed), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, file.name)) }
        .onFailure {
            Toast.makeText(context, context.getString(R.string.open_file_failed), Toast.LENGTH_SHORT).show()
        }
}


@Composable
private fun BindCard(entry: MountEntry, onDelete: () -> Unit, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val isMounted = entry.status == "MOUNTED"
    val name = dirBaseName(entry.dest).ifBlank { dirBaseName(entry.source).ifBlank { stringResource(R.string.bind_fallback) } }
    val iconShape = MaterialShapes.Cookie9Sided.toShape()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(uiInnerColor())
            .padding(14.dp)
            .animateContentSize(sizeSpec()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(iconShape)
                .background(if (isMounted) cs.primaryContainer else cs.primaryContainer.copy(alpha = 0.35f))
                .then(
                    if (isMounted) {
                        Modifier.clickable(
                            onClickLabel = stringResource(R.string.open_in_explorer)
                        ) { onOpen() }
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Folder,
                null,
                tint = if (isMounted) cs.onPrimaryContainer else cs.onPrimaryContainer.copy(alpha = 0.35f),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = cs.onSurface, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.source.ifBlank { stringResource(R.string.no_source) }, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("→ ${entry.dest.ifBlank { stringResource(R.string.no_dest) }}", color = cs.primary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            StatusChip(entry.status)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, null, tint = cs.error, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val cs = MaterialTheme.colorScheme
    // Verde fijo (no ligado al Material You dinámico, que según el wallpaper puede no salir
    // verde) para que "presente"/"montado" se lean como semáforo en cualquier tema.
    val trafficGreenBg = Color(0xFF2E7D32)
    val trafficGreenFg = Color.White
    val (label, bg, fg) = when (status) {
        "MOUNTED" -> Triple(stringResource(R.string.status_mounted), trafficGreenBg, trafficGreenFg)
        // El origen existe pero no está montado -> mismo verde que "montado": lo importante
        // para el usuario es que la unidad está disponible, no si el bind sigue activo.
        "UNMOUNTED" -> Triple(stringResource(R.string.status_present), trafficGreenBg, trafficGreenFg)
        "SOURCE_MISSING" -> Triple(stringResource(R.string.status_missing), cs.errorContainer, cs.onErrorContainer)
        else -> Triple(stringResource(R.string.status_unknown), cs.surfaceContainerHighest, cs.onSurfaceVariant)
    }
    Text(
        label,
        color = fg,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** Logo estático de la app: el mismo icono del launcher (PNG en drawable-nodpi; painterResource no admite el XML adaptativo). */
@Composable
private fun AppLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = modifier)
}

@Composable
private fun AboutPane(
    updates: UpdateController,
    onInstallUpdate: () -> Unit,
    onRefresh: suspend () -> Unit,
    onFlashModule: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val ver = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "2.5.1"
    }
    val iconDp = (minOf(cfg.screenWidthDp, cfg.screenHeightDp) * 0.28f).coerceIn(88f, 132f).dp
    PullRefreshAwait(onRefresh = onRefresh) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        UiHeader(stringResource(R.string.tab_settings))
        Column(Modifier.padding(horizontal = UiScreenPadding)) {
            UiCard(icon = Icons.Filled.Info, title = stringResource(R.string.about)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    AppLogo(Modifier.size(iconDp))
                    Spacer(Modifier.height(16.dp))
                    Text("SD Bind", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.version_fmt, ver ?: "2.5.4"), color = cs.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            UpdatesCard(updates, onInstallUpdate = onInstallUpdate, onFlashModule = onFlashModule)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AboutMiniCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Folder,
                    title = stringResource(R.string.card_binds),
                    body = stringResource(R.string.card_binds_desc)
                )
                AboutMiniCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Build,
                    title = stringResource(R.string.card_module),
                    body = stringResource(R.string.card_module_desc)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AboutMiniCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.FolderOpen,
                    title = stringResource(R.string.card_explorer),
                    body = stringResource(R.string.card_explorer_desc)
                )
                AboutMiniCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.ColorLens,
                    title = stringResource(R.string.card_material),
                    body = stringResource(R.string.card_material_desc)
                )
            }
        }
        Spacer(Modifier.height(UiNavClearance))
    }
    }
}

@Composable
private fun AboutMiniCard(modifier: Modifier, icon: ImageVector, title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(36.dp))
            .background(cs.surfaceContainerHigh)
            .padding(20.dp)
            .height(160.dp)
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(cs.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(body, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private enum class LogLevel(val letter: String) { ERROR("E"), WARN("W"), SUCCESS("S"), INFO("I"), DEBUG("D") }
private enum class LogTag { MOUNT, UNMOUNT, WATCH, SYSTEM }
private data class LogLine(val id: Int, val time: String?, val level: LogLevel, val tag: LogTag, val msg: String)

private val LOG_TS = Regex("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2}) (.*)$")

private fun logLevelOf(msg: String): LogLevel {
    val m = msg.lowercase()
    return when {
        listOf("fallo", "error", "no se pudo", "denied", "failed", "bad ").any { it in m } -> LogLevel.ERROR
        m.startsWith("ok") || "reapareció" in m || "salvó" in m -> LogLevel.SUCCESS
        listOf("omitido", "ausente", "ram baja", "auto-desmontado", "caído", "recién insertado", "cuenta de gracia").any { it in m } -> LogLevel.WARN
        listOf("desmontado", "ya montado", "eliminado", "borrado", "montado").any { it in m } -> LogLevel.INFO
        else -> LogLevel.DEBUG
    }
}

private fun logTagOf(msg: String): LogTag {
    val m = msg.lowercase()
    return when {
        "desmont" in m -> LogTag.UNMOUNT
        m.startsWith("ok") || "ya montado" in m || m.startsWith("fallo") -> LogTag.MOUNT
        listOf("origen", "bind", "subcarpeta", "volumen", "ram", "oom").any { it in m } -> LogTag.WATCH
        else -> LogTag.SYSTEM
    }
}

private fun parseLog(raw: String): List<LogLine> =
    raw.lines().filter { it.isNotBlank() }.mapIndexed { i, line ->
        val m = LOG_TS.matchEntire(line)
        val msg = m?.groupValues?.get(3) ?: line.trim()
        LogLine(i, m?.groupValues?.get(2), logLevelOf(msg), logTagOf(msg), msg)
    }

/** Color semántico de cada nivel. Verde fijo para "éxito" (igual que el semáforo de StatusChip). */
@Composable
private fun logColor(level: LogLevel): Color {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    return when (level) {
        LogLevel.ERROR -> cs.error
        LogLevel.WARN -> if (dark) Color(0xFFFFB74D) else Color(0xFFB36B00)
        LogLevel.SUCCESS -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        LogLevel.INFO -> cs.primary
        LogLevel.DEBUG -> cs.onSurfaceVariant
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val cs = MaterialTheme.colorScheme
    val color = logColor(line.level)
    val mono = FontFamily.Monospace
    val tagLabel = stringResource(
        when (line.tag) {
            LogTag.MOUNT -> R.string.tag_mount
            LogTag.UNMOUNT -> R.string.tag_unmount
            LogTag.WATCH -> R.string.tag_watch
            LogTag.SYSTEM -> R.string.tag_system
        }
    )
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(color, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
    ) {
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 12.dp, top = 7.dp, bottom = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(20.dp).clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(line.level.letter, color = color, fontFamily = mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (line.time != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(line.time, color = cs.onSurfaceVariant, fontFamily = mono, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    tagLabel,
                    color = color,
                    fontFamily = mono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(color.copy(alpha = 0.14f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                line.msg,
                color = if (line.level == LogLevel.DEBUG || line.level == LogLevel.INFO) cs.onSurface else color,
                fontFamily = mono,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun LogPane(log: String, onClear: () -> Unit, onRefresh: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all = remember(log) { parseLog(log) }
    var query by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    val visible = remember(all, query, levelFilter) {
        all.filter { l ->
            (levelFilter == null || l.level == levelFilter) &&
                (query.isBlank() || l.msg.contains(query.trim(), ignoreCase = true))
        }
    }
    val listState = rememberLazyListState()
    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty() && !primed) {
            listState.scrollToItem(visible.lastIndex)
            primed = true
        }
    }
    val firstShown by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val lastShown by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }

    PullRefresh(onRefresh = onRefresh) {
    Column(Modifier.fillMaxSize()) {
        UiHeader(stringResource(R.string.log)) {
            IconButton(onClick = {
                scope.launch {
                    val full = RootOps.fullLog()
                    shareLogFile(context, full)
                }
            }) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_log))
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.clear_log))
            }
        }
        Text(
            if (visible.isEmpty()) stringResource(R.string.log_lines_fmt, 0, 0, 0)
            else stringResource(R.string.log_lines_fmt, firstShown + 1, (lastShown + 1).coerceAtMost(visible.size), visible.size),
            color = cs.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = UiScreenPadding + 4.dp, bottom = 12.dp)
        )
        // Buscador en píldora + botón de filtro por nivel
        Row(
            Modifier
                .padding(horizontal = UiScreenPadding)
                .fillMaxWidth()
                .clip(CircleShape)
                .background(cs.surfaceContainerHigh)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(TextStyle(color = cs.onSurface, fontSize = 15.sp)),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(stringResource(R.string.log_search_hint), color = cs.onSurfaceVariant, fontSize = 15.sp)
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, null, tint = cs.onSurfaceVariant) }
            }
            IconButton(onClick = { showFilters = !showFilters }) {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = null,
                    tint = if (levelFilter != null) cs.primary else cs.onSurfaceVariant
                )
            }
        }
        AnimatedVisibility(showFilters) {
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = UiScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val chips = listOf(
                    null to R.string.log_filter_all,
                    LogLevel.ERROR to R.string.log_filter_error,
                    LogLevel.WARN to R.string.log_filter_warn,
                    LogLevel.SUCCESS to R.string.log_filter_ok,
                    LogLevel.INFO to R.string.log_filter_info
                )
                chips.forEach { (lvl, label) ->
                    FilterChip(
                        selected = levelFilter == lvl,
                        onClick = { levelFilter = lvl },
                        label = { Text(stringResource(label)) }
                    )
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = UiScreenPadding, end = UiScreenPadding, top = 12.dp, bottom = UiNavClearance)
                .clip(RoundedCornerShape(28.dp))
                .background(cs.surfaceContainerLowest)
        ) {
            if (visible.isEmpty()) {
                Text(
                    stringResource(if (all.isEmpty()) R.string.log_empty else R.string.log_no_results),
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(visible, key = { it.id }) { LogRow(it) }
                }
            }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = cs.secondaryContainer,
                    contentColor = cs.onSecondaryContainer
                ) { Icon(Icons.Filled.VerticalAlignTop, contentDescription = stringResource(R.string.log_scroll_top)) }
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem((visible.size - 1).coerceAtLeast(0)) } },
                    containerColor = cs.secondaryContainer,
                    contentColor = cs.onSecondaryContainer
                ) { Icon(Icons.Filled.VerticalAlignBottom, contentDescription = stringResource(R.string.log_scroll_bottom)) }
            }
        }
    }
    }
}

@Composable
private fun FolderPickerScreen(
    title: String,
    hint: String,
    startPath: String,
    confirmLabel: String,
    rejectRoot: Boolean = false,
    onBack: () -> Unit,
    onPicked: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var current by remember { mutableStateOf(startPath) }
    var children by remember { mutableStateOf(listOf<String>()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Mismo criterio que el botón "Subir un nivel": si hay a dónde subir, el gesto/botón de
    // volver atrás del sistema sube un nivel en vez de sacarte de la pantalla entera; recién
    // al llegar arriba de todo, un back más hace lo que hacía onBack (volver al paso anterior
    // del flujo). Antes esto no estaba interceptado y el gesto salía directo de la app.
    BackHandler {
        val trimmed = current.trimEnd('/')
        if (trimmed.isEmpty()) onBack() else current = trimmed.substringBeforeLast("/").ifBlank { "/" }
    }

    fun load(path: String) {
        loading = true
        scope.launch {
            children = RootOps.listSubdirectories(path)
            loading = false
        }
    }
    LaunchedEffect(current) { load(current) }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        TopAppBar(
            title = {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(hint, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            windowInsets = WindowInsets(0, 0, 0, 0)
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShortcutChip(stringResource(R.string.internal), Icons.Outlined.Smartphone) { current = "/storage/emulated/0" }
            Spacer(Modifier.width(8.dp))
            ShortcutChip(stringResource(R.string.sd_otg), Icons.Outlined.SdCard) { current = "/mnt/media_rw" }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            current,
            color = cs.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        AnimatedVisibility(current.trimEnd('/') != "") {
            TextButton(onClick = {
                current = current.trimEnd('/').substringBeforeLast("/").ifBlank { "/" }
            }) {
                Icon(Icons.Filled.ArrowUpward, null, modifier = Modifier.size(16.dp), tint = cs.primary)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.up_one_level), color = cs.primary)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(targetState = loading to children, label = "dirs") { (isLoading, dirs) ->
                when {
                    isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                    dirs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_subfolders), color = cs.onSurfaceVariant)
                    }
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(dirs, key = { it }) { child ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(MaterialTheme.shapes.large)
                                    .background(cs.surfaceContainer)
                                    .clickable { current = child }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(44.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(cs.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Folder, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    dirBaseName(child),
                                    color = cs.onSurface,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant)
                            }
                        }
                        item { Spacer(Modifier.height(12.dp)) }
                    }
                }
            }
        }

        val blocked = rejectRoot && isUnsafeDest(current)
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            AnimatedVisibility(blocked) {
                Text(
                    stringResource(R.string.pick_inner_folder),
                    color = cs.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Button(
                onClick = { onPicked(current) },
                enabled = !blocked,
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                shapes = ButtonDefaults.shapes()
            ) { Text(confirmLabel, style = MaterialTheme.typography.titleMediumEmphasized) }
        }
    }
}

@Composable
private fun ShortcutChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = cs.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = cs.onSurface, fontSize = 13.sp)
    }
}

/**
 * Explorador de archivos simple, integrado en la app: reemplaza el intento anterior de
 * abrir una app externa (que en muchos equipos no encontraba ninguna). Arranca directo en
 * la carpeta montada, deja navegar libremente, abrir archivos con la app que corresponda,
 * y borrar tanto archivos como carpetas.
 */
@Composable
private fun FileBrowserScreen(startPath: String, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var current by remember { mutableStateOf(normalizeDir(startPath)) }
    var dirEntries by remember { mutableStateOf(listOf<FileEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<FileEntry?>(null) }
    val scope = rememberCoroutineScope()

    // Mismo criterio que en FolderPickerScreen: el back del sistema sube un nivel mientras
    // se pueda, y recién al tope de todo cae a onBack (cerrar el explorador).
    BackHandler {
        val trimmed = current.trimEnd('/')
        if (trimmed.isEmpty()) onBack() else current = normalizeDir(trimmed.substringBeforeLast("/").ifBlank { "/" })
    }

    fun load(path: String) {
        loading = true
        scope.launch {
            dirEntries = RootOps.listEntries(path)
            loading = false
        }
    }
    LaunchedEffect(current) { load(current) }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.explore), style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(
                        dirBaseName(current).ifBlank { current },
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            windowInsets = WindowInsets(0, 0, 0, 0)
        )

        Spacer(Modifier.height(4.dp))
        Text(
            current,
            color = cs.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        AnimatedVisibility(current.trimEnd('/') != "") {
            TextButton(onClick = {
                current = normalizeDir(current.trimEnd('/').substringBeforeLast("/").ifBlank { "/" })
            }) {
                Icon(Icons.Filled.ArrowUpward, null, modifier = Modifier.size(16.dp), tint = cs.primary)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.up_one_level), color = cs.primary)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(targetState = loading to dirEntries, label = "files") { (isLoading, files) ->
                when {
                    isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                    files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.empty_folder), color = cs.onSurfaceVariant)
                    }
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(files, key = { it.path }) { entry ->
                            FileRow(
                                entry = entry,
                                onClick = {
                                    if (entry.isDir) current = normalizeDir(entry.path)
                                    else openFileExternally(context, entry.path)
                                },
                                onDelete = { pendingDelete = entry }
                            )
                        }
                        item { Spacer(Modifier.height(96.dp)) }
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(stringResource(if (entry.isDir) R.string.delete_folder_title else R.string.delete_file_title))
            },
            text = {
                Text(
                    stringResource(
                        if (entry.isDir) R.string.delete_folder_body else R.string.delete_file_body,
                        entry.name
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val ok = RootOps.deleteEntry(entry.path, entry.isDir)
                        if (ok) load(current)
                        else Toast.makeText(
                            context,
                            context.getString(R.string.delete_item_failed, entry.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text(stringResource(R.string.delete), color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(MaterialTheme.shapes.large)
            .background(cs.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(cs.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (entry.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                null,
                tint = cs.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!entry.isDir) {
                Text(humanBytes(entry.sizeBytes), color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, null, tint = cs.error, modifier = Modifier.size(18.dp))
        }
        if (entry.isDir) {
            Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant)
        }
    }
}
