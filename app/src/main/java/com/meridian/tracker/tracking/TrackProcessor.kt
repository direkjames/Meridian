package com.meridian.tracker.tracking

import kotlin.math.max
import kotlin.math.min

/**
 * Turns raw GPS fixes into a clean route plus distance, moving time and splits.
 *
 * Jitter filtering:
 *  1. Fixes worse than [MAX_ACCURACY_M] are ignored.
 *  2. A fix is only accepted once it is far enough from the last accepted point
 *     (scaled by the fix's accuracy), so a phone lying still doesn't add distance.
 *  3. Jumps faster than the sport's top speed are discarded as spikes.
 *  4. Very slow "creep" (GPS drift while standing) moves the anchor without adding distance.
 *
 * Pure Kotlin with no Android dependencies, so it can be unit-tested.
 */
class TrackProcessor(private val sport: Sport) {

    val points = ArrayList<TrackPoint>()
    val splitsMs = ArrayList<Long>()

    var distanceM = 0.0
        private set
    var movingMs = 0L
        private set
    var currentSpeed = 0.0
        private set

    private var segment = 0
    private var anchor: TrackPoint? = null
    private var lastSplitMovingMs = 0L

    // (time, cumulative distance) samples for the rolling current-speed window.
    private val window = ArrayDeque<Pair<Long, Double>>()

    /** Call after a pause so the route doesn't draw or count a line across the gap. */
    fun startNewSegment() {
        if (points.isNotEmpty()) segment++
        anchor = null
        window.clear()
        currentSpeed = 0.0
        kVariance = -1.0
    }

    // ---- Kalman smoothing -----------------------------------------------------------------
    // A simple position filter: trusts accurate fixes, averages noisy ones, and lets the
    // estimate move at most about as fast as the sport plausibly allows.

    private var kLat = 0.0
    private var kLon = 0.0
    private var kVariance = -1.0 // metres², negative = not initialised
    private var kTime = 0L
    private var rejectedInARow = 0

    private fun smooth(lat: Double, lon: Double, accuracyM: Float, timeMs: Long): Pair<Double, Double>? {
        val acc = max(accuracyM.toDouble(), 3.0)
        if (kVariance < 0) {
            kLat = lat; kLon = lon; kVariance = acc * acc; kTime = timeMs
            return lat to lon
        }
        val dt = timeMs - kTime
        if (dt <= 0L) return null

        // Spike check against the current estimate, before it can pull the filter off course.
        val jump = Geo.distanceM(kLat, kLon, lat, lon)
        val allowed = sport.maxSpeed * (dt / 1000.0) + 2 * acc + 10.0
        if (jump > allowed) {
            rejectedInARow++
            if (rejectedInARow < 5) return null
            // Five outliers in a row: the estimate is probably what's wrong. Start over here.
            kLat = lat; kLon = lon; kVariance = acc * acc; kTime = timeMs; rejectedInARow = 0
            anchor = null
            return lat to lon
        }
        rejectedInARow = 0

        val q = sport.processNoise
        kVariance += (dt / 1000.0) * q * q
        kTime = timeMs
        val k = kVariance / (kVariance + acc * acc)
        kLat += k * (lat - kLat)
        kLon += k * (lon - kLon)
        kVariance *= (1 - k)
        return kLat to kLon
    }

    /**
     * Feed one GPS fix. [timeMs] must be a monotonic clock in milliseconds.
     * Returns true if the point was added to the route.
     */
    fun onFix(rawLat: Double, rawLon: Double, alt: Double?, accuracyM: Float, timeMs: Long): Boolean {
        if (accuracyM > MAX_ACCURACY_M) return false
        if (anchor == null && accuracyM > GOOD_ACCURACY_M) return false

        val (lat, lon) = smooth(rawLat, rawLon, accuracyM, timeMs) ?: return false

        val prev = anchor
        if (prev == null) {
            val p = TrackPoint(lat, lon, alt, timeMs, segment)
            points.add(p)
            anchor = p
            window.addLast(timeMs to distanceM)
            return true
        }

        val dtMs = timeMs - prev.time
        if (dtMs <= 0L) return false

        val d = Geo.distanceM(prev.lat, prev.lon, lat, lon)
        val threshold = max(MIN_STEP_M, min(accuracyM.toDouble(), 20.0) * 0.8)
        if (d < threshold) {
            if (timeMs - prev.time > STOPPED_AFTER_MS) currentSpeed = 0.0
            return false
        }

        val speed = d / (dtMs / 1000.0)
        if (speed > sport.maxSpeed) return false // GPS spike

        val p = TrackPoint(lat, lon, alt, timeMs, segment)

        if (speed < sport.movingSpeed * 0.5) {
            // Drift while standing still: follow it, but don't count it.
            anchor = p
            currentSpeed = 0.0
            return false
        }

        val prevDistance = distanceM
        val prevMoving = movingMs
        val addedMoving = if (speed >= sport.movingSpeed) dtMs else 0L

        distanceM += d
        movingMs += addedMoving
        points.add(p)
        anchor = p

        recordSplits(prevDistance, prevMoving, d, addedMoving)
        updateCurrentSpeed(timeMs, speed)
        return true
    }

    /** Distance and moving time of the unfinished last kilometre. */
    fun partialSplit(): Pair<Double, Long> =
        (distanceM - splitsMs.size * 1000.0) to (movingMs - lastSplitMovingMs)

    private fun recordSplits(prevDistance: Double, prevMoving: Long, added: Double, addedMoving: Long) {
        while (distanceM >= (splitsMs.size + 1) * 1000.0) {
            val boundary = (splitsMs.size + 1) * 1000.0
            val fraction = ((boundary - prevDistance) / added).coerceIn(0.0, 1.0)
            val movingAtBoundary = prevMoving + (addedMoving * fraction).toLong()
            splitsMs.add(movingAtBoundary - lastSplitMovingMs)
            lastSplitMovingMs = movingAtBoundary
        }
    }

    private fun updateCurrentSpeed(timeMs: Long, instant: Double) {
        window.addLast(timeMs to distanceM)
        while (window.size > 2 && timeMs - window.first().first > SPEED_WINDOW_MS) {
            window.removeFirst()
        }
        val first = window.first()
        val span = timeMs - first.first
        currentSpeed = if (span >= 2_000L) (distanceM - first.second) / (span / 1000.0) else instant
    }

    companion object {
        const val MAX_ACCURACY_M = 35f
        const val GOOD_ACCURACY_M = 20f
        const val MIN_STEP_M = 4.0
        const val SPEED_WINDOW_MS = 15_000L
        const val STOPPED_AFTER_MS = 6_000L
    }
}
