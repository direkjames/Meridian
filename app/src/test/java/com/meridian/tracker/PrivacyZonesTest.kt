package com.meridian.tracker

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.PrivacyZone
import com.meridian.tracker.data.PrivacyZones
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyZonesTest {

    private val mLat = 1.0 / 111_195.0 // degrees of latitude per metre
    private val home = PrivacyZone("h", "Home", 14.6, 121.05, 300.0)

    /** A point [northM] metres north of home. */
    private fun at(northM: Double, i: Int) = TrackPoint(14.6 + northM * mLat, 121.05, null, 1_000L * i, 0)

    private fun record(points: List<TrackPoint>) = ActivityRecord(
        id = "r", title = "t", sport = Sport.RUN, startTimeMs = 0, distanceM = 5000.0, movingMs = 1_800_000, elapsedMs = 1_800_000,
        elevationGainM = 0.0, splitsMs = emptyList(), partialSplitM = 0.0, partialSplitMs = 0, points = points,
    )

    @Test fun insideUsesTheRadius() {
        assertTrue(PrivacyZones.inside(listOf(home), 14.6 + 250 * mLat, 121.05))
        assertTrue(!PrivacyZones.inside(listOf(home), 14.6 + 350 * mLat, 121.05))
        assertTrue(!PrivacyZones.inside(emptyList(), 14.6, 121.05))
    }

    @Test fun startAndEndInsideAreHidden() {
        // Leaves home, runs out to 2 km, comes back.
        val out = (0..20).map { at(it * 100.0, it) }
        val back = (21..40).map { at((40 - it) * 100.0, it) }
        val shown = PrivacyZones.hide(out + back, listOf(home.copy(radiusM = 350.0)))
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.all { !PrivacyZones.inside(listOf(home), it.lat, it.lon) })
        assertEquals(out[4], shown.first()) // 400 m is the first point past 350 m
        assertEquals(back[back.size - 5], shown.last()) // mirror image on the way home
    }

    @Test fun middleOfTheRouteInsideAZoneStays() {
        val start = (0..5).map { at(1000.0 + it, it) }
        val pass = (6..8).map { at(100.0, it) }  // passes through home mid-activity
        val end = (9..14).map { at(1000.0 + it, it) }
        val shown = PrivacyZones.hide(start + pass + end, listOf(home))
        assertEquals(start.size + pass.size + end.size, shown.size)
    }

    @Test fun anActivityEntirelyInsideAZoneShowsNoRoute() {
        val track = (0..30).map { at(it * 5.0, it) }
        assertTrue(PrivacyZones.hide(track, listOf(home)).isEmpty())
    }

    @Test fun tooLittleLeftMeansNoRoute() {
        val track = (0..10).map { at(50.0, it) } + listOf(at(2000.0, 11))
        assertTrue(PrivacyZones.hide(track, listOf(home)).isEmpty())
    }

    @Test fun noZonesLeavesTheRecordUntouched() {
        val r = record((0..20).map { at(it * 100.0, it) })
        assertSame(r, PrivacyZones.apply(r, emptyList()))
    }

    @Test fun applyKeepsTheNumbersAndOnlyTrimsThePoints() {
        val r = record((0..40).map { at(it * 100.0, it) })
        val shown = PrivacyZones.apply(r, listOf(home))
        assertEquals(r.distanceM, shown.distanceM, 0.0)
        assertEquals(r.movingMs, shown.movingMs)
        assertTrue(shown.points.size < r.points.size)
        assertEquals(41, r.points.size) // the original is not changed
    }

    @Test fun zonesAreValidatedAndTidied() {
        assertNull(PrivacyZones.clean(home.copy(lat = 120.0)))
        assertNull(PrivacyZones.clean(home.copy(lon = Double.NaN)))
        assertEquals(PrivacyZones.MIN_RADIUS_M, PrivacyZones.clean(home.copy(radiusM = 5.0))!!.radiusM, 0.0)
        assertEquals(PrivacyZones.MAX_RADIUS_M, PrivacyZones.clean(home.copy(radiusM = 99_999.0))!!.radiusM, 0.0)
        assertEquals("Private place", PrivacyZones.clean(home.copy(name = "   "))!!.name)
        assertEquals(PrivacyZones.MAX_NAME, PrivacyZones.clean(home.copy(name = "x".repeat(200)))!!.name.length)
        assertNotNull(PrivacyZones.clean(home))
    }
}
