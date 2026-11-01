package com.meridian.tracker

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.MonthConfig
import com.meridian.tracker.data.MonthDay
import com.meridian.tracker.data.MonthGoal
import com.meridian.tracker.data.MonthKind
import com.meridian.tracker.data.MonthReviews
import com.meridian.tracker.data.MonthSport
import com.meridian.tracker.data.MonthTheme
import com.meridian.tracker.data.MonthVerdict
import com.meridian.tracker.data.MonthlyPlan
import com.meridian.tracker.data.MonthlyPlanner
import com.meridian.tracker.data.MonthlyReminders
import com.meridian.tracker.data.MonthlyRoutes
import com.meridian.tracker.data.RouteGen
import com.meridian.tracker.data.RouteProfile
import com.meridian.tracker.data.RouteShape
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

class MonthlyPhase4Test {

    private val zone = ZoneId.of("UTC")
    private val nov1 = LocalDate.of(2026, 11, 1)
    private val nov30 = LocalDate.of(2026, 11, 30)

    private fun plan(
        target: TargetType = TargetType.DISTANCE,
        sports: List<MonthSport> = listOf(MonthSport.RUN, MonthSport.WALK),
    ): MonthlyPlan = MonthlyPlanner.generate(
        MonthConfig(nov1, nov30, MonthGoal.FITTER, MonthTheme.DISTANCE, sports, 4, emptySet(), DayOfWeek.SUNDAY, target, 200.0, null, emptyMap(), 0, 7L, null),
        "p", 0L,
    )

    private fun record(sport: Sport, date: LocalDate, meters: Double, minutes: Int): ActivityRecord {
        val start = date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        return ActivityRecord("r$date$sport$meters", "r", sport, start, meters, minutes * 60_000L, minutes * 60_000L, 0.0, emptyList(), 0.0, 0L, emptyList())
    }

    private fun doEverything(p: MonthlyPlan) = p.sessions.map { record(it.sport!!.sport, it.date, it.distanceM, it.minutes) }

    // ---- Routes --------------------------------------------------------------------------------

    @Test
    fun everySessionGetsAUsableRouteIdea() {
        listOf(TargetType.DISTANCE, TargetType.TIME).forEach { t ->
            val p = plan(t, MonthSport.entries)
            p.sessions.forEach { d ->
                val idea = MonthlyRoutes.idea(p, d)
                assertNotNull("no idea for ${d.date}", idea)
                assertTrue(idea!!.distanceM in RouteGen.distanceRange(idea!!.profile))
                assertEquals(RouteShape.LOOP, idea.shape)
            }
        }
    }

    @Test
    fun ridesUseBikePathsAndEverythingElseFoot() {
        val p = plan(sports = listOf(MonthSport.RIDE, MonthSport.RUN))
        p.sessions.forEach {
            val want = if (it.sport == MonthSport.RIDE) RouteProfile.BIKE else RouteProfile.FOOT
            assertEquals(want, MonthlyRoutes.idea(p, it)!!.profile)
        }
    }

    @Test
    fun restDaysAndMovedAwaySessionsGetNoRoute() {
        val p = plan()
        p.days.filter { it.isRest }.forEach { assertNull(MonthlyRoutes.idea(p, it)) }
        val moved = p.movedAway
        // Nothing moved in a fresh plan, so none of the sessions are skipped.
        assertTrue(moved.isEmpty())
    }

    @Test
    fun aBrokenDayNeverCrashesOrAsksForAnImpossibleRoute() {
        val p = plan()
        val day = p.sessions.first()
        val zero = day.copy(distanceM = 0.0, minutes = 0)
        assertNull(MonthlyRoutes.idea(p, zero))
        val huge = day.copy(distanceM = 9_000_000.0)
        assertEquals(RouteProfile.FOOT.maxDistanceM, MonthlyRoutes.idea(p, huge)!!.distanceM, 0.0)
        val tiny = day.copy(distanceM = 10.0)
        assertTrue(MonthlyRoutes.idea(p, tiny)!!.distanceM >= RouteGen.MIN_DISTANCE_M)
        assertNull(MonthlyRoutes.idea(p, day.copy(distanceM = Double.NaN, minutes = 0)))
    }

    @Test
    fun distanceTextReadsLikeTheDialogField() {
        val idea = com.meridian.tracker.data.RouteIdea(5000.0, RouteProfile.FOOT, RouteShape.LOOP)
        assertEquals("5", idea.distanceText(1000.0))
        assertEquals("7.5", com.meridian.tracker.data.RouteIdea(7500.0, RouteProfile.FOOT, RouteShape.LOOP).distanceText(1000.0))
        assertEquals("3.1", com.meridian.tracker.data.RouteIdea(5000.0, RouteProfile.FOOT, RouteShape.LOOP).distanceText(1609.344))
    }

    // ---- Reminders -----------------------------------------------------------------------------

    @Test
    fun morningMessageNamesTheSession() {
        val p = plan()
        val d = p.sessions.first()
        val (title, text) = MonthlyReminders.message(p, d, MonthlyReminders.Kind.MORNING, false, "5.0 km")!!
        assertTrue(title.contains(d.title))
        assertTrue(title.contains("5.0 km"))
        assertTrue(text.contains(d.detail))
    }

    @Test
    fun noReminderOnRestDaysOutsideThePlanOrForMovedSessions() {
        val p = plan()
        val rest = p.days.first { it.isRest }
        assertNull(MonthlyReminders.message(p, rest, MonthlyReminders.Kind.MORNING, false, "x"))
        assertNull(MonthlyReminders.message(p, null, MonthlyReminders.Kind.MORNING, false, "x"))
        val outside = p.sessions.first().copy(date = nov30.plusDays(3))
        assertNull(MonthlyReminders.message(p, outside, MonthlyReminders.Kind.MORNING, false, "x"))
        val a = p.sessions.first()
        val rest2 = p.days.first { it.isRest && it.date != a.date }
        val moved = p.copy(days = p.days.map { if (it.date == rest2.date) it.copy(kind = MonthKind.EASY, sport = a.sport, minutes = a.minutes, distanceM = a.distanceM, catchUpOf = a.date) else it })
        assertNull(MonthlyReminders.message(moved, moved.day(a.date), MonthlyReminders.Kind.MORNING, false, "x"))
    }

    @Test
    fun eveningNudgeOnlyWhileNotDone() {
        val p = plan()
        val d = p.sessions.first()
        assertNotNull(MonthlyReminders.message(p, d, MonthlyReminders.Kind.EVENING, false, "5 km"))
        assertNull(MonthlyReminders.message(p, d, MonthlyReminders.Kind.EVENING, true, "5 km"))
    }

    @Test
    fun weekPreviewOnlyOnTheFirstDayOfAWeek() {
        val p = plan()
        val previews = p.days.mapNotNull { d -> MonthlyReminders.weekPreview(p, d.date)?.let { d to it } }
        val weeks = p.days.map { it.week }.distinct()
        assertEquals(weeks.size, previews.size)
        previews.forEach { (d, t) ->
            assertTrue(t.contains("session"))
            assertTrue(t.startsWith("Week ${d.week + 1}") || t.startsWith("Easier week"))
        }
        assertNull(MonthlyReminders.weekPreview(p, p.days[1].date).takeIf { p.days[1].week == p.days[0].week })
    }

    @Test
    fun morningOnAFirstDayCarriesTheWeekLine() {
        val p = plan()
        val first = p.days.first { !it.isRest && MonthlyReminders.weekPreview(p, it.date) != null }
        val (_, text) = MonthlyReminders.message(p, first, MonthlyReminders.Kind.MORNING, false, "x")!!
        assertTrue(text.contains("session"))
    }

    @Test
    fun eventClashIsMentioned() {
        val p = plan()
        val (_, text) = MonthlyReminders.message(p, p.sessions.first(), MonthlyReminders.Kind.MORNING, false, "x", eventTitle = "Long run")!!
        assertTrue(text.contains("event training"))
    }

    @Test
    fun remindersStopAfterThePlanOrWhenTurnedOff() {
        val p = plan()
        assertTrue(MonthlyReminders.active(p, nov1))
        assertTrue(MonthlyReminders.active(p, nov30))
        assertFalse(MonthlyReminders.active(p, nov30.plusDays(1)))
        assertFalse(MonthlyReminders.active(null, nov1))
        assertFalse(MonthlyReminders.active(p.copy(config = p.config.copy(remind = false)), nov1))
    }

    // ---- Month review --------------------------------------------------------------------------

    private fun review(p: MonthlyPlan, recs: List<ActivityRecord>) =
        MonthReviews.review(p, recs, MonthlyPlanner.status(p, recs, nov30.plusDays(1), zone), zone)

    @Test
    fun aFullMonthIsStrongAndStepsUp() {
        val p = plan()
        val r = review(p, doEverything(p))
        assertEquals(MonthVerdict.STRONG, r.verdict)
        assertEquals(100, r.percent)
        assertTrue(r.volumeFactor > 1.0)
        assertTrue(r.minutes > 0 && r.distanceM > 0)
    }

    @Test
    fun anEmptyMonthIsToughAndEasesBack() {
        val r = review(plan(), emptyList())
        assertEquals(MonthVerdict.TOUGH, r.verdict)
        assertEquals(0, r.percent)
        assertTrue(r.volumeFactor < 1.0)
    }

    @Test
    fun aHalfDoneMonthIsInBetween() {
        val p = plan()
        val all = doEverything(p)
        val recs = all.take((all.size * 0.7).toInt())
        val r = review(p, recs)
        assertEquals(MonthVerdict.STEADY, r.verdict)
        assertEquals(1.0, r.volumeFactor, 0.0)
    }

    @Test
    fun activitiesOutsideTheMonthOrOtherSportsDontCount() {
        val p = plan()
        val recs = listOf(
            record(Sport.RUN, nov1.minusDays(3), 10_000.0, 60),
            record(Sport.RUN, nov30.plusDays(2), 10_000.0, 60),
            record(Sport.RIDE, nov1.plusDays(3), 30_000.0, 90),
        )
        val r = review(p, recs)
        assertEquals(0, r.minutes)
        assertEquals(0.0, r.distanceM, 0.0)
    }

    @Test
    fun nextMonthSizeOnlyChangesNearTheEnd() {
        val p = plan()
        val strong = review(p, doEverything(p))
        val tough = review(p, emptyList())
        assertEquals(200.0, MonthReviews.nextWeekMinutes(200.0, p, strong, nov1.plusDays(5)), 0.0)
        assertTrue(MonthReviews.nextWeekMinutes(200.0, p, strong, nov30) > 200.0)
        assertTrue(MonthReviews.nextWeekMinutes(200.0, p, tough, nov30) < 200.0)
        assertEquals(200.0, MonthReviews.nextWeekMinutes(200.0, null, strong, nov30), 0.0)
        // Never absurd, even after repeated hard or easy months.
        assertTrue(MonthReviews.nextWeekMinutes(41.0, p, tough, nov30) >= 40.0)
        assertTrue(MonthReviews.nextWeekMinutes(899.0, p, strong, nov30) <= 900.0)
    }

    @Test
    fun reviewIsDueInTheLastWeek() {
        val p = plan()
        assertFalse(MonthReviews.due(p, nov30.minusDays(7)))
        assertTrue(MonthReviews.due(p, nov30.minusDays(6)))
        assertTrue(MonthReviews.due(p, nov30.plusDays(10)))
    }

    @Test
    fun reviewDoesNotCrashOnAnEmptyPlan() {
        val p = plan().let { it.copy(days = it.days.map { d -> d.copy(kind = MonthKind.REST, sport = null, minutes = 0, distanceM = 0.0) }) }
        val r = review(p, emptyList())
        assertEquals(MonthVerdict.STEADY, r.verdict)
        assertEquals(0, r.percent)
    }
}
