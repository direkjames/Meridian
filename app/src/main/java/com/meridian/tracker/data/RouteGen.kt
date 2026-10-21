package com.meridian.tracker.data

import com.meridian.tracker.tracking.Geo
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class RouteShape(val label: String) { LOOP("Loop"), OUT_AND_BACK("Out & back") }

/** Which paths the routing service may use. [path] is the profile's address on the service. */
enum class RouteProfile(val label: String, val path: String, val maxDistanceM: Double) {
    FOOT("On foot", "routed-foot", 42_195.0),
    BIKE("By bike", "routed-bike", 100_000.0),
}

data class RouteRequest(
    val lat: Double,
    val lon: Double,
    val targetM: Double,
    val shape: RouteShape,
    val profile: RouteProfile,
    /** Direction to head in first, degrees clockwise from north. */
    val bearingDeg: Double,
)

/** What the routing service answered for one set of waypoints. */
sealed interface RouteAnswer {
    class Ok(val points: List<Pair<Double, Double>>, val distanceM: Double) : RouteAnswer
    /** The service understood but found no path between the waypoints. */
    data object NoRoute : RouteAnswer
    /** No answer at all: offline, timeout or a server error. */
    data object Unreachable : RouteAnswer
}

sealed interface PlanResult {
    /** [withinTolerance] is false when the closest route found is still more than 10% off the target. */
    class Success(val points: List<Pair<Double, Double>>, val distanceM: Double, val withinTolerance: Boolean) : PlanResult
    data object NoPath : PlanResult
    data object Offline : PlanResult
    data object Invalid : PlanResult
}

/**
 * Plans a route of about a given length from where you stand: a loop or an out-and-back. The path
 * itself comes from a routing service; this decides where to send it and adjusts the size until the
 * real distance is close to the target. No network in here, so it's unit-tested with a fake router.
 */
object RouteGen {

    const val TOLERANCE = 0.10
    const val MAX_REQUESTS = 5
    const val MIN_DISTANCE_M = 1_000.0
    private const val EARTH_RADIUS_M = 6_371_008.8
    /** Roads wander: a real route is roughly this much longer than the straight shape through its waypoints. */
    private const val DETOUR = 1.3
    /** The loop is a pentagon with you at one corner; its perimeter is this many radii. */
    private const val PENTAGON_PERIMETER_IN_RADII = 5.878
    private const val MAX_POINTS = 1_500

    fun distanceRange(profile: RouteProfile): ClosedFloatingPointRange<Double> = MIN_DISTANCE_M..profile.maxDistanceM

    fun valid(r: RouteRequest): Boolean =
        r.lat.isFinite() && r.lon.isFinite() && r.lat in -85.0..85.0 && r.lon in -180.0..180.0 &&
            r.targetM.isFinite() && r.targetM in distanceRange(r.profile) && r.bearingDeg.isFinite()

    /** The point [distanceM] away from ([lat], [lon]) heading [bearingDeg]. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val d = distanceM / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540.0) % 360.0 - 180.0)
    }

    /** Where the route has to pass, for the current size guess ([sizeM] is a radius for loops, a one-way length otherwise). */
    fun waypoints(r: RouteRequest, bearingDeg: Double, sizeM: Double): List<Pair<Double, Double>> {
        val start = r.lat to r.lon
        return when (r.shape) {
            RouteShape.OUT_AND_BACK -> listOf(start, destination(r.lat, r.lon, bearingDeg, sizeM))
            RouteShape.LOOP -> {
                val centre = destination(r.lat, r.lon, bearingDeg, sizeM)
                val back = bearingDeg + 180.0
                val corners = (1..4).map { k -> destination(centre.first, centre.second, back + 72.0 * k, sizeM) }
                listOf(start) + corners + listOf(start)
            }
        }
    }

    private fun firstGuess(r: RouteRequest): Double = when (r.shape) {
        RouteShape.OUT_AND_BACK -> r.targetM / 2 / DETOUR
        RouteShape.LOOP -> r.targetM / (PENTAGON_PERIMETER_IN_RADII * DETOUR)
    }

    /** What an answer adds up to for the athlete: out-and-back doubles the one-way path. */
    private fun totalM(r: RouteRequest, answer: RouteAnswer.Ok) =
        if (r.shape == RouteShape.OUT_AND_BACK) answer.distanceM * 2 else answer.distanceM

    private fun finalPoints(r: RouteRequest, answer: RouteAnswer.Ok): List<Pair<Double, Double>> {
        val path = thin(answer.points, if (r.shape == RouteShape.OUT_AND_BACK) MAX_POINTS / 2 else MAX_POINTS)
        return if (r.shape == RouteShape.OUT_AND_BACK) path + path.dropLast(1).reversed() else path
    }

    /**
     * Asks [router] for routes, resizing the shape each time, and returns the one closest to the
     * target. At most [MAX_REQUESTS] calls, since the service is free and shared.
     */
    suspend fun plan(r: RouteRequest, router: suspend (List<Pair<Double, Double>>) -> RouteAnswer): PlanResult {
        if (!valid(r)) return PlanResult.Invalid
        var bearing = r.bearingDeg
        var size = firstGuess(r)
        var best: RouteAnswer.Ok? = null
        var bestError = Double.MAX_VALUE
        repeat(MAX_REQUESTS) {
            when (val answer = router(waypoints(r, bearing, size))) {
                is RouteAnswer.Ok -> {
                    val total = totalM(r, answer)
                    if (total <= 0.0 || answer.points.size < 2) {
                        size *= 2
                    } else {
                        val error = abs(total - r.targetM) / r.targetM
                        if (error < bestError) { best = answer; bestError = error }
                        if (error <= TOLERANCE) return success(r, answer, true)
                        // Distance grows with size, so scale by how far off we were (not too wildly).
                        size *= (r.targetM / total).coerceIn(0.5, 2.0)
                    }
                }
                RouteAnswer.NoRoute -> bearing += 120.0 // try another direction
                RouteAnswer.Unreachable -> return PlanResult.Offline
            }
        }
        return best?.let { success(r, it, false) } ?: PlanResult.NoPath
    }

    private fun success(r: RouteRequest, answer: RouteAnswer.Ok, exact: Boolean): PlanResult {
        val points = finalPoints(r, answer)
        if (points.size < 2) return PlanResult.NoPath
        return PlanResult.Success(points, totalM(r, answer), exact)
    }

    /** At most [max] points, keeping the first and last and spreading the rest evenly. */
    fun thin(points: List<Pair<Double, Double>>, max: Int): List<Pair<Double, Double>> {
        if (points.size <= max || max < 2) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return (0 until max).map { points[Math.round(it * step).toInt().coerceAtMost(points.size - 1)] }
    }

    /**
     * Reads an OSRM route answer (`geometries=geojson`). Null when it is not a usable route.
     * `NoRoute`/`NoSegment` codes come back as [RouteAnswer.NoRoute].
     */
    fun parse(json: String): RouteAnswer? = runCatching {
        val root = JSONObject(json)
        when (root.optString("code")) {
            "Ok" -> {
                val route = root.getJSONArray("routes").getJSONObject(0)
                val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                val points = (0 until coords.length()).map { i ->
                    val c = coords.getJSONArray(i)
                    c.getDouble(1) to c.getDouble(0)
                }
                val distance = route.getDouble("distance")
                if (points.size < 2 || !distance.isFinite() || distance <= 0 ||
                    points.any { it.first !in -90.0..90.0 || it.second !in -180.0..180.0 }
                ) null else RouteAnswer.Ok(points, distance)
            }
            "NoRoute", "NoSegment" -> RouteAnswer.NoRoute
            else -> null
        }
    }.getOrNull()

    /** The request address for [waypoints] on the FOSSGIS OpenStreetMap routing service. */
    fun url(profile: RouteProfile, waypoints: List<Pair<Double, Double>>): String {
        val coords = waypoints.joinToString(";") { String.format(java.util.Locale.US, "%.5f,%.5f", it.second, it.first) }
        // The service picks the profile from the first path part; "driving" is just the API's word.
        return "https://routing.openstreetmap.de/${profile.path}/route/v1/driving/$coords?overview=full&geometries=geojson&steps=false&alternatives=false"
    }

    /** Straight-line length of a point list, in metres. */
    fun lengthM(points: List<Pair<Double, Double>>): Double =
        points.zipWithNext().sumOf { (a, b) -> Geo.distanceM(a.first, a.second, b.first, b.second) }
}
