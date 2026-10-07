package com.sdcardbind.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Kit visual de la app: título grande + cards redondeadas + barra de navegación flotante.
 * Todos los colores salen del ColorScheme (Material You), nada fijo.
 */

/** Margen lateral de pantalla y radio de las cards, igual que la referencia. */
val UiScreenPadding = 16.dp
private val UiCardRadius = 36.dp

/** Espacio inferior que hay que dejar en listas para que la barra flotante no tape contenido. */
val UiNavClearance = 120.dp

/** Título grande a la izquierda + acciones (iconos sin fondo) a la derecha. */
@Composable
fun UiHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = UiScreenPadding + 1.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = cs.onBackground,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

/** Card de sección: icono (color primario) + título y contenido debajo. */
@Composable
fun UiCard(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (containerColor == Color.Unspecified) cs.surfaceContainerHigh else containerColor
    val titleColor = if (contentColor == Color.Unspecified) cs.onSurface else contentColor
    val iconTint = if (contentColor == Color.Unspecified) cs.primary else contentColor
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UiCardRadius))
            .background(bg)
            .padding(20.dp)
            .animateContentSize()
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    title,
                    color = titleColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        content()
    }
}

/** Color de las "píldoras" interiores de una [UiCard] (campos, tiles, filas). */
@Composable
fun uiInnerColor(): Color = MaterialTheme.colorScheme.surfaceContainerHighest

/**
 * Card principal de Inicio: fondo sólido con título, estado e interruptor
 * (con ✕ / ✓ dentro del pulgar).
 */
@Composable
fun UiHeroCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UiCardRadius))
            .background(cs.secondaryContainer)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = 24.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = cs.onSecondaryContainer,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(subtitle, color = cs.onSecondaryContainer.copy(alpha = 0.75f), fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            thumbContent = {
                Icon(
                    if (checked) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        )
    }
}

data class UiNavItem(val label: String, val icon: ImageVector)

/**
 * Barra de navegación flotante en píldora: la pestaña activa se resalta con su etiqueta, las
 * demás muestran solo el icono. Va superpuesta al contenido (no reserva espacio).
 */
@Composable
fun UiNavBar(
    items: List<UiNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(CircleShape)
            .background(cs.secondaryContainer)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { i, item ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) cs.primary else Color.Transparent, label = "navBg")
            val fg by animateColorAsState(if (sel) cs.onPrimary else cs.onSecondaryContainer, label = "navFg")
            Row(
                Modifier
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .selectable(selected = sel, role = Role.Tab, onClick = { onSelect(i) })
                    .padding(horizontal = if (sel) 20.dp else 16.dp)
                    .animateContentSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(item.icon, contentDescription = if (sel) null else item.label, tint = fg, modifier = Modifier.size(24.dp))
                AnimatedVisibility(
                    visible = sel,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut()
                ) {
                    Text(
                        item.label,
                        color = fg,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Difuminado inferior: el contenido se funde con el fondo detrás de la barra flotante y de la
 * zona de la barra de gestos, para que no se vea cortado.
 */
@Composable
fun UiBottomFade(modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier
            .fillMaxWidth()
            .height(inset + 128.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to bg.copy(alpha = 0.85f),
                    1f to bg
                )
            )
    )
}
