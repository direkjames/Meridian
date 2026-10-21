package com.meridian.tracker

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.Coach
import com.meridian.tracker.data.Effort
import com.meridian.tracker.data.Fitness
import com.meridian.tracker.data.Gear
import com.meridian.tracker.data.GearType
import com.meridian.tracker.data.Readiness
import com.meridian.tracker.data.TipKind
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class CoachTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 21) // a Wednesday
    private val noon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun run(daysAgo: Int, meters: Double = 8_000.0, speed: Double = 3.0, hour: Int = 7, sport: Sport = Sport.RUN, gear: List<String> = emptyList()): ActivityRecord {
        val start = today.minusDays(daysAgo.toLong()).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val secs = (meters / speed).toLong()
        val pts = (0..secs.toInt().coerceAtMost(5)).map { TrackPoint(14.6, 121.05, null, start + it * 1000L, 0) }
        return ActivityRecord("r$daysAgo-$hour-$meters", "run", sport, start, meters, secs * 1000, secs * 1000, 0.0, emptyList(), 0.0, 0L, pts, gearIds = gear)
    }

    private fun advise(records: List<ActivityRecord>, gear: List<Gear> = emptyList(), seed: Int = 0, now: Long = noon, imperial: Boolean = false) =
        Coach.advise(
            records = records,
            days = Fitness.series(records, today, zone),
            weekly = Fitness.weeklyLoads(Fitness.series(records, today, zone), today, 8),
            gear = gear,
            thresholds = Effort.thresholds(records),
            nowMs = now,
            zone = zone,
            imperial = imperial,
            seed = seed,
            locale = Locale.ENGLISH,
        )

    @Test fun newUserGetsWelcomeNotCrash() {
        val a = advise(emptyList())
        assertEquals(Readiness.NEW, a.readiness)
        assertTrue(a.headline.isNotBlank() && a.detail.isNotBlank())
        assertTrue(a.tips.isNotEmpty())
    }

    @Test fun onlyFutureActivitiesAreTreatedAsNoHistory() {
        val a = advise(listOf(run(daysAgo = -3)))
        assertEquals(Readiness.NEW, a.readiness)
    }

    @Test fun hardSessionJustNowMeansRest() {
        val r = run(0, meters = 20_000.0, speed = 3.6, hour = 9)
        val a = advise(listOf(r), now = r.startTimeMs + r.elapsedMs + 3_600_000L)
        assertEquals(Readiness.REST, a.readiness)
        assertTrue(a.restLeft.startsWith("~"))
        assertTrue(a.headline.contains("Rest") || a.headline.contains("rest") || a.headline.contains("Recovery") || a.headline.contains("body"))
    }

    @Test fun recoveryHoursGrowWithLoadAndTiredness() {
        assertTrue(Coach.recoveryHours(20.0, 0.0) < Coach.recoveryHours(80.0, 0.0))
        assertTrue(Coach.recoveryHours(80.0, 0.0) < Coach.recoveryHours(200.0, 0.0))
        assertTrue(Coach.recoveryHours(80.0, -20.0) > Coach.recoveryHours(80.0, 0.0))
        assertTrue(Coach.recoveryHours(80.0, 25.0) < Coach.recoveryHours(80.0, 0.0))
        assertTrue(Coach.recoveryHours(1e9, -100.0) <= 96.0)
        assertTrue(Coach.recoveryHours(0.0, 100.0) >= 6.0)
    }

    @Test fun restedAfterLongBreakIsReadyAndWarnsNothing() {
        val a = advise(listOf(run(6), run(7), run(9), run(11), run(13)))
        assertEquals("Ready", a.restLeft)
        assertEquals("Now", a.nextStart)
        assertTrue(a.readiness == Readiness.GO || a.readiness == Readiness.STEADY)
    }

    @Test fun longGapSuggestsComeback() {
        val a = advise(listOf(run(10), run(12), run(14)))
        assertTrue(a.tips.any { it.kind == TipKind.COMEBACK })
    }

    @Test fun overreachingWarns() {
        val records = (0..13).map { run(it, meters = 16_000.0, speed = 3.5) }
        val a = advise(records)
        assertTrue(a.tips.any { it.kind == TipKind.OVERREACH })
        assertTrue(a.tips.any { it.kind == TipKind.REST_DAY })
    }

    @Test fun rampUpIsFlagged() {
        val easy = (7..27 step 3).map { run(it, meters = 4_000.0) }
        val burst = listOf(run(0, 22_000.0, 3.5), run(1, 22_000.0, 3.5), run(2, 22_000.0, 3.5))
        val a = advise(easy + burst)
        assertTrue(a.tips.any { it.kind == TipKind.RAMP_UP || it.kind == TipKind.OVERREACH })
    }

    @Test fun streakIsPraised() {
        val a = advise((0..3).map { run(it, meters = 3_000.0) })
        assertTrue(a.tips.any { it.kind == TipKind.STREAK })
    }

    @Test fun currentDayStreakCountsFromTodayOrYesterday() {
        assertEquals(0, Coach.currentDayStreak(emptyList(), today, zone))
        assertEquals(3, Coach.currentDayStreak(listOf(run(1), run(2), run(3), run(6)), today, zone))
        assertEquals(1, Coach.currentDayStreak(listOf(run(0), run(2)), today, zone))
        assertEquals(0, Coach.currentDayStreak(listOf(run(2)), today, zone))
    }

    @Test fun nearlyWornShoesAreMentionedWithDistanceLeft() {
        val shoe = Gear("g1", "Pegasus 40", GearType.SHOES, limitM = 100_000.0, createdMs = 0L)
        val records = (1..12).map { run(it * 2, meters = 8_000.0, gear = listOf("g1")) } // 96 km
        val a = advise(records, listOf(shoe))
        val tip = a.tips.first { it.kind == TipKind.GEAR }
        assertTrue(tip.text.contains("Pegasus 40"))
        assertTrue(tip.text.contains("4 km"))
    }

    @Test fun wornOutAndRetiredGear() {
        val shoe = Gear("g1", "Old Nikes", GearType.SHOES, limitM = 50_000.0, createdMs = 0L)
        val records = (1..8).map { run(it * 3, meters = 8_000.0, gear = listOf("g1")) }
        assertTrue(advise(records, listOf(shoe)).tips.any { it.kind == TipKind.GEAR })
        assertFalse(advise(records, listOf(shoe.copy(retired = true))).tips.any { it.kind == TipKind.GEAR })
    }

    @Test fun imperialUnitsInGearTip() {
        val shoe = Gear("g1", "Trail", GearType.SHOES, limitM = 100_000.0, createdMs = 0L)
        val records = (1..12).map { run(it * 2, meters = 8_000.0, gear = listOf("g1")) }
        assertTrue(advise(records, listOf(shoe), imperial = true).tips.first { it.kind == TipKind.GEAR }.text.contains("mi"))
    }

    @Test fun hydrationAfterRecentSession() {
        val r = run(0, meters = 12_000.0, hour = 10)
        val a = advise(listOf(r), now = r.startTimeMs + r.elapsedMs + 20 * 60_000L)
        assertTrue(a.tips.any { it.kind == TipKind.HYDRATION })
    }

    @Test fun phrasingVariesWithSeedButIsStableForSameSeed() {
        val records = listOf(run(5), run(7), run(9), run(11), run(13))
        val texts = (0..5).map { advise(records, seed = it).detail }.toSet()
        assertTrue("expected several phrasings, got ${texts.size}", texts.size >= 2)
        assertEquals(advise(records, seed = 4), advise(records, seed = 4))
        assertNotEquals(advise(records, seed = 0).detail, advise(records, seed = 1).detail)
    }

    @Test fun tipsAreCappedAndNeverBlank() {
        val records = (0..20).map { run(it, meters = 15_000.0, speed = 3.4) }
        for (seed in 0..8) {
            val a = advise(records, seed = seed)
            assertTrue(a.tips.size <= Coach.MAX_TIPS)
            assertTrue(a.tips.all { it.text.isNotBlank() })
            assertTrue(a.headline.isNotBlank() && a.detail.isNotBlank() && a.nextStart.isNotBlank())
        }
    }

    @Test fun lateNightReadyTimeRollsToMorning() {
        val r = run(0, meters = 20_000.0, speed = 3.6, hour = 20)
        val a = advise(listOf(r), now = r.startTimeMs + r.elapsedMs + 1_000L)
        assertFalse(a.nextStart.contains("night"))
    }

    @Test fun weirdDataDoesNotCrash() {
        val bad = run(1).copy(movingMs = 0, elapsedMs = -5, distanceM = Double.NaN)
        val zero = run(2).copy(distanceM = 0.0, movingMs = 0, elapsedMs = 0)
        advise(listOf(bad, zero))
        advise(listOf(bad))
    }
}
