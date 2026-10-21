package com.meridian.tracker

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.Body
import com.meridian.tracker.data.BodySex
import com.meridian.tracker.data.Calories
import com.meridian.tracker.data.Sample
import com.meridian.tracker.tracking.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaloriesTest {

    private val t0 = 1_700_000_000_000L

    private fun record(sport: Sport, km: Double, minutes: Int, hr: Int? = null, hrMinutes: Int = minutes): ActivityRecord {
        val ms = minutes * 60_000L
        val samples = if (hr == null) emptyList() else (0..hrMinutes * 6).map { Sample(t0 + it * 10_000L, hr) }
        return ActivityRecord(
            id = "c", title = "t", sport = sport, startTimeMs = t0, distanceM = km * 1000, movingMs = ms, elapsedMs = ms,
            elevationGainM = 0.0, splitsMs = emptyList(), partialSplitM = 0.0, partialSplitMs = 0L, points = emptyList(),
            heartRate = samples,
        )
    }

    private val body70 = Body(weightKg = 70.0, weightKnown = true)

    @Test fun runningUsesAboutOneKcalPerKgPerKm() {
        // 10 km in 60 minutes at 70 kg: roughly 10 METs x 70 kg x 1 h = about 700 kcal.
        val e = Calories.estimate(record(Sport.RUN, 10.0, 60), body70)!!
        assertEquals(700.0, e.kcal.toDouble(), 60.0)
        assertEquals(Calories.Method.PACE, e.method)
    }

    @Test fun walkingBurnsLessThanRunningForTheSameTime() {
        val walk = Calories.estimate(record(Sport.WALK, 5.0, 60), body70)!!.kcal
        val run = Calories.estimate(record(Sport.RUN, 10.0, 60), body70)!!.kcal
        assertTrue(walk in 200..350)
        assertTrue(walk < run)
    }

    @Test fun fasterRidesBurnMore() {
        val easy = Calories.estimate(record(Sport.RIDE, 15.0, 60), body70)!!.kcal
        val fast = Calories.estimate(record(Sport.RIDE, 30.0, 60), body70)!!.kcal
        assertTrue(fast > easy * 2)
    }

    @Test fun heavierPeopleBurnMore() {
        val light = Calories.estimate(record(Sport.RUN, 10.0, 60), Body(55.0, true))!!.kcal
        val heavy = Calories.estimate(record(Sport.RUN, 10.0, 60), Body(90.0, true))!!.kcal
        assertTrue(heavy > light)
    }

    @Test fun heartRateIsUsedWhenAgeAndSexAreKnown() {
        val body = Body(70.0, true, birthYear = 1990, sex = BodySex.MALE)
        val e = Calories.estimate(record(Sport.RUN, 10.0, 60, hr = 155), body, year = 2026)!!
        assertEquals(Calories.Method.HEART_RATE, e.method)
        // Keytel: about 15.3 kcal a minute for a 36-year-old, 70 kg man at 155 bpm.
        assertEquals(916.0, e.kcal.toDouble(), 20.0)
    }

    @Test fun withoutAgeOrSexHeartRateFallsBackToPace() {
        val e = Calories.estimate(record(Sport.RUN, 10.0, 60, hr = 155), body70, year = 2026)!!
        assertEquals(Calories.Method.PACE, e.method)
    }

    @Test fun sparseHeartRateFallsBackToPace() {
        val body = Body(70.0, true, birthYear = 1990, sex = BodySex.FEMALE)
        // Heart rate for only 10 of 60 minutes.
        val e = Calories.estimate(record(Sport.RUN, 10.0, 60, hr = 150, hrMinutes = 10), body, year = 2026)!!
        assertEquals(Calories.Method.PACE, e.method)
    }

    @Test fun veryShortActivitiesGetNoEstimate() {
        assertNull(Calories.estimate(record(Sport.RUN, 0.1, 0), body70))
        assertNotNull(Calories.estimate(record(Sport.RUN, 0.4, 2), body70))
    }

    @Test fun absurdWeightsAreIgnored() {
        val a = Calories.estimate(record(Sport.RUN, 10.0, 60), Body(5.0, true))!!.kcal
        val b = Calories.estimate(record(Sport.RUN, 10.0, 60), Body())!!.kcal
        assertEquals(b, a)
    }
}
