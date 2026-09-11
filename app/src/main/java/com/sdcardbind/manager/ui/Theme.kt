package com.sdcardbind.manager.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.sdcardbind.manager.MODDIR
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (dark) dynamicDarkColorScheme(view.context) else dynamicLightColorScheme(view.context)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(22.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(36.dp)
    )
    val font = remember { loadAppFontFamily() }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Evita que el sistema dibuje un scrim de contraste encima del color que
                // ponemos nosotros (algunos fabricantes lo hacen incluso con colores opacos,
                // y eso lava el tinte hasta que se ve casi blanco).
                window.isNavigationBarContrastEnforced = false
            }
            // Transparente en ambos temas: el color real de esa zona ya lo ponen NavScrim +
            // el pill (difuminado + tinte adaptativo), igual que en el WebUI. Forzar aquí un
            // color sólido (como antes) tapaba ese degradado con una franja opaca.
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }

    LaunchedEffect(colorScheme.primary, colorScheme.background, colorScheme.surface) {
        val css = colorScheme.toWebCss()
        withContext(Dispatchers.IO) {
            Shell.cmd(
                "mkdir -p $MODDIR/webroot; cat > $MODDIR/webroot/theme.css << 'SDBIND_THEME'\n$css\nSDBIND_THEME"
            ).exec()
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = roundedTypography(font),
        shapes = shapes,
        content = content
    )
}

private fun Color.cssHex(): String {
    val v = toArgb()
    return String.format("#%02X%02X%02X", (v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
}

private fun ColorScheme.toWebCss(): String = """
:root {
  --bg: ${background.cssHex()};
  --text: ${onBackground.cssHex()};
  --muted: ${onSurfaceVariant.cssHex()};
  --primary: ${primary.cssHex()};
  --on-primary: ${onPrimary.cssHex()};
  --surface: ${surfaceContainer.cssHex()};
  --surface-2: ${surfaceContainerHigh.cssHex()};
  --danger: ${error.cssHex()};
  --ok: ${tertiary.cssHex()};
  --warn: ${secondary.cssHex()};
  --secondary: ${secondary.cssHex()};
  --primary-container: ${primaryContainer.cssHex()};
  --on-surface: ${onSurface.cssHex()};
}
""".trimIndent()
