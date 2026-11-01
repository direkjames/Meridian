package com.meridian.tracker

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.DayStatus
import com.meridian.tracker.data.MonthConfig
import com.meridian.tracker.data.MonthGoal
import com.meridian.tracker.data.MonthKind
import com.meridian.tracker.data.MonthSport
import com.meridian.tracker.data.MonthTheme
import com.meridian.tracker.data.MonthlyPlan
import com.meridian.tracker.data.MonthlyPlanner
import com.meridian.tracker.data.TargetType
import com.meridian.tracker.tracking.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

class MonthlyPlanTest {

    private val zone = ZoneId.of("UTC")
    private val nov1 = LocalDate.of(2026, 11, 1)

    private fun config(
        start: LocalDate = nov1,
        end: LocalDate = MonthlyPlanner.monthEnd(start),
        goal: MonthGoal = MonthGoal.FITTER,
        theme: MonthTheme = MonthTheme.BASE,
        sports: List<MonthSport> = listOf(MonthSport.RUN, MonthSport.WALK),
        days: Int = 4,
        free: Set<DayOfWeek> = emptySet(),
        longDay: DayOfWeek = DayOfWeek.SUNDAY,
        target: TargetType = TargetType.DISTANCE,
        weekly: Double = 180.0,
        monthTarget: Double? = null,
        index: Int = 0,
        seed: Long = 42L,
        prev: Double? = null,
    ) = MonthConfig(start, end, goal, theme, sports, days, free, longDay, target, weekly, monthTarget, emptyMap(), index, seed, prev)

    private fun gen(c: MonthConfig) = MonthlyPlanner.generate(c, "t", 0L)

    private fun weekMinutes(p: MonthlyPlan) = p.days.groupBy { it.week }.toSortedMap().values.map { w -> w.sumOf { it.minutes } }

    private fun record(sport: Sport, date: LocalDate, meters: Double, minutes: Int): ActivityRecord {
        val start = date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        return ActivityRecord("r$date$sport", "r", sport, start, meters, minutes * 60_000L, minutes * 60_000L, 0.0, emptyList(), 0.0, 0L, emptyList())
    }

    @Test
    fun coversEveryDayOfTheMonthOnce() {
        val p = gen(config())
        assertEquals(30, p.days.size)
        assertEquals(nov1, p.days.first().date)
        assertEquals(LocalDate.of(2026, 11, 30), p.days.last().date)
        assertEquals(p.days.size, p.days.map { it.date }.distinct().size)
    }

    @Test
    fun neverTwoHardDaysInARow() {
        for (theme in MonthTheme.entries) for (days in 2..6) for (seed in 0L..5L) {
            val p = gen(config(theme = theme, days = days, seed = seed))
            p.days.zipWithNext().forEach { (a, b) ->
                assertTrue("$theme $days days: ${a.date} ${a.kind} then ${b.kind}", !(a.kind.hard && b.kind.hard))
            }
        }
    }

    @Test
    fun keepsARestDayAndTheRightNumberOfSessions() {
        for (days in 2..6) {
            val p = gen(config(days = days))
            p.days.groupBy { it.week }.values.filter { it.size == 7 }.forEach { week ->
                assertEquals(days, week.count { !it.isRest })
                assertTrue(week.any { it.isRest })
            }
        }
    }

    @Test
    fun onlyUsesFreeDaysAndTheLongDay() {
        val free = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
        val p = gen(config(free = free, days = 5, longDay = DayOfWeek.SATURDAY))
        p.sessions.forEach { assertTrue(it.date.dayOfWeek in free) }
        p.days.filter { it.kind == MonthKind.LONG }.forEach { assertEquals(DayOfWeek.SATURDAY, it.date.dayOfWeek) }
        // Asked for 5 but only 3 days are free.
        p.days.groupBy { it.week }.values.filter { it.size == 7 }.forEach { assertEquals(3, it.count { d -> !d.isRest }) }
    }

    @Test
    fun longDayNotFreeFallsBackToAFreeDay() {
        val free = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val p = gen(config(free = free, days = 3, longDay = DayOfWeek.SUNDAY))
        p.days.filter { it.kind == MonthKind.LONG }.forEach { assertEquals(DayOfWeek.FRIDAY, it.date.dayOfWeek) }
    }

    @Test
    fun theEasyWeekIsLighter() {
        for (theme in MonthTheme.entries) {
            val p = gen(config(theme = theme))
            assertEquals(3, p.easyWeek)
            val w = weekMinutes(p)
            assertTrue("$theme: $w", w[3] < w[2] * 0.8)
            assertTrue(p.days.filter { it.week == 3 }.none { it.kind in setOf(MonthKind.TEMPO, MonthKind.INTERVALS, MonthKind.HILLS, MonthKind.BRISK) })
        }
    }

    @Test
    fun shortMonthHasNoEasyWeek() {
        val p = gen(config(start = LocalDate.of(2026, 11, 20)))
        assertEquals(11, p.days.size)
        assertEquals(-1, p.easyWeek)
    }

    @Test
    fun theLongSessionIsTheBiggestOfTheWeek() {
        val p = gen(config(days = 5, weekly = 240.0))
        p.days.groupBy { it.week }.values.forEach { week ->
            val long = week.firstOrNull { it.kind == MonthKind.LONG } ?: return@forEach
            week.filterNot { it === long }.forEach { assertTrue("${it.title} ${it.minutes} > ${long.minutes}", it.minutes <= long.minutes) }
        }
    }

    @Test
    fun everyMonthIsDifferent() {
        var prev: MonthlyPlan? = null
        val themes = mutableListOf<MonthTheme>()
        for (i in 0 until 6) {
            val theme = MonthlyPlanner.themeFor(MonthGoal.FITTER, i, prev?.config?.theme)
            val start = nov1.plusMonths(i.toLong())
            val p = gen(config(start = start, theme = theme, index = i, seed = 1000L + i, prev = prev?.totalMinutes?.toDouble()))
            prev?.let { last ->
                assertNotEquals(last.config.theme, p.config.theme)
                val change = abs(p.totalMinutes - last.totalMinutes) / last.totalMinutes.toDouble()
                assertTrue("month $i changed only ${change * 100}%", change >= 0.03)
            }
            themes += theme
            prev = p
        }
        assertEquals(4, themes.take(4).distinct().size)
    }

    @Test
    fun themeNeverRepeatsThePreviousOne() {
        for (goal in MonthGoal.entries) for (i in 0 until 8) for (prev in MonthTheme.entries) {
            assertNotEquals(prev, MonthlyPlanner.themeFor(goal, i, prev))
        }
    }

    @Test
    fun speedMonthHasMoreQualityThanBase() {
        val speed = gen(config(theme = MonthTheme.SPEED, days = 5))
        val base = gen(config(theme = MonthTheme.BASE, days = 5))
        val q = { p: MonthlyPlan -> p.sessions.count { it.kind in setOf(MonthKind.TEMPO, MonthKind.INTERVALS, MonthKind.HILLS) } }
        assertTrue(q(speed) > q(base))
        assertTrue(speed.totalMinutes < base.totalMinutes)
    }

    @Test
    fun mixedMonthCrossTrains() {
        val p = gen(config(theme = MonthTheme.MIXED, sports = listOf(MonthSport.RUN, MonthSport.RIDE, MonthSport.HIKE), days = 5))
        assertTrue(p.sessions.any { it.sport == MonthSport.RIDE })
        assertTrue(p.sessions.any { it.sport == MonthSport.HIKE && it.kind == MonthKind.LONG })
    }

    @Test
    fun everySportYouPickShowsUp() {
        for (theme in MonthTheme.entries) for (days in 2..6) {
            val sports = listOf(MonthSport.RUN, MonthSport.WALK, MonthSport.RIDE)
            val p = gen(config(theme = theme, days = days, sports = sports))
            sports.forEach { s -> assertTrue("$theme $days: no $s", p.sessions.any { it.sport == s }) }
        }
    }

    @Test
    fun steadyGoalKeepsItFriendly() {
        val p = gen(config(goal = MonthGoal.STEADY, theme = MonthTheme.SPEED, days = 5))
        p.days.groupBy { it.week }.values.forEach { week ->
            assertTrue(week.count { it.kind.hard && it.kind != MonthKind.LONG } <= 1)
        }
    }

    @Test
    fun sameSeedSamePlanNewSeedNewMix() {
        val a = gen(config(seed = 7L))
        val b = gen(config(seed = 7L))
        assertEquals(a.days, b.days)
        val others = (8L..20L).map { gen(config(seed = it)).days }
        assertTrue(others.any { it != a.days })
    }

    @Test
    fun targetPullsThePlanButStaysSafe() {
        val plain = gen(config())
        val bigger = gen(config(monthTarget = plain.totalM * 1.08))
        assertTrue(bigger.totalM > plain.totalM)
        val huge = gen(config(monthTarget = plain.totalM * 3))
        assertTrue(huge.totalM <= plain.totalM * 1.2)
        assertNotNull(huge.targetShare)
        assertTrue(huge.targetShare!! < 0.6)
        val time = gen(config(target = TargetType.TIME, monthTarget = plain.totalMinutes * 0.9))
        assertTrue(time.totalMinutes < plain.totalMinutes)
    }

    @Test
    fun ridesCoverMoreGroundThanWalks() {
        val ride = gen(config(sports = listOf(MonthSport.RIDE)))
        val walk = gen(config(sports = listOf(MonthSport.WALK)))
        assertTrue(ride.totalM > walk.totalM * 3)
    }

    @Test
    fun oddInputsDontBreakIt() {
        // One free day, six sessions asked, no sports, tiny week.
        val p = gen(config(free = setOf(DayOfWeek.WEDNESDAY), days = 6, sports = emptyList(), weekly = 1.0))
        assertTrue(p.sessions.all { it.date.dayOfWeek == DayOfWeek.WEDNESDAY && it.sport == MonthSport.WALK })
        assertTrue(p.sessions.all { it.minutes >= 10 && it.distanceM > 0 })
        // End before start: a single day.
        assertEquals(1, gen(config(end = nov1.minusDays(3))).days.size)
        // Huge week stays capped per session.
        assertTrue(gen(config(weekly = 5_000.0, days = 2)).sessions.all { it.minutes <= 240 })
    }

    @Test
    fun trainingDaysAreSpreadOut() {
        val three = MonthlyPlanner.trainingDays(emptySet(), 3, DayOfWeek.SUNDAY)
        assertEquals(3, three.size)
        for (a in three) for (b in three) if (a != b) assertTrue(MonthlyPlanner.gap(a, b) >= 2)
        assertEquals(6, MonthlyPlanner.trainingDays(emptySet(), 7, DayOfWeek.SUNDAY).size)
    }

    @Test
    fun statusTicksOffMatchingActivities() {
        val p = gen(config())
        val run = p.sessions.first { it.sport == MonthSport.RUN }
        val walk = p.sessions.first { it.sport == MonthSport.WALK }
        val today = LocalDate.of(2026, 11, 30)
        val records = listOf(
            record(Sport.TRAIL_RUN, run.date, run.distanceM, run.minutes),
            record(Sport.RIDE, walk.date, walk.distanceM * 5, walk.minutes), // a ride doesn't count as a walk
        )
        val s = MonthlyPlanner.status(p, records, today, zone)
        assertEquals(DayStatus.DONE, s[run.date])
        assertEquals(DayStatus.MISSED, s[walk.date])
        p.days.filter { it.isRest }.forEach { assertEquals(DayStatus.REST, s[it.date]) }
        assertEquals(1f, MonthlyPlanner.progress(p, records, zone)[run.date])
        val (done, total) = MonthlyPlanner.completion(p, s)
        assertEquals(1, done)
        assertEquals(p.sessions.size, total)
    }

    @Test
    fun usualWeekAndPacesFromHistory() {
        val now = nov1.atStartOfDay(zone).toInstant().toEpochMilli()
        val recs = (1..8).map { record(Sport.RUN, nov1.minusDays(it * 3L), 5_000.0, 30) }
        // 8 × 30 min over 4 weeks = 60 min a week, but at least 20 min per session day.
        assertEquals(80.0, MonthlyPlanner.usualWeekMinutes(recs, listOf(MonthSport.RUN), 4, now), 0.01)
        assertEquals(100.0, MonthlyPlanner.usualWeekMinutes(emptyList(), listOf(MonthSport.RUN), 4, now), 0.01)
        val paces = MonthlyPlanner.paces(recs, now)
        assertEquals(360.0 * 1.05, paces[MonthSport.RUN]!!, 0.5)
        assertEquals(MonthSport.WALK.defaultPace, paces[MonthSport.WALK]!!, 0.01)
    }

    @Test
    fun daysLeftAndMonthEnd() {
        assertEquals(30, MonthlyPlanner.daysLeft(nov1))
        assertEquals(1, MonthlyPlanner.daysLeft(LocalDate.of(2026, 11, 30)))
        assertEquals(LocalDate.of(2028, 2, 29), MonthlyPlanner.monthEnd(LocalDate.of(2028, 2, 3)))
        assertEquals(28, ChronoUnit.DAYS.between(nov1, LocalDate.of(2026, 11, 29)))
    }
}
