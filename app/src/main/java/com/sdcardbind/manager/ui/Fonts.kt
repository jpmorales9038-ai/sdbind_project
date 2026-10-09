@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.sdcardbind.manager.ui

import android.graphics.Typeface
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
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
    "variable-rounded",
    "sans-serif-rounded",
    "google-sans-flex",
    "google-sans-text",
    "google-sans"
)

/**
 * Familia con pesos reales (400–800). Antes se envolvía un único Typeface con FontFamily(tf) y
 * Compose ignora el peso en ese caso: todo salía en regular y la negrita no se aplicaba.
 */
fun loadAppFontFamily(): FontFamily = appFontFamily

/** Se calcula una sola vez (la Application lo pide en segundo plano al arrancar el proceso). */
private val appFontFamily: FontFamily by lazy { buildAppFontFamily() }

private fun buildAppFontFamily(): FontFamily {
    findFontFile()?.let { file ->
        try {
            if (isVariableFont(file)) {
                return FontFamily(WEIGHTS.map { w ->
                    Font(
                        file = file,
                        weight = w,
                        // ROND = eje de redondez de Google Sans Flex (100 = terminaciones redondas).
                        // En fuentes sin ese eje (p. ej. Google Sans Rounded) se ignora.
                        variationSettings = FontVariation.Settings(
                            FontVariation.weight(w.weight),
                            FontVariation.Setting("ROND", 100f)
                        )
                    )
                })
            }
            // Fuente estática: declarar el MISMO archivo con peso 700 haría que Compose crea que ya
            // es negrita y no sintetice nada (la negrita no se vería). Se declara solo como regular
            // y, si hay archivos hermanos (-Medium / -Bold), se añaden con su peso real.
            val fonts = mutableListOf(Font(file = file, weight = FontWeight.W400))
            siblingFont(file, "Medium")?.let { fonts += Font(file = it, weight = FontWeight.W500) }
            siblingFont(file, "Bold")?.let { fonts += Font(file = it, weight = FontWeight.W700) }
            return FontFamily(fonts)
        } catch (_: Exception) {}
    }
    for (name in FONT_NAMES) {
        try {
            val tf = Typeface.create(name, Typeface.NORMAL)
            if (tf != null && tf !== Typeface.DEFAULT) {
                return FontFamily(WEIGHTS.map { w -> Font(DeviceFontFamilyName(name), w) })
            }
        } catch (_: Exception) {}
    }
    return FontFamily.SansSerif
}

/** Una fuente es variable si su tabla sfnt incluye `fvar`. */
private fun isVariableFont(file: File): Boolean = try {
    java.io.RandomAccessFile(file, "r").use { raf ->
        val head = ByteArray(12)
        raf.readFully(head)
        val n = ((head[4].toInt() and 0xFF) shl 8) or (head[5].toInt() and 0xFF)
        var found = false
        for (i in 0 until n) {
            val rec = ByteArray(16)
            raf.readFully(rec)
            if (String(rec, 0, 4, Charsets.ISO_8859_1) == "fvar") { found = true; break }
        }
        found
    }
} catch (_: Exception) {
    false
}

private fun siblingFont(file: File, weightName: String): File? {
    val n = file.name
    if (!n.contains("Regular")) return null
    return File(file.parentFile, n.replace("Regular", weightName)).takeIf { it.isFile }
}

private val WEIGHTS = listOf(FontWeight.W400, FontWeight.W500, FontWeight.W600, FontWeight.W700, FontWeight.W800)

private fun findFontFile(): File? {
    FONT_PATHS.forEach { p -> File(p).takeIf { it.isFile }?.let { return it } }
    val dirs = listOf("/system/fonts", "/product/fonts", "/system_ext/fonts")
    val rx = Regex("googlesans.*(round|flex)|google.?sans.*(round|flex)", RegexOption.IGNORE_CASE)
    dirs.forEach { dir ->
        File(dir).listFiles()?.forEach { f ->
            if (f.isFile && !f.name.contains("Italic", ignoreCase = true) &&
                (rx.containsMatchIn(f.name) || f.name.contains("GoogleSansRounded"))
            ) return f
        }
    }
    return null
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun roundedTypography(font: FontFamily): Typography {
    val b = Typography()
    // Escala global 0.9: los tamaños base de Material resultaban grandes en esta app.
    fun TextStyle.r() = copy(
        fontFamily = font,
        fontSize = fontSize * 0.9f,
        lineHeight = lineHeight * 0.9f
    )
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
        labelSmall = b.labelSmall.r(),
        displayLargeEmphasized = b.displayLargeEmphasized.r(),
        displayMediumEmphasized = b.displayMediumEmphasized.r(),
        displaySmallEmphasized = b.displaySmallEmphasized.r(),
        headlineLargeEmphasized = b.headlineLargeEmphasized.r(),
        headlineMediumEmphasized = b.headlineMediumEmphasized.r(),
        headlineSmallEmphasized = b.headlineSmallEmphasized.r(),
        titleLargeEmphasized = b.titleLargeEmphasized.r(),
        titleMediumEmphasized = b.titleMediumEmphasized.r(),
        titleSmallEmphasized = b.titleSmallEmphasized.r(),
        bodyLargeEmphasized = b.bodyLargeEmphasized.r(),
        bodyMediumEmphasized = b.bodyMediumEmphasized.r(),
        bodySmallEmphasized = b.bodySmallEmphasized.r(),
        labelLargeEmphasized = b.labelLargeEmphasized.r(),
        labelMediumEmphasized = b.labelMediumEmphasized.r(),
        labelSmallEmphasized = b.labelSmallEmphasized.r()
    )
}
