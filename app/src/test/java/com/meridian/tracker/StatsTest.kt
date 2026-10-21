package com.meridian.tracker

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.BestKind
import com.meridian.tracker.data.Period
import com.meridian.tracker.data.Stats
import com.meridian.tracker.data.TileMath
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class StatsTest {

    private val zone: ZoneId = ZoneOffset.ofHours(8)
    private val mLat = 1.0 / 111_195.0

    private fun ms(date: LocalDate, hour: Int = 7) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** A straight run at a steady [speed] m/s, one point per second. */
    private fun run(
        id: String,
        date: LocalDate,
        distanceM: Double,
        speed: Double = 3.0,
        sport: Sport = Sport.RUN,
        climb: Double = 0.0,
        hour: Int = 7,
    ): ActivityRecord {
        val start = ms(date, hour)
        val secs = (distanceM / speed).toInt()
        val pts = (0..secs).map { s -> TrackPoint(14.0 + s * speed * mLat, 121.0, null, start + s * 1000L, 0) }
        return ActivityRecord(
            id = id, title = id, sport = sport, startTimeMs = start, distanceM = distanceM,
            movingMs = secs * 1000L, elapsedMs = secs * 1000L, elevationGainM = climb,
            splitsMs = emptyList(), partialSplitM = 0.0, partialSplitMs = 0L, points = pts,
        )
    }

    @Test
    fun bestEffortFindsFastestWindow() {
        // 2 km at 3 m/s, then 2 km at 4 m/s: fastest 1K should be 250 s
        val start = 0L
        val pts = ArrayList<TrackPoint>()
        var t = start
        var d = 0.0
        while (d <= 2000) { pts += TrackPoint(14.0 + d * mLat, 121.0, null, t, 0); d += 3.0; t += 1000 }
        while (d <= 4000) { pts += TrackPoint(14.0 + d * mLat, 121.0, null, t, 0); d += 4.0; t += 1000 }
        val best = Stats.bestEffortMs(pts, 1000.0)!!
        assertEquals(250_000.0, best.toDouble(), 3_000.0)
        assertNull(Stats.bestEffortMs(pts, 5000.0))
    }

    @Test
    fun bestEffortIgnoresPauses() {
        val a = (0..400).map { s -> TrackPoint(14.0 + s * 3.0 * mLat, 121.0, null, s * 1000L, 0) }
        // resume after a 10-minute pause
        val b = (1..400).map { s -> TrackPoint(14.0 + (1200 + s * 3.0) * mLat, 121.0, null, 1_000_000L + s * 1000L, 1) }
        val best = Stats.bestEffortMs(a + b, 1000.0)!!
        assertEquals(333_000.0, best.toDouble(), 4_000.0)
    }

    @Test
    fun monthSummaryAndComparison() {
        val oct = LocalDate.of(2026, 10, 1)
        val records = listOf(
            run("a", LocalDate.of(2026, 10, 3), 5000.0),
            run("b", LocalDate.of(2026, 10, 3), 3000.0),
            run("c", LocalDate.of(2026, 10, 20), 10000.0),
            run("d", LocalDate.of(2026, 9, 15), 9000.0),
        )
        val s = Stats.summarize(records, Period.MONTH, oct, zone, Locale.US)
        assertEquals(31, s.buckets.size)
        assertEquals(18000.0, s.distanceM, 1.0)
        assertEquals(3, s.count)
        assertEquals(2, s.activeDays)
        assertEquals(8000.0, s.buckets[2].distanceM, 1.0)
        assertEquals(1.0, s.change!!, 0.001) // 18 km vs 9 km = +100%
    }

    @Test
    fun yearBucketsAreMonths() {
        val records = listOf(run("a", LocalDate.of(2026, 3, 9), 5000.0), run("b", LocalDate.of(2026, 8, 1), 7000.0))
        val s = Stats.summarize(records, Period.YEAR, LocalDate.of(2026, 1, 1), zone, Locale.US)
        assertEquals(12, s.buckets.size)
        assertEquals(5000.0, s.buckets[2].distanceM, 1.0)
        assertEquals("August", s.buckets[7].fullLabel)
    }

    @Test
    fun weekStartsMonday() {
        assertEquals(LocalDate.of(2026, 10, 12), Stats.periodStart(Period.WEEK, LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun streaks() {
        val days = listOf(1, 2, 3, 5, 6).map { run("d$it", LocalDate.of(2026, 10, it), 2000.0) }
        assertEquals(3, Stats.longestDayStreak(days, zone))
        val weeks = listOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 6)).mapIndexed { i, d -> run("w$i", d, 2000.0) }
        // Today is Tue Oct 13 with no activity yet this week: streak counts last two weeks.
        assertEquals(2, Stats.currentWeekStreak(weeks, LocalDate.of(2026, 10, 13), zone))
    }

    @Test
    fun personalBestsAndNewBests() {
        val first = run("first", LocalDate.of(2026, 10, 1), 6000.0, speed = 3.0)
        val second = run("second", LocalDate.of(2026, 10, 8), 5500.0, speed = 3.4, climb = 40.0)
        val all = listOf(first, second)

        val bests = Stats.personalBests(all, Sport.RUN)
        val fiveK = bests.first { it.spec.key == "effort-5000" }
        assertEquals("second", fiveK.recordId)
        assertEquals("first", bests.first { it.spec.kind == BestKind.DISTANCE }.recordId)

        assertTrue(Stats.newBestsFor(first, all).isEmpty()) // first-ever run: no trophies
        val newOnes = Stats.newBestsFor(second, all).map { it.spec.key }
        assertTrue("effort-5000" in newOnes)
        assertTrue("climb" in newOnes)
        assertTrue("distance" !in newOnes)
    }

    @Test
    fun reviewPicksHighlights() {
        val records = listOf(
            run("a", LocalDate.of(2026, 3, 2), 5000.0, hour = 6),
            run("b", LocalDate.of(2026, 3, 9), 8000.0, hour = 6),
            run("c", LocalDate.of(2026, 8, 3), 12000.0, hour = 19),
            run("r", LocalDate.of(2026, 8, 4), 30000.0, speed = 7.0, sport = Sport.RIDE, hour = 6),
        )
        val r = Stats.review(records, Period.YEAR, LocalDate.of(2026, 1, 1), zone, Locale.US)
        assertEquals(4, r.summary.count)
        assertEquals("r", r.longest!!.id)
        assertEquals(Sport.RUN, r.favoriteSport)
        assertEquals("morning", r.partOfDay)
        assertEquals("August", r.peakBucket!!.fullLabel)
        assertNotNull(r.favoriteWeekday)
        assertEquals(55000.0 / 42195.0, r.marathons, 0.001)
    }

    @Test
    fun tileCountIsSane() {
        // A roughly 5 x 5 km area over Quezon City at zoom 10..15
        val n = TileMath.tileCount(14.68, 14.63, 121.08, 121.03, 10, 15)
        assertTrue("tiles $n", n in 30..80)
    }
}
