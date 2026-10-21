package com.meridian.tracker.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.R
import com.meridian.tracker.data.Prefs

/**
 * The colours in use right now. Every property reads [palette], which is Compose state, so changing
 * the theme redraws everything that uses them.
 */
object MColors {
    var palette by mutableStateOf(DarkPalette)
        private set

    // A plain copy, so checking it doesn't count as reading the state while composing.
    private var current: Palette = DarkPalette

    /** Switches the whole app to [p]. Cheap when nothing changes. */
    fun apply(p: Palette) {
        if (current === p) return
        current = p
        palette = p
    }

    val Bg: Color get() = palette.bg
    val Surface: Color get() = palette.surface
    val Surface2: Color get() = palette.surface2
    val Stroke: Color get() = palette.stroke
    val Hairline: Color get() = palette.hairline
    val Text: Color get() = palette.text
    val Muted: Color get() = palette.muted
    val Faint: Color get() = palette.faint
    val Accent: Color get() = palette.accent
    val AccentDim: Color get() = palette.accentDim
    val OnAccent: Color get() = palette.onAccent
    val Danger: Color get() = palette.danger
    val Bar: Color get() = palette.bar

    /** For things that are always dark, like the map, whatever the theme is. */
    val Dark: Palette get() = DarkPalette
}

val Instrument = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_bold, FontWeight.Bold),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_bold, FontWeight.Bold),
)

/** Text styles used across the app. Numbers use tabular figures so they don't jiggle. */
object MType {
    private class Styles(p: Palette) {
        val hero = TextStyle(
            fontFamily = Instrument, fontWeight = FontWeight.Bold, fontSize = 88.sp,
            lineHeight = 88.sp, letterSpacing = (-3.5).sp, fontFeatureSettings = "tnum", color = p.text,
        )
        val bigNumber = TextStyle(
            fontFamily = Instrument, fontWeight = FontWeight.Bold, fontSize = 30.sp,
            lineHeight = 34.sp, letterSpacing = (-0.8).sp, fontFeatureSettings = "tnum", color = p.text,
        )
        val number = TextStyle(
            fontFamily = Instrument, fontWeight = FontWeight.Bold, fontSize = 17.sp,
            lineHeight = 22.sp, fontFeatureSettings = "tnum", color = p.text,
        )
        val title = TextStyle(
            fontFamily = Instrument, fontWeight = FontWeight.Bold, fontSize = 30.sp,
            lineHeight = 36.sp, letterSpacing = (-0.6).sp, color = p.text,
        )
        val heading = TextStyle(
            fontFamily = Instrument, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            lineHeight = 24.sp, color = p.text,
        )
        val body = TextStyle(
            fontFamily = Instrument, fontWeight = FontWeight.Normal, fontSize = 15.sp,
            lineHeight = 22.sp, color = p.muted,
        )
        val button = TextStyle(
            fontFamily = Instrument, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            lineHeight = 20.sp, letterSpacing = 0.2.sp,
        )
        /** Small uppercase instrument labels. */
        val label = TextStyle(
            fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 11.sp,
            lineHeight = 14.sp, letterSpacing = 1.4.sp, color = p.muted,
        )
        val unit = TextStyle(
            fontFamily = Instrument, fontWeight = FontWeight.Normal, fontSize = 15.sp,
            lineHeight = 20.sp, color = p.muted,
        )
        val wordmark = TextStyle(
            fontFamily = GeistMono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            letterSpacing = 4.sp, color = p.text,
        )
    }

    private var cache: Pair<Palette, Styles>? = null

    /** The styles for the current palette, built once per palette. */
    private val styles: Styles
        get() {
            val p = MColors.palette
            cache?.let { if (it.first === p) return it.second }
            return Styles(p).also { cache = p to it }
        }

    val Hero: TextStyle get() = styles.hero
    val BigNumber: TextStyle get() = styles.bigNumber
    val Number: TextStyle get() = styles.number
    val Title: TextStyle get() = styles.title
    val Heading: TextStyle get() = styles.heading
    val Body: TextStyle get() = styles.body
    val Button: TextStyle get() = styles.button
    val Label: TextStyle get() = styles.label
    val Unit: TextStyle get() = styles.unit
    val Wordmark: TextStyle get() = styles.wordmark
}

@Composable
fun MeridianTheme(content: @Composable () -> Unit) {
    val mode by Prefs.themeMode.collectAsStateWithLifecycle()
    val palette = mode.resolve(isSystemInDarkTheme())
    // Set before anything below reads MColors, so the first frame already has the right colours.
    MColors.apply(palette)

    // System bar icons and the window behind everything follow the theme.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !palette.dark
        controller.isAppearanceLightNavigationBars = !palette.dark
        window.setBackgroundDrawable(ColorDrawable(palette.bg.toArgb()))
    }

    val colors = if (palette.dark) darkColorScheme() else lightColorScheme()
    val scheme = colors.copy(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        secondary = palette.accent,
        onSecondary = palette.onAccent,
        background = palette.bg,
        onBackground = palette.text,
        surface = palette.surface,
        onSurface = palette.text,
        surfaceVariant = palette.surface2,
        onSurfaceVariant = palette.muted,
        outline = palette.stroke,
        outlineVariant = palette.hairline,
        error = palette.danger,
        // Every role mapped to Meridian colours, so pickers and dialogs never fall back to
        // Material's default purple.
        primaryContainer = palette.accentContainer,
        onPrimaryContainer = palette.accent,
        secondaryContainer = palette.surface2,
        onSecondaryContainer = palette.text,
        tertiary = palette.accent,
        onTertiary = palette.onAccent,
        tertiaryContainer = palette.surface2,
        onTertiaryContainer = palette.text,
        surfaceTint = Color.Transparent,
        surfaceBright = palette.surface2,
        surfaceDim = palette.bg,
        surfaceContainerLowest = palette.bg,
        surfaceContainerLow = palette.surface,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.surface2,
        surfaceContainerHighest = palette.stroke,
        inverseSurface = palette.text,
        inverseOnSurface = palette.bg,
        inversePrimary = palette.accentDim,
        scrim = Color.Black,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(bodyLarge = MType.Body, titleLarge = MType.Title),
        content = content,
    )
}
