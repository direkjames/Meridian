package com.meridian.tracker.data

import com.meridian.tracker.tracking.Sport
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Treadmill and other indoor activities have no GPS track, so distance comes from steps times
 * your stride until you type in what the machine says. Pure Kotlin, unit-tested.
 */
object Indoor {

    const val DEFAULT_STRIDE_M = 0.85
    private const val MIN_STRIDE_M = 0.4
    private const val MAX_STRIDE_M = 2.0

    /** Longest distance that can be entered by hand (km). */
    const val MAX_DISTANCE_M = 1_000_000.0

    /** True for a record made here without a route, whose distance may be typed in. */
    fun canSetDistance(r: ActivityRecord): Boolean =
        !r.sport.usesGps && r.points.isEmpty() && r.source == null && !r.id.startsWith("imp-")

    /**
     * Your usual step length, from the median of recent runs and treadmill sessions that counted steps.
     * Falls back to [DEFAULT_STRIDE_M] when there's no history to learn from.
     */
    fun stride(history: List<ActivityRecord>): Double {
        val strides = history.asSequence()
            .filter { it.sport.isRunning && (it.steps ?: 0) >= 200 && it.distanceM >= 500 }
            .sortedByDescending { it.startTimeMs }
            .take(30)
            .mapNotNull { it.strideM }
            .filter { it in MIN_STRIDE_M..MAX_STRIDE_M }
            .sorted()
            .toList()
        if (strides.isEmpty()) return DEFAULT_STRIDE_M
        return strides[strides.size / 2]
    }

    fun distanceFromSteps(steps: Int, strideM: Double): Double = (steps.coerceAtLeast(0) * strideM).coerceAtMost(MAX_DISTANCE_M)

    /** Kilometre splits and the unfinished last stretch for an even effort over [distanceM] in [movingMs]. */
    fun splits(distanceM: Double, movingMs: Long): Triple<List<Long>, Double, Long> {
        if (distanceM < 1.0 || movingMs <= 0) return Triple(emptyList(), 0.0, 0L)
        val perKm = (movingMs * 1000.0 / distanceM).roundToLong()
        val whole = floor(distanceM / 1000.0).toInt()
        val partialM = distanceM - whole * 1000.0
        return Triple(List(whole) { perKm }, partialM, (perKm * partialM / 1000.0).roundToLong())
    }

    /**
     * Sets the distance of a treadmill session to what the machine showed. Null when the distance
     * is not a positive number, is too far, or would mean moving faster than the sport allows.
     */
    fun withDistance(r: ActivityRecord, distanceM: Double): ActivityRecord? {
        if (!canSetDistance(r)) return null
        if (!distanceM.isFinite() || distanceM <= 0 || distanceM > MAX_DISTANCE_M) return null
        val moving = if (r.movingMs > 0) r.movingMs else r.elapsedMs
        if (moving <= 0) return null
        if (distanceM / (moving / 1000.0) > r.sport.maxSpeed) return null
        val (splits, partialM, partialMs) = splits(distanceM, moving)
        return r.copy(
            distanceM = distanceM,
            movingMs = moving,
            splitsMs = splits,
            partialSplitM = partialM,
            partialSplitMs = partialMs,
        )
    }
}
