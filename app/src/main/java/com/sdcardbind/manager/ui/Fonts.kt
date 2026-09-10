package com.sdcardbind.manager.ui

import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import java.io.File

private val FONT_PATHS = listOf(
    "/system/fonts/GoogleSansRounded-Regular.ttf",
    "/system/fonts/GoogleSansRounded-Medium.ttf",
    "/system/fonts/GoogleSansRounded-VF.ttf",
    "/system/fonts/GoogleSansFlex.ttf",
    "/system/fonts/GoogleSansFlex-Regular.ttf",
    "/system/fonts/GoogleSansFlex-Variable.ttf",
    "/system/fonts/GoogleSans-Regular.ttf",
    "/product/fonts/GoogleSansRounded-Regular.ttf",
    "/system_ext/fonts/GoogleSansRounded-Regular.ttf"
)

private val FONT_NAMES = listOf(
    "google-sans-rounded",
    "google-sans-flex",
    "google-sans-text",
    "google-sans",
    "sans-serif-rounded"
)

fun loadAppFontFamily(): FontFamily {
    findFontFile()?.let { file ->
        try { return FontFamily(Typeface.createFromFile(file)) } catch (_: Exception) {}
    }
    for (name in FONT_NAMES) {
        try {
            val tf = Typeface.create(name, Typeface.NORMAL)
            if (tf != null && tf !== Typeface.DEFAULT) return FontFamily(tf)
        } catch (_: Exception) {}
    }
    return FontFamily.SansSerif
}

private fun findFontFile(): File? {
    FONT_PATHS.forEach { p -> File(p).takeIf { it.isFile }?.let { return it } }
    val dirs = listOf("/system/fonts", "/product/fonts", "/system_ext/fonts")
    val rx = Regex("googlesans.*(round|flex)|google.?sans.*(round|flex)", RegexOption.IGNORE_CASE)
    dirs.forEach { dir ->
        File(dir).listFiles()?.forEach { f ->
            if (f.isFile && (rx.containsMatchIn(f.name) || f.name.contains("GoogleSansRounded"))) return f
        }
    }
    return null
}

fun roundedTypography(font: FontFamily): Typography {
    val b = Typography()
    fun TextStyle.r() = copy(fontFamily = font)
    return Typography(
        displayLarge = b.displayLarge.r(),
        displayMedium = b.displayMedium.r(),
        displaySmall = b.displaySmall.r(),
        headlineLarge = b.headlineLarge.r(),
        headlineMedium = b.headlineMedium.r(),
        headlineSmall = b.headlineSmall.r(),
        titleLarge = b.titleLarge.r(),
        titleMedium = b.titleMedium.r(),
        titleSmall = b.titleSmall.r(),
        bodyLarge = b.bodyLarge.r(),
        bodyMedium = b.bodyMedium.r(),
        bodySmall = b.bodySmall.r(),
        labelLarge = b.labelLarge.r(),
        labelMedium = b.labelMedium.r(),
        labelSmall = b.labelSmall.r()
    )
}
