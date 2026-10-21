package com.meridian.tracker.data

import com.meridian.tracker.data.io.RecordBuilder
import com.meridian.tracker.tracking.ElevationTracker
import com.meridian.tracker.tracking.Geo
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint
import kotlin.math.roundToInt

/**
 * Fixing a saved activity: changing its sport and trimming a forgotten start or stop.
 * Pure Kotlin, unit-tested.
 */
object ActivityEditing {

    /** A pause longer than this inside a track counts as a break, like when importing a file. */
    private const val PAUSE_GAP_MS = 120_000L

    /** The least a trimmed activity may keep. */
    const val MIN_POINTS = 2
    private const val MIN_DISTANCE_M = 10.0

    /** Distance, moving time, splits and climb of a stretch of track. */
    data class Measured(
        val distanceM: Double,
        val movingMs: Long,
        val splitsMs: List<Long>,
        val partialSplitM: Double,
        val partialSplitMs: Long,
        /** Null when the points carry no altitude at all. */
        val gainM: Double?,
    )

    /** True for activities recorded by Meridian itself (as opposed to imported or synced ones). */
    private fun recordedHere(r: ActivityRecord) = r.source == null && !r.id.startsWith("imp-")

    /**
     * Measures [points] the same way a recording or an import does. A change of [TrackPoint.segment]
     * (a pause in the app) or a long gap breaks the line, so nothing is counted across it.
     */
    fun measure(points: List<TrackPoint>, sport: Sport, recorded: Boolean): Measured {
        val movingFactor = if (recorded) 1.0 else 0.5
        val elevation = if (recorded) ElevationTracker(alpha = 1.0, thresholdM = 3.0) else ElevationTracker(alpha = 0.5, thresholdM = 3.0)
        var distance = 0.0
        var moving = 0L
        val splits = ArrayList<Long>()
        var lastSplitMoving = 0L
        var sawAltitude = false

        points.forEachIndexed { i, p ->
            if (i > 0) {
                val a = points[i - 1]
                val dt = p.time - a.time
                if (p.segment == a.segment && dt in 1..PAUSE_GAP_MS) {
                    val d = Geo.distanceM(a.lat, a.lon, p.lat, p.lon)
                    val speed = d / (dt / 1000.0)
                    if (speed <= sport.maxSpeed * 1.5) {
                        val prevDistance = distance
                        val prevMoving = moving
                        val addedMoving = if (speed >= sport.movingSpeed * movingFactor) dt else 0L
                        distance += d
                        moving += addedMoving
                        while (distance >= (splits.size + 1) * 1000.0) {
                            val boundary = (splits.size + 1) * 1000.0
                            val f = if (d > 0) ((boundary - prevDistance) / d).coerceIn(0.0, 1.0) else 1.0
                            val at = prevMoving + (addedMoving * f).toLong()
                            splits.add(at - lastSplitMoving)
                            lastSplitMoving = at
                        }
                    }
                }
            }
            p.alt?.let { sawAltitude = true; elevation.onAltitude(it, counting = true) }
        }
        return Measured(
            distanceM = distance,
            movingMs = moving,
            splitsMs = splits,
            partialSplitM = distance - splits.size * 1000.0,
            partialSplitMs = moving - lastSplitMoving,
            gainM = if (sawAltitude) elevation.gainM else null,
        )
    }

    /**
     * Keeps only the part of [record] between [fromMs] and [toMs] (wall-clock, inclusive) and works
     * out its numbers again. Returns null when too little would be left to be an activity.
     */
    fun trim(record: ActivityRecord, fromMs: Long, toMs: Long): ActivityRecord? {
        val kept = record.points.filter { it.time in fromMs..toMs }
        if (kept.size < MIN_POINTS) return null
        val recorded = recordedHere(record)
        val m = measure(kept, record.sport, recorded)
        if (m.distanceM < MIN_DISTANCE_M) return null

        val start = kept.first().time
        // Time spent paused in the app never counted towards elapsed time; keep it that way.
        val elapsed = if (recorded) {
            kept.groupBy { it.segment }.values.sumOf { it.last().time - it.first().time }
        } else {
            kept.last().time - start
        }
        val steps = record.steps?.let { s ->
            if (record.movingMs > 0) (s * m.movingMs.toDouble() / record.movingMs).roundToInt().coerceIn(0, s) else null
        }
        val oldDistance = record.distanceM
        return record.copy(
            startTimeMs = start,
            distanceM = m.distanceM,
            movingMs = m.movingMs,
            elapsedMs = elapsed,
            elevationGainM = m.gainM ?: if (oldDistance > 0) record.elevationGainM * (m.distanceM / oldDistance) else 0.0,
            splitsMs = m.splitsMs,
            partialSplitM = m.partialSplitM,
            partialSplitMs = m.partialSplitMs,
            points = kept,
            heartRate = record.heartRate.filter { it.time in fromMs..toMs },
            cadence = record.cadence.filter { it.time in fromMs..toMs },
            steps = steps,
        )
    }

    /**
     * Puts the route and numbers from [original] back, keeping everything else the athlete has
     * changed since (title, notes, photos, gear, sport).
     */
    fun restoreTrim(current: ActivityRecord, original: ActivityRecord): ActivityRecord = current.copy(
        startTimeMs = original.startTimeMs,
        distanceM = original.distanceM,
        movingMs = original.movingMs,
        elapsedMs = original.elapsedMs,
        elevationGainM = original.elevationGainM,
        splitsMs = original.splitsMs,
        partialSplitM = original.partialSplitM,
        partialSplitMs = original.partialSplitMs,
        points = original.points,
        heartRate = original.heartRate,
        cadence = original.cadence,
        steps = original.steps,
    )

    /**
     * Changes the sport. Distance and time stay as recorded. A title that was just the automatic
     * one ("Morning Run") follows the new sport; a name the athlete chose is left alone.
     */
    fun changeSport(record: ActivityRecord, sport: Sport): ActivityRecord {
        if (sport == record.sport) return record
        val wasAutomatic = record.title == RecordBuilder.defaultTitle(record.sport, record.startTimeMs)
        return record.copy(
            sport = sport,
            title = if (wasAutomatic) RecordBuilder.defaultTitle(sport, record.startTimeMs) else record.title,
        )
    }
}
