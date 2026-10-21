package com.meridian.tracker.data

import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint

/** A timed reading: heart rate (beats per minute) or cadence (steps per minute). [time] is wall-clock ms. */
data class Sample(val time: Long, val value: Int)

data class ActivityRecord(
    val id: String,
    val title: String,
    val sport: Sport,
    val startTimeMs: Long, // wall clock
    val distanceM: Double,
    val movingMs: Long,
    val elapsedMs: Long,
    val elevationGainM: Double,
    val splitsMs: List<Long>,
    /** Distance (m) and moving time (ms) of the unfinished last kilometre. */
    val partialSplitM: Double,
    val partialSplitMs: Long,
    val points: List<TrackPoint>,
    /** Heart rate from a Bluetooth sensor, a watch (Health Connect) or an imported file. */
    val heartRate: List<Sample> = emptyList(),
    /** Steps per minute over time, from the phone's step counter. */
    val cadence: List<Sample> = emptyList(),
    /** Steps taken during the activity, or null when they weren't counted. */
    val steps: Int? = null,
    /** Where it came from when not recorded by Meridian, e.g. "Fitbit" (via Health Connect). */
    val source: String? = null,
) {
    /** Average pace in seconds per km, or null if not meaningful. */
    val avgPaceSecPerKm: Double?
        get() = if (distanceM >= 50 && movingMs > 0) (movingMs / 1000.0) / (distanceM / 1000.0) else null

    /** Average speed in m/s. */
    val avgSpeed: Double
        get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0

    val hasHeartRate: Boolean get() = heartRate.size >= 2

    val avgHeartRate: Int? get() = HeartRate.average(heartRate)

    val maxHeartRate: Int? get() = heartRate.maxOfOrNull { it.value }

    /** Average steps per minute, over the time spent moving. */
    val avgCadence: Int?
        get() {
            val s = steps ?: return null
            val minutes = (if (movingMs > 0) movingMs else elapsedMs) / 60_000.0
            return if (s > 0 && minutes >= 0.5) (s / minutes).toInt() else null
        }

    /** Average step length in metres. */
    val strideM: Double?
        get() {
            val s = steps ?: return null
            return if (s >= 50 && distanceM >= 100) distanceM / s else null
        }
}
