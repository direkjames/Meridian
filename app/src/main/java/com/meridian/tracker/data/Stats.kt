package com.meridian.tracker.data

import com.meridian.tracker.tracking.Geo
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// =================================================================================================
// Periods (week / month / year totals and chart buckets)
// =================================================================================================

enum class Period(val label: String) { WEEK("Week"), MONTH("Month"), YEAR("Year") }

data class Bucket(
    /** Short axis label, e.g. "M", "12", "Aug". */
    val label: String,
    /** Long label for readouts, e.g. "Mon, Oct 13" or "August". */
    val fullLabel: String,
    val distanceM: Double,
    val count: Int,
)

data class PeriodSummary(
    val period: Period,
    val start: LocalDate,
    val endExclusive: LocalDate,
    val buckets: List<Bucket>,
    val distanceM: Double,
    val count: Int,
    val movingMs: Long,
    val elevationM: Double,
    val activeDays: Int,
    val previousDistanceM: Double,
) {
    /** Change vs the previous period, e.g. 0.12 for +12%. Null when there's nothing to compare. */
    val change: Double? get() = if (previousDistanceM > 0) (distanceM - previousDistanceM) / previousDistanceM else null
}

object Stats {

    fun periodStart(period: Period, anchor: LocalDate): LocalDate = when (period) {
        Period.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Period.MONTH -> anchor.withDayOfMonth(1)
        Period.YEAR -> anchor.withDayOfYear(1)
    }

    fun periodEnd(period: Period, start: LocalDate): LocalDate = when (period) {
        Period.WEEK -> start.plusWeeks(1)
        Period.MONTH -> start.plusMonths(1)
        Period.YEAR -> start.plusYears(1)
    }

    fun shift(period: Period, start: LocalDate, by: Long): LocalDate = when (period) {
        Period.WEEK -> start.plusWeeks(by)
        Period.MONTH -> start.plusMonths(by)
        Period.YEAR -> start.plusYears(by)
    }

    fun localDate(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun inRange(records: List<ActivityRecord>, start: LocalDate, endExclusive: LocalDate, zone: ZoneId) =
        records.filter {
            val d = localDate(it.startTimeMs, zone)
            !d.isBefore(start) && d.isBefore(endExclusive)
        }

    fun summarize(
        records: List<ActivityRecord>,
        period: Period,
        start: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): PeriodSummary {
        val end = periodEnd(period, start)
        val inPeriod = inRange(records, start, end, zone)
        val prevStart = shift(period, start, -1)
        val previous = inRange(records, prevStart, start, zone)

        val bucketDates: List<LocalDate> = when (period) {
            Period.WEEK, Period.MONTH -> generateSequence(start) { it.plusDays(1) }.takeWhile { it.isBefore(end) }.toList()
            Period.YEAR -> (0 until 12).map { start.plusMonths(it.toLong()) }
        }
        val buckets = bucketDates.map { d ->
            val bucketEnd = if (period == Period.YEAR) d.plusMonths(1) else d.plusDays(1)
            val list = inRange(inPeriod, d, bucketEnd, zone)
            Bucket(
                label = when (period) {
                    Period.WEEK -> d.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)
                    Period.MONTH -> d.dayOfMonth.toString()
                    Period.YEAR -> d.month.getDisplayName(TextStyle.NARROW, locale)
                },
                fullLabel = when (period) {
                    Period.YEAR -> d.month.getDisplayName(TextStyle.FULL, locale)
                    else -> "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)}, " +
                        "${d.month.getDisplayName(TextStyle.SHORT, locale)} ${d.dayOfMonth}"
                },
                distanceM = list.sumOf { it.distanceM },
                count = list.size,
            )
        }

        return PeriodSummary(
            period = period,
            start = start,
            endExclusive = end,
            buckets = buckets,
            distanceM = inPeriod.sumOf { it.distanceM },
            count = inPeriod.size,
            movingMs = inPeriod.sumOf { it.movingMs },
            elevationM = inPeriod.sumOf { it.elevationGainM },
            activeDays = inPeriod.map { localDate(it.startTimeMs, zone) }.distinct().size,
            previousDistanceM = previous.sumOf { it.distanceM },
        )
    }

    // =============================================================================================
    // Streaks
    // =============================================================================================

    /** Longest run of consecutive days with at least one activity. */
    fun longestDayStreak(records: List<ActivityRecord>, zone: ZoneId = ZoneId.systemDefault()): Int {
        val days = records.map { localDate(it.startTimeMs, zone) }.distinct().sorted()
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days) {
            run = if (prev != null && ChronoUnit.DAYS.between(prev, d) == 1L) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }
        return best
    }

    /** Consecutive weeks (Mon–Sun), up to and including this week, with at least one activity. */
    fun currentWeekStreak(records: List<ActivityRecord>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int {
        val weeks = records.map { periodStart(Period.WEEK, localDate(it.startTimeMs, zone)) }.toSet()
        var week = periodStart(Period.WEEK, today)
        // A streak isn't broken just because this week hasn't had an activity yet.
        if (week !in weeks) week = week.minusWeeks(1)
        var streak = 0
        while (week in weeks) {
            streak++
            week = week.minusWeeks(1)
        }
        return streak
    }

    // =============================================================================================
    // Best efforts and personal bests
    // =============================================================================================

    /**
     * Fastest time (ms) to cover [targetM] anywhere inside the route, e.g. your fastest 5K within
     * a 12K run. Pauses don't count. Null if the route is shorter than the target.
     */
    fun bestEffortMs(points: List<TrackPoint>, targetM: Double): Long? {
        if (points.size < 2) return null
        val n = points.size
        val dist = DoubleArray(n)
        val time = LongArray(n)
        for (i in 1 until n) {
            val a = points[i - 1]
            val b = points[i]
            val same = a.segment == b.segment
            dist[i] = dist[i - 1] + if (same) Geo.distanceM(a.lat, a.lon, b.lat, b.lon) else 0.0
            time[i] = time[i - 1] + if (same) (b.time - a.time).coerceAtLeast(0L) else 0L
        }
        if (dist[n - 1] < targetM) return null

        var best = Long.MAX_VALUE
        var i = 0
        for (j in 1 until n) {
            while (i + 1 < j && dist[j] - dist[i + 1] >= targetM) i++
            val covered = dist[j] - dist[i]
            if (covered >= targetM) {
                val scaled = ((time[j] - time[i]) * (targetM / covered)).toLong()
                if (scaled in 1 until best) best = scaled
            }
        }
        return if (best == Long.MAX_VALUE) null else best
    }

    private val effortCache = ConcurrentHashMap<String, Long>()
    private const val NONE = -1L

    /** Cached [bestEffortMs] for a saved activity. */
    fun effort(record: ActivityRecord, targetM: Double): Long? = cachedEffort(record, targetM)

    private fun cachedEffort(record: ActivityRecord, targetM: Double): Long? {
        val key = "${record.id}:${record.points.size}:$targetM"
        val v = effortCache.getOrPut(key) { bestEffortMs(record.points, targetM) ?: NONE }
        return if (v == NONE) null else v
    }

    fun specsFor(sport: Sport): List<BestSpec> {
        val efforts = when (sport) {
            Sport.RUN, Sport.TRAIL_RUN -> listOf(
                1000.0 to "Fastest 1K",
                5000.0 to "Fastest 5K",
                10000.0 to "Fastest 10K",
                21097.5 to "Fastest half marathon",
                42195.0 to "Fastest marathon",
            )
            Sport.RIDE -> listOf(
                10000.0 to "Fastest 10K",
                20000.0 to "Fastest 20K",
                40000.0 to "Fastest 40K",
                100000.0 to "Fastest 100K",
            )
            Sport.WALK -> listOf(
                1000.0 to "Fastest 1K",
                5000.0 to "Fastest 5K",
            )
            Sport.HIKE, Sport.TREADMILL -> emptyList()
        }.map { (m, title) -> BestSpec("effort-${m.toLong()}", title, BestKind.EFFORT, m) }
        return efforts + listOf(
            BestSpec("distance", "Longest ${sport.label.lowercase()}", BestKind.DISTANCE),
            BestSpec("duration", "Most time moving", BestKind.DURATION),
            BestSpec("climb", "Biggest climb", BestKind.CLIMB),
        )
    }

    fun valueFor(record: ActivityRecord, spec: BestSpec): Double? = when (spec.kind) {
        BestKind.EFFORT -> cachedEffort(record, spec.targetM)?.toDouble()
        BestKind.DISTANCE -> record.distanceM.takeIf { it > 0 }
        BestKind.DURATION -> record.movingMs.toDouble().takeIf { it > 0 }
        BestKind.CLIMB -> record.elevationGainM.takeIf { it >= 1.0 }
    }

    private fun isBetter(spec: BestSpec, candidate: Double, current: Double) =
        if (spec.kind == BestKind.EFFORT) candidate < current else candidate > current

    /** Current personal bests for one sport. */
    fun personalBests(records: List<ActivityRecord>, sport: Sport): List<BestResult> {
        val ofSport = records.filter { it.sport == sport }
        return specsFor(sport).mapNotNull { spec ->
            var best: BestResult? = null
            for (r in ofSport) {
                val v = valueFor(r, spec) ?: continue
                if (best == null || isBetter(spec, v, best.value)) {
                    best = BestResult(spec, sport, v, r.id, r.startTimeMs)
                }
            }
            best
        }
    }

    /**
     * Bests that [record] set compared with everything of the same sport recorded before it.
     * Needs at least one earlier activity of that sport, so a first-ever run isn't a wall of trophies.
     */
    fun newBestsFor(record: ActivityRecord, all: List<ActivityRecord>): List<BestResult> {
        val earlier = all.filter { it.sport == record.sport && it.id != record.id && it.startTimeMs < record.startTimeMs }
        if (earlier.isEmpty()) return emptyList()
        return specsFor(record.sport).mapNotNull { spec ->
            val mine = valueFor(record, spec) ?: return@mapNotNull null
            val previousBest = earlier.mapNotNull { valueFor(it, spec) }
                .reduceOrNull { a, b -> if (isBetter(spec, a, b)) a else b }
            if (previousBest == null || isBetter(spec, mine, previousBest)) {
                BestResult(spec, record.sport, mine, record.id, record.startTimeMs)
            } else {
                null
            }
        }
    }

    // =============================================================================================
    // Month / Year in review
    // =============================================================================================

    fun review(
        all: List<ActivityRecord>,
        period: Period,
        start: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): Review {
        val summary = summarize(all, period, start, zone, locale)
        val records = inRange(all, start, summary.endExclusive, zone)

        val peak = summary.buckets.maxByOrNull { it.distanceM }?.takeIf { it.distanceM > 0 }
        val longest = records.maxByOrNull { it.distanceM }
        val sportCounts = records.groupingBy { it.sport }.eachCount()
        val favorite = sportCounts.maxByOrNull { it.value }

        val weekdayCounts = records.groupingBy { Instant.ofEpochMilli(it.startTimeMs).atZone(zone).dayOfWeek }.eachCount()
        val favoriteDay = weekdayCounts.maxByOrNull { it.value }?.key

        val partCounts = records.groupingBy { partOfDay(Instant.ofEpochMilli(it.startTimeMs).atZone(zone).hour) }.eachCount()
        val favoritePart = partCounts.maxByOrNull { it.value }

        val bestsSet = records.flatMap { newBestsFor(it, all) }
            .sortedWith(compareBy<BestResult> { it.spec.kind.ordinal }.thenBy { it.spec.targetM })

        return Review(
            period = period,
            start = start,
            summary = summary,
            longest = longest,
            favoriteSport = favorite?.key,
            favoriteSportShare = favorite?.let { it.value.toDouble() / records.size } ?: 0.0,
            favoriteWeekday = favoriteDay,
            partOfDay = favoritePart?.key,
            partOfDayShare = favoritePart?.let { it.value.toDouble() / records.size } ?: 0.0,
            peakBucket = peak,
            longestDayStreak = longestDayStreak(records, zone),
            bestsSet = bestsSet,
        )
    }

    fun partOfDay(hour: Int): String = when (hour) {
        in 5..11 -> "morning"
        in 12..17 -> "afternoon"
        in 18..21 -> "evening"
        else -> "night"
    }

    const val MARATHON_M = 42_195.0
    const val EVEREST_M = 8_848.86
}

enum class BestKind { EFFORT, DISTANCE, DURATION, CLIMB }

data class BestSpec(val key: String, val title: String, val kind: BestKind, val targetM: Double = 0.0)

/** [value] is milliseconds for EFFORT/DURATION and metres for DISTANCE/CLIMB. */
data class BestResult(
    val spec: BestSpec,
    val sport: Sport,
    val value: Double,
    val recordId: String,
    val dateMs: Long,
)

data class Review(
    val period: Period,
    val start: LocalDate,
    val summary: PeriodSummary,
    val longest: ActivityRecord?,
    val favoriteSport: Sport?,
    val favoriteSportShare: Double,
    val favoriteWeekday: DayOfWeek?,
    val partOfDay: String?,
    val partOfDayShare: Double,
    val peakBucket: Bucket?,
    val longestDayStreak: Int,
    val bestsSet: List<BestResult>,
) {
    val isEmpty: Boolean get() = summary.count == 0
    val marathons: Double get() = summary.distanceM / Stats.MARATHON_M
    val everests: Double get() = summary.elevationM / Stats.EVEREST_M
}
