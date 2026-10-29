package com.poise.android.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.graphics.fonts.FontStyle as PlatformFontStyle
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.poise.android.R

/** One of the desktop app's themes. [accent] means live, [secondary] means idle or available. */
data class Palette(
        val name: String,
        val bg: Color,
        val bar: Color, // nav bar
        val panel: Color, // cards
        val face: Color, // dial face
        val line: Color,
        val tick: Color,
        val ink: Color,
        val muted: Color,
        val accent: Color,
        val accentWash: Color,
        val secondary: Color
)

private fun palette(
        name: String,
        bg: Long, bar: Long, panel: Long, face: Long, line: Long, tick: Long,
        ink: Long, muted: Long, accent: Long, secondary: Long, wash: Float = 0.16f
) = Palette(
        name, Color(bg), Color(bar), Color(panel), Color(face), Color(line), Color(tick),
        Color(ink), Color(muted), Color(accent), Color(accent).copy(alpha = wash), Color(secondary)
)

val Palettes =
        listOf(
                palette("Halcyon", 0xFF0A111E, 0xFF080E18, 0xFF0F1828, 0xFF111C30, 0xFF1D2A3B, 0xFF34475F,
                        0xFFF8FAFC, 0xFF8B94A3, 0xFF5FD5EB, 0xFFE8C468),
                palette("Verdant", 0xFF0A1110, 0xFF070D0C, 0xFF0F1918, 0xFF14201F, 0xFF1F302E, 0xFF33504B,
                        0xFFEEF3EC, 0xFF8FA6A0, 0xFF35E0A8, 0xFFE9C46A),
                palette("Vigil", 0xFF0A0920, 0xFF07061A, 0xFF141131, 0xFF151236, 0xFF2A2552, 0xFF433C78,
                        0xFFF3EFFF, 0xFF9A93B8, 0xFFB9A6FF, 0xFFE6CD8A),
                palette("Ember", 0xFF140F0A, 0xFF100C08, 0xFF1A130C, 0xFF1E1610, 0xFF2E2318, 0xFF4A3A2A,
                        0xFFFFF8F0, 0xFFA39A8B, 0xFFF5A524, 0xFF6FD0C0),
                palette("Eclipse", 0xFF0A0A0A, 0xFF070707, 0xFF111111, 0xFF141414, 0xFF282828, 0xFF444444,
                        0xFFE6E6E6, 0xFFA3A3A3, 0xFFC8C8C8, 0xFF4A83C9, wash = 0.12f),
                palette("Orchid", 0xFF150D14, 0xFF110A10, 0xFF1B1119, 0xFF20141D, 0xFF30202B, 0xFF4D3344,
                        0xFFFDF2F8, 0xFFA8939F, 0xFFF472B6, 0xFF86C5FF)
        )

val LocalPalette = staticCompositionLocalOf { Palettes[0] }

/** Rajdhani, with Cairo behind it: Latin and digits stay Rajdhani's, Arabic-script letters are Cairo's. */
val Rajdhani = FontFamily(WithArabic(FontWeight.SemiBold), WithArabic(FontWeight.Bold))

@OptIn(ExperimentalTextApi::class)
private class WithArabic(override val weight: FontWeight) : AndroidFont(FontLoadingStrategy.Blocking, WithArabicLoader, FontVariation.Settings()) {
    override val style = FontStyle.Normal
}

// A Compose FontFamily picks fonts by weight, never by script, so the fallback is the platform's
private object WithArabicLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface {
        fun family(id: Int) = PlatformFontFamily.Builder(PlatformFont.Builder(context.resources, id).build()).build()
        val bold = font.weight == FontWeight.Bold
        return Typeface.CustomFallbackBuilder(family(if (bold) R.font.rajdhani_bold else R.font.rajdhani_semibold))
                .addCustomFallback(family(if (bold) R.font.cairo_bold else R.font.cairo_semibold))
                .setSystemFallback("sans-serif") // accents, emoji and every other script
                .setStyle(PlatformFontStyle(font.weight.weight, PlatformFontStyle.FONT_SLANT_UPRIGHT))
                .build()
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont) = loadBlocking(context, font)
}
