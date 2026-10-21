package com.meridian.tracker

import androidx.compose.ui.graphics.Color
import com.meridian.tracker.ui.theme.DarkPalette
import com.meridian.tracker.ui.theme.LightPalette
import com.meridian.tracker.ui.theme.Palette
import com.meridian.tracker.ui.theme.ThemeMode
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ThemeTest {

    private fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    /** WCAG contrast ratio between two colours. */
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> if (la >= lb) la to lb else lb to la } }
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun backgrounds(p: Palette) = listOf(p.bg, p.surface, p.surface2)

    @Test fun textStaysReadableOnEverySurfaceInBothThemes() {
        for (p in listOf(DarkPalette, LightPalette)) {
            for (bg in backgrounds(p)) {
                assertTrue("text", contrast(p.text, bg) >= 7.0)
                assertTrue("muted", contrast(p.muted, bg) >= 4.5)
                assertTrue("accent", contrast(p.accent, bg) >= 4.5)
                assertTrue("danger", contrast(p.danger, bg) >= 4.5)
                assertTrue("faint", contrast(p.faint, bg) >= 2.8)
            }
        }
    }

    @Test fun buttonLabelsAreReadableOnTheAccent() {
        for (p in listOf(DarkPalette, LightPalette)) assertTrue(contrast(p.onAccent, p.accent) >= 4.5)
    }

    @Test fun modeChoosesThePalette() {
        assertSame(DarkPalette, ThemeMode.DARK.resolve(systemDark = false))
        assertSame(LightPalette, ThemeMode.LIGHT.resolve(systemDark = true))
        assertSame(DarkPalette, ThemeMode.SYSTEM.resolve(systemDark = true))
        assertSame(LightPalette, ThemeMode.SYSTEM.resolve(systemDark = false))
    }

    @Test fun lightAndDarkAreFlaggedCorrectly() {
        assertTrue(DarkPalette.dark)
        assertTrue(!LightPalette.dark)
    }
}
