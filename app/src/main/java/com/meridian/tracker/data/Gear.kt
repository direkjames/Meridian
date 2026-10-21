package com.meridian.tracker.data

import com.meridian.tracker.tracking.Sport
import kotlin.math.max

enum class GearType(val label: String) {
    SHOES("Shoes"),
    BIKE("Bike"),
    OTHER("Other"),
}

/**
 * Something you use for activities: running shoes, a bike… Distance is counted from every
 * activity it's tagged on, plus [startM] for mileage it already had before Meridian.
 */
data class Gear(
    val id: String,
    val name: String,
    val type: GearType,
    /** Replace it after this many metres. Null = no limit. */
    val limitM: Double?,
    val startM: Double = 0.0,
    val retired: Boolean = false,
    val createdMs: Long,
)

enum class GearStatus { OK, NEARLY_DONE, WORN_OUT }

data class GearUsage(val gear: Gear, val usedM: Double, val activities: Int) {
    val remainingM: Double? get() = gear.limitM?.let { max(0.0, it - usedM) }

    /** 0 → new, 1 → at its limit (can go past 1). Null without a limit. */
    val fraction: Double? get() = gear.limitM?.takeIf { it > 0 }?.let { usedM / it }

    val status: GearStatus
        get() {
            val f = fraction ?: return GearStatus.OK
            return when {
                f >= 1.0 -> GearStatus.WORN_OUT
                f >= GearMath.NEARLY_DONE -> GearStatus.NEARLY_DONE
                else -> GearStatus.OK
            }
        }
}

/** Gear distances and suggestions. Pure Kotlin, so it's unit-tested. */
object GearMath {

    private const val METERS_PER_MILE = 1609.344

    /**
     * Running shoes are usually replaced somewhere between 500 and 800 km (300–500 mi), when the
     * foam stops cushioning. 700 km / 450 mi is a sensible middle; it can be changed per pair.
     */
    const val SHOE_LIMIT_KM = 700.0
    const val SHOE_LIMIT_MI = 450.0

    /** The warning shows from this share of the limit (e.g. 595 of 700 km). */
    const val NEARLY_DONE = 0.85

    const val MAX_NAME = 40
    const val MAX_PER_ACTIVITY = 4

    fun defaultLimitM(type: GearType, imperial: Boolean): Double? = when (type) {
        GearType.SHOES -> if (imperial) SHOE_LIMIT_MI * METERS_PER_MILE else SHOE_LIMIT_KM * 1000.0
        GearType.BIKE, GearType.OTHER -> null
    }

    fun typeFor(sport: Sport): GearType = if (sport == Sport.RIDE) GearType.BIKE else GearType.SHOES

    /**
     * Distance on [gear] from [activities]. [extra] is an activity that isn't saved yet (the one
     * on the Activity Complete screen); it's counted once even if it's already in the list.
     */
    fun usage(gear: Gear, activities: List<ActivityRecord>, extra: ActivityRecord? = null): GearUsage {
        val tagged = activities.filter { gear.id in it.gearIds && it.id != extra?.id }
        val all = if (extra != null && gear.id in extra.gearIds) tagged + extra else tagged
        return GearUsage(
            gear = gear,
            usedM = gear.startM.coerceAtLeast(0.0) + all.sumOf { it.distanceM.coerceAtLeast(0.0) },
            activities = all.size,
        )
    }

    /**
     * What to tick for a new activity: the gear from your latest activity of the same sport,
     * as long as it still exists and isn't retired.
     */
    fun suggest(sport: Sport, activities: List<ActivityRecord>, gear: List<Gear>, excludeId: String? = null): List<String> {
        val active = gear.filterNot { it.retired }.map { it.id }.toSet()
        val last = activities
            .filter { it.sport == sport && it.id != excludeId && it.gearIds.any { id -> id in active } }
            .maxByOrNull { it.startTimeMs }
            ?: return emptyList()
        return last.gearIds.filter { it in active }.take(MAX_PER_ACTIVITY)
    }

    /** Cleans up a name typed by the user. */
    fun cleanName(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(MAX_NAME)

    /** Parses a typed distance ("700", "450.5", "1,200") in the display unit. Null when invalid. */
    fun parseDistance(text: String, imperial: Boolean): Double? {
        val v = text.trim().replace(",", "").toDoubleOrNull() ?: return null
        if (v.isNaN() || v.isInfinite() || v < 0 || v > 100_000) return null
        return v * if (imperial) METERS_PER_MILE else 1000.0
    }
}
