package com.sdcardbind.manager.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Tema Material 3 Expressive: color dinámico (Material You) + esquema de movimiento
 * expressive (resortes con rebote suave) + tipografía redondeada con estilos enfatizados.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = remember(dark) {
        val base = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> darkColorScheme()
            else -> lightColorScheme()
        }
        // A pedido: el fondo más oscuro que las cards (surfaceContainer) en ambos temas.
        // Se intercambian background <-> surfaceContainer solo si el fondo original es más
        // claro que las cards; surfaceContainerHigh no se toca.
        if (base.background.luminance() > base.surfaceContainer.luminance()) {
            base.copy(
                background = base.surfaceContainer,
                surface = base.surfaceContainer,
                surfaceContainer = base.background
            )
        } else {
            base
        }
    }
    val shapes = remember {
        Shapes(
            extraSmall = RoundedCornerShape(12.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(22.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp)
        )
    }
    val typography = remember { roundedTypography(loadAppFontFamily()) }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = shapes,
        typography = typography,
        content = content
    )
}
