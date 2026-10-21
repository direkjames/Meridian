package com.meridian.tracker

import com.meridian.tracker.data.ActivityAnalysis
import com.meridian.tracker.tracking.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityAnalysisTest {

    private val mLat = 1.0 / 111_195.0

    @Test
    fun profileOfSteadyClimbingRun() {
        // 2 km at 3 m/s, climbing 0.02 m per metre
        val pts = (0..666).map { i ->
            TrackPoint(14.0 + i * 3.0 * mLat, 121.0, alt = 10.0 + i * 3.0 * 0.02, time = i * 1000L, segment = 0)
        }
        val p = ActivityAnalysis.profile(pts, buckets = 50)
        assertEquals(50, p.samples.size)
        assertEquals(2000.0, p.totalM, 20.0)
        p.samples.forEach { s -> assertEquals(3.0, s.speed!!, 0.15) }
        assertEquals(3.0, p.maxSpeed, 0.15)
        assertEquals(10.0, p.minElevation!!, 2.0)
        assertEquals(50.0, p.maxElevation!!, 2.0)
        assertTrue(p.hasElevation)
    }

    @Test
    fun pauseGapIsNotCountedAsSlowSection() {
        val first = (0..200).map { i -> TrackPoint(14.0 + i * 3.0 * mLat, 121.0, null, i * 1000L, 0) }
        // 10 minute pause, then continue from the same spot
        val second = (0..200).map { i ->
            TrackPoint(14.0 + (600 + i * 3.0) * mLat, 121.0, null, 800_000L + i * 1000L, 1)
        }
        val p = ActivityAnalysis.profile(first + second, buckets = 40)
        assertEquals(1200.0, p.totalM, 15.0)
        p.samples.mapNotNull { it.speed }.forEach { assertEquals(3.0, it, 0.2) }
        assertTrue(!p.hasElevation)
    }

    @Test
    fun tinyActivityHasNoProfile() {
        val pts = listOf(TrackPoint(14.0, 121.0, null, 0, 0), TrackPoint(14.0 + 5 * mLat, 121.0, null, 2000, 0))
        assertTrue(ActivityAnalysis.profile(pts).samples.isEmpty())
    }
}
