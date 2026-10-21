package com.meridian.tracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.meridian.tracker.R

/** Meridian palette: near-black instrument panel with a single warm yellow accent. */
object MColors {
    val Bg = Color(0xFF0B0C0E)
    val Surface = Color(0xFF141518)
    val Surface2 = Color(0xFF1C1E22)
    val Stroke = Color(0xFF2A2D32)
    val Hairline = Color(0xFF202227)
    val Text = Color(0xFFF4F2EA)
    val Muted = Color(0xFF9A9A93)
    val Faint = Color(0xFF64655F)
    val Accent = Color(0xFFFFE566)
    val AccentDim = Color(0xFF8C7E38)
    val OnAccent = Color(0xFF0B0C0E)
    val Danger = Color(0xFFFF8A73)
    val Bar = Color(0xFF3C3F45)
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
    val Hero = TextStyle(
        fontFamily = Instrument, fontWeight = FontWeight.Bold, fontSize = 88.sp,
        lineHeight = 88.sp, letterSpacing = (-3.5).sp, fontFeatureSettings = "tnum", color = MColors.Text,
    )
    val BigNumber = TextStyle(
        fontFamily = Instrument, fontWeight = FontWeight.Bold, fontSize = 30.sp,
        lineHeight = 34.sp, letterSpacing = (-0.8).sp, fontFeatureSettings = "tnum", color = MColors.Text,
    )
    val Number = TextStyle(
        fontFamily = Instrument, fontWeight = FontWeight.Bold, fontSize = 17.sp,
        lineHeight = 22.sp, fontFeatureSettings = "tnum", color = MColors.Text,
    )
    val Title = TextStyle(
        fontFamily = Instrument, fontWeight = FontWeight.Bold, fontSize = 30.sp,
        lineHeight = 36.sp, letterSpacing = (-0.6).sp, color = MColors.Text,
    )
    val Heading = TextStyle(
        fontFamily = Instrument, fontWeight = FontWeight.Bold, fontSize = 18.sp,
        lineHeight = 24.sp, color = MColors.Text,
    )
    val Body = TextStyle(
        fontFamily = Instrument, fontWeight = FontWeight.Normal, fontSize = 15.sp,
        lineHeight = 22.sp, color = MColors.Muted,
    )
    val Button = TextStyle(
        fontFamily = Instrument, fontWeight = FontWeight.Bold, fontSize = 16.sp,
        lineHeight = 20.sp, letterSpacing = 0.2.sp,
    )
    /** Small uppercase instrument labels. */
    val Label = TextStyle(
        fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 11.sp,
        lineHeight = 14.sp, letterSpacing = 1.4.sp, color = MColors.Muted,
    )
    val Unit = TextStyle(
        fontFamily = Instrument, fontWeight = FontWeight.Normal, fontSize = 15.sp,
        lineHeight = 20.sp, color = MColors.Muted,
    )
    val Wordmark = TextStyle(
        fontFamily = GeistMono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        letterSpacing = 4.sp, color = MColors.Text,
    )
}

@Composable
fun MeridianTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = MColors.Accent,
        onPrimary = MColors.OnAccent,
        secondary = MColors.Accent,
        onSecondary = MColors.OnAccent,
        background = MColors.Bg,
        onBackground = MColors.Text,
        surface = MColors.Surface,
        onSurface = MColors.Text,
        surfaceVariant = MColors.Surface2,
        onSurfaceVariant = MColors.Muted,
        outline = MColors.Stroke,
        outlineVariant = MColors.Hairline,
        error = MColors.Danger,
        // Every role mapped to Meridian colours, so pickers and dialogs never fall back to
        // Material's default purple.
        primaryContainer = Color(0xFF3A3420),
        onPrimaryContainer = MColors.Accent,
        secondaryContainer = MColors.Surface2,
        onSecondaryContainer = MColors.Text,
        tertiary = MColors.Accent,
        onTertiary = MColors.OnAccent,
        tertiaryContainer = MColors.Surface2,
        onTertiaryContainer = MColors.Text,
        surfaceTint = Color.Transparent,
        surfaceBright = MColors.Surface2,
        surfaceDim = MColors.Bg,
        surfaceContainerLowest = MColors.Bg,
        surfaceContainerLow = MColors.Surface,
        surfaceContainer = MColors.Surface,
        surfaceContainerHigh = MColors.Surface2,
        surfaceContainerHighest = MColors.Stroke,
        inverseSurface = MColors.Text,
        inverseOnSurface = MColors.Bg,
        inversePrimary = MColors.AccentDim,
        scrim = Color.Black,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(bodyLarge = MType.Body, titleLarge = MType.Title),
        content = content,
    )
}
