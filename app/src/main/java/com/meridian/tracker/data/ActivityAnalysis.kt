package com.meridian.tracker.data

import com.meridian.tracker.tracking.Geo
import com.meridian.tracker.tracking.TrackPoint

/** One slice of an activity, used to draw the pace and elevation charts. */
data class ProfileSample(
    /** Distance at the middle of this slice, in metres. */
    val distanceM: Double,
    /** Average speed over the slice in m/s, or null if no moving data fell in it. */
    val speed: Double?,
    /** Average elevation in metres, or null if unknown. */
    val elevation: Double?,
)

data class ActivityProfile(
    val samples: List<ProfileSample>,
    val totalM: Double,
    /** Fastest slice speed in m/s (smoothed, so single GPS spikes don't count). */
    val maxSpeed: Double,
    val minElevation: Double?,
    val maxElevation: Double?,
) {
    val hasElevation: Boolean get() = minElevation != null && maxElevation != null
    val hasSpeed: Boolean get() = samples.any { it.speed != null }
}

/**
 * Turns a recorded route into evenly spaced samples by distance.
 * Pauses (segment changes) don't count as distance or time. Pure Kotlin, unit-tested.
 */
object ActivityAnalysis {

    fun profile(points: List<TrackPoint>, buckets: Int = 120): ActivityProfile {
        val empty = ActivityProfile(emptyList(), 0.0, 0.0, null, null)
        if (points.size < 2) return empty

        // Cumulative distance per point; segments don't connect.
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val step = if (a.segment == b.segment) Geo.distanceM(a.lat, a.lon, b.lat, b.lon) else 0.0
            cumulative[i] = cumulative[i - 1] + step
        }
        val total = cumulative.last()
        if (total < 20.0) return empty

        val n = buckets.coerceAtLeast(2)
        val width = total / n
        val bucketDist = DoubleArray(n)
        val bucketTime = DoubleArray(n)
        val elevSum = DoubleArray(n)
        val elevCount = IntArray(n)

        fun bucketOf(d: Double) = (d / width).toInt().coerceIn(0, n - 1)

        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (a.segment != b.segment) continue
            val d = cumulative[i] - cumulative[i - 1]
            val dt = (b.time - a.time) / 1000.0
            if (d <= 0.0 || dt <= 0.0) continue
            val k = bucketOf((cumulative[i] + cumulative[i - 1]) / 2)
            bucketDist[k] += d
            bucketTime[k] += dt
        }
        points.forEachIndexed { i, p ->
            val alt = p.alt ?: return@forEachIndexed
            val k = bucketOf(cumulative[i])
            elevSum[k] += alt
            elevCount[k]++
        }

        // Fill elevation gaps by carrying the nearest known value forward, then backward.
        val elev = arrayOfNulls<Double>(n)
        for (k in 0 until n) if (elevCount[k] > 0) elev[k] = elevSum[k] / elevCount[k]
        var last: Double? = null
        for (k in 0 until n) { if (elev[k] != null) last = elev[k] else elev[k] = last }
        last = null
        for (k in n - 1 downTo 0) { if (elev[k] != null) last = elev[k] else elev[k] = last }

        val rawSpeed = (0 until n).map { k -> if (bucketTime[k] > 0) bucketDist[k] / bucketTime[k] else null }
        // Light smoothing (3-slice moving average) so the chart reads like effort, not GPS noise.
        val speed = rawSpeed.indices.map { k ->
            val window = (k - 1..k + 1).mapNotNull { rawSpeed.getOrNull(it) }
            if (rawSpeed[k] == null || window.isEmpty()) null else window.average()
        }

        val samples = (0 until n).map { k ->
            ProfileSample(distanceM = (k + 0.5) * width, speed = speed[k], elevation = elev[k])
        }
        val elevations = samples.mapNotNull { it.elevation }
        return ActivityProfile(
            samples = samples,
            totalM = total,
            maxSpeed = speed.filterNotNull().maxOrNull() ?: 0.0,
            minElevation = elevations.minOrNull(),
            maxElevation = elevations.maxOrNull(),
        )
    }
}
