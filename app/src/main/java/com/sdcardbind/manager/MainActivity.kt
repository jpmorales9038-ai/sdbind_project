package com.sdcardbind.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdcardbind.manager.ui.*
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
        enableEdgeToEdge()
        setContent {
            FyloTheme { BindApp() }
        }
    }
}

private enum class Tab { Home, Log }
private enum class Flow { Home, PickSource, PickDest }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindApp() {
    val scope = rememberCoroutineScope()
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

    Scaffold(
        containerColor = FyloBg,
        snackbarHost = {
            snack?.let { Snackbar(Modifier.padding(16.dp), containerColor = FyloSurfaceHigh, contentColor = FyloWhite) { Text(it) } }
        },
        floatingActionButton = {
            if (flow == Flow.Home && tab == Tab.Home && rootOk == true) {
                FloatingActionButton(
                    onClick = { flow = Flow.PickSource; pendingSource = "" },
                    containerColor = FyloAccent,
                    contentColor = FyloOnAccent,
                    shape = CircleShape
                ) { Icon(Icons.Filled.Add, contentDescription = "Añadir vínculo") }
            }
        },
        bottomBar = {
            if (flow == Flow.Home && rootOk == true) {
                FyloBottomBar(tab = tab, onTab = { tab = it })
            }
        }
    ) { pad ->
        when {
            rootOk == false -> NoRoot(Modifier.padding(pad))
            flow == Flow.PickSource -> FolderPickerScreen(
                title = "Origen (SD / OTG)",
                hint = "Carpeta de la tarjeta que querés montar",
                startPath = "/mnt/media_rw",
                confirmLabel = "Siguiente: elegir destino",
                onBack = { flow = Flow.Home },
                onPicked = { path ->
                    pendingSource = normalizeDir(path)
                    flow = Flow.PickDest
                }
            )
            flow == Flow.PickDest -> FolderPickerScreen(
                title = "Destino (interno)",
                hint = "Entrá hasta la carpeta final, ej. Games/GTAV",
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
            else -> {
                if (tab == Tab.Home) {
                    HomePane(
                        volumes = volumes,
                        entries = entries,
                        busy = busy,
                        modifier = Modifier.padding(pad),
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
                } else {
                    LogPane(log, Modifier.padding(pad))
                }
            }
        }
    }
}

@Composable
private fun FyloBottomBar(tab: Tab, onTab: (Tab) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .background(FyloNav, RoundedCornerShape(40.dp))
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavChip("Inicio", Icons.Filled.Home, tab == Tab.Home) { onTab(Tab.Home) }
            NavChip("Registro", Icons.Filled.Notes, tab == Tab.Log) { onTab(Tab.Log) }
        }
    }
}

@Composable
private fun NavChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(32.dp))
            .background(if (selected) FyloAccent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) FyloAccent else FyloMuted, modifier = Modifier.size(20.dp))
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = FyloWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun NoRoot(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Lock, null, tint = FyloDanger, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Sin acceso root", color = FyloWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Concedé el permiso cuando KernelSU lo pida y volvé a abrir la app.", color = FyloMuted)
    }
}

@Composable
private fun HomePane(
    volumes: List<StorageVolume>,
    entries: List<MountEntry>,
    busy: Boolean,
    modifier: Modifier,
    onToggle: (Int, Boolean) -> Unit,
    onDelete: (Int) -> Unit,
    onApply: () -> Unit,
    onUnmount: () -> Unit
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SD Bind", color = FyloWhite, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(Modifier.background(FyloOk.copy(alpha = 0.18f), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("root ok", color = FyloOk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(20.dp))

        StorageHero(volumes)
        Spacer(Modifier.height(28.dp))

        Text("Vínculos", color = FyloWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(FyloSurface).padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Folder, null, tint = FyloAccent, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Nada vinculado todavía", color = FyloWhite, fontWeight = FontWeight.Medium)
                    Text("Tocá + y elegí origen y destino", color = FyloMuted, fontSize = 13.sp)
                }
            }
        } else {
            entries.forEachIndexed { i, e ->
                BindCard(e, onToggle = { onToggle(i, it) }, onDelete = { onDelete(i) })
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onApply,
            enabled = !busy && entries.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = FyloAccent, contentColor = FyloOnAccent)
        ) { Text("Guardar y montar", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onUnmount, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Desmontar todo", color = FyloDanger)
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun StorageHero(volumes: List<StorageVolume>) {
    val internal = volumes.firstOrNull { it.path.contains("emulated") } ?: volumes.firstOrNull()
    val sd = volumes.firstOrNull { it.path.contains("media_rw") }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(FyloSurface).padding(20.dp)
    ) {
        Text("Almacenamiento", color = FyloMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))
        if (internal == null) {
            Text("Sin datos todavía", color = FyloMuted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StorageRing(internal.usePercent, Modifier.size(92.dp))
                Spacer(Modifier.width(18.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Smartphone, null, tint = FyloMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Interno", color = FyloMuted, fontSize = 13.sp)
                    }
                    Text("${internal.availHuman} libres", color = FyloWhite, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("${internal.usedHuman} / ${internal.totalHuman}", color = FyloMuted, fontSize = 13.sp)
                }
            }
        }
        if (sd != null) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = FyloSurfaceHigh)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SdCard, null, tint = FyloAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("SD / OTG", color = FyloWhite, fontWeight = FontWeight.Medium)
                    Text(sd.path, color = FyloMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${sd.availHuman} libres", color = FyloAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StorageRing(percent: Int, modifier: Modifier = Modifier) {
    val p = percent.coerceIn(0, 100) / 100f
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(
                FyloAccentDim, -90f, 360f, false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                FyloAccent, -90f, 360f * p, false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Text("$percent%", color = FyloWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun BindCard(entry: MountEntry, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val name = dirBaseName(entry.dest).ifBlank { dirBaseName(entry.source).ifBlank { "vínculo" } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(FyloSurface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(FyloAccentDim), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Folder, null, tint = FyloAccent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = FyloWhite, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.source.ifBlank { "sin origen" }, color = FyloMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("→ ${entry.dest.ifBlank { "sin destino" }}", color = FyloAccent.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            StatusChip(entry.status)
        }
        Column(horizontalAlignment = Alignment.End) {
            Switch(
                checked = entry.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = FyloAccent, checkedThumbColor = FyloOnAccent)
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, null, tint = FyloDanger, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (label, color) = when (status) {
        "MOUNTED" -> "montado" to FyloOk
        "UNMOUNTED" -> "no montado" to FyloWarn
        "SOURCE_MISSING" -> "origen ausente" to FyloDanger
        else -> "sin comprobar" to FyloMuted
    }
    Text(
        label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun LogPane(log: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text("Registro", color = FyloWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(Color(0xFF120C10)).padding(16.dp)
        ) {
            Text(
                log.ifBlank { "(sin registros aún)" },
                color = FyloOk,
                fontSize = 11.sp,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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

    Column(Modifier.fillMaxSize().background(FyloBg).statusBarsPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, null, tint = FyloWhite)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = FyloWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(hint, color = FyloMuted, fontSize = 12.sp)
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
            color = FyloMuted,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        if (current.trimEnd('/') != "") {
            TextButton(onClick = {
                current = current.trimEnd('/').substringBeforeLast("/").ifBlank { "/" }
            }) {
                Icon(Icons.Filled.ArrowUpward, null, modifier = Modifier.size(16.dp), tint = FyloAccent)
                Spacer(Modifier.width(6.dp))
                Text("Subir un nivel", color = FyloAccent)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FyloAccent)
                }
                children.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Sin subcarpetas. Podés usar esta.", color = FyloMuted)
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(children) { child ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(FyloSurface)
                                .clickable { current = child }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(42.dp).clip(CircleShape).background(FyloAccentDim),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Folder, null, tint = FyloAccent, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                dirBaseName(child),
                                color = FyloWhite,
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Filled.ChevronRight, null, tint = FyloMuted)
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        val blocked = rejectRoot && isUnsafeDest(current)
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            if (blocked) {
                Text("Entrá a una subcarpeta (Games/GTAV, no la raíz).", color = FyloWarn, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            Button(
                onClick = { onPicked(current) },
                enabled = !blocked,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FyloAccent, contentColor = FyloOnAccent)
            ) { Text(confirmLabel, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ShortcutChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(FyloSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = FyloAccent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = FyloWhite, fontSize = 13.sp)
    }
}
