package com.meridian.tracker.ui

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Number formatting shared by the UI, share images and the notification. Pure Kotlin.
 * Distances, paces and speeds follow the km / miles setting ([imperial]).
 */
object Format {

    private const val DASH_PACE = "–:––"
    private const val METERS_PER_MILE = 1609.344

    /** Set from Settings. false = kilometres, true = miles. */
    @Volatile
    var imperial: Boolean = false

    val distUnit: String get() = if (imperial) "mi" else "km"
    val paceUnit: String get() = if (imperial) "/mi" else "/km"
    val speedUnit: String get() = if (imperial) "mph" else "km/h"
    val distWord: String get() = if (imperial) "MILES" else "KILOMETRES"
    /** Metres in one display unit (1000 or 1609.344). */
    val unitMeters: Double get() = if (imperial) METERS_PER_MILE else 1000.0

    /** Distance in the chosen unit: 4271.3 m -> "4.27" (km) or "2.65" (mi). */
    fun km(meters: Double): String =
        String.format(Locale.US, "%.2f", meters / (if (imperial) METERS_PER_MILE else 1000.0))

    /** Same, with one decimal: "4.3". */
    fun dist1(meters: Double): String =
        String.format(Locale.US, "%.1f", meters / (if (imperial) METERS_PER_MILE else 1000.0))

    /** 1458000 ms -> "24:18", 4980000 -> "1:23:00" */
    fun duration(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /** Seconds per km -> "5:41" per km, or per mile when [imperial]. */
    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm <= 0.0) return DASH_PACE
        val perUnit = if (imperial) secPerKm * METERS_PER_MILE / 1000.0 else secPerKm
        if (perUnit > 99 * 60) return DASH_PACE
        val rounded = perUnit.roundToLong()
        return String.format(Locale.US, "%d:%02d", rounded / 60, rounded % 60)
    }

    /** Speed in m/s -> pace "m:ss" per km (or mile). */
    fun paceFromSpeed(speed: Double): String =
        if (speed < 0.3) DASH_PACE else pace(1000.0 / speed)

    /** Speed in m/s -> "23.4" in km/h (or mph). */
    fun speedKmh(speed: Double): String =
        String.format(Locale.US, "%.1f", if (imperial) speed * 3600.0 / METERS_PER_MILE else speed * 3.6)

    /** 12345 -> "12,345". */
    fun count(n: Int): String = String.format(Locale.US, "%,d", n)

    /** Metres -> "38" (elevation always in metres). */
    fun meters(m: Double): String = String.format(Locale.US, "%.0f", m)
}
