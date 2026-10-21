package com.meridian.tracker

import com.meridian.tracker.tracking.ElevationTracker
import com.meridian.tracker.tracking.Geo
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackProcessor
import com.meridian.tracker.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TrackProcessorTest {

    // ~1 metre of latitude in degrees
    private val mLat = 1.0 / 111_195.0
    private val baseLat = 14.6507
    private val baseLon = 121.0490

    @Test
    fun haversineMatchesKnownDistance() {
        // 0.01 degrees of latitude is ~1112 m
        val d = Geo.distanceM(14.0, 121.0, 14.01, 121.0)
        assertEquals(1112.0, d, 2.0)
    }

    @Test
    fun steadyRunCountsDistanceTimeAndSplits() {
        val p = TrackProcessor(Sport.RUN)
        val speed = 3.0 // m/s = 5:33 /km
        for (t in 0..1200) { // 20 minutes, 1 fix per second
            p.onFix(baseLat + t * speed * mLat, baseLon, null, 5f, t * 1000L)
        }
        assertEquals(3600.0, p.distanceM, 40.0)
        assertEquals(1_200_000.0, p.movingMs.toDouble(), 5_000.0)
        assertEquals(3, p.splitsMs.size)
        p.splitsMs.forEach { assertEquals(333_333.0, it.toDouble(), 3_000.0) }
        assertEquals(3.0, p.currentSpeed, 0.1)
    }

    @Test
    fun stationaryJitterAddsAlmostNothing() {
        val p = TrackProcessor(Sport.RUN)
        val rnd = Random(7)
        for (t in 0..600) { // 10 minutes standing still with ±6 m noise
            val nLat = (rnd.nextGaussian() * 3) * mLat
            val nLon = (rnd.nextGaussian() * 3) * mLat
            p.onFix(baseLat + nLat, baseLon + nLon, null, 8f, t * 1000L)
        }
        assertTrue("jitter distance was ${p.distanceM}", p.distanceM < 30.0)
        assertTrue("moving time was ${p.movingMs}", p.movingMs < 30_000L)
    }

    @Test
    fun noisyRunAndWalkStayAccurate() {
        for ((sport, speed) in listOf(Sport.RUN to 3.0, Sport.WALK to 1.3, Sport.RIDE to 7.0)) {
            val p = TrackProcessor(sport)
            val rnd = Random(11)
            for (t in 0..1200) {
                val nLat = rnd.nextGaussian() * 2.5 * mLat
                val nLon = rnd.nextGaussian() * 2.5 * mLat
                p.onFix(baseLat + t * speed * mLat + nLat, baseLon + nLon, null, 6f, t * 1000L)
            }
            val truth = 1200 * speed
            val err = (p.distanceM - truth) / truth
            assertTrue("$sport distance ${p.distanceM} vs $truth", kotlin.math.abs(err) < 0.04)
            assertTrue("$sport moving ${p.movingMs}", p.movingMs > 1_100_000L)
        }
    }

    @Test
    fun spikesAreRejected() {
        val p = TrackProcessor(Sport.RUN)
        for (t in 0..100) {
            val lat = if (t == 50) baseLat + 500 * mLat else baseLat + t * 3.0 * mLat
            p.onFix(lat, baseLon, null, 5f, t * 1000L)
        }
        assertEquals(300.0, p.distanceM, 15.0)
    }

    @Test
    fun inaccurateFixesIgnored() {
        val p = TrackProcessor(Sport.WALK)
        assertTrue(!p.onFix(baseLat, baseLon, null, 50f, 0L))
        assertTrue(!p.onFix(baseLat, baseLon, null, 25f, 1000L)) // first fix needs <= 20 m
        assertTrue(p.onFix(baseLat, baseLon, null, 10f, 2000L))
    }

    @Test
    fun pauseStartsNewSegmentWithoutCountingGap() {
        val p = TrackProcessor(Sport.RUN)
        for (t in 0..100) p.onFix(baseLat + t * 3.0 * mLat, baseLon, null, 5f, t * 1000L)
        p.startNewSegment()
        // resume 500 m further away, 5 minutes later
        for (t in 0..100) p.onFix(baseLat + (800 + t * 3.0) * mLat, baseLon, null, 5f, 400_000L + t * 1000L)
        assertEquals(600.0, p.distanceM, 20.0)
        assertEquals(2, p.points.map { it.segment }.distinct().size)
    }

    @Test
    fun elevationIgnoresNoiseButCountsClimb() {
        val e = ElevationTracker.barometer()
        val rnd = Random(3)
        repeat(2000) { e.onAltitude(50 + rnd.nextGaussian() * 0.4, counting = true) } // flat, noisy
        assertTrue("flat gain ${e.gainM}", e.gainM < 3.0)
        for (i in 0..2000) e.onAltitude(50 + i * 0.02 + rnd.nextGaussian() * 0.4, counting = true) // +40 m
        assertEquals(40.0, e.gainM, 6.0)
    }

    @Test
    fun formatting() {
        assertEquals("4.27", Format.km(4271.3))
        assertEquals("24:18", Format.duration(1_458_000L))
        assertEquals("1:23:00", Format.duration(4_980_000L))
        assertEquals("5:41", Format.pace(341.0))
        assertEquals("–:––", Format.paceFromSpeed(0.0))
        assertEquals("21.6", Format.speedKmh(6.0))
    }
}
