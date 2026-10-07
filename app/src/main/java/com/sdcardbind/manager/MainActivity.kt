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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            AnimatedVisibility(
                visible = showChrome,
                enter = slideInVertically(slideSpec) { it } + fadeIn(fadeInSpec),
                exit = slideOutVertically(slideSpec) { it } + fadeOut(fadeOutSpec)
            ) {
                AppNavBar(pagerState = pagerState, onTab = goTab)
            }
        },
        floatingActionButton = {
            // Sin ninguna unidad externa (SD/OTG) montada no hay de dónde elegir un origen:
            // el FAB se ve apagado y, en vez de abrir el selector, avisa por qué.
            AnimatedVisibility(
                visible = showChrome && currentTab == Tab.Home,
                enter = scaleIn(fabSpec) + fadeIn(fadeInSpec),
                exit = scaleOut(fabSpec) + fadeOut(fadeOutSpec)
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (hasExternal) {
                            flow = Flow.PickSource; pendingSource = ""
                        } else {
                            snack = context.getString(R.string.no_external_hint)
                        }
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_bind)) },
                    containerColor = if (hasExternal) cs.primaryContainer else cs.surfaceVariant,
                    contentColor = if (hasExternal) cs.onPrimaryContainer else cs.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            AnimatedVisibility(
                visible = showChrome && currentTab == Tab.Log,
                enter = scaleIn(fabSpec) + fadeIn(fadeInSpec),
                exit = scaleOut(fabSpec) + fadeOut(fadeOutSpec)
            ) {
                FloatingActionButton(
                    onClick = { clearLogNow() },
                    containerColor = cs.errorContainer,
                    contentColor = cs.onErrorContainer
                ) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.clear_log)) }
            }
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
                        1 -> LogPane(log)
                        2 -> AboutPane(
                            busy = busy,
                            onCheck = {
                                scope.launch {
                                    busy = true
                                    snack = context.getString(R.string.checking_updates)
                                    val ver = runCatching {
                                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                                    }.getOrNull() ?: "2.5.3"
                                    when (val out = Updater.checkAndDownload(context, ver ?: "2.5.3")) {
                                        is UpdateOutcome.Info -> snack = out.message
                                        is UpdateOutcome.Ready -> {
                                            snack = context.getString(R.string.update_ready, out.tag)
                                            runCatching { Updater.openForFlash(context, out.zip) }
                                                .onFailure {
                                                    snack = context.getString(R.string.update_saved_downloads)
                                                }
                                        }
                                    }
                                    busy = false
                                }
                            }
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
                            onApply = {
                                scope.launch {
                                    busy = true
                                    popupQuietUntil = System.currentTimeMillis() + 8000
                                    val ok = RootOps.saveAndApply(entries)
                                    snack = if (ok) context.getString(R.string.binds_applied)
                                    else context.getString(R.string.mount_failed)
                                    refresh()
                                    busy = false
                                }
                            },
                            onUnmount = { unmountAllNow() }
                        )
                    }
                }
            }
        }
        }
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

/**
 * Barra de navegación Material 3 Expressive (reemplaza el "pill" flotante hecho a mano y su
 * difuminado): menos capas de dibujo, accesibilidad y animaciones del sistema.
 */
@Composable
private fun AppNavBar(pagerState: PagerState, onTab: (Tab) -> Unit) {
    val labels = listOf(
        stringResource(R.string.tab_home),
        stringResource(R.string.tab_log),
        stringResource(R.string.tab_about)
    )
    val icons = listOf(Icons.Filled.Home, Icons.Filled.Notes, Icons.Filled.Info)
    ShortNavigationBar {
        Tab.entries.forEachIndexed { i, tab ->
            ShortNavigationBarItem(
                selected = pagerState.currentPage == i,
                onClick = { onTab(tab) },
                icon = { Icon(icons[i], contentDescription = null) },
                label = { Text(labels[i]) }
            )
        }
    }
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
    onApply: () -> Unit,
    onUnmount: () -> Unit
) {
    if (landscape) {
        Row(Modifier.fillMaxSize().padding(12.dp)) {
            Column(
                Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 8.dp)
            ) {
                HomeHeader()
                Spacer(Modifier.height(16.dp))
                StorageHero(volumes, playToken, onRefreshStorage)
                Spacer(Modifier.height(16.dp))
                ActionButtons(busy, entries.isNotEmpty(), volumes.any { it.kind == VolumeKind.EXTERNAL }, onApply, onUnmount)
                Spacer(Modifier.height(24.dp))
            }
            Column(
                Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 8.dp)
            ) {
                BindList(entries, onDelete, onOpen)
                Spacer(Modifier.height(80.dp))
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            HomeHeader()
            Spacer(Modifier.height(20.dp))
            StorageHero(volumes, playToken, onRefreshStorage)
            Spacer(Modifier.height(28.dp))
            BindList(entries, onDelete, onOpen)
            Spacer(Modifier.height(16.dp))
            ActionButtons(busy, entries.isNotEmpty(), volumes.any { it.kind == VolumeKind.EXTERNAL }, onApply, onUnmount)
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun HomeHeader() {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("SD Bind", color = cs.onBackground, style = MaterialTheme.typography.displaySmallEmphasized, modifier = Modifier.weight(1f))
        Box(
            Modifier.clip(CircleShape).background(cs.tertiaryContainer).padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(stringResource(R.string.root_ok), color = cs.onTertiaryContainer, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
    Text(stringResource(R.string.binds), color = cs.onBackground, style = MaterialTheme.typography.titleLargeEmphasized)
    Spacer(Modifier.height(12.dp))
    if (entries.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(cs.surfaceContainer).padding(28.dp),
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
            BindCard(e, onDelete = { onDelete(i) }, onOpen = { onOpen(e.dest) })
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ActionButtons(busy: Boolean, hasEntries: Boolean, hasExternal: Boolean, onApply: () -> Unit, onUnmount: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onApply,
        enabled = !busy && hasEntries && hasExternal,
        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
        shapes = ButtonDefaults.shapes()
    ) { Text(stringResource(R.string.save_mount), style = MaterialTheme.typography.titleMediumEmphasized) }
    Spacer(Modifier.height(8.dp))
    // "Desmontar todo" queda siempre disponible a propósito (no depende de hasExternal): es
    // la vía manual de escape si algo quedó mal desmontado justo después de retirar la
    // unidad, aunque el auto-desmontado ya debería encargarse solo.
    TextButton(onClick = onUnmount, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.unmount_all), color = cs.error)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageHero(volumes: List<StorageVolume>, playToken: Int, onRefresh: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(cs.surfaceContainer)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onRefresh() }) }
            .padding(20.dp)
            .animateContentSize(sizeSpec())
    ) {
        Text(stringResource(R.string.storage), color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))
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
            Text(stringResource(R.string.free_fmt, vol.availHuman), color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("${vol.usedHuman} / ${vol.totalHuman}", color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StorageRing(percent: Int, modifier: Modifier = Modifier, secondary: Boolean = false, playToken: Int = 0) {
    val cs = MaterialTheme.colorScheme
    val effects = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
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
    val strokePx = with(LocalDensity.current) { 8.dp.toPx() }
    val stroke = remember(strokePx) { Stroke(width = strokePx, cap = StrokeCap.Round) }
    Box(modifier, contentAlignment = Alignment.Center) {
        // Anillo ondulado Expressive: la "ola" marca visualmente el progreso.
        CircularWavyProgressIndicator(
            progress = { anim.value },
            modifier = Modifier.fillMaxSize(),
            color = arc,
            trackColor = track,
            stroke = stroke,
            trackStroke = stroke
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
            .clip(MaterialTheme.shapes.extraLarge)
            .background(cs.surfaceContainer)
            .padding(14.dp)
            .animateContentSize(sizeSpec()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(52.dp)
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

@Composable
private fun AppMark(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val inf = rememberInfiniteTransition(label = "appMark")
    val rot by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "rot"
    )
    val rot2 by inf.animateFloat(
        360f, 0f,
        infiniteRepeatable(tween(11000, easing = LinearEasing), RepeatMode.Restart),
        label = "rot2"
    )
    val sweep by inf.animateFloat(
        0.42f, 0.86f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sweep"
    )
    val pulse by inf.animateFloat(
        0.94f, 1.06f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.085f
            val pad = stroke * 0.9f
            drawArc(
                color = cs.primary.copy(alpha = 0.18f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = Size(size.width - pad * 2, size.height - pad * 2),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = cs.primary,
                startAngle = -90f + rot,
                sweepAngle = 360f * sweep,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = Size(size.width - pad * 2, size.height - pad * 2),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            val inner = pad * 2.35f
            drawArc(
                color = cs.tertiary,
                startAngle = 90f + rot2,
                sweepAngle = 220f * sweep,
                useCenter = false,
                topLeft = Offset(inner, inner),
                size = Size(size.width - inner * 2, size.height - inner * 2),
                style = Stroke(stroke * 0.72f, cap = StrokeCap.Round)
            )
        }
        Box(
            Modifier
                .fillMaxSize(0.42f)
                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                .clip(RoundedCornerShape(32))
                .background(cs.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Link,
                contentDescription = null,
                tint = cs.onPrimary,
                modifier = Modifier.fillMaxSize(0.55f)
            )
        }
    }
}

@Composable
private fun AboutPane(busy: Boolean, onCheck: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val ver = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "2.5.1"
    }
    val iconDp = (minOf(cfg.screenWidthDp, cfg.screenHeightDp) * 0.32f).coerceIn(104f, 176f).dp
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.about), color = cs.onBackground, style = MaterialTheme.typography.displaySmallEmphasized)
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(36.dp))
                .background(cs.surfaceContainer)
                .padding(vertical = 28.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppMark(Modifier.size(iconDp))
            Spacer(Modifier.height(16.dp))
            Text("SD Bind", color = cs.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.version_fmt, ver ?: "2.5.4"), color = cs.onSurfaceVariant, fontSize = 14.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.tools), color = cs.onBackground, style = MaterialTheme.typography.titleLargeEmphasized)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(cs.surfaceContainerHigh)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.SystemUpdate, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.updates), color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    stringResource(R.string.updates_desc),
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onCheck,
                enabled = !busy,
                shapes = ButtonDefaults.shapes()
            ) {
                if (busy) {
                    LoadingIndicator(Modifier.size(24.dp), color = cs.onPrimary)
                } else {
                    Text(stringResource(R.string.search))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun AboutMiniCard(modifier: Modifier, icon: ImageVector, title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(cs.surfaceContainer)
            .padding(16.dp)
            .height(148.dp)
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(cs.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(body, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LogPane(log: String) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            // Abajo se deja más aire que en los otros lados: el FAB de borrar queda
            // flotando sobre el borde inferior de la tarjeta del registro.
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 96.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.log),
                color = cs.onBackground,
                style = MaterialTheme.typography.displaySmallEmphasized,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                scope.launch {
                    val full = RootOps.fullLog()
                    shareLogFile(context, full)
                }
            }) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_log))
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(cs.surfaceContainerLowest)
                .padding(16.dp)
        ) {
            // Al vaciar el log (botón de borrar), el texto anterior se desvanece deslizándose
            // hacia arriba en vez de desaparecer de golpe; el mensaje de "log vacío" entra con
            // un fundido suave por detrás.
            AnimatedContent(
                targetState = log,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 120)))
                        .togetherWith(
                            fadeOut(tween(220)) + slideOutVertically(tween(220)) { h -> -h / 3 }
                        )
                },
                label = "logContent"
            ) { text ->
                Text(
                    text.ifBlank { stringResource(R.string.log_empty) },
                    color = cs.tertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
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
