package com.meridian.tracker.data

import com.meridian.tracker.tracking.Sport
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** How ready you are for the next session. */
enum class Readiness(val label: String) {
    NEW("Welcome"),
    REST("Recover"),
    EASY("Easy day"),
    STEADY("Steady"),
    GO("Go hard"),
}

enum class TipTone { INFO, GOOD, WARN }

enum class TipKind { SESSION, OVERREACH, RAMP_UP, REST_DAY, STREAK, LONG, HYDRATION, RECOVERY, GEAR, COMEBACK, WEEK, LEARNING }

data class CoachTip(val kind: TipKind, val tone: TipTone, val text: String)

/** The coach's answer: a headline about resting, three quick facts and a few extra tips. */
data class CoachAdvice(
    val readiness: Readiness,
    val headline: String,
    val detail: String,
    /** "Rest left", "Next start" and "Best for", shown as three small stats. */
    val restLeft: String,
    val nextStart: String,
    val bestFor: String,
    val tips: List<CoachTip>,
)

/**
 * Offline, rule-based coach. It reads your recent training load, form (freshness), streaks and gear,
 * and picks wording from several variants. [seed] picks the variant, so the text is steady during
 * a day but changes the next day (or when the user asks for another tip). Pure Kotlin: unit-tested.
 */
object Coach {

    const val MAX_TIPS = 3

    private const val HOUR_MS = 3_600_000L
    private const val MAX_REST_H = 96.0

    fun advise(
        records: List<ActivityRecord>,
        days: List<FitnessDay>,
        weekly: List<Pair<LocalDate, Double>>,
        gear: List<Gear>,
        thresholds: Map<Sport, Double>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        imperial: Boolean = false,
        seed: Int = 0,
        locale: Locale = Locale.getDefault(),
    ): CoachAdvice {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        // Anything dated in the future (a wrong clock on a watch, say) can't tell us how rested you are.
        val past = records.filter { it.startTimeMs <= nowMs }
        val last = past.maxByOrNull { it.startTimeMs }
            ?: return welcome(records.isNotEmpty(), seed)

        val form = days.lastOrNull()?.form?.takeIf { it.isFinite() } ?: 0.0
        val lastLoad = Effort.load(last, thresholds).takeIf { it.isFinite() } ?: 0.0
        val lastEndMs = last.startTimeMs + max(last.elapsedMs, last.movingMs).coerceAtLeast(0L)
        val hoursSince = ((nowMs - lastEndMs).coerceAtLeast(0L)) / HOUR_MS.toDouble()
        val daysSince = java.time.temporal.ChronoUnit.DAYS.between(Stats.localDate(last.startTimeMs, zone), today).toInt()

        val needH = recoveryHours(lastLoad, form)
        val leftH = (needH - hoursSince).coerceAtLeast(0.0)
        val recovering = leftH > 0.0
        val readiness = when {
            recovering -> Readiness.REST
            form > 15 -> Readiness.GO
            form > -10 -> Readiness.STEADY
            form > -30 -> Readiness.EASY
            else -> Readiness.REST
        }
        val overreached = form <= -30
        val readyAtMs = lastEndMs + (needH * HOUR_MS).toLong()
        val dayPart = dayPart(nowMs, zone)
        val sinceText = daysSince.let { if (it <= 0) "today" else if (it == 1) "yesterday" else "$it days ago" }

        val nextStart = when {
            readiness == Readiness.REST && overreached && !recovering -> "After a rest day"
            recovering -> whenText(readyAtMs, nowMs, zone, locale)
            else -> "Now"
        }
        val restLeft = when {
            recovering -> "~" + hoursText(leftH)
            overreached -> "1 more day"
            else -> "Ready"
        }
        val bestFor = when (readiness) {
            Readiness.REST -> if (recovering) "Walk or stretch" else "Full rest"
            Readiness.EASY -> "Easy session"
            Readiness.STEADY -> "Steady session"
            Readiness.GO -> "Hard session"
            Readiness.NEW -> "First activity"
        }

        val headline: String
        val detail: String
        when {
            recovering -> {
                val t = hoursText(leftH)
                headline = pick(seed, "h-rec",
                    "Rest for about $t more",
                    "Give your body about $t",
                    "Recovery mode: roughly $t to go",
                )
                detail = pick(seed, "d-rec",
                    "Your last session ($sinceText, ${Effort.label(lastLoad).lowercase()}) needs time to sink in. A good time to start the next one is ${nextStart.lowercase()}.",
                    "That ${Effort.label(lastLoad).lowercase()} effort $sinceText is still being absorbed. Aim to start again ${nextStart.lowercase()}; a gentle walk is fine before then.",
                    "Fitness is built while you rest, not while you train. Your next good window opens ${nextStart.lowercase()}.",
                )
            }
            overreached -> {
                headline = pick(seed, "h-over",
                    "Take a full rest day",
                    "Rest first, train later",
                    "Your body is asking for a break",
                )
                detail = pick(seed, "d-over",
                    "Your form is very low, which means a lot of load lately. One or two days off will bring you back stronger.",
                    "You've been carrying a heavy load. Skip the next session, sleep well and come back easy.",
                    "Pushing on now risks injury and a flat patch. A real rest day is the fastest way forward.",
                )
            }
            daysSince >= 4 -> {
                headline = pick(seed, "h-idle",
                    "You're rested and ready",
                    "Fully recharged: time to move",
                    "Plenty of rest, go get one in",
                )
                detail = pick(seed, "d-idle",
                    "It's been $daysSince days since your last activity. Fitness starts to fade after about a week, so a $dayPart session would be perfect.",
                    "You've rested $daysSince days. That's enough: pick an easy start this $dayPart and build back up.",
                    "Rest is done. Even a short outing this $dayPart keeps your momentum going.",
                )
            }
            else -> {
                headline = when (readiness) {
                    Readiness.GO -> pick(seed, "h-go", "Green light for a hard session", "You're fresh: go for it", "Today can be a big one")
                    Readiness.STEADY -> pick(seed, "h-steady", "Ready for a steady session", "Good to go at a normal pace", "A solid day to train")
                    else -> pick(seed, "h-easy", "Keep the next one easy", "Easy does it today", "A gentle session is the smart call")
                }
                detail = when (readiness) {
                    Readiness.GO -> pick(seed, "d-go",
                        "Recovered and fresh. A hard effort, intervals or a long one this $dayPart will pay off.",
                        "Your form is high, so your body can handle quality work. Make this $dayPart count.",
                        "You've rested enough to hit it hard. Warm up well and enjoy it.",
                    )
                    Readiness.STEADY -> pick(seed, "d-steady",
                        "You've recovered from your last session. Train at a comfortable, steady pace this $dayPart.",
                        "Training and recovery are in step. A moderate session keeps fitness climbing.",
                        "Nothing is holding you back. Keep it steady and save the hard efforts for when you feel fresher.",
                    )
                    else -> pick(seed, "d-easy",
                        "Your legs still carry some fatigue. A relaxed, conversational pace this $dayPart helps you absorb it.",
                        "Load has been high lately. Keep it short and easy and the fatigue will clear.",
                        "Easy days make hard days possible. Go gentle and stop while it still feels good.",
                    )
                }
            }
        }

        val tips = ArrayList<Pair<Int, CoachTip>>()
        fun tip(priority: Int, kind: TipKind, tone: TipTone, text: String) {
            tips += priority to CoachTip(kind, tone, text)
        }

        // --- Warnings first ---
        val thisWeek = weekly.lastOrNull()?.second ?: 0.0
        val prevAvg = weekly.dropLast(1).takeLast(3).map { it.second }.average().takeIf { it.isFinite() } ?: 0.0
        if (overreached) {
            tip(1, TipKind.OVERREACH, TipTone.WARN, pick(seed, "t-over",
                "Overreaching alert: your fatigue is well above your fitness. Rest before you push again.",
                "Careful, you're deep in the red. More hard days now mean injury risk, not gains.",
                "Your body is overloaded. Sleep, eat well and keep the next day or two very light.",
            ))
        }
        if (prevAvg > 20 && thisWeek > prevAvg * 1.5) {
            tip(2, TipKind.RAMP_UP, TipTone.WARN, pick(seed, "t-ramp",
                "You've ramped up fast this week, about ${((thisWeek / prevAvg - 1) * 100).roundToInt()}% above your usual. Sudden jumps cause injuries: ease off.",
                "Big jump in load this week. Keep increases under about 30% a week and the gains will last.",
                "Slow down the ramp-up. Your body adapts a little at a time, not all at once.",
            ))
        }
        val dayStreak = currentDayStreak(past, today, zone)
        if (dayStreak >= 5) {
            tip(3, TipKind.REST_DAY, TipTone.WARN, pick(seed, "t-restday",
                "That's $dayStreak days in a row. Schedule a rest day: it's when you actually get fitter.",
                "$dayStreak straight days of activity. Even pros take days off, so plan one soon.",
                "Streaks are great, but a rest day keeps this one going. Take one after $dayStreak days.",
            ))
        }
        val gearTip = gearTip(past, gear, imperial, seed)
        if (gearTip != null) tip(4, TipKind.GEAR, gearTip.first, gearTip.second)

        // --- Session ideas ---
        if (!recovering && !overreached) {
            val text = when (readiness) {
                Readiness.GO -> pick(seed, "t-hard",
                    "Hard day idea: 5 x 3 min fast with 2 min easy between, plus a warm-up and cool-down.",
                    "Try a tempo block today: 20 minutes at a comfortably hard pace.",
                    "Hill repeats or short fast intervals are a good use of your fresh legs.",
                )
                Readiness.STEADY -> pick(seed, "t-steady",
                    "Steady idea: 30 to 45 minutes at a pace where you could still chat.",
                    "A smooth, even-paced session builds your aerobic base without costing much recovery.",
                    "Keep the effort at about 6 out of 10 and enjoy the rhythm.",
                )
                else -> pick(seed, "t-easy",
                    "Easy day idea: 20 to 30 minutes at a talking pace, or a walk.",
                    "Keep it so gentle that you finish feeling better than you started.",
                    "Short and slow wins today. Save your energy for the next quality session.",
                )
            }
            tip(6, TipKind.SESSION, if (readiness == Readiness.GO) TipTone.GOOD else TipTone.INFO, text)
        }
        longSessionTip(past, form, recovering, today, zone, imperial, seed)?.let { tip(7, TipKind.LONG, TipTone.INFO, it) }
        if (!recovering && daysSince >= 7) {
            tip(5, TipKind.COMEBACK, TipTone.INFO, pick(seed, "t-back",
                "Coming back after a break: start at about half your old session and add a little each time.",
                "Welcome back. Two or three easy sessions this week will rebuild your rhythm safely.",
                "Don't try to pick up where you left off. Ease in, and your old pace returns quickly.",
            ))
        }

        // --- Recovery reminders ---
        if (hoursSince < 8 && (last.movingMs >= 45 * 60_000L || lastLoad >= 50)) {
            tip(8, TipKind.HYDRATION, TipTone.INFO, pick(seed, "t-hydr",
                "Drink water now and have a snack with some protein and carbs within the hour.",
                "Rehydrate: a big glass of water and a proper meal will speed up your recovery.",
                "Refuel soon. Fluids, carbs and a little protein help your muscles repair.",
            ))
        } else if (lastLoad >= 110 && hoursSince < 36) {
            tip(8, TipKind.RECOVERY, TipTone.INFO, pick(seed, "t-recov",
                "After a hard effort, sleep is your best recovery tool. Aim for an early night.",
                "Stretch gently, eat well and get a full night's sleep. That's where you rebuild.",
                "Tight legs? A short walk and light stretching help more than sitting still.",
            ))
        }

        // --- Praise and weekly progress ---
        val weekStreak = Stats.currentWeekStreak(past, today, zone)
        if (dayStreak in 3..4) {
            tip(9, TipKind.STREAK, TipTone.GOOD, pick(seed, "t-streak",
                "$dayStreak days in a row. Nice consistency!",
                "You're on a $dayStreak-day streak. Keep that rhythm going.",
                "$dayStreak days straight: that's how habits are built.",
            ))
        } else if (weekStreak >= 4) {
            tip(9, TipKind.STREAK, TipTone.GOOD, pick(seed, "t-weeks",
                "$weekStreak active weeks in a row. That's real consistency.",
                "Week after week, you keep showing up ($weekStreak so far). Well done.",
                "A $weekStreak-week streak! Consistency beats intensity.",
            ))
        }
        val range = Fitness.targetRange(weekly)
        if (range != null && !overreached) {
            val lateInWeek = today.dayOfWeek >= DayOfWeek.THURSDAY
            if (thisWeek < range.start && lateInWeek && thisWeek >= 0.0) {
                tip(10, TipKind.WEEK, TipTone.INFO, pick(seed, "t-week",
                    "You're a bit under your usual weekly effort. One more session would put you right on track.",
                    "The week is getting on and there's room for more. A moderate session would round it out.",
                    "Below your normal load this week. Fit in another outing if you feel good.",
                ))
            } else if (thisWeek in range.start..range.endInclusive && thisWeek > 0) {
                tip(10, TipKind.WEEK, TipTone.GOOD, pick(seed, "t-ontrack",
                    "Your weekly effort is right in your sweet spot. Keep going.",
                    "On track for the week: a nicely balanced load.",
                    "This week's load looks just right. Nothing to change.",
                ))
            }
        }
        if (past.size < 5) {
            tip(11, TipKind.LEARNING, TipTone.INFO, pick(seed, "t-learn",
                "I'm still learning you. The more activities you record, the sharper my advice gets.",
                "Only ${past.size} activit${if (past.size == 1) "y" else "ies"} so far. After a few more, I can tailor your rest times.",
                "Keep recording and I'll learn your fitness, your recovery and your best routine.",
            ))
        }

        return CoachAdvice(
            readiness, headline, detail, restLeft, nextStart, bestFor,
            tips.sortedBy { it.first }.map { it.second }.take(MAX_TIPS),
        )
    }

    /** Shown when there's nothing to go on yet. */
    private fun welcome(hasOnlyFuture: Boolean, seed: Int): CoachAdvice = CoachAdvice(
        readiness = Readiness.NEW,
        headline = pick(seed, "h-new", "Let's get your first one in", "Ready when you are", "Your coach is warming up"),
        detail = pick(seed, "d-new",
            "Record or import an activity and I'll tell you how long to rest and when to go again.",
            "Once you've done a few activities, I'll track your fatigue and fitness and plan your rest days.",
            "No history yet. Start with an easy 20 to 30 minutes and I'll take it from there.",
        ),
        restLeft = "--",
        nextStart = "Whenever",
        bestFor = "Easy start",
        tips = listOf(
            CoachTip(TipKind.LEARNING, TipTone.INFO, pick(seed, "t-new",
                "Start easy. Your first few weeks are about building the habit, not speed.",
                "Tip: pace yourself so you could talk in full sentences. That's the right effort for now.",
                "Pick a regular time of day. Routines make it easy to stay consistent.",
            )),
            CoachTip(TipKind.HYDRATION, TipTone.INFO, pick(seed, "t-new2",
                "Drink water before and after each session. Little habits add up.",
                "Wear comfortable shoes and add them under Gear so I can warn you before they wear out.",
                "Sleep well: it matters as much as the activity itself.",
            )),
        ).let { if (hasOnlyFuture) it.take(1) else it },
    )

    // ---- Rules ----------------------------------------------------------------------------

    /** Hours to rest after a session of [load], shortened when fresh and stretched when tired. */
    fun recoveryHours(load: Double, form: Double): Double {
        val base = when {
            load < 25 -> 12.0
            load < 60 -> 24.0
            load < 110 -> 36.0
            load < 180 -> 48.0
            else -> 72.0
        }
        val adjusted = when {
            form > 15 -> base * 0.75
            form <= -30 -> base + 24
            form <= -10 -> base + 12
            else -> base
        }
        return adjusted.coerceIn(6.0, MAX_REST_H)
    }

    /** Consecutive days with an activity, ending today (or yesterday, if today has none yet). */
    fun currentDayStreak(records: List<ActivityRecord>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int {
        val days = records.map { Stats.localDate(it.startTimeMs, zone) }.toSet()
        var d = if (today in days) today else today.minusDays(1)
        var n = 0
        while (d in days) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    private fun gearTip(records: List<ActivityRecord>, gear: List<Gear>, imperial: Boolean, seed: Int): Pair<TipTone, String>? {
        val worst = gear.filterNot { it.retired }
            .map { GearMath.usage(it, records) }
            .filter { it.status != GearStatus.OK }
            .maxByOrNull { it.fraction ?: 0.0 }
            ?: return null
        val name = worst.gear.name
        if (worst.status == GearStatus.WORN_OUT) {
            return TipTone.WARN to pick(seed, "t-worn",
                "$name has passed its limit. Worn gear raises injury risk, so consider replacing it.",
                "Time to retire $name. It's past its recommended distance.",
                "$name is worn out. Fresh gear protects your legs and joints.",
            )
        }
        val left = distText(worst.remainingM ?: 0.0, imperial)
        return TipTone.WARN to pick(seed, "t-near",
            "$name is nearly worn: about $left left. Start shopping for a replacement.",
            "Heads up: $name has only $left to go. Rotate in a newer pair for a while.",
            "$name is close to its limit ($left left). Plan its replacement soon.",
        )
    }

    private fun longSessionTip(
        records: List<ActivityRecord>,
        form: Double,
        recovering: Boolean,
        today: LocalDate,
        zone: ZoneId,
        imperial: Boolean,
        seed: Int,
    ): String? {
        if (recovering || form <= -10) return null
        val recent = records.filter { Stats.localDate(it.startTimeMs, zone) in today.minusDays(42)..today && it.distanceM >= 500 }
        val sport = recent.groupBy { it.sport }.maxByOrNull { it.value.size }?.takeIf { it.value.size >= 4 } ?: return null
        val avg = sport.value.map { it.distanceM }.average()
        val longest10 = sport.value.filter { Stats.localDate(it.startTimeMs, zone) >= today.minusDays(10) }.maxOfOrNull { it.distanceM } ?: 0.0
        if (!avg.isFinite() || avg < 1000 || longest10 >= avg * 1.4) return null
        val unit = if (imperial) 1609.344 else 1000.0
        val target = ((avg * 1.5 / unit) * 2).roundToInt() / 2.0
        val goal = String.format(Locale.US, "%.1f %s", target, if (imperial) "mi" else "km")
        val word = sport.key.label.lowercase()
        return pick(seed, "t-long",
            "You haven't done a long $word lately. Try about $goal at an easy pace.",
            "Time for a longer one: a relaxed $goal $word builds endurance.",
            "Stretch it out this week with a $goal $word, slow and steady.",
        )
    }

    // ---- Wording helpers ------------------------------------------------------------------

    private fun pick(seed: Int, key: String, vararg variants: String): String =
        variants[Math.floorMod(seed + key.hashCode(), variants.size)]

    private fun distText(m: Double, imperial: Boolean): String {
        val unit = if (imperial) 1609.344 else 1000.0
        return String.format(Locale.US, "%.0f %s", (m / unit).coerceAtLeast(0.0), if (imperial) "mi" else "km")
    }

    private fun hoursText(h: Double): String = when {
        h < 1.0 -> "under an hour"
        h < 48.0 -> "${h.roundToInt()} h"
        else -> "${(h / 24).roundToInt()} days"
    }

    private fun dayPart(ms: Long, zone: ZoneId): String = when (Instant.ofEpochMilli(ms).atZone(zone).hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        else -> "evening"
    }

    /** "this evening", "tomorrow morning", "Friday afternoon": when the rest ends, never at night. */
    private fun whenText(readyMs: Long, nowMs: Long, zone: ZoneId, locale: Locale): String {
        var at = Instant.ofEpochMilli(readyMs).atZone(zone)
        if (at.hour >= 22) at = at.plusDays(1).withHour(7)
        else if (at.hour < 5) at = at.withHour(7)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val date = at.toLocalDate()
        val part = dayPart(at.toInstant().toEpochMilli(), zone)
        return when (java.time.temporal.ChronoUnit.DAYS.between(today, date)) {
            0L -> if (part == "evening") "this evening" else "later this $part"
            1L -> "tomorrow $part"
            else -> "${date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)} $part"
        }
    }
}
