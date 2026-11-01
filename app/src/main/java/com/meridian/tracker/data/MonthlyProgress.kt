package com.meridian.tracker.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Monthly plan, phase 3: how you are doing against the plan, and what to do about a missed session.
 *  - [MonthlyProgress]: planned vs actual per week, and whether you are on track so far
 *  - [MonthlyCatchUp]: moves a missed key session to a free day (when you agree), and keeps next
 *    week gentle after a week with several missed sessions
 * Pure Kotlin, unit-tested. Author: direk james.
 */

/** One week of the plan next to what you actually recorded. */
data class WeekStat(
    val week: Int,
    val start: LocalDate,
    val end: LocalDate,
    val plannedMin: Int,
    val plannedM: Double,
    val actualMin: Int,
    val actualM: Double,
    val sessions: Int,
    val done: Int,
    val missed: Int,
    val isEasy: Boolean,
) {
    fun planned(type: TargetType): Double = if (type == TargetType.TIME) plannedMin.toDouble() else plannedM
    fun actual(type: TargetType): Double = if (type == TargetType.TIME) actualMin.toDouble() else actualM
}

enum class Pace(val label: String) {
    NOT_STARTED("Not started"),
    AHEAD("Ahead"),
    ON_TRACK("On track"),
    BEHIND("A bit behind"),
    WAY_BEHIND("Behind"),
}

/** [planned] and [actual] are metres or minutes, depending on the plan's targets. */
data class PaceCheck(val pace: Pace, val planned: Double, val actual: Double)

object MonthlyProgress {

    /** Weeks of the plan with planned and actual totals. Actual counts every matching activity, even on a rest day. */
    fun weeks(
        plan: MonthlyPlan,
        records: List<ActivityRecord>,
        status: Map<LocalDate, DayStatus>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<WeekStat> {
        val moved = plan.movedAway
        val sports = plan.config.sports
        val matching = records.filter { r -> sports.any { it.counts(r.sport) } }
            .map { Stats.localDate(it.startTimeMs, zone) to it }
        return plan.days.groupBy { it.week }.toSortedMap().map { (week, days) ->
            val start = days.minOf { it.date }
            val end = days.maxOf { it.date }
            val sessions = days.filter { !it.isRest && it.date !in moved }
            val actual = matching.filter { (d, _) -> !d.isBefore(start) && !d.isAfter(end) }.map { it.second }
            WeekStat(
                week = week,
                start = start,
                end = end,
                plannedMin = sessions.sumOf { it.minutes },
                plannedM = sessions.sumOf { it.distanceM },
                actualMin = (actual.sumOf { it.movingMs } / 60_000.0).roundToInt(),
                actualM = actual.sumOf { it.distanceM },
                sessions = sessions.size,
                done = sessions.count { status[it.date] == DayStatus.DONE },
                missed = sessions.count { status[it.date] == DayStatus.MISSED },
                isEasy = week == plan.easyWeek,
            )
        }
    }

    /**
     * Planned so far (sessions before today, plus today's if it is done) against what you did up to today.
     * A missed session you moved to a later day doesn't count against you until that day comes.
     */
    fun paceCheck(
        plan: MonthlyPlan,
        records: List<ActivityRecord>,
        status: Map<LocalDate, DayStatus>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PaceCheck {
        val type = plan.config.targetType
        val moved = plan.movedAway
        val due = plan.days.filter { d ->
            !d.isRest && d.date !in moved && (d.date.isBefore(today) || (d.date == today && status[d.date] == DayStatus.DONE))
        }
        val planned = if (type == TargetType.TIME) due.sumOf { it.minutes }.toDouble() else due.sumOf { it.distanceM }
        val sports = plan.config.sports
        val actual = records
            .filter { r -> sports.any { it.counts(r.sport) } }
            .filter { r ->
                val d = Stats.localDate(r.startTimeMs, zone)
                !d.isBefore(plan.config.startDate) && !d.isAfter(today)
            }
            .let { list -> if (type == TargetType.TIME) list.sumOf { it.movingMs } / 60_000.0 else list.sumOf { it.distanceM } }
        val pace = when {
            planned <= 0.0 -> if (actual > 0.0) Pace.AHEAD else Pace.NOT_STARTED
            actual / planned >= 1.10 -> Pace.AHEAD
            actual / planned >= 0.85 -> Pace.ON_TRACK
            actual / planned >= 0.55 -> Pace.BEHIND
            else -> Pace.WAY_BEHIND
        }
        return PaceCheck(pace, planned, actual)
    }
}

/**
 * A missed session and the day it could be done instead. [nextToHardDay] is true when no day
 * with a hard session on neither side was free, so the catch-up would follow or precede one.
 */
data class CatchUp(val missed: MonthDay, val target: LocalDate, val nextToHardDay: Boolean = false)

/** After a week with several missed sessions: keep the next week at this week's load. */
data class HoldWeek(
    val week: Int,
    val missedCount: Int,
    val nextWeek: Int,
    val fromMin: Int,
    val toMin: Int,
    /** Identifies this suggestion in [MonthlyPlan.dismissed]: the first day of [week]. */
    val key: LocalDate,
)

object MonthlyCatchUp {

    private const val LOOK_BACK_DAYS = 7L
    private const val LOOK_AHEAD_DAYS = 6L
    private const val MAX_SUGGESTIONS = 2
    private const val HOLD_AFTER_MISSED = 2
    private const val HOLD_MARGIN = 1.08
    private const val HOLD_FLOOR = 0.7

    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    /**
     * Missed key sessions (long, tempo, intervals, hills, brisk) from the last week, each with the
     * first free rest day in the coming week that doesn't sit next to another hard day. If every
     * free day touches one, the earliest day with a hard session on one side only is offered, and
     * flagged. A day with hard sessions on both sides is never offered.
     */
    fun suggestions(plan: MonthlyPlan, status: Map<LocalDate, DayStatus>, today: LocalDate): List<CatchUp> {
        val moved = plan.movedAway
        val missed = plan.days
            .filter { d ->
                !d.isRest && d.kind.hard && d.date !in moved && d.date !in plan.dismissed &&
                    status[d.date] == DayStatus.MISSED &&
                    d.date.isBefore(today) && !d.date.isBefore(today.minusDays(LOOK_BACK_DAYS))
            }
            .sortedWith(compareByDescending<MonthDay> { it.kind == MonthKind.LONG }.thenByDescending { it.date })
            .take(MAX_SUGGESTIONS)
        val used = mutableSetOf<LocalDate>()
        val out = mutableListOf<CatchUp>()
        for (m in missed) {
            val (slot, touching) = findSlot(plan, today, used) ?: continue
            used += slot
            out += CatchUp(m, slot, nextToHardDay = touching)
        }
        return out
    }

    private fun findSlot(plan: MonthlyPlan, today: LocalDate, taken: Set<LocalDate>): Pair<LocalDate, Boolean>? {
        val free = plan.config.freeDays
        var fallback: LocalDate? = null
        for (offset in 0..LOOK_AHEAD_DAYS) {
            val d = today.plusDays(offset)
            val day = plan.day(d) ?: continue
            if (!day.isRest || day.edited || d in taken) continue
            if (free.isNotEmpty() && d.dayOfWeek !in free) continue
            val hardSides = listOf(d.minusDays(1), d.plusDays(1)).count { n -> n in taken || plan.day(n)?.kind?.hard == true }
            if (hardSides == 0) return d to false
            if (hardSides == 1 && fallback == null) fallback = d
        }
        return fallback?.let { it to true }
    }

    /** Schedules the missed session again on [CatchUp.target]. Null when that day is no longer free. */
    fun apply(plan: MonthlyPlan, c: CatchUp, today: LocalDate): MonthlyPlan? {
        val missed = plan.day(c.missed.date)?.takeIf { !it.isRest } ?: return null
        val target = plan.day(c.target) ?: return null
        if (!target.isRest || c.target.isBefore(today) || !MonthlyEdits.canEdit(plan, c.target, today)) return null
        if (missed.date in plan.movedAway) return null
        val copy = missed.copy(
            date = c.target,
            week = target.week,
            catchUpOf = missed.date,
            detail = "Catch-up for ${missed.date.format(dayFmt)}. ${missed.detail}".trim(),
        )
        return MonthlyEdits.put(plan, listOf(copy)).let { it.copy(dismissed = it.dismissed - missed.date) }
    }

    /** "No thanks": don't suggest this again. */
    fun dismiss(plan: MonthlyPlan, key: LocalDate): MonthlyPlan = plan.copy(dismissed = plan.dismissed + key)

    /**
     * The most recent week with two or more missed sessions, when the week after it is bigger and
     * still has days to come. Applying it brings that week down to this week's load.
     */
    fun hold(plan: MonthlyPlan, status: Map<LocalDate, DayStatus>, today: LocalDate): HoldWeek? {
        val moved = plan.movedAway
        val byWeek = plan.days.groupBy { it.week }
        for (week in byWeek.keys.sortedDescending()) {
            val days = byWeek.getValue(week)
            val key = days.minOf { it.date }
            if (key in plan.dismissed) continue
            val missed = days.count { !it.isRest && it.date !in moved && status[it.date] == DayStatus.MISSED }
            if (missed < HOLD_AFTER_MISSED) continue
            val next = byWeek[week + 1] ?: return null
            val upcoming = next.filter { !it.isRest && !it.date.isBefore(today) }
            if (upcoming.isEmpty()) return null
            val from = days.filter { !it.isRest && it.date !in moved }.sumOf { it.minutes }
            val to = next.filter { !it.isRest }.sumOf { it.minutes }
            if (from <= 0 || to <= from * HOLD_MARGIN) return null
            return HoldWeek(week, missed, week + 1, from, to, key)
        }
        return null
    }

    /** Scales the coming sessions of the next week (not ones you edited) so the week matches this one. */
    fun applyHold(plan: MonthlyPlan, h: HoldWeek, today: LocalDate): MonthlyPlan {
        val factor = max(HOLD_FLOOR, min(1.0, h.fromMin.toDouble() / h.toMin))
        val byTime = plan.config.targetType == TargetType.TIME
        var out = plan
        plan.days
            .filter { it.week == h.nextWeek && !it.isRest && !it.edited && !it.date.isBefore(today) && it.sport != null }
            .forEach { d ->
                val amount = (if (byTime) d.minutes.toDouble() else d.distanceM) * factor
                MonthlyEdits.setSession(out, d.date, d.sport!!, MonthlyEdits.editableKind(d, d.sport!!), amount, today)?.let { out = it }
            }
        return dismiss(out, h.key)
    }
}
