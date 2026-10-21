package com.meridian.tracker

import com.meridian.tracker.data.LazyTrack
import com.meridian.tracker.tracking.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyTrackTest {

    private fun pts(n: Int) = List(n) { TrackPoint(it.toDouble(), 0.0, null, it * 1000L, 0) }

    @Test
    fun sizeIsKnownWithoutLoading() {
        var loads = 0
        val t = LazyTrack(3) { loads++; pts(3) }
        assertEquals(3, t.size)
        assertEquals(0, loads)
    }

    @Test
    fun loadsOnceAndServesPoints() {
        var loads = 0
        val t = LazyTrack(3) { loads++; pts(3) }
        assertEquals(1.0, t[1].lat, 0.0)
        assertEquals(3, t.map { it.lat }.size)
        assertEquals(1, loads)
    }

    @Test
    fun unreadableFileLooksEmptyInsteadOfCrashing() {
        val t = LazyTrack(5) { throw java.io.IOException("gone") }
        // Walking the route is what triggers the read; afterwards the route simply looks empty.
        assertEquals(emptyList<TrackPoint>(), t.toList())
        assertTrue(t.isEmpty())
        assertEquals(0, t.size)
    }

    @Test
    fun equalityDoesNotReadTheFile() {
        var loads = 0
        val t = LazyTrack(2) { loads++; pts(2) }
        assertTrue(t == t)
        assertFalse(t == LazyTrack(2) { pts(2) })
        assertEquals(0, loads)
    }
}
