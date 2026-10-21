package com.meridian.tracker.data

import com.meridian.tracker.tracking.Geo
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// =================================================================================================
// Relative Effort (training load)
// =================================================================================================

/**
 * Training load. With heart rate it uses how hard your heart worked (see [HeartRate.load]).
 * Without it, in the style of running TSS:
 * hours × intensity² × 100, where intensity compares the activity's speed with your own
 * "threshold" speed for that sport (worked out from your best efforts).
 */
object Effort {

    private val DEFAULT_THRESHOLD = mapOf(Sport.RUN to 3.0, Sport.RIDE to 7.0, Sport.WALK to 1.7, Sport.TRAIL_RUN to 2.6, Sport.HIKE to 1.4, Sport.TREADMILL to 3.0)

    /** Your sustainable hard speed (m/s) per sport, from your history. */
    fun thresholds(records: List<ActivityRecord>): Map<Sport, Double> = Sport.entries.associateWith { sport ->
        val mine = records.filter { it.sport == sport && it.distanceM >= 1000 && it.movingMs > 0 }
        val fromEfforts = listOf(10_000.0 to 0.99, 5_000.0 to 0.95, 21_097.5 to 1.03)
            .mapNotNull { (d, factor) ->
                mine.mapNotNull { r -> Stats.effort(r, d) }.minOrNull()?.let { ms -> d / (ms / 1000.0) * factor }
            }
            .maxOrNull()
        val fromAverages = mine.map { it.avgSpeed }.sortedDescending().take(3).takeIf { it.isNotEmpty() }?.average()?.times(1.08)
        (fromEfforts ?: fromAverages ?: DEFAULT_THRESHOLD.getValue(sport)).coerceAtLeast(DEFAULT_THRESHOLD.getValue(sport) * 0.5)
    }

    fun load(record: ActivityRecord, thresholds: Map<Sport, Double>): Double {
        if (record.movingMs <= 0) return 0.0
        // With heart rate (strap, watch or imported file), effort comes from how hard your heart worked.
        fromHeartRate(record)?.let { return it }
        val threshold = thresholds[record.sport] ?: DEFAULT_THRESHOLD.getValue(record.sport)
        val intensity = (record.avgSpeed / threshold).coerceIn(0.35, 1.3)
        val hours = record.movingMs / 3_600_000.0
        // Climbing adds work: roughly 10 m of climb ≈ 100 m of flat effort for runs and walks.
        val climbBonus = if (record.sport == Sport.RIDE) 1.0 else 1.0 + min(0.3, record.elevationGainM * 10 / max(record.distanceM, 1.0))
        return (hours * intensity * intensity * 100.0 * climbBonus).coerceAtMost(500.0)
    }

    /** Heart-rate based load, or null when the activity has too little heart-rate data. */
    fun fromHeartRate(record: ActivityRecord): Double? =
        if (record.hasHeartRate) HeartRate.load(record.heartRate, record.elapsedMs)?.coerceAtMost(500.0) else null

    fun label(load: Double): String = when {
        load < 25 -> "Easy"
        load < 60 -> "Moderate"
        load < 110 -> "Hard"
        load < 180 -> "Very hard"
        else -> "Massive"
    }
}

// =================================================================================================
// Fitness & Freshness
// =================================================================================================

data class FitnessDay(val date: LocalDate, val load: Double, val fitness: Double, val fatigue: Double) {
    /** Positive = fresh, negative = tired. */
    val form: Double get() = fitness - fatigue
}

object Fitness {

    private const val FITNESS_DAYS = 42.0
    private const val FATIGUE_DAYS = 7.0

    /** Daily fitness (42-day load average) and fatigue (7-day), ending today. */
    fun series(
        records: List<ActivityRecord>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        thresholds: Map<Sport, Double> = Effort.thresholds(records),
    ): List<FitnessDay> {
        if (records.isEmpty()) return emptyList()
        val loads = HashMap<LocalDate, Double>()
        records.forEach { r ->
            val d = Stats.localDate(r.startTimeMs, zone)
            loads[d] = (loads[d] ?: 0.0) + Effort.load(r, thresholds)
        }
        val first = loads.keys.min()
        if (first.isAfter(today)) return emptyList()
        val kFit = 1 - exp(-1 / FITNESS_DAYS)
        val kFat = 1 - exp(-1 / FATIGUE_DAYS)
        var fit = 0.0
        var fat = 0.0
        val out = ArrayList<FitnessDay>()
        var d = first
        while (!d.isAfter(today)) {
            val load = loads[d] ?: 0.0
            fit += (load - fit) * kFit
            fat += (load - fat) * kFat
            out.add(FitnessDay(d, load, fit, fat))
            d = d.plusDays(1)
        }
        return out
    }

    fun formLabel(form: Double): String = when {
        form > 15 -> "Fresh"
        form > -10 -> "Neutral"
        form > -30 -> "Building"
        else -> "Overreaching"
    }

    /** Sum of load per week (Mon–Sun) for the last [weeks] weeks, oldest first. */
    fun weeklyLoads(days: List<FitnessDay>, today: LocalDate, weeks: Int): List<Pair<LocalDate, Double>> {
        val thisWeek = Stats.periodStart(Period.WEEK, today)
        return (weeks - 1 downTo 0).map { i ->
            val start = thisWeek.minusWeeks(i.toLong())
            val end = start.plusWeeks(1)
            start to days.filter { !it.date.isBefore(start) && it.date.isBefore(end) }.sumOf { it.load }
        }
    }

    /** A sensible range for this week, based on the previous three weeks. */
    fun targetRange(weekly: List<Pair<LocalDate, Double>>): ClosedFloatingPointRange<Double>? {
        val previous = weekly.dropLast(1).takeLast(3).map { it.second }
        if (previous.none { it > 0 }) return null
        val avg = previous.average()
        return (avg * 0.8)..(avg * 1.3)
    }
}

// =================================================================================================
// Race predictor
// =================================================================================================

data class Prediction(val label: String, val distanceM: Double, val timeMs: Long, val basedOn: String)

object RacePredictor {

    private val TARGETS = listOf(5_000.0 to "5K", 10_000.0 to "10K", 21_097.5 to "Half marathon", 42_195.0 to "Marathon")
    private val SOURCES = listOf(1_000.0 to "1K", 3_000.0 to "3K", 5_000.0 to "5K", 10_000.0 to "10K", 21_097.5 to "half")

    /**
     * Riegel's formula, T2 = T1 × (D2 / D1)^1.06, from your best recent efforts.
     * Each target uses the effort whose distance is closest, and only reasonable stretches.
     */
    fun predict(records: List<ActivityRecord>, sport: Sport, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Prediction> {
        val since = today.minusDays(120)
        val recent = records.filter { it.sport == sport && !Stats.localDate(it.startTimeMs, zone).isBefore(since) }
        val best = SOURCES.mapNotNull { (d, name) ->
            recent.mapNotNull { Stats.effort(it, d) }.minOrNull()?.let { Triple(d, name, it) }
        }
        if (best.isEmpty()) return emptyList()
        return TARGETS.mapNotNull { (target, label) ->
            best
                .filter { (d, _, _) -> target / d <= 8.5 }
                .minByOrNull { (d, _, _) -> abs(ln(target / d)) }
                ?.let { (d, name, ms) ->
                    Prediction(label, target, (ms * (target / d).pow(1.06)).toLong(), "from your best $name")
                }
        }
    }
}

// =================================================================================================
// Matched routes
// =================================================================================================

object RouteMatch {

    private const val SAMPLES = 24
    private val cache = ConcurrentHashMap<String, List<Pair<Double, Double>>>()

    /** 24 points spaced evenly along the route. */
    fun signature(points: List<TrackPoint>): List<Pair<Double, Double>> {
        if (points.size < 2) return emptyList()
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            cum[i] = cum[i - 1] + if (a.segment == b.segment) Geo.distanceM(a.lat, a.lon, b.lat, b.lon) else 0.0
        }
        val total = cum.last()
        if (total <= 0) return emptyList()
        var j = 0
        return (0 until SAMPLES).map { k ->
            val target = total * k / (SAMPLES - 1)
            while (j < points.size - 1 && cum[j + 1] < target) j++
            points[j].lat to points[j].lon
        }
    }

    private fun sig(r: ActivityRecord) = cache.getOrPut("${r.id}:${r.points.size}") { signature(r.points) }

    fun similar(a: ActivityRecord, b: ActivityRecord): Boolean {
        if (a.sport != b.sport || a.distanceM < 500 || b.distanceM < 500) return false
        if (abs(a.distanceM - b.distanceM) / max(a.distanceM, b.distanceM) > 0.1) return false
        val sa = sig(a); val sb = sig(b)
        if (sa.size != SAMPLES || sb.size != SAMPLES) return false
        fun d(p: Pair<Double, Double>, q: Pair<Double, Double>) = Geo.distanceM(p.first, p.second, q.first, q.second)
        if (d(sa.first(), sb.first()) > 250 || d(sa.last(), sb.last()) > 250) return false
        fun meanNearest(x: List<Pair<Double, Double>>, y: List<Pair<Double, Double>>) = x.map { p -> y.minOf { d(p, it) } }.average()
        return meanNearest(sa, sb) < 120 && meanNearest(sb, sa) < 120
    }

    /** Every activity on the same route as [record] (including itself), oldest first. */
    fun matches(record: ActivityRecord, all: List<ActivityRecord>): List<ActivityRecord> =
        (all.filter { it.id != record.id && similar(record, it) } + record).sortedBy { it.startTimeMs }
}

// =================================================================================================
// Goals
// =================================================================================================

enum class GoalMetric(val label: String) { DISTANCE("Distance"), TIME("Time"), COUNT("Activities") }

data class Goal(
    val id: String,
    val period: Period,
    val metric: GoalMetric,
    /** Metres for DISTANCE, minutes for TIME, a count for COUNT. */
    val target: Double,
    val sport: Sport? = null,
)

data class GoalProgress(val goal: Goal, val value: Double, val start: LocalDate, val end: LocalDate) {
    val fraction: Double get() = if (goal.target > 0) (value / goal.target).coerceIn(0.0, 1.0) else 0.0
    val done: Boolean get() = value >= goal.target
}

object Goals {
    fun progress(goal: Goal, records: List<ActivityRecord>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): GoalProgress {
        val start = Stats.periodStart(goal.period, today)
        val end = Stats.periodEnd(goal.period, start)
        val inPeriod = Stats.inRange(records, start, end, zone).filter { goal.sport == null || it.sport == goal.sport }
        val value = when (goal.metric) {
            GoalMetric.DISTANCE -> inPeriod.sumOf { it.distanceM }
            GoalMetric.TIME -> inPeriod.sumOf { it.movingMs } / 60_000.0
            GoalMetric.COUNT -> inPeriod.size.toDouble()
        }
        return GoalProgress(goal, value, start, end)
    }
}

// =================================================================================================
// Badges & milestones
// =================================================================================================

data class BadgeDef(val id: String, val title: String, val description: String, val group: String)

data class EarnedBadge(val def: BadgeDef, val recordId: String?, val dateMs: Long)

object Badges {

    val ALL: List<BadgeDef> = buildList {
        add(BadgeDef("first", "First steps", "Record or import your first activity", "Milestones"))
        listOf(10, 25, 50, 100, 250).forEach { add(BadgeDef("count-$it", "$it activities", "Complete $it activities", "Milestones")) }
        listOf(50, 100, 250, 500, 1000, 2500).forEach { add(BadgeDef("total-$it", "$it km club", "Cover $it km in total", "Distance")) }
        add(BadgeDef("d-5k", "5K", "Go 5 km in one activity", "Distance"))
        add(BadgeDef("d-10k", "10K", "Go 10 km in one activity", "Distance"))
        add(BadgeDef("d-half", "Half marathon", "Go 21.1 km in one activity", "Distance"))
        add(BadgeDef("d-full", "Marathon", "Go 42.2 km in one activity", "Distance"))
        add(BadgeDef("d-century", "Century ride", "Ride 100 km in one go", "Distance"))
        listOf(3, 7, 14, 30).forEach { add(BadgeDef("streak-$it", "$it-day streak", "Be active $it days in a row", "Consistency")) }
        listOf(500, 1000, 5000, 8849).forEach {
            add(BadgeDef("climb-$it", if (it == 8849) "Everest" else "$it m climbed", "Climb $it m in total", "Climbing"))
        }
        add(BadgeDef("early", "Early bird", "Start an activity before 6 AM", "Habits"))
        add(BadgeDef("night", "Night owl", "Start an activity after 9 PM", "Habits"))
        add(BadgeDef("all3", "All-rounder", "Run, ride and walk at least once each", "Habits"))
        add(BadgeDef("month100", "Big month", "Cover 100 km in a calendar month", "Consistency"))
        add(BadgeDef("plan", "Plan complete", "Finish every session of a training plan", "Training"))
    }

    /** Badges earned, each with the activity that unlocked it. */
    fun earned(records: List<ActivityRecord>, zone: ZoneId = ZoneId.systemDefault(), planCompletedMs: Long? = null): List<EarnedBadge> {
        val byId = ALL.associateBy { it.id }
        val out = LinkedHashMap<String, EarnedBadge>()
        fun award(id: String, r: ActivityRecord?) {
            if (id !in out) byId[id]?.let { out[id] = EarnedBadge(it, r?.id, r?.startTimeMs ?: System.currentTimeMillis()) }
        }
        var total = 0.0
        var climb = 0.0
        val sports = HashSet<Sport>()
        val days = ArrayList<LocalDate>()
        var streak = 0
        var lastDay: LocalDate? = null
        val monthly = HashMap<java.time.YearMonth, Double>()

        records.sortedBy { it.startTimeMs }.forEachIndexed { i, r ->
            val count = i + 1
            award("first", r)
            listOf(10, 25, 50, 100, 250).forEach { if (count >= it) award("count-$it", r) }
            total += r.distanceM
            listOf(50, 100, 250, 500, 1000, 2500).forEach { if (total >= it * 1000.0) award("total-$it", r) }
            if (r.sport != Sport.RIDE) {
                if (r.distanceM >= 5_000) award("d-5k", r)
                if (r.distanceM >= 10_000) award("d-10k", r)
                if (r.distanceM >= 21_097) award("d-half", r)
                if (r.distanceM >= 42_195) award("d-full", r)
            } else if (r.distanceM >= 100_000) {
                award("d-century", r)
            }
            climb += r.elevationGainM
            listOf(500, 1000, 5000, 8849).forEach { if (climb >= it) award("climb-$it", r) }
            val local = java.time.Instant.ofEpochMilli(r.startTimeMs).atZone(zone)
            if (local.hour < 6 && local.hour >= 3) award("early", r)
            if (local.hour >= 21) award("night", r)
            sports.add(r.sport)
            if (sports.size == 3) award("all3", r)
            val day = local.toLocalDate()
            if (day != lastDay) {
                streak = if (lastDay != null && ChronoUnit.DAYS.between(lastDay, day) == 1L) streak + 1 else 1
                lastDay = day
                days.add(day)
            }
            listOf(3, 7, 14, 30).forEach { if (streak >= it) award("streak-$it", r) }
            val ym = java.time.YearMonth.from(day)
            monthly[ym] = (monthly[ym] ?: 0.0) + r.distanceM
            if ((monthly[ym] ?: 0.0) >= 100_000) award("month100", r)
        }
        if (planCompletedMs != null) byId["plan"]?.let { out["plan"] = EarnedBadge(it, null, planCompletedMs) }
        return out.values.toList()
    }
}
