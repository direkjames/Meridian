package com.meridian.tracker.tracking

/**
 * Smooths altitude readings and counts total climb with hysteresis, so sensor noise
 * doesn't inflate elevation gain.
 *
 * Barometer altitude is precise for relative changes, so it uses a small threshold.
 * GPS altitude is much noisier, so it needs heavier smoothing and a larger threshold.
 */
class ElevationTracker(private val alpha: Double, private val thresholdM: Double) {

    var gainM = 0.0
        private set
    var current: Double? = null
        private set

    private var reference: Double? = null

    fun onAltitude(raw: Double, counting: Boolean) {
        if (raw.isNaN() || raw.isInfinite()) return
        val smoothed = current?.let { it + alpha * (raw - it) } ?: raw
        current = smoothed

        if (!counting) {
            reference = smoothed
            return
        }
        val ref = reference
        if (ref == null) {
            reference = smoothed
            return
        }
        if (smoothed - ref >= thresholdM) {
            gainM += smoothed - ref
            reference = smoothed
        } else if (ref - smoothed >= thresholdM) {
            reference = smoothed
        }
    }

    companion object {
        fun barometer() = ElevationTracker(alpha = 0.08, thresholdM = 2.0)
        fun gps() = ElevationTracker(alpha = 0.3, thresholdM = 6.0)
    }
}
