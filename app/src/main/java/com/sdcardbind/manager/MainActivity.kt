package com.sdcardbind.manager

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdcardbind.manager.ui.AppTheme
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        init {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(20)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        setContent {
            AppTheme { BindApp() }
        }
    }
}

private enum class Tab { Home, Log }
private enum class Flow { Home, PickSource, PickDest }

private val spatial = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow
)
private val sizeSpring = spring<IntSize>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow
)
private val floatSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow
)

@Composable
fun BindApp() {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var entries by remember { mutableStateOf(listOf<MountEntry>()) }
    var volumes by remember { mutableStateOf(listOf<StorageVolume>()) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var flow by remember { mutableStateOf(Flow.Home) }
    var pendingSource by remember { mutableStateOf("") }
    var snack by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            entries = RootOps.loadMounts()
            volumes = RootOps.storageVolumes()
            log = RootOps.tailLog()
        }
    }

    LaunchedEffect(Unit) {
        rootOk = RootOps.isRootAvailable()
        if (rootOk == true) refresh()
    }
    LaunchedEffect(snack) {
        if (snack != null) {
            kotlinx.coroutines.delay(2800)
            snack = null
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screen = when {
        rootOk == false -> "noroot"
        flow == Flow.PickSource -> "src"
        flow == Flow.PickDest -> "dst"
        tab == Tab.Log -> "log"
        else -> "home"
    }

    Scaffold(
        containerColor = cs.background,
        snackbarHost = {
            snack?.let {
                Snackbar(
                    Modifier.padding(16.dp),
                    containerColor = cs.inverseSurface,
                    contentColor = cs.inverseOnSurface
                ) { Text(it) }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = screen == "home" && rootOk == true,
                enter = scaleIn(floatSpring) + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                FloatingActionButton(
                    onClick = { flow = Flow.PickSource; pendingSource = "" },
                    containerColor = cs.primaryContainer,
                    contentColor = cs.onPrimaryContainer,
                    shape = CircleShape
                ) { Icon(Icons.Filled.Add, contentDescription = "Añadir vínculo") }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !landscape && (screen == "home" || screen == "log") && rootOk == true,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                BottomNav(tab = tab, onTab = { tab = it })
            }
        }
    ) { pad ->
        Row(Modifier.fillMaxSize().padding(pad)) {
            if (landscape && (screen == "home" || screen == "log") && rootOk == true) {
                SideRail(tab = tab, onTab = { tab = it })
            }
            AnimatedContent(
                targetState = screen,
                modifier = Modifier.weight(1f),
            transitionSpec = {
                val forward = targetState == "src" || (initialState == "src" && targetState == "dst")
                if (forward) {
                    (slideInHorizontally(spatial) { it } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(spatial) { -it / 4 } + fadeOut(tween(180)))
                } else {
                    (slideInHorizontally(spatial) { -it / 4 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(spatial) { it } + fadeOut(tween(180)))
                }.using(SizeTransform(clip = false))
            },
            label = "screen"
        ) { s ->
            when (s) {
                "noroot" -> NoRoot()
                "src" -> FolderPickerScreen(
                    title = "Origen",
                    hint = "Carpeta de la tarjeta o unidad que querés montar",
                    startPath = "/mnt/media_rw",
                    confirmLabel = "Siguiente",
                    onBack = { flow = Flow.Home },
                    onPicked = { path ->
                        pendingSource = normalizeDir(path)
                        flow = Flow.PickDest
                    }
                )
                "dst" -> FolderPickerScreen(
                    title = "Destino",
                    hint = "Carpeta del almacenamiento interno donde se va a ver",
                    startPath = "/storage/emulated/0",
                    confirmLabel = "Vincular y montar",
                    rejectRoot = true,
                    onBack = { flow = Flow.PickSource },
                    onPicked = { path ->
                        val dest = normalizeDir(path)
                        if (isUnsafeDest(dest)) {
                            snack = "Elegí una subcarpeta, no la raíz"
                        } else {
                            scope.launch {
                                busy = true
                                val next = entries + MountEntry(pendingSource, dest, true)
                                val ok = RootOps.saveAndApply(next)
                                snack = if (ok) "Montado en $dest" else "Error al montar, mirá el registro"
                                refresh()
                                busy = false
                                flow = Flow.Home
                            }
                        }
                    }
                )
                "log" -> LogPane(log)
                else -> HomePane(
                    volumes = volumes,
                    entries = entries,
                    busy = busy,
                    landscape = landscape,
                    onToggle = { i, on ->
                        entries = entries.toMutableList().also { it[i] = it[i].copy(enabled = on) }
                    },
                    onDelete = { i -> entries = entries.toMutableList().also { it.removeAt(i) } },
                    onApply = {
                        scope.launch {
                            busy = true
                            val ok = RootOps.saveAndApply(entries)
                            snack = if (ok) "Vínculos aplicados" else "Error al montar"
                            refresh()
                            busy = false
                        }
                    },
                    onUnmount = {
                        scope.launch {
                            busy = true
                            RootOps.unmountAll()
                            refresh()
                            busy = false
                        }
                    }
                )
            }
        }
        }
    }
}

@Composable
private fun SideRail(tab: Tab, onTab: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    NavigationRail(
        containerColor = cs.surface,
        modifier = Modifier.fillMaxHeight()
    ) {
        Spacer(Modifier.height(12.dp))
        NavigationRailItem(
            selected = tab == Tab.Home,
            onClick = { onTab(Tab.Home) },
            icon = { Icon(Icons.Filled.Home, contentDescription = "Inicio") },
            label = { Text("Inicio") }
        )
        NavigationRailItem(
            selected = tab == Tab.Log,
            onClick = { onTab(Tab.Log) },
            icon = { Icon(Icons.Filled.Notes, contentDescription = "Registro") },
            label = { Text("Registro") }
        )
    }
}

@Composable
private fun BottomNav(tab: Tab, onTab: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(cs.surfaceContainerHigh)
                .padding(6.dp)
                .animateContentSize(sizeSpring),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavChip("Inicio", Icons.Filled.Home, tab == Tab.Home) { onTab(Tab.Home) }
            NavChip("Registro", Icons.Filled.Notes, tab == Tab.Log) { onTab(Tab.Log) }
        }
    }
}

@Composable
private fun NavChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (selected) cs.secondaryContainer else cs.surfaceContainerHigh,
        label = "navBg"
    )
    val fg by animateColorAsState(
        if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
        label = "navFg"
    )
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .animateContentSize(sizeSpring),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        AnimatedVisibility(selected) {
            Row {
                Spacer(Modifier.width(8.dp))
                Text(label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
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
        Text("Sin acceso root", color = cs.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Concedé el permiso cuando KernelSU lo pida y volvé a abrir la app.", color = cs.onSurfaceVariant)
    }
}

@Composable
private fun HomePane(
    volumes: List<StorageVolume>,
    entries: List<MountEntry>,
    busy: Boolean,
    landscape: Boolean,
    onToggle: (Int, Boolean) -> Unit,
    onDelete: (Int) -> Unit,
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
                StorageHero(volumes)
                Spacer(Modifier.height(16.dp))
                ActionButtons(busy, entries.isNotEmpty(), onApply, onUnmount)
                Spacer(Modifier.height(24.dp))
            }
            Column(
                Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 8.dp)
            ) {
                BindList(entries, onToggle, onDelete)
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
            StorageHero(volumes)
            Spacer(Modifier.height(28.dp))
            BindList(entries, onToggle, onDelete)
            Spacer(Modifier.height(16.dp))
            ActionButtons(busy, entries.isNotEmpty(), onApply, onUnmount)
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun HomeHeader() {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("SD Bind", color = cs.onBackground, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Box(
            Modifier.clip(CircleShape).background(cs.tertiaryContainer).padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("root ok", color = cs.onTertiaryContainer, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BindList(
    entries: List<MountEntry>,
    onToggle: (Int, Boolean) -> Unit,
    onDelete: (Int) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Text("Vínculos", color = cs.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    if (entries.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(cs.surfaceContainer).padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Folder, null, tint = cs.primary, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(10.dp))
                Text("Nada vinculado todavía", color = cs.onSurface, fontWeight = FontWeight.Medium)
                Text("Tocá + y elegí origen y destino", color = cs.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    } else {
        entries.forEachIndexed { i, e ->
            BindCard(e, onToggle = { onToggle(i, it) }, onDelete = { onDelete(i) })
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ActionButtons(busy: Boolean, hasEntries: Boolean, onApply: () -> Unit, onUnmount: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onApply,
        enabled = !busy && hasEntries,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = MaterialTheme.shapes.large
    ) { Text("Guardar y montar", fontWeight = FontWeight.Bold) }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onUnmount, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Desmontar todo", color = cs.error)
    }
}

@Composable
private fun StorageHero(volumes: List<StorageVolume>) {
    val cs = MaterialTheme.colorScheme
    val internal = volumes.firstOrNull { it.kind == VolumeKind.INTERNAL }
    val externals = volumes.filter { it.kind == VolumeKind.EXTERNAL }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(cs.surfaceContainer)
            .padding(20.dp)
            .animateContentSize(sizeSpring)
    ) {
        Text("Almacenamiento", color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))
        if (internal == null && externals.isEmpty()) {
            Text("Sin datos todavía", color = cs.onSurfaceVariant)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (internal != null) {
                    StorageCell(internal, Modifier.weight(1f), secondary = false)
                }
                if (externals.isEmpty()) {
                    StorageCell(
                        StorageVolume(VolumeKind.EXTERNAL, "", "—", "—", "—", 0),
                        Modifier.weight(1f),
                        secondary = true,
                        empty = true
                    )
                } else {
                    externals.take(1).forEach { StorageCell(it, Modifier.weight(1f), secondary = true) }
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
    empty: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        StorageRing(if (empty) 0 else vol.usePercent, Modifier.size(88.dp), secondary = secondary)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (vol.kind == VolumeKind.INTERNAL) Icons.Outlined.Smartphone else Icons.Outlined.SdCard,
                null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(if (empty) "SD / OTG" else vol.label, color = cs.onSurfaceVariant, fontSize = 12.sp)
        }
        if (empty) {
            Text("Sin unidad", color = cs.onSurfaceVariant, fontSize = 13.sp)
        } else {
            Text("${vol.availHuman} libres", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("${vol.usedHuman} / ${vol.totalHuman}", color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StorageRing(percent: Int, modifier: Modifier = Modifier, secondary: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val p = percent.coerceIn(0, 100) / 100f
    val animated by animateFloatAsState(p, animationSpec = floatSpring, label = "ring")
    val track = if (secondary) cs.secondaryContainer else cs.primaryContainer
    val arc = if (secondary) cs.secondary else cs.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(
                track, -90f, 360f, false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                arc, -90f, 360f * animated, false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Text("${(animated * 100).toInt()}%", color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun BindCard(entry: MountEntry, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val name = dirBaseName(entry.dest).ifBlank { dirBaseName(entry.source).ifBlank { "vínculo" } }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.surfaceContainer)
            .padding(14.dp)
            .animateContentSize(sizeSpring),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(cs.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Folder, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.source.ifBlank { "sin origen" }, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("→ ${entry.dest.ifBlank { "sin destino" }}", color = cs.primary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            StatusChip(entry.status)
        }
        Column(horizontalAlignment = Alignment.End) {
            Switch(checked = entry.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, null, tint = cs.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val cs = MaterialTheme.colorScheme
    val (label, bg, fg) = when (status) {
        "MOUNTED" -> Triple("montado", cs.tertiaryContainer, cs.onTertiaryContainer)
        "UNMOUNTED" -> Triple("no montado", cs.secondaryContainer, cs.onSecondaryContainer)
        "SOURCE_MISSING" -> Triple("origen ausente", cs.errorContainer, cs.onErrorContainer)
        else -> Triple("sin comprobar", cs.surfaceContainerHighest, cs.onSurfaceVariant)
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
private fun LogPane(log: String) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Registro", color = cs.onBackground, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(cs.surfaceContainerLowest)
                .padding(16.dp)
        ) {
            Text(
                log.ifBlank { "(sin registros aún)" },
                color = cs.tertiary,
                fontSize = 11.sp,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
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

    fun load(path: String) {
        loading = true
        scope.launch {
            children = RootOps.listSubdirectories(path)
            loading = false
        }
    }
    LaunchedEffect(current) { load(current) }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, null, tint = cs.onBackground)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = cs.onBackground, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(hint, color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShortcutChip("Interno", Icons.Outlined.Smartphone) { current = "/storage/emulated/0" }
            Spacer(Modifier.width(8.dp))
            ShortcutChip("SD / OTG", Icons.Outlined.SdCard) { current = "/mnt/media_rw" }
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
                Text("Subir un nivel", color = cs.primary)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(targetState = loading to children, label = "dirs") { (isLoading, dirs) ->
                when {
                    isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = cs.primary)
                    }
                    dirs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Sin subcarpetas. Podés usar esta.", color = cs.onSurfaceVariant)
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
                                    Modifier.size(42.dp).clip(CircleShape).background(cs.primaryContainer),
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
                    "Elegí una carpeta interior, no la raíz del almacenamiento.",
                    color = cs.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Button(
                onClick = { onPicked(current) },
                enabled = !blocked,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = MaterialTheme.shapes.large
            ) { Text(confirmLabel, fontWeight = FontWeight.Bold) }
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
