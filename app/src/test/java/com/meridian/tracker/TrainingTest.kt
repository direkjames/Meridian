package com.meridian.tracker

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.Badges
import com.meridian.tracker.data.DayStatus
import com.meridian.tracker.data.Effort
import com.meridian.tracker.data.Fitness
import com.meridian.tracker.data.Goal
import com.meridian.tracker.data.GoalMetric
import com.meridian.tracker.data.Goals
import com.meridian.tracker.data.Period
import com.meridian.tracker.data.PlanConfig
import com.meridian.tracker.data.PlanGenerator
import com.meridian.tracker.data.PlanMode
import com.meridian.tracker.data.RacePredictor
import com.meridian.tracker.data.RouteMatch
import com.meridian.tracker.data.SessionKind
import com.meridian.tracker.data.TargetType
import com.meridian.tracker.tracking.GhostTracker
import com.meridian.tracker.tracking.RouteFollower
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class TrainingTest {

    private val zone: ZoneId = ZoneOffset.ofHours(8)
    private val mLat = 1.0 / 111_195.0

    private fun activity(
        id: String,
        date: LocalDate,
        meters: Double,
        speed: Double = 3.0,
        sport: Sport = Sport.RUN,
        hour: Int = 7,
        lonOffset: Double = 0.0,
    ): ActivityRecord {
        val start = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val secs = (meters / speed).toInt()
        val pts = (0..secs).map { s -> TrackPoint(14.6 + s * speed * mLat, 121.05 + lonOffset, null, start + s * 1000L, 0) }
        return ActivityRecord(id, id, sport, start, meters, secs * 1000L, secs * 1000L, 0.0, emptyList(), 0.0, 0L, pts)
    }

    // ---- Plans -------------------------------------------------------------------------------

    private fun config(
        mode: PlanMode = PlanMode.RUN_WALK,
        event: Double = 10_000.0,
        weeks: Long = 8,
        days: Int = 4,
        target: TargetType = TargetType.DISTANCE,
    ) = PlanConfig(
        name = "Fun Run 10K",
        mode = mode,
        targetType = target,
        eventDistanceM = event,
        startDate = LocalDate.of(2026, 10, 12),
        eventDate = LocalDate.of(2026, 10, 12).plusWeeks(weeks).plusDays(6),
        daysPerWeek = days,
        longDay = DayOfWeek.SUNDAY,
        currentLongM = 3_000.0,
        paceSecPerKm = PlanGenerator.defaultPace(mode),
    )

    @Test
    fun planHasRightShape() {
        val cfg = config()
        val plan = PlanGenerator.generate(cfg, "p", 0L)
        assertEquals(cfg.startDate, plan.days.first().date)
        assertEquals(cfg.eventDate, plan.days.last().date)
        assertEquals(SessionKind.RACE, plan.days.last().kind)
        assertEquals(cfg.eventDistanceM, plan.days.last().distanceM, 1.0)
        assertFalse(plan.compressed)

        // Every full week has the chosen number of sessions and the day before the long day is rest.
        plan.days.groupBy { it.week }.filterKeys { it < plan.weeks - 1 }.forEach { (_, week) ->
            if (week.size == 7) assertEquals(4, week.count { !it.isRest })
            week.filter { it.kind == SessionKind.LONG }.forEach { long ->
                val dayBefore = plan.day(long.date.minusDays(1))
                assertTrue(dayBefore == null || dayBefore.isRest)
            }
        }
        // Long sessions grow, never jump more than ~12% a week, and include lighter weeks.
        val longs = plan.days.filter { it.kind == SessionKind.LONG }.map { it.distanceM }
        assertTrue(longs.max() > longs.first())
        // Compared with the best of the previous two weeks, so returning from a recovery week is allowed.
        longs.windowed(3).forEach { (a, b, c) -> assertTrue("$a, $b -> $c", c <= maxOf(a, b) * 1.22 + 100) }
        assertTrue(longs.zipWithNext().any { (a, b) -> b < a })
        // No back-to-back hard sessions: the day before the event is rest.
        assertTrue(plan.day(cfg.eventDate.minusDays(1))!!.isRest)
    }

    @Test
    fun runWalkPlanUsesRunWalkSessions() {
        val plan = PlanGenerator.generate(config(mode = PlanMode.RUN_WALK), "p", 0L)
        assertTrue(plan.days.any { it.kind == SessionKind.RUN_WALK })
        assertTrue(plan.days.none { it.kind == SessionKind.TEMPO || it.kind == SessionKind.INTERVALS })
        assertTrue(plan.days.first { it.kind == SessionKind.LONG }.detail.contains("walk"))
    }

    @Test
    fun timeTargetsAndCompressedPlans() {
        val plan = PlanGenerator.generate(config(mode = PlanMode.RUN, target = TargetType.TIME, event = 21_097.5, weeks = 3), "p", 0L)
        assertTrue(plan.compressed)
        assertTrue(plan.sessions.all { it.minutes >= 10 })
    }

    @Test
    fun statusAndAdaptation() {
        val cfg = config(mode = PlanMode.RUN_WALK)
        val plan = PlanGenerator.generate(cfg, "p", 0L)
        val firstLong = plan.days.first { it.kind == SessionKind.LONG }
        val firstSession = plan.sessions.first()
        val walk = activity("w", firstSession.date, firstSession.distanceM, speed = 1.6, sport = Sport.WALK)
        val today = firstLong.date.plusDays(1)
        val status = PlanGenerator.status(plan, listOf(walk), today, zone)
        assertEquals(DayStatus.DONE, status[firstSession.date])
        assertEquals(DayStatus.MISSED, status[firstLong.date])
        assertEquals(DayStatus.REST, status[plan.days.first { it.isRest }.date])
        // Missed long session: next week's long session doesn't increase.
        val adjusted = PlanGenerator.adjusted(plan, status)
        val nextLong = adjusted.first { it.kind == SessionKind.LONG && it.week == firstLong.week + 1 }
        assertTrue(nextLong.distanceM <= firstLong.distanceM)
        val (done, total) = PlanGenerator.completion(plan, status)
        assertEquals(1, done)
        assertEquals(plan.sessions.size, total)
    }

    // ---- Effort, fitness, predictor -----------------------------------------------------------

    @Test
    fun effortAndFitness() {
        val today = LocalDate.of(2026, 10, 13)
        val records = (0 until 28).map { i -> activity("r$i", today.minusDays(27L - i), 6_000.0, speed = 3.0) }
        val thresholds = Effort.thresholds(records)
        val load = Effort.load(records.last(), thresholds)
        assertTrue("load $load", load in 20.0..80.0)
        val series = Fitness.series(records, today, zone, thresholds)
        assertEquals(28, series.size)
        assertTrue(series.last().fitness > series.first().fitness)
        assertTrue(series.last().fatigue > series.last().fitness) // daily training: fatigue above fitness
        val weekly = Fitness.weeklyLoads(series, today, 4)
        assertEquals(4, weekly.size)
        assertNotNull(Fitness.targetRange(weekly))
    }

    @Test
    fun racePredictorUsesRiegel() {
        val today = LocalDate.of(2026, 10, 13)
        val run = activity("r", today.minusDays(3), 5_000.0, speed = 1000.0 / 300.0) // 25:00 5K
        val p = RacePredictor.predict(listOf(run), Sport.RUN, today, zone)
        val tenK = p.first { it.label == "10K" }
        // 25:00 × 2^1.06 ≈ 52:07
        assertEquals(3127.0, tenK.timeMs / 1000.0, 15.0)
        assertTrue(p.any { it.label == "Marathon" })
    }

    // ---- Matching, goals, badges ---------------------------------------------------------------

    @Test
    fun matchedRoutes() {
        val d = LocalDate.of(2026, 10, 1)
        val a = activity("a", d, 5_000.0)
        val b = activity("b", d.plusDays(2), 5_100.0, speed = 3.3)
        val c = activity("c", d.plusDays(4), 5_000.0, lonOffset = 0.01) // 1 km east: different route
        assertTrue(RouteMatch.similar(a, b))
        assertFalse(RouteMatch.similar(a, c))
        assertEquals(listOf("a", "b"), RouteMatch.matches(b, listOf(a, b, c)).map { it.id })
    }

    @Test
    fun goalsProgress() {
        val today = LocalDate.of(2026, 10, 14)
        val records = listOf(activity("a", LocalDate.of(2026, 10, 12), 5_000.0), activity("b", LocalDate.of(2026, 10, 5), 9_000.0))
        val g = Goals.progress(Goal("g", Period.WEEK, GoalMetric.DISTANCE, 20_000.0), records, today, zone)
        assertEquals(5_000.0, g.value, 1.0)
        assertEquals(0.25, g.fraction, 0.001)
        val m = Goals.progress(Goal("m", Period.MONTH, GoalMetric.COUNT, 2.0), records, today, zone)
        assertTrue(m.done)
    }

    @Test
    fun badgesUnlockInOrder() {
        val start = LocalDate.of(2026, 9, 1)
        val records = (0 until 10).map { i -> activity("r$i", start.plusDays(i.toLong()), if (i == 9) 10_500.0 else 3_000.0, hour = if (i == 2) 5 else 7) }
        val earned = Badges.earned(records, zone).associateBy { it.def.id }
        assertEquals("r0", earned["first"]!!.recordId)
        assertEquals("r9", earned["count-10"]!!.recordId)
        assertEquals("r2", earned["streak-3"]!!.recordId)
        assertEquals("r6", earned["streak-7"]!!.recordId)
        assertEquals("r2", earned["early"]!!.recordId)
        assertEquals("r9", earned["d-10k"]!!.recordId)
        assertTrue("total-50" !in earned) // 37.5 km so far
    }

    // ---- Ghost and route following ------------------------------------------------------------

    @Test
    fun ghostAndFollower() {
        val ghostRun = activity("g", LocalDate.of(2026, 10, 1), 3_000.0, speed = 3.0)
        val ghost = GhostTracker(ghostRun.points)
        assertEquals(3_000.0, ghost.totalM, 15.0)
        assertEquals(500_000.0, ghost.timeAtDistance(1_500.0)!!.toDouble(), 2_000.0)
        // I reach 1.5 km in 480 s: 20 s ahead of the ghost.
        assertTrue(ghost.secondsAhead(1_500.0, 480_000L)!! in 19L..21L)
        val pos = ghost.positionAt(500_000L)!!
        assertEquals(14.6 + 1_500 * mLat, pos.first, 1e-4)

        val route = (0..30).map { i -> (14.6 + i * 100 * mLat) to 121.05 }
        val f = RouteFollower(route)
        val p1 = f.update(14.6 + 1_000 * mLat, 121.05 + 20 * mLat)!!
        assertEquals(1_000.0, p1.alongM, 10.0)
        assertEquals(2_000.0, p1.remainingM, 15.0)
        assertTrue(p1.offRouteM in 15.0..25.0)
        val off = f.update(14.6 + 1_100 * mLat, 121.05 + 200 * mLat)!!
        assertTrue(off.offRouteM > 150)
    }

    @Suppress("unused")
    private fun daysBetween(a: LocalDate, b: LocalDate) = ChronoUnit.DAYS.between(a, b)
}
