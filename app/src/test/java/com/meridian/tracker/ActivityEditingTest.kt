package com.meridian.tracker

import com.meridian.tracker.data.ActivityEditing
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.Sample
import com.meridian.tracker.data.io.RecordBuilder
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityEditingTest {

    private val mLat = 1.0 / 111_195.0
    private val t0 = 1_700_000_000_000L

    /** [n] seconds of running due north at [speed] m/s, one point per second, 100 m of climb per km. */
    private fun record(n: Int = 600, speed: Double = 3.0, sport: Sport = Sport.RUN, segmentAt: Int? = null): ActivityRecord {
        val pts = (0 until n).map { i ->
            TrackPoint(14.6 + i * speed * mLat, 121.05, 10.0 + i * speed * 0.1, t0 + i * 1000L, if (segmentAt != null && i >= segmentAt) 1 else 0)
        }
        val m = ActivityEditing.measure(pts, sport, recorded = true)
        return ActivityRecord(
            id = "r1", title = "Morning Run", sport = sport, startTimeMs = t0,
            distanceM = m.distanceM, movingMs = m.movingMs, elapsedMs = (n - 1) * 1000L, elevationGainM = m.gainM ?: 0.0,
            splitsMs = m.splitsMs, partialSplitM = m.partialSplitM, partialSplitMs = m.partialSplitMs, points = pts,
            heartRate = pts.filterIndexed { i, _ -> i % 10 == 0 }.map { Sample(it.time, 140) },
            steps = 1_000,
        )
    }

    @Test fun trimmingTheEndsShortensDistanceAndTime() {
        val r = record()
        val t = ActivityEditing.trim(r, t0 + 100_000L, t0 + 400_000L)
        assertNotNull(t)
        assertEquals(900.0, t!!.distanceM, 15.0) // 300 s at 3 m/s
        assertEquals(300_000L, t.elapsedMs)
        assertEquals(t0 + 100_000L, t.startTimeMs)
        assertTrue(t.distanceM < r.distanceM)
        assertTrue(t.points.first().time >= t0 + 100_000L && t.points.last().time <= t0 + 400_000L)
    }

    @Test fun splitsAreRebuiltForTheKeptPart() {
        val t = ActivityEditing.trim(record(900), t0 + 50_000L, t0 + 850_000L)!!
        // 800 s at 3 m/s = 2.4 km: two full kilometres and a partial one.
        assertEquals(2, t.splitsMs.size)
        assertEquals(400.0, t.partialSplitM, 20.0)
    }

    @Test fun heartRateAndStepsFollowTheTrim() {
        val r = record()
        val t = ActivityEditing.trim(r, t0 + 100_000L, t0 + 300_000L)!!
        assertTrue(t.heartRate.all { it.time in (t0 + 100_000L)..(t0 + 300_000L) })
        assertTrue(t.heartRate.isNotEmpty())
        // Steps scale with moving time: about a third of the activity is kept.
        assertEquals(333.0, t.steps!!.toDouble(), 15.0)
    }

    @Test fun aPauseInTheKeptPartIsNotCountedAsMovement() {
        val r = record(600, segmentAt = 300)
        val t = ActivityEditing.trim(r, t0, t0 + 599_000L)!!
        // Segment change at 300 s: one second of travel (3 m) is not counted across it.
        assertEquals(r.distanceM, t.distanceM, 0.5)
        assertTrue(t.distanceM < 599 * 3.0)
    }

    @Test fun tooLittleLeftIsRefused() {
        val r = record()
        assertNull(ActivityEditing.trim(r, t0, t0))                 // one point
        assertNull(ActivityEditing.trim(r, t0 + 1_000_000L, t0 + 2_000_000L)) // nothing in range
        assertNull(ActivityEditing.trim(r, t0, t0 + 2_000L))        // 6 m: not an activity
    }

    @Test fun restoreBringsBackTheRouteButKeepsOtherEdits() {
        val original = record()
        val trimmed = ActivityEditing.trim(original, t0 + 100_000L, t0 + 300_000L)!!
            .copy(title = "Tempo", note = "felt great")
        val back = ActivityEditing.restoreTrim(trimmed, original)
        assertEquals(original.distanceM, back.distanceM, 0.001)
        assertEquals(original.points.size, back.points.size)
        assertEquals(original.startTimeMs, back.startTimeMs)
        assertEquals("Tempo", back.title)
        assertEquals("felt great", back.note)
    }

    @Test fun changingSportRenamesAutomaticTitlesOnly() {
        val r = record().copy(title = RecordBuilder.defaultTitle(Sport.RUN, t0))
        val ride = ActivityEditing.changeSport(r, Sport.RIDE)
        assertEquals(Sport.RIDE, ride.sport)
        assertEquals(RecordBuilder.defaultTitle(Sport.RIDE, t0), ride.title)
        assertEquals(r.distanceM, ride.distanceM, 0.0)

        val named = r.copy(title = "Parkrun PB attempt")
        assertEquals("Parkrun PB attempt", ActivityEditing.changeSport(named, Sport.WALK).title)
    }
}
