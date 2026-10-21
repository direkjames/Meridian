package com.meridian.tracker

import com.meridian.tracker.data.PlanResult
import com.meridian.tracker.data.RouteAnswer
import com.meridian.tracker.data.RouteGen
import com.meridian.tracker.data.RouteProfile
import com.meridian.tracker.data.RouteRequest
import com.meridian.tracker.data.RouteShape
import com.meridian.tracker.tracking.Geo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGenTest {

    private fun request(km: Double = 10.0, shape: RouteShape = RouteShape.LOOP, profile: RouteProfile = RouteProfile.FOOT) =
        RouteRequest(14.6, 121.05, km * 1000, shape, profile, bearingDeg = 40.0)

    /** A pretend routing service: roads are 30% longer than straight lines through the waypoints. */
    private val windyRoads: suspend (List<Pair<Double, Double>>) -> RouteAnswer = { wp -> RouteAnswer.Ok(wp, RouteGen.lengthM(wp) * 1.3) }

    @Test fun destinationIsTheRightDistanceAwayInTheRightDirection() {
        val (lat, lon) = RouteGen.destination(14.6, 121.05, 90.0, 5000.0)
        assertEquals(5000.0, Geo.distanceM(14.6, 121.05, lat, lon), 5.0)
        assertEquals(14.6, lat, 0.001) // due east keeps the latitude
        assertTrue(lon > 121.05)
        val north = RouteGen.destination(14.6, 121.05, 0.0, 5000.0)
        assertTrue(north.first > 14.6)
    }

    @Test fun loopWaypointsStartAndEndAtYouAndSurroundACentre() {
        val r = request()
        val wp = RouteGen.waypoints(r, 40.0, 1500.0)
        assertEquals(6, wp.size)
        assertEquals(wp.first(), wp.last())
        assertEquals(r.lat to r.lon, wp.first())
        val centre = RouteGen.destination(r.lat, r.lon, 40.0, 1500.0)
        wp.take(5).forEach { assertEquals(1500.0, Geo.distanceM(centre.first, centre.second, it.first, it.second), 5.0) }
    }

    @Test fun loopLandsWithinTenPercentOfTheTarget() = runBlocking {
        for (km in listOf(3.0, 5.0, 10.0, 21.0)) {
            val result = RouteGen.plan(request(km), windyRoads) as PlanResult.Success
            assertTrue("$km km", result.withinTolerance)
            assertEquals(km * 1000, result.distanceM, km * 1000 * RouteGen.TOLERANCE)
            assertEquals(result.points.first(), result.points.last()) // it is a loop
        }
    }

    @Test fun outAndBackIsMirroredAndCountsBothWays() = runBlocking {
        val result = RouteGen.plan(request(8.0, RouteShape.OUT_AND_BACK), windyRoads) as PlanResult.Success
        assertTrue(result.withinTolerance)
        assertEquals(8000.0, result.distanceM, 800.0)
        assertEquals(result.points.first(), result.points.last())
        val n = result.points.size
        assertEquals(result.points[1], result.points[n - 2])
    }

    @Test fun keepsTheClosestRouteWhenNothingIsWithinTolerance() = runBlocking {
        var calls = 0
        // A service that always answers 20 km, whatever is asked.
        val stubborn: suspend (List<Pair<Double, Double>>) -> RouteAnswer = { wp -> calls++; RouteAnswer.Ok(wp, 20_000.0) }
        val result = RouteGen.plan(request(10.0), stubborn) as PlanResult.Success
        assertFalse(result.withinTolerance)
        assertEquals(20_000.0, result.distanceM, 1.0)
        assertEquals(RouteGen.MAX_REQUESTS, calls)
    }

    @Test fun triesAnotherDirectionWhenThereIsNoPath() = runBlocking {
        val seen = ArrayList<List<Pair<Double, Double>>>()
        val router: suspend (List<Pair<Double, Double>>) -> RouteAnswer = { wp ->
            seen += wp
            if (seen.size == 1) RouteAnswer.NoRoute else RouteAnswer.Ok(wp, RouteGen.lengthM(wp) * 1.3)
        }
        val result = RouteGen.plan(request(), router)
        assertTrue(result is PlanResult.Success)
        assertTrue(seen[0][1] != seen[1][1]) // a different set of waypoints
    }

    @Test fun reportsNoPathAndOffline() = runBlocking {
        assertTrue(RouteGen.plan(request()) { RouteAnswer.NoRoute } is PlanResult.NoPath)
        var calls = 0
        assertTrue(RouteGen.plan(request()) { calls++; RouteAnswer.Unreachable } is PlanResult.Offline)
        assertEquals(1, calls) // gives up straight away rather than hammering the service
    }

    @Test fun rejectsRequestsOutsideTheLimits() = runBlocking {
        var called = false
        val spy: suspend (List<Pair<Double, Double>>) -> RouteAnswer = { called = true; RouteAnswer.NoRoute }
        assertTrue(RouteGen.plan(request(0.2), spy) is PlanResult.Invalid)
        assertTrue(RouteGen.plan(request(60.0), spy) is PlanResult.Invalid) // too far on foot
        assertTrue(RouteGen.plan(request(60.0, profile = RouteProfile.BIKE), windyRoads) is PlanResult.Success) // fine by bike
        assertTrue(RouteGen.plan(request().copy(lat = 91.0), spy) is PlanResult.Invalid)
        assertTrue(RouteGen.plan(request().copy(lat = Double.NaN), spy) is PlanResult.Invalid)
        assertFalse(called)
    }

    @Test fun readsAnOsrmAnswer() {
        val json = """{"code":"Ok","routes":[{"distance":1523.4,"duration":900,
            "geometry":{"type":"LineString","coordinates":[[121.05,14.6],[121.051,14.601],[121.052,14.602]]}}],"waypoints":[]}"""
        val ok = RouteGen.parse(json) as RouteAnswer.Ok
        assertEquals(1523.4, ok.distanceM, 1e-9)
        assertEquals(3, ok.points.size)
        assertEquals(14.6 to 121.05, ok.points.first()) // latitude first, though the service sends longitude first
    }

    @Test fun readsErrorsAndRejectsJunk() {
        assertTrue(RouteGen.parse("""{"code":"NoRoute","message":"x"}""") is RouteAnswer.NoRoute)
        assertTrue(RouteGen.parse("""{"code":"NoSegment"}""") is RouteAnswer.NoRoute)
        assertNull(RouteGen.parse("""{"code":"InvalidQuery"}"""))
        assertNull(RouteGen.parse("<html>429</html>"))
        assertNull(RouteGen.parse("""{"code":"Ok","routes":[]}"""))
        assertNull(RouteGen.parse("""{"code":"Ok","routes":[{"distance":0,"geometry":{"coordinates":[[1,2],[3,4]]}}]}"""))
        assertNull(RouteGen.parse("""{"code":"Ok","routes":[{"distance":10,"geometry":{"coordinates":[[500,2],[3,4]]}}]}"""))
    }

    @Test fun buildsTheRequestAddress() {
        val url = RouteGen.url(RouteProfile.BIKE, listOf(14.6 to 121.05, 14.61 to 121.06))
        assertTrue(url.startsWith("https://routing.openstreetmap.de/routed-bike/route/v1/driving/121.05000,14.60000;121.06000,14.61000?"))
        assertTrue(url.contains("geometries=geojson"))
        assertTrue(RouteGen.url(RouteProfile.FOOT, listOf(1.0 to 2.0, 3.0 to 4.0)).contains("/routed-foot/"))
    }

    @Test fun thinningKeepsTheEndsAndTheOrder() {
        val pts = (0..9999).map { it.toDouble() to 0.0 }
        val thin = RouteGen.thin(pts, 100)
        assertEquals(100, thin.size)
        assertEquals(pts.first(), thin.first())
        assertEquals(pts.last(), thin.last())
        assertEquals(thin, thin.sortedBy { it.first })
        assertNotNull(RouteGen.thin(pts.take(5), 100))
        assertEquals(5, RouteGen.thin(pts.take(5), 100).size)
    }

    @Test fun offersFourDifferentRoutesInDifferentDirections() = runBlocking {
        val progress = ArrayList<Int>()
        val out = RouteGen.planChoices(request(8.0), windyRoads) { progress += it }
        assertNull(out.failure)
        assertEquals(RouteGen.WANTED_CHOICES, out.choices.size)
        assertEquals(listOf(1, 2, 3, 4), progress)
        out.choices.forEach {
            assertEquals(8000.0, it.distanceM, 8000.0 * RouteGen.TOLERANCE)
            assertEquals(it.points.first(), it.points.last())
        }
        // Headings are all different, and no two routes are the same.
        assertEquals(4, out.choices.map { it.bearingDeg }.toSet().size)
        for (i in out.choices.indices) for (j in i + 1 until out.choices.size) {
            assertFalse(RouteGen.sameRoute(out.choices[i].points, out.choices[j].points))
        }
    }

    @Test fun choicesAreSortedByHowCloseTheyAreToTheTarget() = runBlocking {
        val out = RouteGen.planChoices(request(8.0), windyRoads)
        val errors = out.choices.map { kotlin.math.abs(it.distanceM - 8000.0) }
        assertEquals(errors.sorted(), errors)
    }

    @Test fun neverAsksTheServiceForMoreThanTheCap() = runBlocking {
        var calls = 0
        // Always wrong, so every direction burns all its tries.
        val stubborn: suspend (List<Pair<Double, Double>>) -> RouteAnswer = { wp -> calls++; RouteAnswer.Ok(wp, 30_000.0) }
        val out = RouteGen.planChoices(request(10.0), stubborn)
        assertTrue(calls <= RouteGen.MAX_CHOICE_REQUESTS)
        assertEquals(1, out.choices.size) // only the closest of the misses is offered
        assertNull(out.failure)
    }

    @Test fun dropsRoutesThatAreTheSameRoadsAgain() = runBlocking {
        // The service ignores where we point and always sends the same loop.
        val fixed = windyRoads(RouteGen.waypoints(request(8.0), 40.0, 1000.0))
        var calls = 0
        val sameEveryTime: suspend (List<Pair<Double, Double>>) -> RouteAnswer = { calls++; RouteAnswer.Ok((fixed as RouteAnswer.Ok).points, 8000.0) }
        val out = RouteGen.planChoices(request(8.0), sameEveryTime)
        assertEquals(1, out.choices.size)
        assertTrue(calls <= RouteGen.MAX_CHOICE_REQUESTS)
    }

    @Test fun keepsTheRoutesItHasWhenTheConnectionDropsPartWay() = runBlocking {
        var calls = 0
        val dropsAfterTwo: suspend (List<Pair<Double, Double>>) -> RouteAnswer = { wp ->
            if (++calls > 2) RouteAnswer.Unreachable else RouteAnswer.Ok(wp, RouteGen.lengthM(wp) * 1.3)
        }
        val out = RouteGen.planChoices(request(8.0), dropsAfterTwo)
        assertTrue(out.choices.isNotEmpty())
        assertNull(out.failure)
    }

    @Test fun reportsOfflineWhenNothingCameBack() = runBlocking {
        val out = RouteGen.planChoices(request(), { RouteAnswer.Unreachable })
        assertTrue(out.choices.isEmpty())
        assertTrue(out.failure is PlanResult.Offline)
    }

    @Test fun reportsNoPathAndRejectsBadRequests() = runBlocking {
        val none = RouteGen.planChoices(request(), { RouteAnswer.NoRoute })
        assertTrue(none.choices.isEmpty())
        assertTrue(none.failure is PlanResult.NoPath)
        assertTrue(RouteGen.planChoices(request(0.1), windyRoads).failure is PlanResult.Invalid)
    }

    @Test fun recognisesTheSameRouteAndDifferentOnes() {
        val a = (0..100).map { 14.6 + it * 0.0001 to 121.05 }
        val shifted = a.map { it.first to it.second + 0.0001 } // ~10 m sideways
        val far = a.map { it.first to it.second + 0.01 } // ~1 km away
        assertTrue(RouteGen.sameRoute(a, shifted))
        assertFalse(RouteGen.sameRoute(a, far))
        assertFalse(RouteGen.sameRoute(emptyList(), a))
    }
}
