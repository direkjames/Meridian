package com.meridian.tracker.data

import java.util.Locale
import kotlin.math.roundToLong

/** A route worth asking the route planner for: how far, on foot or by bike, and what shape. */
data class RouteIdea(val distanceM: Double, val profile: RouteProfile, val shape: RouteShape) {
    /** The distance as the route dialog shows it, in the chosen unit: "5", "7.5". */
    fun distanceText(unitMeters: Double): String {
        val v = String.format(Locale.US, "%.1f", distanceM / unitMeters)
        return v.removeSuffix(".0")
    }
}

/** Turns a planned session into a route request, so a month's sessions can each get a route. */
object MonthlyRoutes {

    /** The route for [day], or null for rest days, sessions moved away, and anything with no distance. */
    fun idea(plan: MonthlyPlan, day: MonthDay): RouteIdea? {
        val sport = day.sport ?: return null
        if (day.isRest || day.date in plan.movedAway) return null
        val profile = if (sport == MonthSport.RIDE) RouteProfile.BIKE else RouteProfile.FOOT
        // A time plan has minutes first, so work the distance out from your easy pace.
        val raw = if (day.distanceM > 0) day.distanceM else day.minutes * 60.0 / plan.config.pace(sport) * 1_000.0
        if (!raw.isFinite() || raw <= 0) return null
        val range = RouteGen.distanceRange(profile)
        val rounded = (raw / 100.0).roundToLong() * 100.0
        return RouteIdea(rounded.coerceIn(range.start, range.endInclusive), profile, RouteShape.LOOP)
    }
}
