package com.meridian.tracker.data.io

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.HeartRate
import com.meridian.tracker.data.Sample
import com.meridian.tracker.tracking.ElevationTracker
import com.meridian.tracker.tracking.Geo
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint
import java.util.Calendar

/** A point read from a GPX, TCX or FIT file. [timeMs] is wall-clock UTC milliseconds. */
data class RawPoint(val lat: Double, val lon: Double, val ele: Double?, val timeMs: Long, val hr: Int? = null)

/** What a file reader returns before it becomes an activity. */
data class ParsedTrack(
    val points: List<RawPoint>,
    val name: String? = null,
    val sport: Sport? = null,
)

/**
 * Turns an imported track into a Meridian activity with the same stats a live recording gets:
 * distance, moving time, splits and climb. Gaps over two minutes count as pauses.
 * Pure Kotlin, unit-tested.
 */
object RecordBuilder {

    private const val PAUSE_GAP_MS = 120_000L

    fun build(track: ParsedTrack, fallbackSport: Sport = Sport.RUN, nameOverride: String? = null): ActivityRecord? {
        val pts = track.points
            .filter { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && !(it.lat == 0.0 && it.lon == 0.0) }
            .sortedBy { it.timeMs }
        if (pts.size < 2) return null
        val sport = track.sport ?: fallbackSport

        val out = ArrayList<TrackPoint>(pts.size)
        var segment = 0
        var distance = 0.0
        var moving = 0L
        val splits = ArrayList<Long>()
        var lastSplitMoving = 0L
        val elevation = ElevationTracker(alpha = 0.5, thresholdM = 3.0)

        pts.forEachIndexed { i, p ->
            if (i > 0) {
                val a = pts[i - 1]
                val dt = p.timeMs - a.timeMs
                if (dt > PAUSE_GAP_MS) {
                    segment++
                } else if (dt > 0) {
                    val d = Geo.distanceM(a.lat, a.lon, p.lat, p.lon)
                    val speed = d / (dt / 1000.0)
                    if (speed <= sport.maxSpeed * 1.5) {
                        val prevDistance = distance
                        val prevMoving = moving
                        val addedMoving = if (speed >= sport.movingSpeed * 0.5) dt else 0L
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
            p.ele?.let { elevation.onAltitude(it, counting = true) }
            out.add(TrackPoint(p.lat, p.lon, p.ele, p.timeMs, segment))
        }
        if (distance < 10.0) return null

        val start = pts.first().timeMs
        return ActivityRecord(
            id = "imp-$start",
            title = nameOverride?.takeIf { it.isNotBlank() } ?: track.name?.takeIf { it.isNotBlank() } ?: defaultTitle(sport, start),
            sport = sport,
            startTimeMs = start,
            distanceM = distance,
            movingMs = moving,
            elapsedMs = pts.last().timeMs - start,
            elevationGainM = elevation.gainM,
            splitsMs = splits,
            partialSplitM = distance - splits.size * 1000.0,
            partialSplitMs = moving - lastSplitMoving,
            points = out,
            heartRate = HeartRate.clean(pts.mapNotNull { p -> p.hr?.let { Sample(p.timeMs, it) } }),
        )
    }

    fun defaultTitle(sport: Sport, wallMs: Long): String {
        val hour = Calendar.getInstance().apply { timeInMillis = wallMs }.get(Calendar.HOUR_OF_DAY)
        val part = when (hour) {
            in 5..10 -> "Morning"
            in 11..13 -> "Lunch"
            in 14..17 -> "Afternoon"
            in 18..21 -> "Evening"
            else -> "Night"
        }
        return "$part ${sport.label}"
    }

    /** Maps sport names used by Strava, GPX `<type>` and TCX `Sport` to Meridian sports. */
    fun sportFrom(text: String?): Sport? {
        val t = text?.trim()?.lowercase() ?: return null
        return when {
            t.isEmpty() -> null
            // Strava GPX type codes
            t == "9" -> Sport.RUN
            t == "1" -> Sport.RIDE
            t == "10" || t == "4" -> Sport.WALK
            "run" in t || "jog" in t -> Sport.RUN
            "ride" in t || "bik" in t || "cycl" in t || "bicycl" in t -> Sport.RIDE
            "walk" in t || "hik" in t || "trek" in t -> Sport.WALK
            else -> null
        }
    }
}
