package com.sdcardbind.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdcardbind.manager.ui.UiCard
import com.sdcardbind.manager.ui.uiInnerColor
import kotlinx.coroutines.launch

/**
 * Tarjeta "Rendimiento" de Ajustes: lectura anticipada de la SD/OTG, frecuencia del vigilante,
 * vigilante ligero y montaje rápido. Cada cambio se guarda y se aplica al momento.
 */
@Composable
fun PerformanceCard(perf: PerfController, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val cfg = perf.config
    fun change(next: PerfConfig) {
        scope.launch { perf.update(next) }
    }

    UiCard(modifier = modifier, icon = Icons.Filled.Speed, title = stringResource(R.string.perf_title)) {
        Text(stringResource(R.string.perf_desc), color = cs.onSurfaceVariant, fontSize = 13.sp)
        if (perf.saveFailed) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.perf_save_failed),
                color = cs.error,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(16.dp))

        PerfTile(stringResource(R.string.perf_readahead_title), stringResource(R.string.perf_readahead_desc)) {
            ChipRow {
                PERF_READAHEAD_OPTIONS.forEach { kb ->
                    FilterChip(
                        selected = cfg.readAheadKb == kb,
                        onClick = { change(cfg.copy(readAheadKb = kb)) },
                        enabled = perf.loaded,
                        label = {
                            Text(
                                when (kb) {
                                    0 -> stringResource(R.string.perf_system)
                                    1024 -> "1 MB"
                                    2048 -> "2 MB"
                                    else -> "$kb KB"
                                }
                            )
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PerfTile(stringResource(R.string.perf_interval_title), stringResource(R.string.perf_interval_desc)) {
            ChipRow {
                PERF_INTERVAL_OPTIONS.forEach { s ->
                    FilterChip(
                        selected = cfg.intervalSec == s,
                        onClick = { change(cfg.copy(intervalSec = s)) },
                        enabled = perf.loaded,
                        label = { Text("$s s") }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PerfSwitchTile(
            title = stringResource(R.string.perf_light_title),
            body = stringResource(R.string.perf_light_desc),
            checked = cfg.lightGuard,
            enabled = perf.loaded,
            onChange = { change(cfg.copy(lightGuard = it)) }
        )
        Spacer(Modifier.height(12.dp))
        PerfSwitchTile(
            title = stringResource(R.string.perf_fast_title),
            body = stringResource(R.string.perf_fast_desc),
            checked = cfg.fastLabel,
            enabled = perf.loaded,
            onChange = { change(cfg.copy(fastLabel = it)) }
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.perf_hint), color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun PerfTile(title: String, body: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(uiInnerColor())
            .padding(16.dp)
    ) {
        Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun PerfSwitchTile(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(uiInnerColor())
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(body, color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            thumbContent = {
                Icon(
                    if (checked) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
    }
}
