package com.meridian.tracker.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Editing single days of a plan, for both the event plan and the monthly plan.
 *
 * Rules that keep a plan safe:
 *  - only today and future days can change; past days are history
 *  - the event day itself can't be edited, moved or removed
 *  - moving a day swaps it with the other day, so the week keeps the same number of sessions
 *  - every edit remembers how the day was first planned, so "Reset" always works
 *  - edits that are risky (hard days back to back, a much bigger week) are allowed but come with a warning
 *
 * Pure Kotlin, unit-tested. Author: direk james.
 */

/** Limits shared by both plans. */
object EditLimits {
    const val MIN_DISTANCE_M = 500.0
    const val MAX_DISTANCE_M = 60_000.0
    const val MIN_MINUTES = 10
    const val MAX_MINUTES = 360
}

// =================================================================================================
// Event plan
// =================================================================================================

object PlanEdits {

    /** Session types you can pick for each way of doing the plan. */
    fun kinds(mode: PlanMode): List<SessionKind> = when (mode) {
        PlanMode.RUN -> listOf(SessionKind.EASY, SessionKind.LONG, SessionKind.TEMPO, SessionKind.INTERVALS)
        PlanMode.WALK -> listOf(SessionKind.EASY, SessionKind.LONG, SessionKind.BRISK_WALK)
        PlanMode.RUN_WALK -> listOf(SessionKind.RUN_WALK, SessionKind.LONG, SessionKind.BRISK_WALK)
    }

    fun label(kind: SessionKind, mode: PlanMode): String = when (kind) {
        SessionKind.EASY -> if (mode == PlanMode.RUN) "Easy run" else "Easy walk"
        SessionKind.LONG -> "Long"
        SessionKind.TEMPO -> "Tempo"
        SessionKind.INTERVALS -> "Intervals"
        SessionKind.RUN_WALK -> "Run-walk"
        SessionKind.BRISK_WALK -> "Brisk walk"
        SessionKind.REST -> "Rest"
        SessionKind.RACE -> "Event"
    }

    private val HARD = setOf(SessionKind.LONG, SessionKind.TEMPO, SessionKind.INTERVALS)

    /** Whether [date] can be changed: inside the plan, not in the past, and not the event day. */
    fun canEdit(plan: TrainingPlan, date: LocalDate, today: LocalDate): Boolean {
        val d = plan.day(date) ?: return false
        return !date.isBefore(today) && d.kind != SessionKind.RACE
    }

    /** The days a session on [date] can be swapped with. */
    fun moveTargets(plan: TrainingPlan, date: LocalDate, today: LocalDate): List<LocalDate> {
        if (!canEdit(plan, date, today)) return emptyList()
        return plan.days.filter { it.date != date && canEdit(plan, it.date, today) }.map { it.date }
    }

    /** Something sensible to start from when adding a session on a rest day. */
    fun defaultAmount(plan: TrainingPlan): Double {
        val s = plan.sessions.filter { it.kind != SessionKind.RACE }
        return if (plan.config.targetType == TargetType.TIME) {
            val m = s.map { it.minutes }.average().takeIf { !it.isNaN() } ?: 30.0
            (m / 5).roundToInt() * 5.0
        } else {
            val m = s.map { it.distanceM }.average().takeIf { !it.isNaN() } ?: 3_000.0
            ((m / 500).roundToInt() * 500.0).coerceAtLeast(EditLimits.MIN_DISTANCE_M)
        }
    }

    /** Distance or minutes (depending on the plan's targets) allowed for one session. */
    fun range(plan: TrainingPlan): ClosedFloatingPointRange<Double> =
        if (plan.config.targetType == TargetType.TIME) EditLimits.MIN_MINUTES.toDouble()..EditLimits.MAX_MINUTES.toDouble()
        else EditLimits.MIN_DISTANCE_M..EditLimits.MAX_DISTANCE_M

    /**
     * Sets [date] to a [kind] session of [amount] (metres for distance targets, minutes for time
     * targets). Returns null when the day can't be edited.
     */
    fun setSession(plan: TrainingPlan, date: LocalDate, kind: SessionKind, amount: Double, today: LocalDate): TrainingPlan? {
        if (!canEdit(plan, date, today) || kind !in kinds(plan.config.mode) || amount.isNaN()) return null
        val cfg = plan.config
        val cur = plan.day(date) ?: return null
        val a = amount.coerceIn(range(plan))
        val day = if (cfg.targetType == TargetType.TIME) {
            val minutes = ((a / 5).roundToInt() * 5).coerceIn(EditLimits.MIN_MINUTES, EditLimits.MAX_MINUTES)
            val dist = minutes * 60.0 / (cfg.paceSecPerKm * PlanGenerator.paceFactor(kind)) * 1_000.0
            PlanGenerator.session(cfg, kind, dist.coerceAtLeast(EditLimits.MIN_DISTANCE_M), date, cur.week, "", plan.weeks).copy(minutes = minutes)
        } else {
            PlanGenerator.session(cfg, kind, a, date, cur.week, "", plan.weeks)
        }
        return put(plan, listOf(day))
    }

    fun makeRest(plan: TrainingPlan, date: LocalDate, today: LocalDate): TrainingPlan? {
        if (!canEdit(plan, date, today)) return null
        val cur = plan.day(date) ?: return null
        if (cur.isRest) return plan
        return put(plan, listOf(PlanDay(date, SessionKind.REST, 0.0, 0, "Rest", "A rest day, as you chose.", cur.week)))
    }

    /** Swaps what is planned on [from] and [to]. */
    fun move(plan: TrainingPlan, from: LocalDate, to: LocalDate, today: LocalDate): TrainingPlan? {
        if (from == to || !canEdit(plan, from, today) || !canEdit(plan, to, today)) return null
        val a = plan.day(from) ?: return null
        val b = plan.day(to) ?: return null
        if (a.isRest && b.isRest) return plan
        return put(plan, listOf(a.copy(date = to, week = b.week), b.copy(date = from, week = a.week)))
    }

    fun reset(plan: TrainingPlan, date: LocalDate): TrainingPlan {
        val orig = plan.originals[date] ?: return plan
        return plan.copy(
            days = plan.days.map { if (it.date == date) orig else it },
            originals = plan.originals - date,
        )
    }

    fun resetAll(plan: TrainingPlan): TrainingPlan =
        plan.copy(days = plan.days.map { plan.originals[it.date] ?: it }, originals = emptyMap())

    /** Puts saved edits back on a freshly generated plan. Unknown or unsafe entries are ignored. */
    fun restore(plan: TrainingPlan, edits: List<PlanDay>): TrainingPlan {
        val valid = edits.filter { e ->
            val base = plan.day(e.date)
            base != null && base.kind != SessionKind.RACE && e.kind != SessionKind.RACE &&
                e.distanceM.isFinite() && e.distanceM >= 0 && e.minutes in 0..EditLimits.MAX_MINUTES &&
                (e.kind == SessionKind.REST || e.minutes > 0)
        }
        return if (valid.isEmpty()) plan else put(plan, valid.map { it.copy(week = plan.day(it.date)?.week ?: it.week) })
    }

    /** The edited days, for saving. */
    fun edits(plan: TrainingPlan): List<PlanDay> = plan.days.filter { it.edited }

    private fun put(plan: TrainingPlan, changes: List<PlanDay>): TrainingPlan {
        val byDate = changes.associateBy { it.date }
        val originals = plan.originals.toMutableMap()
        plan.days.forEach { d -> if (d.date in byDate && d.date !in originals) originals[d.date] = d }
        val days = plan.days.map { d ->
            val n = byDate[d.date] ?: return@map d
            // Changed back to exactly how it was first planned: not an edit any more.
            val orig = originals[d.date]
            if (orig != null && (orig == n.copy(edited = false) || (orig.isRest && n.isRest))) {
                originals.remove(d.date)
                orig
            } else {
                n.copy(edited = true)
            }
        }
        return plan.copy(days = days, originals = originals)
    }

    /** Heads-ups about [date]; the edit is still allowed. */
    fun warnings(plan: TrainingPlan, date: LocalDate): List<String> {
        val d = plan.day(date) ?: return emptyList()
        val out = LinkedHashSet<String>()
        val event = plan.config.eventDate
        if (!d.isRest && d.kind != SessionKind.RACE) {
            val daysToEvent = ChronoUnit.DAYS.between(date, event)
            if (daysToEvent == 1L) out += "The day before the event is best as a rest day."
            else if (daysToEvent in 2..3 && d.kind in HARD) out += "That's close to the event. Keep the last days easy."
            if (d.kind in HARD) {
                val touching = listOf(date.minusDays(1), date.plusDays(1)).mapNotNull { plan.day(it) }.any { it.kind in HARD }
                if (touching) out += "Two hard days back to back. A day between them helps you recover."
            }
            val orig = plan.days.filter { !it.isRest && it.kind != SessionKind.RACE }.map { plan.originals[it.date] ?: it }
            val longest = orig.maxOfOrNull { it.minutes } ?: 0
            if (longest > 0 && d.minutes > longest * 1.25) out += "That's longer than any session planned so far. Build up gradually."
            val week = plan.days.filter { it.week == d.week }
            val planned = week.sumOf { (plan.originals[it.date] ?: it).minutes }
            val now = week.sumOf { it.minutes }
            if (planned > 0 && now > planned * 1.25) out += "This week is now ${((now.toDouble() / planned - 1) * 100).roundToInt()}% bigger than planned."
        }
        return out.toList()
    }
}

// =================================================================================================
// Monthly plan
// =================================================================================================

object MonthlyEdits {

    /** Session types that make sense for each sport. */
    fun kinds(sport: MonthSport): List<MonthKind> = when (sport) {
        MonthSport.RUN, MonthSport.RIDE -> listOf(MonthKind.EASY, MonthKind.LONG, MonthKind.TEMPO, MonthKind.INTERVALS, MonthKind.HILLS, MonthKind.RECOVERY)
        MonthSport.RUN_WALK -> listOf(MonthKind.EASY, MonthKind.LONG, MonthKind.INTERVALS, MonthKind.HILLS, MonthKind.RECOVERY)
        MonthSport.WALK -> listOf(MonthKind.EASY, MonthKind.LONG, MonthKind.BRISK, MonthKind.INTERVALS, MonthKind.HILLS, MonthKind.RECOVERY)
        MonthSport.HIKE -> listOf(MonthKind.EASY, MonthKind.LONG, MonthKind.HILLS, MonthKind.RECOVERY)
    }

    fun label(kind: MonthKind): String = when (kind) {
        MonthKind.EASY, MonthKind.CROSS -> "Easy"
        MonthKind.LONG -> "Long"
        MonthKind.TEMPO -> "Tempo"
        MonthKind.INTERVALS -> "Intervals"
        MonthKind.HILLS -> "Hills"
        MonthKind.BRISK -> "Brisk"
        MonthKind.RECOVERY -> "Recovery"
        MonthKind.REST -> "Rest"
    }

    /** The kind to show when editing: cross-training and rest start as plain easy sessions. */
    fun editableKind(day: MonthDay, sport: MonthSport): MonthKind =
        day.kind.takeIf { it in kinds(sport) } ?: MonthKind.EASY

    private fun maxMinutes(sport: MonthSport) = if (sport == MonthSport.RIDE) 300 else 240

    fun canEdit(plan: MonthlyPlan, date: LocalDate, today: LocalDate): Boolean =
        plan.day(date) != null && !date.isBefore(today)

    fun moveTargets(plan: MonthlyPlan, date: LocalDate, today: LocalDate): List<LocalDate> {
        if (!canEdit(plan, date, today)) return emptyList()
        return plan.days.filter { it.date != date && canEdit(plan, it.date, today) }.map { it.date }
    }

    fun defaultAmount(plan: MonthlyPlan): Double {
        val s = plan.sessions
        return if (plan.config.targetType == TargetType.TIME) {
            val m = s.map { it.minutes }.average().takeIf { !it.isNaN() } ?: 30.0
            (m / 5).roundToInt() * 5.0
        } else {
            val m = s.map { it.distanceM }.average().takeIf { !it.isNaN() } ?: 3_000.0
            ((m / 500).roundToInt() * 500.0).coerceAtLeast(EditLimits.MIN_DISTANCE_M)
        }
    }

    /** Distance (m) or minutes allowed for one [sport] session of [kind]. */
    fun range(plan: MonthlyPlan, sport: MonthSport, kind: MonthKind): ClosedFloatingPointRange<Double> =
        if (plan.config.targetType == TargetType.TIME) EditLimits.MIN_MINUTES.toDouble()..maxMinutes(sport).toDouble()
        else EditLimits.MIN_DISTANCE_M..max(
            EditLimits.MIN_DISTANCE_M,
            MonthlyPlanner.roundTo(MonthlyPlanner.distance(plan.config, sport, kind, maxMinutes(sport).toDouble()), 100.0),
        )

    fun setSession(plan: MonthlyPlan, date: LocalDate, sport: MonthSport, kind: MonthKind, amount: Double, today: LocalDate): MonthlyPlan? {
        if (!canEdit(plan, date, today) || amount.isNaN()) return null
        val k = if (kind in kinds(sport)) kind else MonthKind.EASY
        val cur = plan.day(date) ?: return null
        val cfg = plan.config
        val a = amount.coerceIn(range(plan, sport, k))
        val minutes: Int
        val dist: Double
        if (cfg.targetType == TargetType.TIME) {
            minutes = ((a / 5).roundToInt() * 5).coerceIn(EditLimits.MIN_MINUTES, maxMinutes(sport))
            dist = MonthlyPlanner.roundTo(MonthlyPlanner.distance(cfg, sport, k, minutes.toDouble()), 100.0).coerceAtLeast(EditLimits.MIN_DISTANCE_M)
        } else {
            val rawMin = a / 1_000.0 * cfg.pace(sport) * MonthlyPlanner.paceFactor(k) / 60.0
            minutes = ((rawMin / 5).roundToInt() * 5).coerceIn(EditLimits.MIN_MINUTES, maxMinutes(sport))
            dist = MonthlyPlanner.roundTo(a, 100.0).coerceAtLeast(EditLimits.MIN_DISTANCE_M)
        }
        val (title, detail) = MonthlyPlanner.describe(cfg, sport, k, minutes, cur.week, date.dayOfMonth)
        return put(plan, listOf(MonthDay(date, sport, k, minutes, dist, title, detail, cur.week)))
    }

    fun makeRest(plan: MonthlyPlan, date: LocalDate, today: LocalDate): MonthlyPlan? {
        if (!canEdit(plan, date, today)) return null
        val cur = plan.day(date) ?: return null
        if (cur.isRest) return plan
        return put(plan, listOf(MonthDay(date, null, MonthKind.REST, 0, 0.0, "Rest", "A rest day, as you chose.", cur.week)))
    }

    fun move(plan: MonthlyPlan, from: LocalDate, to: LocalDate, today: LocalDate): MonthlyPlan? {
        if (from == to || !canEdit(plan, from, today) || !canEdit(plan, to, today)) return null
        val a = plan.day(from) ?: return null
        val b = plan.day(to) ?: return null
        if (a.isRest && b.isRest) return plan
        return put(plan, listOf(a.copy(date = to, week = b.week), b.copy(date = from, week = a.week)))
    }

    fun reset(plan: MonthlyPlan, date: LocalDate): MonthlyPlan {
        val orig = plan.originals[date] ?: return plan
        return plan.copy(
            days = plan.days.map { if (it.date == date) orig else it },
            originals = plan.originals - date,
        )
    }

    fun resetAll(plan: MonthlyPlan): MonthlyPlan =
        plan.copy(days = plan.days.map { plan.originals[it.date] ?: it }, originals = emptyMap())

    private fun put(plan: MonthlyPlan, changes: List<MonthDay>): MonthlyPlan {
        val byDate = changes.associateBy { it.date }
        val originals = plan.originals.toMutableMap()
        plan.days.forEach { d -> if (d.date in byDate && d.date !in originals) originals[d.date] = d }
        val days = plan.days.map { d ->
            val n = byDate[d.date] ?: return@map d
            val orig = originals[d.date]
            if (orig != null && (orig == n.copy(edited = false) || (orig.isRest && n.isRest))) {
                originals.remove(d.date)
                orig
            } else {
                n.copy(edited = true)
            }
        }
        return plan.copy(days = days, originals = originals)
    }

    /** Heads-ups about [date]; the edit is still allowed. */
    fun warnings(plan: MonthlyPlan, date: LocalDate): List<String> {
        val d = plan.day(date) ?: return emptyList()
        val out = LinkedHashSet<String>()
        if (!d.isRest) {
            if (d.kind.hard) {
                val touching = listOf(date.minusDays(1), date.plusDays(1)).mapNotNull { plan.day(it) }.any { it.kind.hard }
                if (touching) out += "Two hard days back to back. A day between them helps you recover."
            }
            val orig = plan.sessions.map { plan.originals[it.date] ?: it }
            val longest = orig.maxOfOrNull { it.minutes } ?: 0
            if (longest > 0 && d.minutes > longest * 1.25) out += "That's longer than any session planned so far. Build up gradually."
            val week = plan.days.filter { it.week == d.week }
            val planned = week.sumOf { (plan.originals[it.date] ?: it).minutes }
            val now = week.sumOf { it.minutes }
            if (planned > 0 && now > planned * 1.25) out += "This week is now ${((now.toDouble() / planned - 1) * 100).roundToInt()}% bigger than planned."
            if (plan.easyWeek == d.week && d.kind.hard && d.kind != MonthKind.LONG) out += "This is your lighter week. A hard session works against the rest."
        }
        return out.toList()
    }
}
