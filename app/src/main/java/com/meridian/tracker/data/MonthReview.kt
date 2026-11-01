package com.meridian.tracker.data

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

enum class MonthVerdict(val label: String) {
    STRONG("Strong month"),
    STEADY("Steady month"),
    TOUGH("Tough month"),
}

/** How a month went, and what size the next one should be. */
data class MonthReview(
    val done: Int,
    val planned: Int,
    val minutes: Int,
    val distanceM: Double,
    val verdict: MonthVerdict,
    /** Multiply your usual week by this when planning the next month. */
    val volumeFactor: Double,
    val note: String,
) {
    val percent: Int get() = if (planned > 0) (done * 100.0 / planned).roundToInt().coerceIn(0, 100) else 0
}

object MonthReviews {

    /** The review card shows for the last week of the plan, and after it ends. */
    fun due(plan: MonthlyPlan, today: LocalDate): Boolean = !today.isBefore(plan.config.endDate.minusDays(6))

    fun review(
        plan: MonthlyPlan,
        records: List<ActivityRecord>,
        status: Map<LocalDate, DayStatus>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MonthReview {
        val (done, planned) = MonthlyPlanner.completion(plan, status)
        val mine = records.filter {
            val d = Stats.localDate(it.startTimeMs, zone)
            !d.isBefore(plan.config.startDate) && !d.isAfter(plan.config.endDate) && plan.config.sports.any { s -> s.counts(it.sport) }
        }
        val ratio = if (planned > 0) done.toDouble() / planned else 0.0
        val verdict = when {
            planned == 0 -> MonthVerdict.STEADY
            ratio >= 0.85 -> MonthVerdict.STRONG
            ratio >= 0.6 -> MonthVerdict.STEADY
            else -> MonthVerdict.TOUGH
        }
        val (factor, note) = when (verdict) {
            MonthVerdict.STRONG -> 1.08 to "You hit nearly every session. Next month can step up a little."
            MonthVerdict.STEADY -> 1.0 to "A solid month. Next month keeps the same size with a fresh theme."
            MonthVerdict.TOUGH -> 0.9 to "Life gets in the way. Next month eases back so it feels doable."
        }
        return MonthReview(
            done = done,
            planned = planned,
            minutes = (mine.sumOf { it.movingMs } / 60_000.0).roundToInt(),
            distanceM = mine.sumOf { it.distanceM },
            verdict = verdict,
            volumeFactor = factor,
            note = note,
        )
    }

    /** Your usual week scaled by how last month went; unchanged until the month is nearly over. */
    fun nextWeekMinutes(usual: Double, previous: MonthlyPlan?, review: MonthReview?, today: LocalDate): Double {
        if (previous == null || review == null || !due(previous, today)) return usual
        return (((usual * review.volumeFactor) / 5.0).roundToInt() * 5.0).coerceIn(40.0, 900.0)
    }
}
