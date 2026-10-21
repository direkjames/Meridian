package com.meridian.tracker.tracking

/**
 * Activity types. Speeds are in metres per second.
 * - maxSpeed: anything faster between two fixes is treated as a GPS spike.
 * - movingSpeed: below this the athlete counts as stopped (moving time pauses).
 */
enum class Sport(
    val label: String,
    val maxSpeed: Double,
    val movingSpeed: Double,
    val showsSpeed: Boolean,
    /** Kalman process noise (m/s): how quickly the position estimate may change. */
    val processNoise: Double,
    /** Compact name for chip selectors; falls back to [label]. */
    shortLabel: String? = null,
) {
    RUN("Run", maxSpeed = 12.0, movingSpeed = 0.8, showsSpeed = false, processNoise = 3.0),
    TRAIL_RUN("Trail run", maxSpeed = 10.0, movingSpeed = 0.5, showsSpeed = false, processNoise = 3.5, shortLabel = "Trail"),
    WALK("Walk", maxSpeed = 5.0, movingSpeed = 0.4, showsSpeed = false, processNoise = 2.0),
    HIKE("Hike", maxSpeed = 4.5, movingSpeed = 0.3, showsSpeed = false, processNoise = 2.5),
    RIDE("Ride", maxSpeed = 30.0, movingSpeed = 1.5, showsSpeed = true, processNoise = 5.0);

    val short: String = shortLabel ?: label

    /** Running-type sports share pace-based targets, plans and training load. */
    val isRunning: Boolean get() = this == RUN || this == TRAIL_RUN
    val isWalking: Boolean get() = this == WALK || this == HIKE
}

/** One recorded point on the route. [segment] changes after every pause. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val time: Long,
    val segment: Int,
)

/** Latest raw GPS reading, used for the GPS status pill and the map's "you" dot. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val receivedAt: Long, // SystemClock.elapsedRealtime()
)

enum class RecordStatus { IDLE, RECORDING, PAUSED }

enum class GpsQuality { SEARCHING, WEAK, GOOD, STRONG }

data class TrackingState(
    val status: RecordStatus = RecordStatus.IDLE,
    val sport: Sport = Sport.RUN,
    val points: List<TrackPoint> = emptyList(),
    val distanceM: Double = 0.0,
    val movingMs: Long = 0L,
    /** Elapsed time banked before the current recording stretch. */
    val elapsedBankedMs: Long = 0L,
    /** elapsedRealtime when the current stretch started, null while paused/idle. */
    val runningSince: Long? = null,
    val elevationGainM: Double = 0.0,
    val splitsMs: List<Long> = emptyList(),
    val currentSpeed: Double = 0.0,
    val lastFix: Fix? = null,
    val startedAtWallMs: Long = 0L,
    val hasBarometer: Boolean = false,
    /** Latest heart rate from a Bluetooth sensor, null when none is connected or it went quiet. */
    val heartRate: Int? = null,
    /** Steps this recording, null when steps aren't being counted. */
    val steps: Int? = null,
    /** Live steps per minute. */
    val cadence: Int? = null,
) {
    fun elapsedMs(nowRealtime: Long): Long =
        elapsedBankedMs + (runningSince?.let { (nowRealtime - it).coerceAtLeast(0L) } ?: 0L)

    fun gpsQuality(nowRealtime: Long): GpsQuality {
        val fix = lastFix ?: return GpsQuality.SEARCHING
        if (nowRealtime - fix.receivedAt > 10_000L) return GpsQuality.SEARCHING
        return when {
            fix.accuracy <= 8f -> GpsQuality.STRONG
            fix.accuracy <= TrackProcessor.GOOD_ACCURACY_M -> GpsQuality.GOOD
            else -> GpsQuality.WEAK
        }
    }
}
