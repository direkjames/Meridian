package com.meridian.tracker.data

import com.meridian.tracker.tracking.Geo
import com.meridian.tracker.tracking.TrackPoint

/** A circle (like home or work) where the start and end of a route are hidden when sharing or exporting. */
data class PrivacyZone(val id: String, val name: String, val lat: Double, val lon: Double, val radiusM: Double)

/**
 * Privacy zones hide where you live. They only change what leaves the app (share images and
 * GPX files): saved activities, stats and backups keep the full route. Pure Kotlin, unit-tested.
 */
object PrivacyZones {

    const val MAX_ZONES = 10
    const val MAX_NAME = 40
    const val MIN_RADIUS_M = 100.0
    const val MAX_RADIUS_M = 2_000.0

    /** Radii offered in the picker (metres). */
    val RADII_M = listOf(150.0, 300.0, 500.0, 1_000.0)

    /** A tidy copy of [zone], or null when its position is not a real place. */
    fun clean(zone: PrivacyZone): PrivacyZone? {
        if (!zone.lat.isFinite() || !zone.lon.isFinite() || !zone.radiusM.isFinite()) return null
        if (zone.lat !in -90.0..90.0 || zone.lon !in -180.0..180.0) return null
        val name = zone.name.trim().take(MAX_NAME).ifEmpty { "Private place" }
        return zone.copy(name = name, radiusM = zone.radiusM.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M))
    }

    fun inside(zones: List<PrivacyZone>, lat: Double, lon: Double): Boolean =
        zones.any { Geo.distanceM(lat, lon, it.lat, it.lon) <= it.radiusM }

    /**
     * Drops the points at the start and at the end of [points] that lie inside a zone, up to the
     * first point outside one. Parts of the route that pass through a zone in the middle stay, so
     * a lap around the block still looks like one. Returns an empty list when nothing is left to show.
     */
    fun hide(points: List<TrackPoint>, zones: List<PrivacyZone>): List<TrackPoint> {
        if (zones.isEmpty() || points.isEmpty()) return points
        var from = 0
        while (from < points.size && inside(zones, points[from].lat, points[from].lon)) from++
        if (from == points.size) return emptyList()
        var to = points.size
        while (to > from && inside(zones, points[to - 1].lat, points[to - 1].lon)) to--
        if (from == 0 && to == points.size) return points
        return points.subList(from, to).toList().takeIf { it.size >= 2 } ?: emptyList()
    }

    /** [record] as it may leave the app: same numbers, route trimmed by the zones. */
    fun apply(record: ActivityRecord, zones: List<PrivacyZone>): ActivityRecord {
        if (zones.isEmpty() || record.points.isEmpty()) return record
        val shown = hide(record.points, zones)
        return if (shown.size == record.points.size) record else record.copy(points = shown)
    }
}
