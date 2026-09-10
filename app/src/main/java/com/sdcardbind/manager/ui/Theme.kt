package com.sdcardbind.manager.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme

val FyloBg = Color(0xFF1A1218)
val FyloSurface = Color(0xFF2B1F26)
val FyloSurfaceHigh = Color(0xFF362830)
val FyloNav = Color(0xFF3D2A34)
val FyloAccent = Color(0xFFE8A8C8)
val FyloAccentDim = Color(0xFF5A3148)
val FyloOnAccent = Color(0xFF3A1828)
val FyloMuted = Color(0xFFB9A4AE)
val FyloOk = Color(0xFF8FCB9A)
val FyloWarn = Color(0xFFE8C07A)
val FyloDanger = Color(0xFFE07A7A)
val FyloWhite = Color(0xFFF8EEF3)

val CatGreen = Color(0xFF3E5C48)
val CatRed = Color(0xFF8B4E42)
val CatGold = Color(0xFF8B6B3C)
val CatBlue = Color(0xFF3A4E78)

@Composable
fun FyloTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FyloAccent,
            onPrimary = FyloOnAccent,
            background = FyloBg,
            surface = FyloSurface,
            onBackground = FyloWhite,
            onSurface = FyloWhite,
            error = FyloDanger
        ),
        content = content
    )
}
