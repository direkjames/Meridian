package com.meridian.tracker.data

import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint

/** A photo or a video attached to an activity. */
enum class MediaKind { PHOTO, VIDEO }

/** [file] is a plain file name inside the activity's media folder (never a path). */
data class MediaItem(val id: String, val kind: MediaKind, val file: String, val addedMs: Long)

/** How the athlete felt afterwards. */
enum class Feeling(val label: String) {
    GREAT("Great"),
    GOOD("Good"),
    OKAY("Okay"),
    TIRED("Tired"),
    HURTING("Hurting"),
}

/** Rules for the "how it went" fields, shared by the editor, saving and loading. */
object ActivityDetails {
    const val MAX_NOTE = 500

    fun cleanNote(note: String): String = note.trim().take(MAX_NOTE)

    /** A 1 to 10 effort rating, or null when missing or out of range. */
    fun cleanEffort(value: Int?): Int? = value?.takeIf { it in 1..10 }

    fun effortWord(rating: Int): String = when (rating) {
        in 1..2 -> "Very easy"
        in 3..4 -> "Easy"
        in 5..6 -> "Moderate"
        in 7..8 -> "Hard"
        9 -> "Very hard"
        else -> "All out"
    }
}

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
    /** Photos and videos added after the activity, at most [ActivityMedia.MAX_PER_ACTIVITY]. */
    val media: List<MediaItem> = emptyList(),
    /** Shoes, bikes… used for this activity (ids from [ExtrasStore.gear]). */
    val gearIds: List<String> = emptyList(),
    /** What the athlete wrote about this activity. */
    val note: String = "",
    /** Perceived effort, 1 (very easy) to 10 (all out), or null when not rated. */
    val perceivedEffort: Int? = null,
    val feeling: Feeling? = null,
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
