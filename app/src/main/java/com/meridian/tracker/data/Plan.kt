package com.meridian.tracker.data

import com.meridian.tracker.tracking.Sport
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class PlanMode(val label: String, val sport: Sport) {
    RUN("Run", Sport.RUN),
    WALK("Walk", Sport.WALK),
    RUN_WALK("Run-walk", Sport.WALK),
}

enum class TargetType { DISTANCE, TIME }

enum class SessionKind { EASY, LONG, TEMPO, INTERVALS, RUN_WALK, BRISK_WALK, REST, RACE }

data class PlanConfig(
    val name: String,
    val mode: PlanMode,
    val targetType: TargetType,
    val eventDistanceM: Double,
    val startDate: LocalDate,
    val eventDate: LocalDate,
    val daysPerWeek: Int,
    val longDay: DayOfWeek,
    /** The distance you can comfortably cover today. */
    val currentLongM: Double,
    /** Your usual easy pace for this mode, seconds per km. */
    val paceSecPerKm: Double,
    val reminderHour: Int = 6,
    val reminderMinute: Int = 0,
    val eveningNudge: Boolean = true,
)

data class PlanDay(
    val date: LocalDate,
    val kind: SessionKind,
    val distanceM: Double,
    val minutes: Int,
    val title: String,
    val detail: String,
    val week: Int,
) {
    val isRest: Boolean get() = kind == SessionKind.REST
}

data class TrainingPlan(
    val id: String,
    val config: PlanConfig,
    val days: List<PlanDay>,
    val createdMs: Long,
    /** True when the dates are shorter than ideal for the distance. */
    val compressed: Boolean,
    /** The longest training session before the event. */
    val longestM: Double = 0.0,
) {
    fun day(date: LocalDate): PlanDay? = days.firstOrNull { it.date == date }
    val sessions: List<PlanDay> get() = days.filterNot { it.isRest }
    val weeks: Int get() = (days.maxOfOrNull { it.week } ?: 0) + 1
}

enum class DayStatus { DONE, PARTIAL, MISSED, TODAY, UPCOMING, REST }

/**
 * Builds a periodised plan, base → build (with a lighter week every 4th week) → taper → event,
 * the same shape coaches use. Pure Kotlin, unit-tested.
 */
object PlanGenerator {

    fun minWeeks(eventM: Double): Int = when {
        eventM <= 5_500 -> 4
        eventM <= 10_500 -> 6
        eventM <= 21_500 -> 10
        else -> 16
    }

    private fun taperWeeks(eventM: Double): Int = when {
        eventM <= 10_500 -> 1
        eventM <= 21_500 -> 2
        else -> 3
    }

    fun defaultPace(mode: PlanMode): Double = when (mode) {
        PlanMode.RUN -> 420.0
        PlanMode.WALK -> 720.0
        PlanMode.RUN_WALK -> 570.0
    }

    /** Training weekdays as "days before the long day", so the day before it is always rest. */
    private fun offsets(daysPerWeek: Int): List<Int> = when (daysPerWeek.coerceIn(3, 6)) {
        3 -> listOf(5, 3)
        4 -> listOf(5, 4, 2)
        5 -> listOf(6, 5, 3, 2)
        else -> listOf(6, 5, 4, 3, 2)
    }

    fun generate(config: PlanConfig, id: String, createdMs: Long): TrainingPlan {
        val start = config.startDate
        val event = maxOf(config.eventDate, start)
        val e = config.eventDistanceM
        val totalDays = ChronoUnit.DAYS.between(start, event).toInt()
        val weeks = max(1, ceil((totalDays + 1) / 7.0).toInt())
        val taper = min(taperWeeks(e), weeks - 1).coerceAtLeast(0)
        val build = weeks - taper

        val peak = when (config.mode) {
            PlanMode.RUN -> when {
                e <= 5_500 -> e * 1.2
                e <= 10_500 -> e
                e <= 21_500 -> e * 0.85
                else -> min(32_000.0, e * 0.76)
            }
            else -> if (e <= 21_500) e * 0.9 else e * 0.7
        }
        val startLong = config.currentLongM.coerceIn(min(1_000.0, peak), peak)

        // Long session per week.
        val recovery = (0 until build).map { w -> w > 0 && (w + 1) % 4 == 0 && w < build - 1 }
        val growthWeeks = recovery.count { !it } - 1
        // Walking-based plans can build faster than running plans: less impact per step.
        val maxGrowth = if (config.mode == PlanMode.RUN) 1.12 else 1.2
        val growth = if (growthWeeks > 0) (peak / startLong).pow(1.0 / growthWeeks).coerceIn(1.0, maxGrowth) else 1.0
        val longs = DoubleArray(weeks)
        var current = startLong
        var lastNormal = startLong
        for (w in 0 until build) {
            if (w == 0) {
                longs[w] = startLong
            } else if (recovery[w]) {
                longs[w] = lastNormal * 0.8
                continue
            } else {
                current = min(peak, current * growth)
                longs[w] = current
            }
            lastNormal = longs[w]
        }
        val taperFactors = when (taper) {
            1 -> listOf(0.6)
            2 -> listOf(0.75, 0.5)
            3 -> listOf(0.8, 0.65, 0.45)
            else -> emptyList()
        }
        taperFactors.forEachIndexed { i, f -> longs[build + i] = max(peak * f, 1_000.0) }

        val trainingOffsets = offsets(config.daysPerWeek)
        val out = ArrayList<PlanDay>()
        var d = start
        while (!d.isAfter(event)) {
            val week = (ChronoUnit.DAYS.between(start, d) / 7).toInt()
            val isRecovery = week < build && recovery[week]
            val isTaper = week >= build
            val daysToEvent = ChronoUnit.DAYS.between(d, event).toInt()
            val before = Math.floorMod(config.longDay.value - d.dayOfWeek.value, 7)
            val long = longs[week.coerceAtMost(weeks - 1)]

            val day = when {
                d == event -> session(config, SessionKind.RACE, e, d, week, "", weeks)
                daysToEvent == 1 -> rest(d, week, "Rest before the big day. Lay out your kit tonight.")
                before == 0 && daysToEvent <= 3 -> session(config, easyKind(config), max(1_000.0, e * 0.3), d, week, "Short and easy. Save your legs.", weeks)
                before == 0 -> session(config, SessionKind.LONG, long, d, week, if (isRecovery) "Recovery week: a little lighter." else "", weeks)
                before in trainingOffsets -> {
                    val idx = trainingOffsets.indexOf(before)
                    val kind = midKind(config, idx, week, isRecovery || isTaper || daysToEvent <= 6)
                    val factor = if (isTaper) 0.7 else 1.0
                    val dist = when (kind) {
                        SessionKind.TEMPO -> long * 0.55
                        SessionKind.INTERVALS -> long * 0.5
                        else -> long * 0.5
                    }.coerceAtLeast(if (config.mode == PlanMode.RUN) 2_000.0 else 1_500.0) * factor
                    session(config, kind, dist, d, week, if (isRecovery) "Recovery week: a little lighter." else "", weeks)
                }
                else -> rest(d, week, "Recovery is training too. Stretch, or take a gentle stroll.")
            }
            out.add(day)
            d = d.plusDays(1)
        }
        val longest = out.filter { it.kind == SessionKind.LONG }.maxOfOrNull { it.distanceM } ?: 0.0
        return TrainingPlan(
            id, config, out, createdMs,
            compressed = weeks < minWeeks(e) || longest < peak * 0.75,
            longestM = longest,
        )
    }

    private fun easyKind(c: PlanConfig) = when (c.mode) {
        PlanMode.RUN -> SessionKind.EASY
        PlanMode.WALK -> SessionKind.EASY
        PlanMode.RUN_WALK -> SessionKind.RUN_WALK
    }

    private fun midKind(c: PlanConfig, index: Int, week: Int, easyWeek: Boolean): SessionKind = when (c.mode) {
        PlanMode.RUN -> when {
            easyWeek || week < 2 || index != 1 -> SessionKind.EASY
            week % 2 == 0 -> SessionKind.TEMPO
            else -> SessionKind.INTERVALS
        }
        PlanMode.WALK -> if (index % 2 == 1 && !easyWeek) SessionKind.BRISK_WALK else SessionKind.EASY
        PlanMode.RUN_WALK -> if (index == 1 && !easyWeek) SessionKind.BRISK_WALK else SessionKind.RUN_WALK
    }

    private fun rest(date: LocalDate, week: Int, detail: String) =
        PlanDay(date, SessionKind.REST, 0.0, 0, "Rest", detail, week)

    private fun session(c: PlanConfig, kind: SessionKind, distanceM: Double, date: LocalDate, week: Int, extra: String, weeks: Int = 1): PlanDay {
        val paceFactor = when (kind) {
            SessionKind.LONG -> 1.05
            SessionKind.TEMPO -> 0.9
            SessionKind.BRISK_WALK -> 0.9
            else -> 1.0
        }
        val dist = (distanceM / 100.0).roundToInt() * 100.0
        val minutes = max(10, ((dist / 1000.0) * c.paceSecPerKm * paceFactor / 60.0 / 5).roundToInt() * 5)
        val noun = when (c.mode) {
            PlanMode.RUN -> "run"
            PlanMode.WALK -> "walk"
            PlanMode.RUN_WALK -> "run-walk"
        }
        val progress = if (weeks > 1) week.toDouble() / (weeks - 1) else 1.0
        val runMin = 1 + (progress * 4).roundToInt()
        val walkMin = if (progress < 0.5) 2 else 1
        val (title, detail) = when (kind) {
            SessionKind.EASY -> "Easy $noun" to
                if (c.mode == PlanMode.RUN) "Comfortable pace. You could hold a conversation." else "Relaxed and steady."
            SessionKind.LONG -> "Long $noun" to when (c.mode) {
                PlanMode.RUN_WALK -> "Your key session. Run $runMin min, walk $walkMin min, and repeat."
                else -> "Your key session this week. Slower than event pace is perfect."
            }
            SessionKind.TEMPO -> "Tempo run" to "10 min easy, ${max(10, minutes - 20)} min comfortably hard, 10 min easy."
            SessionKind.INTERVALS -> "Intervals" to "Warm up, then ${4 + min(4, week / 2)} × 400 m fast with 200 m easy between. Cool down."
            SessionKind.RUN_WALK -> "Run-walk" to "Run $runMin min, walk $walkMin min, and repeat. Walk whenever you need to."
            SessionKind.BRISK_WALK -> "Brisk walk" to "Walk with purpose: quick steps, swing your arms."
            SessionKind.RACE -> c.name.ifBlank { "Event day" } to
                if (c.mode == PlanMode.RUN_WALK) "Start with your run, walk when you need to, and enjoy every metre."
                else "Start easy, settle in, enjoy it. You've trained for this."
            SessionKind.REST -> "Rest" to ""
        }
        return PlanDay(date, kind, dist, minutes, title, listOf(detail, extra).filter { it.isNotBlank() }.joinToString(" "), week)
    }

    // ---- Progress ---------------------------------------------------------------------------

    private fun countsFor(mode: PlanMode, sport: Sport) = when (mode) {
        PlanMode.RUN -> sport == Sport.RUN
        PlanMode.WALK, PlanMode.RUN_WALK -> sport == Sport.WALK || sport == Sport.RUN
    }

    /** How each day went, comparing the plan with what you actually recorded. */
    fun status(
        plan: TrainingPlan,
        records: List<ActivityRecord>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<LocalDate, DayStatus> {
        val byDay = records
            .filter { countsFor(plan.config.mode, it.sport) }
            .groupBy { Stats.localDate(it.startTimeMs, zone) }
        return plan.days.associate { day ->
            val status = if (day.isRest) {
                DayStatus.REST
            } else {
                val done = byDay[day.date].orEmpty()
                val ratio = when (plan.config.targetType) {
                    TargetType.DISTANCE -> done.sumOf { it.distanceM } / max(day.distanceM, 1.0)
                    TargetType.TIME -> done.sumOf { it.movingMs } / 60_000.0 / max(day.minutes, 1)
                }
                when {
                    ratio >= 0.8 -> DayStatus.DONE
                    ratio > 0 && !day.date.isAfter(today) -> if (day.date == today) DayStatus.TODAY else DayStatus.PARTIAL
                    day.date.isBefore(today) -> DayStatus.MISSED
                    day.date == today -> DayStatus.TODAY
                    else -> DayStatus.UPCOMING
                }
            }
            day.date to status
        }
    }

    /**
     * Gentle adaptation: if last week's long session was missed, this week's long session
     * stays at last week's level instead of jumping up.
     */
    fun adjusted(plan: TrainingPlan, status: Map<LocalDate, DayStatus>): List<PlanDay> {
        val longByWeek = plan.days.filter { it.kind == SessionKind.LONG }.associateBy { it.week }
        return plan.days.map { day ->
            if (day.kind != SessionKind.LONG) return@map day
            val prev = longByWeek[day.week - 1] ?: return@map day
            if (status[prev.date] == DayStatus.MISSED && prev.distanceM < day.distanceM) {
                day.copy(
                    distanceM = prev.distanceM,
                    minutes = prev.minutes,
                    detail = day.detail + " Kept at last week's distance since that one was missed.",
                )
            } else {
                day
            }
        }
    }

    fun completion(plan: TrainingPlan, status: Map<LocalDate, DayStatus>): Pair<Int, Int> {
        val sessions = plan.sessions
        return sessions.count { status[it.date] == DayStatus.DONE } to sessions.size
    }
}
