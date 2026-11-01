package com.meridian.tracker

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.CatchUp
import com.meridian.tracker.data.DayStatus
import com.meridian.tracker.data.MonthConfig
import com.meridian.tracker.data.MonthDay
import com.meridian.tracker.data.MonthGoal
import com.meridian.tracker.data.MonthKind
import com.meridian.tracker.data.MonthSport
import com.meridian.tracker.data.MonthTheme
import com.meridian.tracker.data.MonthlyCatchUp
import com.meridian.tracker.data.MonthlyEdits
import com.meridian.tracker.data.MonthlyPlan
import com.meridian.tracker.data.MonthlyPlanner
import com.meridian.tracker.data.MonthlyProgress
import com.meridian.tracker.data.Pace
import com.meridian.tracker.data.TargetType
import com.meridian.tracker.tracking.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class MonthlyProgressTest {

    private val zone = ZoneId.of("UTC")
    private val nov1 = LocalDate.of(2026, 11, 1)

    private fun plan(target: TargetType = TargetType.DISTANCE, free: Set<DayOfWeek> = emptySet(), days: Int = 4): MonthlyPlan =
        MonthlyPlanner.generate(
            MonthConfig(
                nov1, LocalDate.of(2026, 11, 30), MonthGoal.FITTER, MonthTheme.DISTANCE, listOf(MonthSport.RUN, MonthSport.WALK),
                days, free, DayOfWeek.SUNDAY, target, 200.0, null, emptyMap(), 0, 7L, null,
            ),
            "p", 0L,
        )

    private fun record(sport: Sport, date: LocalDate, meters: Double, minutes: Int): ActivityRecord {
        val start = date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        return ActivityRecord("r$date$sport$meters", "r", sport, start, meters, minutes * 60_000L, minutes * 60_000L, 0.0, emptyList(), 0.0, 0L, emptyList())
    }

    /** Does every session on time and exactly as planned. */
    private fun doEverything(p: MonthlyPlan, upTo: LocalDate = LocalDate.of(2026, 11, 30)): List<ActivityRecord> =
        p.sessions.filter { !it.date.isAfter(upTo) }.map { record(it.sport!!.sport, it.date, it.distanceM, it.minutes) }

    private fun status(p: MonthlyPlan, recs: List<ActivityRecord>, today: LocalDate) = MonthlyPlanner.status(p, recs, today, zone)

    // ---- Planned vs actual --------------------------------------------------------------------

    @Test
    fun weeksAddUpToThePlan() {
        val p = plan()
        val recs = doEverything(p)
        val today = LocalDate.of(2026, 12, 1)
        val w = MonthlyProgress.weeks(p, recs, status(p, recs, today), zone)
        assertEquals(p.weeks, w.size)
        assertEquals(p.totalMinutes, w.sumOf { it.plannedMin })
        assertEquals(p.totalM, w.sumOf { it.plannedM }, 0.5)
        assertEquals(p.sessions.size, w.sumOf { it.sessions })
        assertEquals(p.sessions.size, w.sumOf { it.done })
        assertEquals(0, w.sumOf { it.missed })
        assertEquals(p.easyWeek, w.first { it.isEasy }.week)
        w.forEach { assertEquals(it.plannedM, it.actualM, 0.5) }
        w.zipWithNext().forEach { (a, b) -> assertEquals(a.end.plusDays(1), b.start) }
    }

    @Test
    fun actualCountsExtraRunsButNotOtherSports() {
        val p = plan()
        val rest = p.days.first { it.isRest }
        val recs = listOf(
            record(Sport.RUN, rest.date, 5_000.0, 30),   // a bonus run on a rest day counts
            record(Sport.RIDE, rest.date, 40_000.0, 90), // a ride isn't in this plan's sports
        )
        val w = MonthlyProgress.weeks(p, recs, status(p, recs, LocalDate.of(2026, 12, 1)), zone).first { it.week == rest.week }
        assertEquals(5_000.0, w.actualM, 0.1)
        assertEquals(30, w.actualMin)
    }

    @Test
    fun paceCheckLevels() {
        val p = plan()
        val today = LocalDate.of(2026, 11, 15)
        // Nothing planned yet on day 1.
        assertEquals(Pace.NOT_STARTED, MonthlyProgress.paceCheck(p, emptyList(), status(p, emptyList(), nov1), nov1, zone).pace)
        val all = doEverything(p, today.minusDays(1))
        assertEquals(Pace.ON_TRACK, MonthlyProgress.paceCheck(p, all, status(p, all, today), today, zone).pace)
        assertEquals(Pace.WAY_BEHIND, MonthlyProgress.paceCheck(p, emptyList(), status(p, emptyList(), today), today, zone).pace)
        val half = all.filterIndexed { i, _ -> i % 2 == 0 }
        val pace = MonthlyProgress.paceCheck(p, half, status(p, half, today), today, zone).pace
        assertTrue(pace == Pace.BEHIND || pace == Pace.WAY_BEHIND)
        val extra = all + doEverything(p, today.minusDays(1)).map { it.copy(id = it.id + "x") }
        assertEquals(Pace.AHEAD, MonthlyProgress.paceCheck(p, extra, status(p, extra, today), today, zone).pace)
    }

    @Test
    fun paceCheckWorksForTimeTargets() {
        val p = plan(target = TargetType.TIME)
        val today = LocalDate.of(2026, 11, 15)
        val all = doEverything(p, today.minusDays(1))
        val c = MonthlyProgress.paceCheck(p, all, status(p, all, today), today, zone)
        assertEquals(Pace.ON_TRACK, c.pace)
        assertEquals(c.planned, c.actual, 1.0)
    }

    // ---- Catch-up -----------------------------------------------------------------------------

    private fun missedSetup(): Triple<MonthlyPlan, LocalDate, MonthDay> {
        val p = plan()
        val missed = p.days.first { it.kind == MonthKind.LONG && it.week == 1 }
        val today = missed.date.plusDays(1)
        return Triple(p, today, missed)
    }

    @Test
    fun suggestsAFreeRestDayForAMissedKeySession() {
        val (p, today, missed) = missedSetup()
        val recs = doEverything(p, missed.date.minusDays(1)) // everything except the long session
        val st = status(p, recs, today)
        assertEquals(DayStatus.MISSED, st[missed.date])
        val s = MonthlyCatchUp.suggestions(p, st, today)
        assertTrue(s.isNotEmpty())
        val c = s.first { it.missed.date == missed.date }
        assertTrue(p.day(c.target)!!.isRest)
        assertFalse(c.target.isBefore(today))
        assertTrue(c.target.isBefore(today.plusDays(7)))
        // Next to a hard day on at most one side, and flagged when it is.
        val hardSides = listOf(c.target.minusDays(1), c.target.plusDays(1)).count { n -> p.day(n)?.kind?.hard == true }
        assertTrue(hardSides <= 1)
        assertEquals(hardSides == 1, c.nextToHardDay)
    }

    @Test
    fun easySessionsAndOldMissesAreNotSuggested() {
        val p = plan()
        val easy = p.days.first { it.kind == MonthKind.EASY && it.week == 1 }
        val today = easy.date.plusDays(1)
        val st = status(p, emptyList(), today)
        assertTrue(MonthlyCatchUp.suggestions(p, st, today).none { it.missed.kind == MonthKind.EASY })
        // A hard session missed more than a week ago is history.
        val old = p.days.first { it.kind.hard }
        val later = old.date.plusDays(12)
        assertTrue(MonthlyCatchUp.suggestions(p, status(p, emptyList(), later), later).none { it.missed.date == old.date })
    }

    @Test
    fun applyingMovesTheSessionAndCountsItOnce() {
        val (p, today, missed) = missedSetup()
        val recs = doEverything(p, missed.date.minusDays(1))
        val st = status(p, recs, today)
        val c = MonthlyCatchUp.suggestions(p, st, today).first { it.missed.date == missed.date }
        val q = MonthlyCatchUp.apply(p, c, today)!!
        val moved = q.day(c.target)!!
        assertEquals(missed.kind, moved.kind)
        assertEquals(missed.minutes, moved.minutes)
        assertEquals(missed.sport, moved.sport)
        assertEquals(missed.date, moved.catchUpOf)
        assertTrue(moved.edited)
        assertTrue(moved.detail.startsWith("Catch-up for"))
        assertTrue(missed.date in q.movedAway)
        // The missed day no longer counts as planned or missed.
        assertEquals(DayStatus.MOVED, status(q, recs, today)[missed.date])
        assertEquals(p.sessions.size, q.sessions.size)
        assertEquals(p.totalMinutes, q.totalMinutes)
        assertEquals(p.sessions.size, MonthlyPlanner.completion(q, status(q, recs, today)).second)
        // No more suggestion for it.
        assertTrue(MonthlyCatchUp.suggestions(q, status(q, recs, today), today).none { it.missed.date == missed.date })
        // Doing the catch-up completes it.
        val done = recs + record(moved.sport!!.sport, moved.date, moved.distanceM, moved.minutes)
        assertEquals(DayStatus.DONE, status(q, done, moved.date.plusDays(1))[moved.date])
        // Resetting the day brings the miss back.
        val back = MonthlyEdits.reset(q, c.target)
        assertTrue(back.movedAway.isEmpty())
        assertEquals(DayStatus.MISSED, status(back, recs, today)[missed.date])
    }

    @Test
    fun movingTheCatchUpKeepsTheLinkAndRestingItBringsTheMissBack() {
        val (p, today, missed) = missedSetup()
        val recs = doEverything(p, missed.date.minusDays(1))
        val c = MonthlyCatchUp.suggestions(p, status(p, recs, today), today).first { it.missed.date == missed.date }
        val q = MonthlyCatchUp.apply(p, c, today)!!
        val other = q.days.first { it.isRest && !it.date.isBefore(today) && it.date != c.target }
        val swapped = MonthlyEdits.move(q, c.target, other.date, today)!!
        assertEquals(missed.date, swapped.day(other.date)!!.catchUpOf)
        assertTrue(missed.date in swapped.movedAway)
        val gone = MonthlyEdits.makeRest(q, c.target, today)!!
        assertTrue(gone.movedAway.isEmpty())
        // Editing its amount keeps the link too.
        val edited = MonthlyEdits.setSession(q, c.target, missed.sport!!, missed.kind, 3_000.0, today)!!
        assertEquals(missed.date, edited.day(c.target)!!.catchUpOf)
    }

    @Test
    fun applyRefusesStaleOrBusyTargets() {
        val (p, today, missed) = missedSetup()
        val busy = p.days.first { !it.isRest && !it.date.isBefore(today) }
        assertNull(MonthlyCatchUp.apply(p, CatchUp(missed, busy.date), today))
        assertNull(MonthlyCatchUp.apply(p, CatchUp(missed, today.minusDays(3)), today))
        assertNull(MonthlyCatchUp.apply(p, CatchUp(missed.copy(date = LocalDate.of(2027, 1, 1)), p.days.first { it.isRest && !it.date.isBefore(today) }.date), today))
    }

    @Test
    fun skippingHidesTheSuggestionAndResetBringsItBack() {
        val (p, today, missed) = missedSetup()
        val recs = doEverything(p, missed.date.minusDays(1))
        val st = status(p, recs, today)
        val q = MonthlyCatchUp.dismiss(p, missed.date)
        assertTrue(MonthlyCatchUp.suggestions(q, st, today).none { it.missed.date == missed.date })
        assertTrue(MonthlyCatchUp.suggestions(MonthlyEdits.resetAll(q), st, today).any { it.missed.date == missed.date })
    }

    @Test
    fun respectsFreeDaysAndEditedRestDays() {
        val free = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val p = plan(free = free)
        val missed = p.days.first { it.kind == MonthKind.LONG && it.week == 1 }
        val today = missed.date.plusDays(1)
        val st = status(p, emptyList(), today)
        MonthlyCatchUp.suggestions(p, st, today).forEach { assertTrue(it.target.dayOfWeek in free) }
    }

    // ---- Keep next week gentle ----------------------------------------------------------------

    @Test
    fun holdsNextWeekAfterTwoMissedSessions() {
        val p = plan()
        // Find a week followed by a bigger one, and miss everything in it.
        val weeks = p.days.groupBy { it.week }
        val w = weeks.keys.sorted().first { k ->
            k + 1 in weeks && weeks.getValue(k + 1).sumOf { it.minutes } > weeks.getValue(k).sumOf { it.minutes } * 1.12 && k >= 0
        }
        val today = weeks.getValue(w).maxOf { it.date }.plusDays(1)
        val st = status(p, emptyList(), today)
        val h = MonthlyCatchUp.hold(p, st, today)
        assertNotNull(h)
        h!!
        assertEquals(w + 1, h.nextWeek)
        assertTrue(h.toMin > h.fromMin)
        val q = MonthlyCatchUp.applyHold(p, h, today)
        val next = q.days.filter { it.week == h.nextWeek }
        assertTrue(next.sumOf { it.minutes } < h.toMin)
        assertTrue(next.sumOf { it.minutes } <= h.fromMin * 1.12 || next.sumOf { it.minutes } <= h.toMin * 0.72)
        // Other weeks are untouched, and it doesn't come back.
        q.days.filter { it.week != h.nextWeek }.forEach { assertEquals(p.day(it.date), it) }
        assertNull(MonthlyCatchUp.hold(q, st, today))
        // Everything it changed can be reset.
        assertEquals(p.days, MonthlyEdits.resetAll(q).days)
    }

    @Test
    fun noHoldWithOneMissedSessionOrWhenNothingIsLeft() {
        val p = plan()
        val weeks = p.days.groupBy { it.week }
        val first = weeks.getValue(0)
        val today = first.maxOf { it.date }.plusDays(1)
        val onlyOne = doEverything(p, today).filterNot { it.startTimeMs == first.first { d -> !d.isRest }.date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli() }
        assertNull(MonthlyCatchUp.hold(p, status(p, onlyOne, today), today))
        val end = LocalDate.of(2026, 12, 3)
        assertNull(MonthlyCatchUp.hold(p, status(p, emptyList(), end), end))
    }

    @Test
    fun dismissingTheHoldHidesIt() {
        val p = plan()
        val weeks = p.days.groupBy { it.week }
        val w = weeks.keys.sorted().first { k -> k + 1 in weeks && weeks.getValue(k + 1).sumOf { it.minutes } > weeks.getValue(k).sumOf { it.minutes } * 1.12 }
        val today = weeks.getValue(w).maxOf { it.date }.plusDays(1)
        val st = status(p, emptyList(), today)
        val h = MonthlyCatchUp.hold(p, st, today)!!
        assertNull(MonthlyCatchUp.hold(MonthlyCatchUp.dismiss(p, h.key), st, today))
    }
}
