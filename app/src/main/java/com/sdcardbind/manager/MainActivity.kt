package com.sdcardbind.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        init {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(15)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                MainScreen()
            }
        }
    }
}

private val AccentColor = Color(0xFF4FA3FF)
private val BgColor = Color(0xFF101214)
private val CardColor = Color(0xFF1A1D21)
private val BorderColor = Color(0xFF2A2E33)
private val MutedColor = Color(0xFF9AA0A6)
private val OkColor = Color(0xFF4CAF50)
private val WarnColor = Color(0xFFFFB74D)
private val DangerColor = Color(0xFFFF6B6B)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = AccentColor,
        background = BgColor,
        surface = CardColor,
        error = DangerColor
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val scope = rememberCoroutineScope()

    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var entries by remember { mutableStateOf(listOf<MountEntry>()) }
    var volumes by remember { mutableStateOf(listOf<StorageVolume>()) }
    var candidates by remember { mutableStateOf(listOf<String>()) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }

    // Diálogo de exploración de carpetas: guarda a qué índice/campo aplica
    var browserTarget by remember { mutableStateOf<Pair<Int, Boolean>?>(null) } // index, isSource

    fun refreshAll() {
        scope.launch {
            entries = RootOps.loadMounts()
            volumes = RootOps.storageVolumes()
            log = RootOps.tailLog()
        }
    }

    LaunchedEffect(Unit) {
        rootOk = RootOps.isRootAvailable()
        if (rootOk == true) refreshAll()
    }

    LaunchedEffect(snackbarMsg) {
        if (snackbarMsg != null) {
            kotlinx.coroutines.delay(2800)
            snackbarMsg = null
        }
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("SD Bind Manager", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor, titleContentColor = Color.White),
                actions = { StatusBadge(rootOk) }
            )
        },
        snackbarHost = {
            snackbarMsg?.let {
                Snackbar(modifier = Modifier.padding(8.dp)) { Text(it) }
            }
        }
    ) { padding ->

        if (rootOk == false) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = DangerColor, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    "No se detectó acceso root. Abre esta app y concede el permiso cuando el Manager lo pida.",
                    color = MutedColor,
                    textAlign = TextAlign.Center
                )
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {

            // ---- Almacenamiento ----
            SectionCard(title = "Almacenamiento") {
                if (volumes.isEmpty()) {
                    Text("Sin datos todavía.", color = MutedColor, fontSize = 13.sp)
                } else {
                    volumes.forEach { v -> StorageRow(v) }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- Carpetas configuradas ----
            SectionCard(title = "Carpetas configuradas") {
                entries.forEachIndexed { index, entry ->
                    MountRow(
                        entry = entry,
                        onPickSource = { browserTarget = index to true },
                        onPickDest = { browserTarget = index to false },
                        onToggle = { checked ->
                            entries = entries.toMutableList().also {
                                it[index] = it[index].copy(enabled = checked)
                            }
                        },
                        onDelete = {
                            entries = entries.toMutableList().also { it.removeAt(index) }
                        }
                    )
                }
                OutlinedButton(
                    onClick = {
                        entries = entries + MountEntry("", "", true)
                        browserTarget = entries.lastIndex to true
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Añadir carpeta")
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- Detección ----
            SectionCard(title = "Detectar SD / OTG") {
                Text(
                    "Busca tarjetas SD y unidades OTG ya montadas por el sistema.",
                    color = MutedColor, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)
                )
                Button(
                    onClick = { scope.launch { candidates = RootOps.detectCandidates() } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BorderColor, contentColor = Color.White)
                ) { Text("Buscar unidades") }

                candidates.forEach { path ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .background(Color(0xFF16181B), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            path, color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val base = path.substringAfterLast("/")
                                entries = entries + MountEntry(path, "/storage/emulated/0/$base", true)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.Black)
                        ) { Text("Usar", fontSize = 12.sp) }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- Acciones ----
            SectionCard(title = "Acciones") {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val ok = RootOps.saveAndApply(entries)
                            snackbarMsg = if (ok) "Guardado y montado" else "Hubo un problema, revisa el registro"
                            refreshAll()
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.Black)
                ) { Text("Guardar y montar") }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            RootOps.unmountAll()
                            refreshAll()
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF6B6B), contentColor = DangerColor)
                ) { Text("Desmontar todo") }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { refreshAll() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Actualizar estado") }
            }

            Spacer(Modifier.height(12.dp))

            // ---- Registro ----
            SectionCard(title = "Registro") {
                Text(
                    log.ifBlank { "(sin registros aún)" },
                    color = OkColor,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 200.dp)
                        .verticalScroll(rememberScrollState())
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- Diálogo selector de carpetas ----
    browserTarget?.let { (index, isSource) ->
        FolderBrowserDialog(
            startPath = if (isSource) "/mnt/media_rw" else "/storage/emulated/0",
            onDismiss = { browserTarget = null },
            onSelect = { chosen ->
                entries = entries.toMutableList().also {
                    if (index in it.indices) {
                        val cur = it[index]
                        it[index] = if (isSource) cur.copy(source = chosen) else cur.copy(dest = chosen)
                    }
                }
                browserTarget = null
            }
        )
    }
}

@Composable
fun StatusBadge(rootOk: Boolean?) {
    val (text, color) = when (rootOk) {
        true -> "root ok" to OkColor
        false -> "sin root" to DangerColor
        null -> "comprobando…" to MutedColor
    }
    Box(
        Modifier
            .padding(end = 12.dp)
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(title, color = MutedColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun StorageRow(v: StorageVolume) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(v.path, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${v.usedHuman} / ${v.totalHuman}", color = MutedColor, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (v.usePercent.coerceIn(0, 100)) / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (v.usePercent > 90) DangerColor else AccentColor,
            trackColor = BorderColor
        )
        Text("Libre: ${v.availHuman}", color = MutedColor, fontSize = 11.sp)
    }
}

@Composable
fun MountRow(
    entry: MountEntry,
    onPickSource: () -> Unit,
    onPickDest: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Color(0xFF16181B), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PathField(label = "Origen", value = entry.source, onClick = onPickSource)
                Spacer(Modifier.height(6.dp))
                PathField(label = "Destino", value = entry.dest, onClick = onPickDest)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Eliminar", tint = DangerColor)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Switch(checked = entry.enabled, onCheckedChange = onToggle)
            Spacer(Modifier.width(6.dp))
            Text("Habilitado", color = MutedColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
            StatusTag(entry.status)
        }
    }
}

@Composable
fun PathField(label: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(BgColor, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Text(label, color = MutedColor, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value.ifBlank { "(sin elegir)" },
                color = if (value.isBlank()) MutedColor else Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClick) { Text("Elegir", fontSize = 12.sp) }
        }
    }
}

@Composable
fun StatusTag(status: String) {
    val (label, color) = when (status) {
        "MOUNTED" -> "montado" to OkColor
        "UNMOUNTED" -> "no montado" to WarnColor
        "SOURCE_MISSING" -> "origen no encontrado" to DangerColor
        else -> "sin comprobar" to MutedColor
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(label, color = color, fontSize = 10.sp)
    }
}

@Composable
fun FolderBrowserDialog(
    startPath: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var currentPath by remember { mutableStateOf(startPath) }
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

    LaunchedEffect(currentPath) { load(currentPath) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(560.dp)
                .background(CardColor, RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Text("Elegir carpeta", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))

            // Accesos rápidos
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("/storage/emulated/0", "/mnt/media_rw").forEach { shortcut ->
                    OutlinedButton(
                        onClick = { currentPath = shortcut },
                        modifier = Modifier.padding(end = 6.dp)
                    ) { Text(shortcut.substringAfterLast("/"), fontSize = 11.sp) }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(currentPath, color = MutedColor, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))

            if (currentPath != "/") {
                TextButton(onClick = { currentPath = currentPath.substringBeforeLast("/").ifBlank { "/" } }) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Subir un nivel", fontSize = 12.sp)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentColor)
                    }
                } else if (children.isEmpty()) {
                    Text("Sin subcarpetas aquí.", color = MutedColor, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(children) { child ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { currentPath = child }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Folder, contentDescription = null, tint = AccentColor, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    child.substringAfterLast("/"),
                                    color = Color.White, fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSelect(currentPath) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.Black)
                ) { Text("Elegir esta carpeta") }
            }
        }
    }
}
