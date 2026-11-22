package io.github.evgenykon.mybooks.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.evgenykon.mybooks.R
import io.github.evgenykon.mybooks.core.BlockKind
import io.github.evgenykon.mybooks.data.ReaderSettings

/** Colour set of a reading theme. */
data class ReaderPalette(
    val background: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val surface: Color,
)

val palettes = listOf(
    ReaderPalette(Color(0xFFFDFDFB), Color(0xFF1A1A1A), Color(0xFF7A7A7A), Color(0xFF8A6D3B), Color(0xFFF1F0EC)),
    ReaderPalette(Color(0xFFF6EDDC), Color(0xFF3B3229), Color(0xFF8A7A63), Color(0xFF9A6B3F), Color(0xFFEFE4CE)),
    ReaderPalette(Color(0xFF14161A), Color(0xFFD8D8D6), Color(0xFF8C8F95), Color(0xFFC8A46A), Color(0xFF1F2228)),
)

fun paletteOf(settings: ReaderSettings): ReaderPalette = palettes.getOrElse(settings.theme) { palettes[0] }

/**
 * Bundled Google Fonts (SIL OFL, subsets with Latin + Cyrillic), loaded from resources:
 * Spectral, PT Serif, Alegreya and Merriweather. Indices 3..6 extend the list, so settings
 * saved before the fonts existed (0..2) keep working.
 */
private val spectralFamily = FontFamily(
    Font(R.font.spectral_regular, FontWeight.Normal),
    Font(R.font.spectral_bold, FontWeight.Bold),
    Font(R.font.spectral_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.spectral_bold_italic, FontWeight.Bold, FontStyle.Italic),
)
private val ptSerifFamily = FontFamily(
    Font(R.font.pt_serif_regular, FontWeight.Normal),
    Font(R.font.pt_serif_bold, FontWeight.Bold),
    Font(R.font.pt_serif_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.pt_serif_bold_italic, FontWeight.Bold, FontStyle.Italic),
)
private val alegreyaFamily = FontFamily(
    Font(R.font.alegreya_regular, FontWeight.Normal),
    Font(R.font.alegreya_bold, FontWeight.Bold),
    Font(R.font.alegreya_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.alegreya_bold_italic, FontWeight.Bold, FontStyle.Italic),
)
private val merriweatherFamily = FontFamily(
    Font(R.font.merriweather_regular, FontWeight.Normal),
    Font(R.font.merriweather_bold, FontWeight.Bold),
    Font(R.font.merriweather_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.merriweather_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

val fontFamilies = listOf(
    FontFamily.Serif,
    FontFamily.SansSerif,
    FontFamily.Monospace,
    spectralFamily,
    ptSerifFamily,
    alegreyaFamily,
    merriweatherFamily,
)
val fontFamilyNames = listOf(
    "С засечками",
    "Без засечек",
    "Моно",
    "Spectral",
    "PT Serif",
    "Alegreya",
    "Merriweather",
)

fun fontFamilyOf(settings: ReaderSettings): FontFamily =
    fontFamilies.getOrElse(settings.fontFamily) { FontFamily.Serif }


/** Text style for a block kind, given reader settings and the active palette. */
fun styleFor(kind: BlockKind, settings: ReaderSettings, palette: ReaderPalette): TextStyle {
    val size = settings.fontSize.sp
    val lineHeight = (settings.fontSize * settings.lineHeight).sp
    val family = fontFamilyOf(settings)
    val base = TextStyle(
        fontFamily = family,
        fontSize = size,
        lineHeight = lineHeight,
        color = palette.text,
        textAlign = if (settings.justified) TextAlign.Justify else TextAlign.Start,
    )
    return when (kind) {
        BlockKind.HEADING1 -> base.copy(
            fontSize = (settings.fontSize * 1.55f).sp,
            lineHeight = (settings.fontSize * settings.lineHeight * 1.25f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        BlockKind.HEADING2 -> base.copy(
            fontSize = (settings.fontSize * 1.3f).sp,
            lineHeight = (settings.fontSize * settings.lineHeight * 1.2f).sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        BlockKind.HEADING3 -> base.copy(
            fontSize = (settings.fontSize * 1.12f).sp,
            fontWeight = FontWeight.SemiBold,
        )
        BlockKind.SUBTITLE -> base.copy(fontWeight = FontWeight.Medium, color = palette.secondary)
        BlockKind.EPIGRAPH -> base.copy(
            fontSize = (settings.fontSize * 0.92f).sp,
            color = palette.secondary,
        )
        BlockKind.EPIGRAPH_AUTHOR -> base.copy(
            fontSize = (settings.fontSize * 0.88f).sp,
            color = palette.secondary,
            textAlign = TextAlign.End,
        )
        BlockKind.POEM -> base.copy(
            fontSize = (settings.fontSize * 0.95f).sp,
            color = palette.text,
            textAlign = TextAlign.Start,
        )
        BlockKind.ANNOTATION -> base.copy(color = palette.secondary)
        else -> base
    }
}

fun headingWeight(kind: BlockKind): Int = when (kind) {
    BlockKind.HEADING1 -> 2
    BlockKind.HEADING2 -> 3
    else -> 4
}
