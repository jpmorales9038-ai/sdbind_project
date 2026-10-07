package com.sdcardbind.manager

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdcardbind.manager.ui.UiCard
import com.sdcardbind.manager.ui.uiSuccessTone
import com.sdcardbind.manager.ui.uiWarningTone

/**
 * Tarjeta "Actualizaciones": se pone verde cuando hay una actualización lista para instalar y solo
 * entonces muestra el botón (que confirma la instalación); también es verde al terminar. Si la
 * versión del módulo no coincide con la de la app, se tiñe de ámbar y permite flashear el módulo.
 */
@Composable
fun UpdatesCard(
    state: UpdateController,
    onInstallUpdate: () -> Unit,
    onFlashModule: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val success = uiSuccessTone()
    val warning = uiWarningTone()
    val done = state.phase == AppPhase.Done
    val ready = state.phase == AppPhase.Ready
    val module = state.module
    val mismatch = module is ModuleStatus.Mismatch && !done && !ready
    val tone = when {
        done || ready -> success
        mismatch -> warning
        else -> null
    }
    val container by animateColorAsState(tone?.container ?: cs.surfaceContainerHigh, label = "updBg")
    val content by animateColorAsState(tone?.content ?: cs.onSurface, label = "updFg")
    val soft = if (tone == null) cs.onSurfaceVariant else content
    val icon = when {
        done -> Icons.Filled.CheckCircle
        mismatch -> Icons.Filled.Warning
        else -> Icons.Filled.SystemUpdate
    }
    val btnColors = if (tone != null) {
        ButtonDefaults.buttonColors(containerColor = tone.content, contentColor = tone.container)
    } else {
        ButtonDefaults.buttonColors()
    }
    val spinner = tone?.container ?: cs.onPrimary
    val spinnerAlone = tone?.content ?: cs.primary
    val anyBusy = state.busy || state.moduleBusy

    UiCard(
        modifier = modifier,
        icon = icon,
        title = stringResource(R.string.updates),
        containerColor = container,
        contentColor = if (tone == null) null else content
    ) {
        Text(
            stringResource(R.string.upd_app_line, Updater.displayFull(state.appVersion)),
            color = soft,
            fontSize = 14.sp
        )
        when (module) {
            is ModuleStatus.NotFound ->
                Text(stringResource(R.string.upd_module_none), color = soft, fontSize = 14.sp)
            is ModuleStatus.Synced ->
                Text(stringResource(R.string.upd_module_line, Updater.displayVersion(module.version)), color = soft, fontSize = 14.sp)
            is ModuleStatus.PendingReboot ->
                Text(stringResource(R.string.upd_module_line, Updater.displayVersion(module.version)), color = soft, fontSize = 14.sp)
            is ModuleStatus.Mismatch ->
                Text(stringResource(R.string.upd_module_line, Updater.displayVersion(module.moduleVersion)), color = soft, fontSize = 14.sp)
            ModuleStatus.Unknown -> {}
        }
        Spacer(Modifier.height(12.dp))

        val res = state.msgRes
        if (mismatch) {
            Text(
                stringResource(
                    R.string.upd_mismatch_body,
                    Updater.displayVersion(state.appVersion),
                    Updater.displayVersion((module as ModuleStatus.Mismatch).moduleVersion)
                ),
                color = content,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (res != null) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(res, state.msgArg), color = content, fontSize = 13.sp)
            }
        } else if (res != null) {
            Text(
                stringResource(res, state.msgArg),
                color = if (tone == null) cs.onSurface else content,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        } else if (module is ModuleStatus.PendingReboot) {
            Text(
                stringResource(R.string.upd_pending_reboot, Updater.displayVersion(module.version)),
                color = cs.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Text(stringResource(R.string.updates_desc), color = soft, fontSize = 13.sp)
        }

        if (ready) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onInstallUpdate,
                enabled = !anyBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                shapes = ButtonDefaults.shapes(),
                colors = btnColors
            ) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.upd_btn_install))
            }
        } else if (state.busy) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LoadingIndicator(Modifier.size(32.dp), color = spinnerAlone)
            }
        }
        if (mismatch) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onFlashModule,
                enabled = !anyBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                shapes = ButtonDefaults.shapes(),
                colors = btnColors
            ) {
                if (state.moduleBusy) {
                    LoadingIndicator(Modifier.size(24.dp), color = spinner)
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.upd_btn_flash_module))
                }
            }
        }
    }
}
