package com.meridian.tracker

import com.meridian.tracker.share.StatLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatLayoutTest {

    /** A fake font: every character is 0.48 × the text size wide (close to the real bold digits). */
    private fun widths(values: List<String>, labelW: Float = 120f): (Float) -> List<Float> = { size ->
        values.map { maxOf(labelW, it.length * size * 0.48f) }
    }

    @Test
    fun shortValuesKeepEqualColumns() {
        val c = StatLayout.columns(72f, 936f, 100f, 60f, 44f, widths(listOf("24:18", "5:41", "38")))
        assertEquals(100f, c.size)
        assertEquals(listOf(72f, 384f, 696f), c.xs)
    }

    @Test
    fun longTimeNoLongerOverlapsPace() {
        // The screenshot: TIME 3:27:52 ran into PACE 12:53.
        val values = listOf("3:27:52", "12:53", "556")
        val c = StatLayout.columns(72f, 936f, 100f, 60f, 44f, widths(values))
        val w = widths(values)(c.size)
        for (i in 0 until values.size - 1) {
            assertTrue("column $i overlaps the next", c.xs[i] + w[i] + 44f <= c.xs[i + 1] + 0.01f)
        }
        assertTrue("runs off the image", c.xs.last() + w.last() <= 72f + 936f + 0.01f)
    }

    @Test
    fun veryLongValuesShrinkToFit() {
        val values = listOf("123:27:52", "112:53", "12,556")
        val c = StatLayout.columns(72f, 936f, 100f, 40f, 44f, widths(values))
        assertTrue(c.size < 100f)
        val w = widths(values)(c.size)
        assertTrue(c.xs.last() + w.last() <= 72f + 936f + 0.01f)
    }

    @Test
    fun fitShrinksOnlyWhenNeeded() {
        val measure = { s: Float -> 6 * s * 0.55f } // "123.45"
        assertEquals(100f, StatLayout.fit(100f, 50f, 1000f, measure))
        val fitted = StatLayout.fit(320f, 160f, 936f, measure)
        assertTrue(fitted < 320f && measure(fitted) <= 936f)
        assertEquals(160f, StatLayout.fit(320f, 160f, 10f, measure)) // never below the minimum
    }
}
