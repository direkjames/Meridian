package com.meridian.tracker.data

import java.time.LocalDate

/** What the monthly plan's reminders say. Pure text logic; [PlanReminders] schedules and shows them. */
object MonthlyReminders {

    const val EVENING_HOUR = 19

    enum class Kind { MORNING, EVENING }

    /** True while the plan still has days to remind about. */
    fun active(plan: MonthlyPlan?, today: LocalDate): Boolean =
        plan != null && plan.config.remind && !today.isAfter(plan.config.endDate)

    /**
     * The notification for [day], or null when there's nothing to say: rest days, sessions moved
     * to another day, days outside the plan, and an evening nudge once the session is done.
     * [amount] is the session's target as the user reads it, e.g. "5.0 km" or "40 min".
     */
    fun message(
        plan: MonthlyPlan,
        day: MonthDay?,
        kind: Kind,
        done: Boolean,
        amount: String,
        eventTitle: String? = null,
    ): Pair<String, String>? {
        if (day == null || day.isRest || day.sport == null || day.date in plan.movedAway) return null
        if (day.date.isBefore(plan.config.startDate) || day.date.isAfter(plan.config.endDate)) return null
        val tail = if (eventTitle != null) " Your event training is on today too: do that first." else ""
        return when (kind) {
            Kind.MORNING -> {
                val preview = weekPreview(plan, day.date)
                "Today: ${day.title} · $amount" to (day.detail + tail + (preview?.let { "\n$it" } ?: ""))
            }
            Kind.EVENING -> {
                if (done) return null
                "Still time for today's ${day.title.lowercase()}" to "$amount planned. Even a short one counts."
            }
        }
    }

    /** A line about the week ahead, only on the first day of a plan week. */
    fun weekPreview(plan: MonthlyPlan, date: LocalDate): String? {
        val day = plan.day(date) ?: return null
        val prev = plan.day(date.minusDays(1))
        if (prev != null && prev.week == day.week) return null
        val sessions = plan.sessions.filter { it.week == day.week }
        if (sessions.isEmpty()) return null
        val minutes = sessions.sumOf { it.minutes }
        val time = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        val lead = if (plan.easyWeek == day.week) "Easier week" else "Week ${day.week + 1}"
        return "$lead: ${sessions.size} session${if (sessions.size == 1) "" else "s"}, about $time."
    }
}
