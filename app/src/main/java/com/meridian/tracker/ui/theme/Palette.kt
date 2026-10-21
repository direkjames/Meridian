package com.meridian.tracker.ui.theme

import androidx.compose.ui.graphics.Color

/** One complete set of Meridian colours. */
class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val stroke: Color,
    val hairline: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentDim: Color,
    val onAccent: Color,
    val danger: Color,
    val bar: Color,
    /** Tint for the "selected" container in pickers and dialogs. */
    val accentContainer: Color,
)

/** Near-black instrument panel with a single warm yellow accent. */
val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF0B0C0E),
    surface = Color(0xFF141518),
    surface2 = Color(0xFF1C1E22),
    stroke = Color(0xFF2A2D32),
    hairline = Color(0xFF202227),
    text = Color(0xFFF4F2EA),
    muted = Color(0xFF9A9A93),
    faint = Color(0xFF64655F),
    accent = Color(0xFFFFE566),
    accentDim = Color(0xFF8C7E38),
    onAccent = Color(0xFF0B0C0E),
    danger = Color(0xFFFF8A73),
    bar = Color(0xFF3C3F45),
    accentContainer = Color(0xFF3A3420),
)

/**
 * Warm paper with the same accent family, deepened to antique gold so yellow text and lines stay
 * readable on light backgrounds (the pale yellow would vanish).
 */
val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF6F5F0),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEFEDE6),
    stroke = Color(0xFFD6D3C8),
    hairline = Color(0xFFE6E3DA),
    text = Color(0xFF15161A),
    muted = Color(0xFF5C5D58),
    faint = Color(0xFF77786F),
    accent = Color(0xFF836500),
    accentDim = Color(0xFFD9C27A),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFBC3A24),
    bar = Color(0xFFCFCCC2),
    accentContainer = Color(0xFFF1E6B8),
)

/** What the athlete picked in Settings. */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");

    fun resolve(systemDark: Boolean): Palette = when (this) {
        SYSTEM -> if (systemDark) DarkPalette else LightPalette
        LIGHT -> LightPalette
        DARK -> DarkPalette
    }
}
