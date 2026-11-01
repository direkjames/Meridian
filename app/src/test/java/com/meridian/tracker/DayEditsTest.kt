package com.meridian.tracker

import com.meridian.tracker.data.MonthConfig
import com.meridian.tracker.data.MonthGoal
import com.meridian.tracker.data.MonthKind
import com.meridian.tracker.data.MonthSport
import com.meridian.tracker.data.MonthTheme
import com.meridian.tracker.data.MonthlyEdits
import com.meridian.tracker.data.MonthlyPlan
import com.meridian.tracker.data.MonthlyPlanner
import com.meridian.tracker.data.PlanConfig
import com.meridian.tracker.data.PlanEdits
import com.meridian.tracker.data.PlanGenerator
import com.meridian.tracker.data.PlanMode
import com.meridian.tracker.data.SessionKind
import com.meridian.tracker.data.TargetType
import com.meridian.tracker.data.TrainingPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class DayEditsTest {

    private val start = LocalDate.of(2026, 11, 2) // a Monday
    private val event = LocalDate.of(2026, 12, 27)

    private fun eventPlan(mode: PlanMode = PlanMode.RUN, target: TargetType = TargetType.DISTANCE): TrainingPlan =
        PlanGenerator.generate(
            PlanConfig("Fun Run", mode, target, 10_000.0, start, event, 4, DayOfWeek.SUNDAY, 4_000.0, PlanGenerator.defaultPace(mode)),
            "p", 0L,
        )

    private fun monthPlan(target: TargetType = TargetType.DISTANCE, sports: List<MonthSport> = listOf(MonthSport.RUN, MonthSport.WALK, MonthSport.RIDE)): MonthlyPlan =
        MonthlyPlanner.generate(
            MonthConfig(
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), MonthGoal.FITTER, MonthTheme.BASE, sports,
                4, emptySet(), DayOfWeek.SUNDAY, target, 180.0, null, emptyMap(), 0, 42L, null,
            ),
            "m", 0L,
        )

    // ---- Event plan ---------------------------------------------------------------------------

    @Test
    fun eventDayAndPastDaysCannotBeEdited() {
        val p = eventPlan()
        val today = start.plusDays(10)
        assertFalse(PlanEdits.canEdit(p, event, today))
        assertFalse(PlanEdits.canEdit(p, today.minusDays(1), today))
        assertFalse(PlanEdits.canEdit(p, event.plusDays(3), today)) // outside the plan
        assertNull(PlanEdits.setSession(p, event, SessionKind.EASY, 5_000.0, today))
        assertNull(PlanEdits.makeRest(p, event, today))
        assertNull(PlanEdits.move(p, event, today.plusDays(2), today))
        assertNull(PlanEdits.move(p, today.plusDays(2), event, today))
        assertNull(PlanEdits.setSession(p, today.plusDays(2), SessionKind.RACE, 5_000.0, today))
        assertNull(PlanEdits.setSession(p, today.plusDays(2), SessionKind.BRISK_WALK, 5_000.0, today)) // not a run-plan session
        assertNull(PlanEdits.setSession(p, today.plusDays(2), SessionKind.EASY, Double.NaN, today))
    }

    @Test
    fun editingASessionChangesOnlyThatDayAndCanBeReset() {
        val p = eventPlan()
        val day = p.sessions.first { it.kind == SessionKind.EASY && it.date.isAfter(start.plusDays(8)) }
        val edited = PlanEdits.setSession(p, day.date, SessionKind.TEMPO, 6_000.0, start)!!
        val d = edited.day(day.date)!!
        assertTrue(d.edited)
        assertEquals(SessionKind.TEMPO, d.kind)
        assertEquals(6_000.0, d.distanceM, 0.01)
        assertEquals("Tempo run", d.title)
        assertEquals(p.days.size, edited.days.size)
        edited.days.filter { it.date != day.date }.forEach { assertEquals(p.day(it.date), it) }
        assertEquals(day, edited.originals[day.date])
        val back = PlanEdits.reset(edited, day.date)
        assertEquals(p.days, back.days)
        assertTrue(back.originals.isEmpty())
    }

    @Test
    fun editedBackToTheOriginalIsNoLongerAnEdit() {
        val p = eventPlan()
        val day = p.sessions.first { it.kind == SessionKind.EASY }
        val same = PlanEdits.setSession(p, day.date, day.kind, day.distanceM, start)!!
        assertFalse(same.day(day.date)!!.edited)
        assertTrue(same.originals.isEmpty())
    }

    @Test
    fun timeTargetsKeepTheMinutesYouPick() {
        val p = eventPlan(target = TargetType.TIME)
        val day = p.sessions.first { it.kind == SessionKind.EASY }
        val edited = PlanEdits.setSession(p, day.date, SessionKind.EASY, 47.0, start)!!.day(day.date)!!
        assertEquals(45, edited.minutes)
        assertTrue(edited.distanceM > 0)
        // Out-of-range amounts are clamped.
        assertEquals(10, PlanEdits.setSession(p, day.date, SessionKind.EASY, -5.0, start)!!.day(day.date)!!.minutes)
        assertEquals(360, PlanEdits.setSession(p, day.date, SessionKind.EASY, 99_999.0, start)!!.day(day.date)!!.minutes)
    }

    @Test
    fun distanceIsClamped() {
        val p = eventPlan()
        val day = p.sessions.first { it.kind == SessionKind.EASY }
        assertEquals(500.0, PlanEdits.setSession(p, day.date, SessionKind.EASY, 0.0, start)!!.day(day.date)!!.distanceM, 0.01)
        assertEquals(60_000.0, PlanEdits.setSession(p, day.date, SessionKind.EASY, 1e9, start)!!.day(day.date)!!.distanceM, 0.01)
    }

    @Test
    fun restDayCanBecomeASessionAndBack() {
        val p = eventPlan()
        val rest = p.days.first { it.isRest && it.date.isAfter(start.plusDays(10)) && it.date.isBefore(event.minusDays(5)) }
        val added = PlanEdits.setSession(p, rest.date, SessionKind.EASY, 3_000.0, start)!!
        assertFalse(added.day(rest.date)!!.isRest)
        assertEquals(p.sessions.size + 1, added.sessions.size)
        val gone = PlanEdits.makeRest(added, rest.date, start)!!
        assertTrue(gone.day(rest.date)!!.isRest)
        assertTrue(gone.originals.isEmpty()) // back to how it was planned
    }

    @Test
    fun makingASessionRestRemovesIt() {
        val p = eventPlan()
        val day = p.sessions.first { it.kind == SessionKind.EASY }
        val r = PlanEdits.makeRest(p, day.date, start)!!
        assertTrue(r.day(day.date)!!.isRest)
        assertEquals(p.sessions.size - 1, r.sessions.size)
        assertEquals(r, PlanEdits.makeRest(r, day.date, start)) // already rest: nothing changes
    }

    @Test
    fun movingSwapsTwoDaysAndKeepsTheirWeeks() {
        val p = eventPlan()
        val a = p.sessions.first { it.kind == SessionKind.EASY && it.week == 2 }
        val b = p.days.first { it.isRest && it.week == 3 }
        val m = PlanEdits.move(p, a.date, b.date, start)!!
        assertTrue(m.day(a.date)!!.isRest)
        assertEquals(a.title, m.day(b.date)!!.title)
        assertEquals(a.week, m.day(a.date)!!.week)
        assertEquals(b.week, m.day(b.date)!!.week)
        assertEquals(p.sessions.size, m.sessions.size)
        // Swap back: no edits left.
        val back = PlanEdits.move(m, a.date, b.date, start)!!
        assertEquals(p.days, back.days)
        assertTrue(back.originals.isEmpty())
        // Two rest days: nothing to move.
        val r1 = p.days.first { it.isRest }
        val r2 = p.days.last { it.isRest && it.date.isBefore(event.minusDays(2)) }
        assertEquals(p, PlanEdits.move(p, r1.date, r2.date, start))
        assertNull(PlanEdits.move(p, a.date, a.date, start))
    }

    @Test
    fun moveTargetsAreFutureDaysExceptTheEvent() {
        val p = eventPlan()
        val today = start.plusDays(20)
        val from = p.sessions.first { it.date.isAfter(today) && it.kind != SessionKind.RACE }
        val t = PlanEdits.moveTargets(p, from.date, today)
        assertFalse(event in t)
        assertFalse(from.date in t)
        assertTrue(t.all { !it.isBefore(today) })
        assertTrue(t.isNotEmpty())
        assertTrue(PlanEdits.moveTargets(p, today.minusDays(1), today).isEmpty())
    }

    @Test
    fun missedLongSessionAdjustmentSkipsEditedDays() {
        val p = eventPlan()
        val longs = p.days.filter { it.kind == SessionKind.LONG }
        val target = longs.first { it.week >= 2 }
        val edited = PlanEdits.setSession(p, target.date, SessionKind.LONG, target.distanceM + 3_000, start)!!
        val missed = edited.days.associate { it.date to com.meridian.tracker.data.DayStatus.MISSED }
        val adj = PlanGenerator.adjusted(edited, missed)
        assertEquals(target.distanceM + 3_000, adj.first { it.date == target.date }.distanceM, 0.01)
    }

    @Test
    fun restoreIgnoresBadEntries() {
        val p = eventPlan()
        val ok = p.sessions.first { it.kind == SessionKind.EASY }.copy(distanceM = 4_000.0, minutes = 30, title = "Easy run")
        val raceDay = p.day(event)!!.copy(distanceM = 1.0)
        val outside = ok.copy(date = event.plusDays(40))
        val bad = ok.copy(date = p.sessions.last { it.kind == SessionKind.EASY }.date, distanceM = Double.NaN)
        val negative = ok.copy(date = p.days.first { it.isRest }.date, distanceM = -5.0)
        val r = PlanEdits.restore(p, listOf(ok, raceDay, outside, bad, negative))
        assertEquals(listOf(ok.date), PlanEdits.edits(r).map { it.date })
        assertEquals(p.day(event), r.day(event))
    }

    @Test
    fun warningsCoverTheRiskyCases() {
        val p = eventPlan()
        // Hard day back to back.
        val long = p.days.first { it.kind == SessionKind.LONG && it.week >= 1 }
        val before = long.date.minusDays(1)
        val hard = PlanEdits.setSession(p, before, SessionKind.TEMPO, 6_000.0, start)!!
        assertTrue(PlanEdits.warnings(hard, before).any { it.contains("back to back") })
        // Day before the event.
        val eve = event.minusDays(1)
        val withEve = PlanEdits.setSession(p, eve, SessionKind.EASY, 3_000.0, start)!!
        assertTrue(PlanEdits.warnings(withEve, eve).any { it.contains("before the event") })
        // Much bigger than anything planned.
        val big = PlanEdits.setSession(p, p.sessions.first { it.kind == SessionKind.EASY }.date, SessionKind.LONG, 30_000.0, start)!!
        val bigDay = big.days.first { it.edited }
        val w = PlanEdits.warnings(big, bigDay.date)
        assertTrue(w.any { it.contains("longer than any") })
        assertTrue(w.any { it.contains("bigger than planned") })
        // An untouched plan has none.
        p.days.forEach { assertTrue(PlanEdits.warnings(p, it.date).isEmpty()) }
    }

    // ---- Monthly plan -------------------------------------------------------------------------

    @Test
    fun monthlyPastDaysCannotBeEdited() {
        val p = monthPlan()
        val today = LocalDate.of(2026, 11, 10)
        assertFalse(MonthlyEdits.canEdit(p, today.minusDays(1), today))
        assertTrue(MonthlyEdits.canEdit(p, today, today))
        assertFalse(MonthlyEdits.canEdit(p, LocalDate.of(2026, 12, 2), today))
        assertNull(MonthlyEdits.setSession(p, today.minusDays(2), MonthSport.RUN, MonthKind.EASY, 5_000.0, today))
    }

    @Test
    fun monthlyEditChangesSportKindAndAmount() {
        val p = monthPlan()
        val today = LocalDate.of(2026, 11, 1)
        val day = p.sessions.first { it.kind == MonthKind.EASY }
        val e = MonthlyEdits.setSession(p, day.date, MonthSport.RIDE, MonthKind.HILLS, 20_000.0, today)!!
        val d = e.day(day.date)!!
        assertTrue(d.edited)
        assertEquals(MonthSport.RIDE, d.sport)
        assertEquals(MonthKind.HILLS, d.kind)
        assertEquals(20_000.0, d.distanceM, 0.01)
        assertEquals("Hilly ride", d.title)
        assertTrue(d.minutes in 10..300)
        assertEquals(day, e.originals[day.date])
        assertEquals(p.days, MonthlyEdits.reset(e, day.date).days)
        assertEquals(p.days, MonthlyEdits.resetAll(e).days)
    }

    @Test
    fun monthlyKindThatDoesNotFitTheSportFallsBackToEasy() {
        val p = monthPlan()
        val day = p.sessions.first()
        val e = MonthlyEdits.setSession(p, day.date, MonthSport.HIKE, MonthKind.TEMPO, 8_000.0, LocalDate.of(2026, 11, 1))!!
        assertEquals(MonthKind.EASY, e.day(day.date)!!.kind)
        assertEquals(MonthSport.HIKE, e.day(day.date)!!.sport)
    }

    @Test
    fun monthlyTimeTargetsAndClamping() {
        val p = monthPlan(target = TargetType.TIME)
        val today = LocalDate.of(2026, 11, 1)
        val day = p.sessions.first()
        val e = MonthlyEdits.setSession(p, day.date, MonthSport.WALK, MonthKind.EASY, 52.0, today)!!.day(day.date)!!
        assertEquals(50, e.minutes)
        assertEquals(10, MonthlyEdits.setSession(p, day.date, MonthSport.WALK, MonthKind.EASY, 0.0, today)!!.day(day.date)!!.minutes)
        assertEquals(240, MonthlyEdits.setSession(p, day.date, MonthSport.WALK, MonthKind.EASY, 10_000.0, today)!!.day(day.date)!!.minutes)
        assertEquals(300, MonthlyEdits.setSession(p, day.date, MonthSport.RIDE, MonthKind.EASY, 10_000.0, today)!!.day(day.date)!!.minutes)
        // Distance plans: a huge distance is capped to what fits in the session length cap.
        val dp = monthPlan()
        val cap = MonthlyEdits.range(dp, MonthSport.WALK, MonthKind.EASY)
        val big = MonthlyEdits.setSession(dp, day.date, MonthSport.WALK, MonthKind.EASY, 1e9, today)!!.day(day.date)!!
        assertTrue(big.distanceM <= cap.endInclusive + 1)
        assertEquals(240, big.minutes)
    }

    @Test
    fun monthlyMoveRestAndAddSession() {
        val p = monthPlan()
        val today = LocalDate.of(2026, 11, 1)
        val a = p.sessions.first { it.kind == MonthKind.EASY }
        val rest = p.days.first { it.isRest && it.date.isAfter(a.date) }
        val m = MonthlyEdits.move(p, a.date, rest.date, today)!!
        assertTrue(m.day(a.date)!!.isRest)
        assertEquals(a.title, m.day(rest.date)!!.title)
        assertEquals(p.sessions.size, m.sessions.size)
        assertEquals(p.days, MonthlyEdits.move(m, a.date, rest.date, today)!!.days)
        val r = MonthlyEdits.makeRest(p, a.date, today)!!
        assertEquals(p.sessions.size - 1, r.sessions.size)
        val added = MonthlyEdits.setSession(r, a.date, MonthSport.WALK, MonthKind.EASY, 3_000.0, today)!!
        assertEquals(p.sessions.size, added.sessions.size)
        assertTrue(MonthlyEdits.moveTargets(p, a.date, today).isNotEmpty())
        assertTrue(MonthlyEdits.moveTargets(p, a.date, today.plusDays(40)).isEmpty())
    }

    @Test
    fun monthlyWarnings() {
        val p = monthPlan()
        val today = LocalDate.of(2026, 11, 1)
        val long = p.days.first { it.kind == MonthKind.LONG && it.week == 1 }
        val dayBefore = long.date.minusDays(1)
        val e = MonthlyEdits.setSession(p, dayBefore, MonthSport.RUN, MonthKind.TEMPO, 6_000.0, today)!!
        assertTrue(MonthlyEdits.warnings(e, dayBefore).any { it.contains("back to back") })
        val easyWeekDay = p.days.first { it.week == p.easyWeek && it.isRest }
        val inEasy = MonthlyEdits.setSession(p, easyWeekDay.date, MonthSport.RUN, MonthKind.INTERVALS, 5_000.0, today)!!
        assertTrue(MonthlyEdits.warnings(inEasy, easyWeekDay.date).any { it.contains("lighter week") })
        p.days.forEach { assertTrue("${it.date}", MonthlyEdits.warnings(p, it.date).isEmpty()) }
    }

    @Test
    fun makingSessionsRestDoesNotMakeThePlanEasierToComplete() {
        val ep = eventPlan()
        val today = start
        val gone = ep.sessions.filter { it.kind != SessionKind.RACE }.take(5).fold(ep) { acc, d -> PlanEdits.makeRest(acc, d.date, today)!! }
        assertEquals(ep.sessions.size, PlanGenerator.completion(gone, emptyMap()).second)
        val mp = monthPlan()
        val mt = LocalDate.of(2026, 11, 1)
        val mgone = mp.sessions.take(5).fold(mp) { acc, d -> MonthlyEdits.makeRest(acc, d.date, mt)!! }
        assertEquals(mp.sessions.size, MonthlyPlanner.completion(mgone, emptyMap()).second)
        // Adding sessions raises the total; moving doesn't change it.
        val rest = mp.days.first { it.isRest }
        val added = MonthlyEdits.setSession(mp, rest.date, MonthSport.WALK, MonthKind.EASY, 3_000.0, mt)!!
        assertEquals(mp.sessions.size + 1, MonthlyPlanner.completion(added, emptyMap()).second)
        val moved = MonthlyEdits.move(mp, mp.sessions.first().date, rest.date, mt)!!
        assertEquals(mp.sessions.size, MonthlyPlanner.completion(moved, emptyMap()).second)
    }

    @Test
    fun kindsPerSportAreSensible() {
        MonthSport.entries.forEach { s ->
            val k = MonthlyEdits.kinds(s)
            assertTrue(MonthKind.EASY in k && MonthKind.LONG in k)
            assertFalse(MonthKind.REST in k || MonthKind.CROSS in k)
        }
        assertFalse(MonthKind.BRISK in MonthlyEdits.kinds(MonthSport.RUN))
        assertNotNull(PlanEdits.kinds(PlanMode.RUN_WALK))
        PlanMode.entries.forEach { m -> assertTrue(PlanEdits.kinds(m).all { PlanEdits.label(it, m).isNotBlank() }) }
    }
}
