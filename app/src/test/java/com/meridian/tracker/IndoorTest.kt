package com.meridian.tracker

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.Indoor
import com.meridian.tracker.data.io.RecordBuilder
import com.meridian.tracker.tracking.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndoorTest {

    private val t0 = 1_700_000_000_000L

    private fun record(
        sport: Sport = Sport.TREADMILL,
        km: Double = 0.0,
        minutes: Int = 30,
        steps: Int? = null,
        id: String = "i1",
        source: String? = null,
        start: Long = t0,
    ): ActivityRecord {
        val ms = minutes * 60_000L
        return ActivityRecord(
            id = id, title = "t", sport = sport, startTimeMs = start, distanceM = km * 1000, movingMs = ms, elapsedMs = ms,
            elevationGainM = 0.0, splitsMs = emptyList(), partialSplitM = 0.0, partialSplitMs = 0L, points = emptyList(),
            steps = steps, source = source,
        )
    }

    @Test fun treadmillHasNoGpsAndCountsAsRunning() {
        assertFalse(Sport.TREADMILL.usesGps)
        assertTrue(Sport.RUN.usesGps && Sport.HIKE.usesGps && Sport.RIDE.usesGps)
        assertTrue(Sport.TREADMILL.isRunning)
        assertEquals("Indoor", Sport.TREADMILL.short)
    }

    @Test fun importedNamesMapToTreadmill() {
        assertEquals(Sport.TREADMILL, RecordBuilder.sportFrom("Treadmill Running"))
        assertEquals(Sport.RUN, RecordBuilder.sportFrom("Running"))
    }

    @Test fun strideDefaultsWithoutHistory() {
        assertEquals(Indoor.DEFAULT_STRIDE_M, Indoor.stride(emptyList()), 1e-9)
        // Too few steps to trust.
        assertEquals(Indoor.DEFAULT_STRIDE_M, Indoor.stride(listOf(record(Sport.RUN, 5.0, 30, steps = 100))), 1e-9)
    }

    @Test fun strideIsTheMedianOfRecentRuns() {
        val runs = listOf(
            record(Sport.RUN, 5.0, 30, steps = 5000, id = "a", start = t0),       // 1.00
            record(Sport.RUN, 5.0, 30, steps = 4000, id = "b", start = t0 + 1),   // 1.25
            record(Sport.TRAIL_RUN, 5.0, 30, steps = 4545, id = "c", start = t0 + 2), // ~1.10
            record(Sport.RIDE, 20.0, 60, steps = 5000, id = "d", start = t0 + 3), // ignored
        )
        assertEquals(1.1, Indoor.stride(runs), 0.01)
    }

    @Test fun distanceFromStepsIsStepsTimesStride() {
        assertEquals(850.0, Indoor.distanceFromSteps(1000, 0.85), 1e-6)
        assertEquals(0.0, Indoor.distanceFromSteps(-5, 0.85), 1e-9)
    }

    @Test fun splitsAreEvenAndKeepTheRemainder() {
        val (splits, partialM, partialMs) = Indoor.splits(2500.0, 15 * 60_000L)
        assertEquals(listOf(360_000L, 360_000L), splits)
        assertEquals(500.0, partialM, 1e-6)
        assertEquals(180_000L, partialMs)
        assertTrue(Indoor.splits(0.0, 60_000L).first.isEmpty())
    }

    @Test fun enteringTheMachineDistanceUpdatesTheRecord() {
        val r = Indoor.withDistance(record(minutes = 30), 5_000.0)!!
        assertEquals(5_000.0, r.distanceM, 1e-6)
        assertEquals(5, r.splitsMs.size)
        assertEquals(360_000L, r.splitsMs[0])
        assertEquals(360.0, r.avgPaceSecPerKm!!, 0.5)
    }

    @Test fun rejectsImpossibleOrInvalidDistances() {
        val r = record(minutes = 30)
        assertNull(Indoor.withDistance(r, 0.0))
        assertNull(Indoor.withDistance(r, -3.0))
        assertNull(Indoor.withDistance(r, Double.NaN))
        assertNull(Indoor.withDistance(r, 80_000.0)) // 160 km/h
        assertNull(Indoor.withDistance(record(minutes = 0), 1_000.0))
    }

    @Test fun onlyRecordedIndoorSessionsCanBeEdited() {
        assertTrue(Indoor.canSetDistance(record()))
        assertFalse(Indoor.canSetDistance(record(sport = Sport.RUN)))
        assertFalse(Indoor.canSetDistance(record(source = "Fitbit")))
        assertFalse(Indoor.canSetDistance(record(id = "imp-1")))
        assertNull(Indoor.withDistance(record(sport = Sport.RUN), 5_000.0))
        assertNotNull(Indoor.withDistance(record(), 5_000.0))
    }
}
