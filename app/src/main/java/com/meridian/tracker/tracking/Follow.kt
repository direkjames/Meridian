package com.meridian.tracker.tracking

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Your previous effort as a "ghost": where it was at the same moving time, and how far
 * ahead or behind you are. Pauses in the old activity don't count against it.
 */
class GhostTracker(private val points: List<TrackPoint>) {

    private val dist = DoubleArray(points.size)
    private val time = LongArray(points.size)

    init {
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val same = a.segment == b.segment
            dist[i] = dist[i - 1] + if (same) Geo.distanceM(a.lat, a.lon, b.lat, b.lon) else 0.0
            time[i] = time[i - 1] + if (same) (b.time - a.time).coerceAtLeast(0L) else 0L
        }
    }

    val totalM: Double get() = dist.lastOrNull() ?: 0.0

    /** Moving time (ms) the ghost needed to reach [meters], or null beyond its finish. */
    fun timeAtDistance(meters: Double): Long? {
        if (points.size < 2 || meters > totalM) return null
        var lo = 0
        var hi = dist.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (dist[mid] < meters) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return 0L
        val span = dist[lo] - dist[lo - 1]
        val f = if (span > 0) (meters - dist[lo - 1]) / span else 0.0
        return time[lo - 1] + ((time[lo] - time[lo - 1]) * f).toLong()
    }

    /** The ghost's position after [movingMs] of moving time. */
    fun positionAt(movingMs: Long): Pair<Double, Double>? {
        if (points.size < 2) return null
        if (movingMs >= time.last()) return points.last().lat to points.last().lon
        var lo = 0
        var hi = time.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (time[mid] < movingMs) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return points[0].lat to points[0].lon
        val span = (time[lo] - time[lo - 1]).toDouble()
        val f = if (span > 0) (movingMs - time[lo - 1]) / span else 0.0
        val a = points[lo - 1]
        val b = points[lo]
        return (a.lat + (b.lat - a.lat) * f) to (a.lon + (b.lon - a.lon) * f)
    }

    /** Seconds ahead of the ghost (negative = behind). Null when not comparable. */
    fun secondsAhead(myDistanceM: Double, myMovingMs: Long): Long? {
        if (myDistanceM < 50) return null
        val ghostMs = timeAtDistance(myDistanceM) ?: return null
        return (ghostMs - myMovingMs) / 1000
    }
}

/** Where you are along a planned route, and whether you've wandered off it. */
class RouteFollower(private val route: List<Pair<Double, Double>>) {

    private val cum = DoubleArray(route.size)
    private var lastIndex = 0

    init {
        for (i in 1 until route.size) {
            cum[i] = cum[i - 1] + Geo.distanceM(route[i - 1].first, route[i - 1].second, route[i].first, route[i].second)
        }
    }

    val totalM: Double get() = cum.lastOrNull() ?: 0.0

    data class Progress(val alongM: Double, val offRouteM: Double, val remainingM: Double)

    fun update(lat: Double, lon: Double): Progress? {
        if (route.size < 2) return null
        // Look near the last position first, so out-and-back routes don't jump ahead.
        var best = search(lat, lon, max(0, lastIndex - 5), minOf(route.size - 2, lastIndex + 60))
        if (best.second > 80) {
            val global = search(lat, lon, 0, route.size - 2)
            if (global.second < best.second - 20) best = global
        }
        val (along, off, index) = Triple(best.first, best.second, best.third)
        lastIndex = index
        return Progress(along, off, (totalM - along).coerceAtLeast(0.0))
    }

    /** (distance along, distance off, segment index) of the nearest point in segments [from, to]. */
    private fun search(lat: Double, lon: Double, from: Int, to: Int): Triple<Double, Double, Int> {
        val kx = cos(lat * PI / 180.0) * 111_320.0
        val ky = 110_540.0
        var bestOff = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestIdx = from
        for (i in from..to) {
            val (aLat, aLon) = route[i]
            val (bLat, bLon) = route[i + 1]
            val ax = (aLon - lon) * kx; val ay = (aLat - lat) * ky
            val bx = (bLon - lon) * kx; val by = (bLat - lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx; val py = ay + t * dy
            val off = sqrt(px * px + py * py)
            if (off < bestOff) {
                bestOff = off
                bestAlong = cum[i] + (cum[i + 1] - cum[i]) * t
                bestIdx = i
            }
        }
        return Triple(bestAlong, bestOff, bestIdx)
    }
}
