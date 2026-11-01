package com.meridian.tracker.data

import com.meridian.tracker.tracking.Sport
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * Monthly plan: a month of mixed walking, running, hiking and riding, separate from the event plan.
 * Every month gets a different theme (base, speed, long distance, mixed) and a seeded shuffle, so
 * no two months feel the same. Pure Kotlin, unit-tested. Author: direk james.
 */

/** Sports a monthly plan can mix. Run-walk records as a walk, like the event plan. */
enum class MonthSport(
    val label: String,
    val sport: Sport,
    /** Default easy pace, seconds per km. */
    val defaultPace: Double,
    /** Shortest useful session, minutes. */
    val minMinutes: Int,
    private val paceRange: ClosedFloatingPointRange<Double>,
) {
    RUN("Run", Sport.RUN, 420.0, 20, 180.0..900.0),
    RUN_WALK("Run-walk", Sport.WALK, 570.0, 20, 300.0..1_000.0),
    WALK("Walk", Sport.WALK, 720.0, 20, 400.0..1_200.0),
    HIKE("Hike", Sport.HIKE, 900.0, 40, 500.0..1_800.0),
    RIDE("Ride", Sport.RIDE, 150.0, 30, 80.0..400.0);

    /** Whether an activity of [s] counts toward a session of this sport. */
    fun counts(s: Sport): Boolean = when (this) {
        RUN -> s.isRunning
        RUN_WALK, WALK -> s.isRunning || s.isWalking
        HIKE -> s.isWalking || s == Sport.TRAIL_RUN
        RIDE -> s == Sport.RIDE
    }

    /** History that tells us this sport's usual pace. */
    fun paceSource(s: Sport): Boolean = when (this) {
        RUN -> s.isRunning
        RUN_WALK -> false
        WALK -> s == Sport.WALK
        HIKE -> s == Sport.HIKE
        RIDE -> s == Sport.RIDE
    }

    fun clampPace(p: Double): Double = if (p.isNaN() || p <= 0) defaultPace else p.coerceIn(paceRange)

    val noun: String
        get() = when (this) {
            RUN -> "run"
            RUN_WALK -> "run-walk"
            WALK -> "walk"
            HIKE -> "hike"
            RIDE -> "ride"
        }
}

enum class MonthGoal(val label: String, val blurb: String) {
    FITTER("Get fitter", "A balanced mix with a little speed."),
    FURTHER("Go further", "Grows your long session month by month."),
    BURN("Burn more", "More time moving, mostly easy."),
    STEADY("Stay consistent", "Friendly sessions that are easy to keep up."),
}

enum class MonthTheme(
    val label: String,
    val tagline: String,
    /** Weekly load for the first three weeks, relative to your usual week. */
    val shape: List<Double>,
    /** Share of the week's time in the long session. */
    val longShare: Double,
    /** Harder sessions per week (before the goal and easy week trim it). */
    val quality: Int,
    /** Share of easy days given to your other sports. */
    val crossShare: Double,
    /** Overall size of the month compared with your usual month. */
    val volume: Double,
) {
    BASE("Base month", "Plenty of easy time on your feet to build your engine.", listOf(0.96, 1.04, 1.10), 0.30, 1, 0.15, 1.05),
    SPEED("Speed month", "A little shorter, a little faster: tempo, intervals and hills.", listOf(1.0, 1.08, 1.03), 0.27, 2, 0.10, 0.94),
    DISTANCE("Long-distance month", "One big session each week that keeps growing.", listOf(0.92, 1.03, 1.14), 0.40, 1, 0.10, 1.08),
    MIXED("Mixed month", "Something different: more cross-training and new terrain.", listOf(1.0, 1.06, 1.06), 0.30, 1, 0.40, 1.0),
}

enum class MonthKind(val hard: Boolean) {
    EASY(false), LONG(true), TEMPO(true), INTERVALS(true), HILLS(true), BRISK(true), CROSS(false), RECOVERY(false), REST(false)
}

data class MonthConfig(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val goal: MonthGoal,
    val theme: MonthTheme,
    /** The first sport is your main one: it gets the long session. */
    val sports: List<MonthSport>,
    val daysPerWeek: Int,
    /** Days you can train. Empty means any day. */
    val freeDays: Set<DayOfWeek>,
    val longDay: DayOfWeek,
    val targetType: TargetType,
    /** Your usual week right now, in minutes of activity. */
    val weeklyMinutes: Double,
    /** Optional month target: metres for distance targets, minutes for time targets. */
    val monthTarget: Double? = null,
    /** Easy pace per sport, seconds per km. */
    val paces: Map<MonthSport, Double> = emptyMap(),
    /** How many monthly plans came before this one (drives the theme rotation). */
    val monthIndex: Int = 0,
    val seed: Long = 0L,
    /** Last month's planned minutes, so this month can be a different size. */
    val prevTotalMin: Double? = null,
    /** Daily reminders for this plan. */
    val remind: Boolean = true,
    val reminderHour: Int = 7,
    val reminderMinute: Int = 0,
    /** A second nudge in the evening if today's session isn't done. */
    val eveningNudge: Boolean = true,
) {
    fun pace(s: MonthSport): Double = s.clampPace(paces[s] ?: s.defaultPace)
}

data class MonthDay(
    val date: LocalDate,
    val sport: MonthSport?,
    val kind: MonthKind,
    val minutes: Int,
    val distanceM: Double,
    val title: String,
    val detail: String,
    val week: Int,
    /** True when you changed this day yourself (see [MonthlyEdits]). */
    val edited: Boolean = false,
    /** Set when this session is a catch-up for a missed one on that date (see [MonthlyCatchUp]). */
    val catchUpOf: LocalDate? = null,
) {
    val isRest: Boolean get() = kind == MonthKind.REST
}

data class MonthlyPlan(
    val id: String,
    val config: MonthConfig,
    val days: List<MonthDay>,
    val createdMs: Long,
    /** Index of the lighter week, or -1 when the plan is too short for one. */
    val easyWeek: Int,
    /** For every day you edited: how it was first planned, so it can be reset. */
    val originals: Map<LocalDate, MonthDay> = emptyMap(),
    /** Suggestions you said no to: a missed session's date, or the first day of a week (see [MonthlyCatchUp]). */
    val dismissed: Set<LocalDate> = emptySet(),
) {
    fun day(date: LocalDate): MonthDay? = days.firstOrNull { it.date == date }

    /** Missed sessions that now have a catch-up scheduled: they no longer count as planned. */
    val movedAway: Set<LocalDate> get() = days.filter { !it.isRest }.mapNotNull { it.catchUpOf }.toSet()

    val sessions: List<MonthDay> get() = days.filter { !it.isRest && it.date !in movedAway }
    val totalMinutes: Int get() = sessions.sumOf { it.minutes }
    val totalM: Double get() = sessions.sumOf { it.distanceM }
    val weeks: Int get() = (days.maxOfOrNull { it.week } ?: 0) + 1

    /** How close the plan gets to the month target (1.0 = all of it), or null without a target. */
    val targetShare: Double?
        get() = config.monthTarget?.takeIf { it > 0 }?.let {
            (if (config.targetType == TargetType.TIME) totalMinutes.toDouble() else totalM) / it
        }
}

object MonthlyPlanner {

    /** A plan needs at least a week to be worth it. */
    const val MIN_DAYS = 7
    private const val EASY_WEEK = 0.70
    private const val AFTER_EASY = 0.90

    fun monthEnd(d: LocalDate): LocalDate = d.withDayOfMonth(d.lengthOfMonth())

    /** Days left in this month, counting today. */
    fun daysLeft(today: LocalDate): Int = ChronoUnit.DAYS.between(today, monthEnd(today)).toInt() + 1

    private fun rotation(goal: MonthGoal): List<MonthTheme> = when (goal) {
        MonthGoal.FITTER -> listOf(MonthTheme.BASE, MonthTheme.SPEED, MonthTheme.DISTANCE, MonthTheme.MIXED)
        MonthGoal.FURTHER -> listOf(MonthTheme.BASE, MonthTheme.DISTANCE, MonthTheme.SPEED, MonthTheme.MIXED)
        MonthGoal.BURN -> listOf(MonthTheme.BASE, MonthTheme.MIXED, MonthTheme.DISTANCE, MonthTheme.SPEED)
        MonthGoal.STEADY -> listOf(MonthTheme.BASE, MonthTheme.MIXED, MonthTheme.SPEED, MonthTheme.DISTANCE)
    }

    /** The theme for the [index]th month, never the same as [previous]. */
    fun themeFor(goal: MonthGoal, index: Int, previous: MonthTheme? = null): MonthTheme {
        val order = rotation(goal)
        val t = order[Math.floorMod(index, order.size)]
        return if (t == previous) order[Math.floorMod(index + 1, order.size)] else t
    }

    /** Your usual week in minutes, from the last four weeks of matching activities. */
    fun usualWeekMinutes(records: List<ActivityRecord>, sports: Collection<MonthSport>, daysPerWeek: Int, nowMs: Long): Double {
        val since = nowMs - 28L * 86_400_000L
        val minutes = records
            .filter { r -> r.startTimeMs in since..nowMs && sports.any { it.counts(r.sport) } }
            .sumOf { it.movingMs } / 60_000.0 / 4.0
        val floor = daysPerWeek.coerceIn(1, 7) * 20.0
        val start = if (minutes < 1) daysPerWeek.coerceIn(1, 7) * 25.0 else max(minutes, floor)
        return roundTo(start.coerceIn(40.0, 900.0), 5.0)
    }

    /** Your easy pace for each sport, from the last 60 days (a touch slower than average). */
    fun paces(records: List<ActivityRecord>, nowMs: Long): Map<MonthSport, Double> {
        val since = nowMs - 60L * 86_400_000L
        val recent = records.filter { it.startTimeMs in since..nowMs }
        return MonthSport.entries.associateWith { s ->
            val p = recent.filter { s.paceSource(it.sport) }.mapNotNull { it.avgPaceSecPerKm }
            s.clampPace(if (p.size >= 2) p.average() * 1.05 else s.defaultPace)
        }
    }

    /**
     * Which weekdays you train: the long day plus days spread as far apart as possible,
     * always leaving at least one rest day.
     */
    fun trainingDays(free: Set<DayOfWeek>, count: Int, longDay: DayOfWeek): List<DayOfWeek> {
        val pool = free.ifEmpty { DayOfWeek.entries.toSet() }.sorted()
        val n = count.coerceIn(1, min(6, pool.size))
        val long = if (longDay in pool) longDay else pool.last()
        val chosen = mutableListOf(long)
        while (chosen.size < n) {
            val next = pool.filterNot { it in chosen }.maxWith(
                compareBy<DayOfWeek> { d -> chosen.minOf { gap(d, it) } }.thenBy { d -> -d.value }
            )
            chosen += next
        }
        return chosen.sorted()
    }

    /** Days between two weekdays, going round the week either way. */
    fun gap(a: DayOfWeek, b: DayOfWeek): Int {
        val d = abs(a.value - b.value)
        return min(d, 7 - d)
    }

    private class Raw(val date: LocalDate, val sport: MonthSport?, val kind: MonthKind, var minutes: Double, val week: Int, val variant: Int)

    fun generate(config: MonthConfig, id: String, createdMs: Long): MonthlyPlan {
        val sports = config.sports.distinct().ifEmpty { listOf(MonthSport.WALK) }
        val main = sports.first()
        val cross = sports.drop(1)
        val start = config.startDate
        val end = maxOf(config.endDate, start)
        val totalDays = ChronoUnit.DAYS.between(start, end).toInt() + 1
        val fullWeeks = totalDays / 7
        val easyWeek = if (fullWeeks >= 4) 3 else -1
        val rnd = Random(config.seed xor (config.monthIndex.toLong() * 7_919L) xor start.toEpochDay())
        val theme = config.theme

        val days = trainingDays(config.freeDays, config.daysPerWeek, config.longDay)
        val long = if (config.longDay in days) config.longDay else days.last()
        val base = config.weeklyMinutes.coerceIn(20.0, 1_200.0) * theme.volume
        val qualitySport = listOf(MonthSport.RUN, MonthSport.RUN_WALK, MonthSport.WALK, MonthSport.RIDE, MonthSport.HIKE)
            .firstOrNull { it in sports && it != MonthSport.HIKE } ?: main

        val raw = ArrayList<Raw>()
        var crossTurn = 0
        val weeks = (totalDays + 6) / 7
        for (w in 0 until weeks) {
            val isEasy = w == easyWeek
            val load = when {
                isEasy -> EASY_WEEK
                easyWeek in 0 until w -> AFTER_EASY
                else -> theme.shape[min(w, theme.shape.lastIndex)]
            } * (0.96 + rnd.nextDouble() * 0.08)
            val weekMin = base * load

            // Harder sessions: never next to the long day or each other.
            var q = when {
                isEasy || days.size < 3 -> 0
                config.goal == MonthGoal.STEADY -> min(1, theme.quality)
                theme == MonthTheme.SPEED && days.size < 4 -> 1
                else -> theme.quality
            }
            if (w == 0 && theme != MonthTheme.SPEED) q = min(q, 1)
            val hard = mutableListOf(long)
            val qualityDays = mutableListOf<DayOfWeek>()
            days.filterNot { it == long }
                .sortedByDescending { gap(it, long) }
                .forEach { d ->
                    if (qualityDays.size < q && hard.all { gap(d, it) > 1 }) {
                        qualityDays += d
                        hard += d
                    }
                }
            val easyDays = days.filterNot { it == long || it in qualityDays }

            // Cross-training on some easy days, picked with the month's shuffle. Every sport you
            // picked shows up: one cross day a week (every other week when that is your only easy day).
            val crossCount = if (cross.isEmpty() || easyDays.isEmpty()) 0 else
                max(if (easyDays.size >= 2 || cross.size >= 2 || w % 2 == 1) 1 else 0, (easyDays.size * theme.crossShare).roundToInt())
                    .coerceAtMost(easyDays.size)
            val crossDays = easyDays.shuffled(rnd).take(crossCount).toSet()

            // The long session: on mixed months a hike can take its place every other week.
            val longSport = if (theme == MonthTheme.MIXED && MonthSport.HIKE in cross && w % 2 == 1) MonthSport.HIKE else main

            val qualityShare = if (theme == MonthTheme.SPEED) 0.17 else 0.18
            val longMin = weekMin * theme.longShare * (if (isEasy) 0.85 else 1.0)
            val qMin = weekMin * qualityShare
            val easyTotal = weekMin - longMin - qMin * qualityDays.size
            val easyEach = if (easyDays.isEmpty()) 0.0 else max(easyTotal, 0.0) / easyDays.size
            // Without easy days, the rest goes to the long session.
            val extraLong = if (easyDays.isEmpty()) max(easyTotal, 0.0) else 0.0

            for (offset in 0 until 7) {
                val date = start.plusDays((w * 7 + offset).toLong())
                if (date.isAfter(end)) break
                val dow = date.dayOfWeek
                val variant = rnd.nextInt(1_000)
                raw += when (dow) {
                    long -> Raw(date, longSport, MonthKind.LONG, longMin + extraLong, w, variant)
                    in qualityDays -> {
                        val sport = qualitySport
                        Raw(date, sport, qualityKind(sport, theme, w + qualityDays.indexOf(dow) + config.monthIndex), qMin, w, variant)
                    }
                    in easyDays -> {
                        val isCross = dow in crossDays
                        // Take turns, so every other sport you picked gets its share.
                        val sport = if (isCross) cross[Math.floorMod(crossTurn++ + config.monthIndex, cross.size)] else main
                        val kind = when {
                            isEasy -> MonthKind.RECOVERY
                            isCross -> MonthKind.CROSS
                            else -> MonthKind.EASY
                        }
                        Raw(date, sport, kind, min(easyEach, longMin * 0.85).coerceAtLeast(easyEach * 0.5), w, variant)
                    }
                    else -> Raw(date, null, MonthKind.REST, 0.0, w, variant)
                }
            }
        }

        // A different size from last month, so months never feel copy-pasted.
        var scale = 1.0
        val plannedMin = raw.sumOf { it.minutes }
        config.prevTotalMin?.takeIf { it > 0 && plannedMin > 0 }?.let { prev ->
            if (abs(plannedMin - prev) / prev < 0.04) scale *= if (theme.volume >= 1.0) 1.05 else 0.95
        }
        // Lean toward the month target, but never jump more than 15% past your usual load.
        config.monthTarget?.takeIf { it > 0 }?.let { target ->
            val actual = if (config.targetType == TargetType.TIME) plannedMin * scale
            else raw.sumOf { r -> r.sport?.let { distance(config, it, r.kind, r.minutes * scale) } ?: 0.0 }
            if (actual > 0) scale *= (target / actual).coerceIn(0.8, 1.15)
        }

        val out = raw.map { r -> finish(config, r, scale) }
        return MonthlyPlan(id, config.copy(sports = sports), out, createdMs, easyWeek)
    }

    private fun qualityKind(sport: MonthSport, theme: MonthTheme, i: Int): MonthKind {
        val cycle = when (sport) {
            MonthSport.RUN -> if (theme == MonthTheme.SPEED) listOf(MonthKind.INTERVALS, MonthKind.TEMPO, MonthKind.HILLS)
            else listOf(MonthKind.TEMPO, MonthKind.HILLS, MonthKind.INTERVALS)
            MonthSport.RUN_WALK -> listOf(MonthKind.INTERVALS, MonthKind.HILLS)
            MonthSport.WALK -> listOf(MonthKind.BRISK, MonthKind.INTERVALS, MonthKind.HILLS)
            MonthSport.HIKE -> listOf(MonthKind.HILLS)
            MonthSport.RIDE -> listOf(MonthKind.TEMPO, MonthKind.INTERVALS, MonthKind.HILLS)
        }
        return cycle[Math.floorMod(i, cycle.size)]
    }

    internal fun paceFactor(kind: MonthKind) = when (kind) {
        MonthKind.LONG -> 1.05
        MonthKind.TEMPO -> 0.92
        MonthKind.INTERVALS -> 0.97
        MonthKind.HILLS -> 1.12
        MonthKind.BRISK -> 0.90
        MonthKind.RECOVERY -> 1.05
        else -> 1.0
    }

    internal fun distance(c: MonthConfig, sport: MonthSport, kind: MonthKind, minutes: Double): Double =
        minutes * 60.0 / (c.pace(sport) * paceFactor(kind)) * 1_000.0

    private fun finish(c: MonthConfig, r: Raw, scale: Double): MonthDay {
        val sport = r.sport
        if (sport == null || r.kind == MonthKind.REST) {
            return MonthDay(r.date, null, MonthKind.REST, 0, 0.0, "Rest", REST_LINES[r.variant % REST_LINES.size], r.week)
        }
        val floor = if (r.kind == MonthKind.RECOVERY) 15 else sport.minMinutes
        val cap = if (sport == MonthSport.RIDE) 300 else 240
        val minutes = roundTo((r.minutes * scale).coerceIn(floor.toDouble(), cap.toDouble()), 5.0).toInt().coerceAtLeast(10)
        val dist = roundTo(distance(c, sport, r.kind, minutes.toDouble()), 100.0).coerceAtLeast(500.0)
        val (title, detail) = describe(c, sport, r.kind, minutes, r.week, r.variant)
        return MonthDay(r.date, sport, r.kind, minutes, dist, title, detail, r.week)
    }

    internal fun describe(c: MonthConfig, s: MonthSport, kind: MonthKind, minutes: Int, week: Int, variant: Int): Pair<String, String> {
        val runMin = (1 + week + c.monthIndex).coerceIn(1, 8)
        val walkMin = if (runMin < 4) 2 else 1
        val reps = (4 + week + if (c.theme == MonthTheme.SPEED) 2 else 0).coerceAtMost(10)
        return when (kind) {
            MonthKind.EASY -> "Easy ${s.noun}" to when (s) {
                MonthSport.RUN -> "Comfortable pace. You could hold a conversation."
                MonthSport.RUN_WALK -> "Run $runMin min, walk $walkMin min, and repeat."
                MonthSport.RIDE -> "Smooth, steady spinning. No need to push."
                MonthSport.HIKE -> "An easy trail at your own pace."
                MonthSport.WALK -> "Relaxed and steady."
            }
            MonthKind.CROSS -> "Easy ${s.noun}" to "Cross-training: keeps you fit while your legs get a break from your main sport."
            MonthKind.RECOVERY -> "Recovery ${s.noun}" to "Lighter week. Go easy and let the last three weeks sink in."
            MonthKind.LONG -> "Long ${s.noun}" to when (s) {
                MonthSport.RUN_WALK -> "Your key session. Run $runMin min, walk $walkMin min, and repeat."
                MonthSport.HIKE -> "Your key session. Find some trail or hills, and bring water."
                MonthSport.RIDE -> "Your key session. Steady effort, eat and drink on the way."
                else -> if (c.theme == MonthTheme.DISTANCE) "Your key session. This month it grows every week, so keep it relaxed."
                else "Your key session this week. Slow and steady is perfect."
            }
            MonthKind.TEMPO -> "Tempo ${s.noun}" to when (s) {
                MonthSport.RIDE -> "Warm up, then ${max(2, (minutes - 20) / 8)} × 5 min strong with 3 min easy spinning."
                else -> "10 min easy, ${max(10, minutes - 20)} min comfortably hard, 10 min easy."
            }
            MonthKind.INTERVALS -> when (s) {
                MonthSport.RUN -> "Intervals" to when (variant % 3) {
                    0 -> "Warm up, then $reps × 400 m quick with 200 m easy between. Cool down."
                    1 -> "Warm up, then ${reps + 2} × 1 min fast, 1 min easy. Cool down."
                    else -> "Warm up, then ${max(3, reps / 2)} × 800 m strong with 400 m easy between. Cool down."
                }
                MonthSport.RUN_WALK -> "Run-walk intervals" to "Run 1 min strong, walk 2 min. Repeat $reps times, then walk it out."
                MonthSport.WALK -> "Walk intervals" to "Warm up, then $reps × 3 min fast, 2 min easy."
                MonthSport.RIDE -> "Ride intervals" to "Warm up, then $reps × 2 min hard with 2 min easy spinning."
                MonthSport.HIKE -> "Hill hike" to "Push the climbs, relax on the way down."
            }
            MonthKind.HILLS -> when (s) {
                MonthSport.RUN -> "Hill repeats" to "Warm up, then $reps × 60 s strong uphill, jog or walk down. Cool down."
                MonthSport.RIDE -> "Hilly ride" to "Pick a route with climbs. Stay seated and steady on the way up."
                MonthSport.HIKE -> "Hilly hike" to "Pick some climbs. Push the ups, take the downs gently."
                else -> "Hill ${s.noun}" to "Pick a hilly route. Push the climbs, relax on the way down."
            }
            MonthKind.BRISK -> "Brisk walk" to "Walk with purpose: quick steps, swing your arms."
            MonthKind.REST -> "Rest" to ""
        }
    }

    private val REST_LINES = listOf(
        "Recovery is training too.",
        "Rest day. A gentle stretch is plenty.",
        "Day off. Sleep well and drink some water.",
        "Rest up. Tomorrow will feel better for it.",
    )

    internal fun roundTo(v: Double, step: Double) = (v / step).roundToInt() * step

    // ---- Progress ---------------------------------------------------------------------------

    /** How each day went, comparing the plan with what you recorded. */
    fun status(
        plan: MonthlyPlan,
        records: List<ActivityRecord>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<LocalDate, DayStatus> {
        val byDay = records.groupBy { Stats.localDate(it.startTimeMs, zone) }
        val moved = plan.movedAway
        return plan.days.associate { day ->
            day.date to if (day.isRest || day.sport == null) DayStatus.REST else if (day.date in moved) DayStatus.MOVED else {
                val done = byDay[day.date].orEmpty().filter { day.sport.counts(it.sport) }
                val r = ratio(plan, day, done)
                when {
                    r >= 0.8 -> DayStatus.DONE
                    r > 0 && day.date.isBefore(today) -> DayStatus.PARTIAL
                    day.date.isBefore(today) -> DayStatus.MISSED
                    day.date == today -> DayStatus.TODAY
                    else -> DayStatus.UPCOMING
                }
            }
        }
    }

    /** 0 = nothing yet, 1 = the full session, for the calendar rings. */
    fun progress(plan: MonthlyPlan, records: List<ActivityRecord>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Float> {
        val byDay = records.groupBy { Stats.localDate(it.startTimeMs, zone) }
        val moved = plan.movedAway
        return plan.days.filter { !it.isRest && it.sport != null && it.date !in moved }.associate { day ->
            day.date to ratio(plan, day, byDay[day.date].orEmpty().filter { day.sport!!.counts(it.sport) }).toFloat().coerceIn(0f, 1f)
        }
    }

    private fun ratio(plan: MonthlyPlan, day: MonthDay, done: List<ActivityRecord>): Double = when (plan.config.targetType) {
        TargetType.DISTANCE -> done.sumOf { it.distanceM } / max(day.distanceM, 1.0)
        TargetType.TIME -> done.sumOf { it.movingMs } / 60_000.0 / max(day.minutes, 1)
    }

    fun completion(plan: MonthlyPlan, status: Map<LocalDate, DayStatus>): Pair<Int, Int> {
        val sessions = plan.sessions
        // Turning sessions into rest days doesn't shrink the total below what was first planned.
        val planned = plan.days.count { !(plan.originals[it.date] ?: it).isRest }
        return sessions.count { status[it.date] == DayStatus.DONE } to max(sessions.size, planned)
    }
}
