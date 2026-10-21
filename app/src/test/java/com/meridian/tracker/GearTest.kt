package com.meridian.tracker

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.Gear
import com.meridian.tracker.data.GearMath
import com.meridian.tracker.data.GearStatus
import com.meridian.tracker.data.GearType
import com.meridian.tracker.tracking.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GearTest {

    private fun activity(id: String, km: Double, gear: List<String>, sport: Sport = Sport.RUN, start: Long = 0L) =
        ActivityRecord(
            id = id, title = id, sport = sport, startTimeMs = start, distanceM = km * 1000,
            movingMs = 1, elapsedMs = 1, elevationGainM = 0.0, splitsMs = emptyList(),
            partialSplitM = 0.0, partialSplitMs = 0, points = emptyList(), gearIds = gear,
        )

    private val shoes = Gear("s", "Pegasus", GearType.SHOES, limitM = 700_000.0, startM = 50_000.0, createdMs = 0)

    @Test
    fun usageAddsTaggedActivitiesAndStartingDistance() {
        val acts = listOf(activity("a", 10.0, listOf("s")), activity("b", 5.0, listOf("x")), activity("c", 20.0, listOf("s", "x")))
        val u = GearMath.usage(shoes, acts)
        assertEquals(80_000.0, u.usedM, 1e-6)
        assertEquals(2, u.activities)
        assertEquals(620_000.0, u.remainingM!!, 1e-6)
        assertEquals(GearStatus.OK, u.status)
    }

    @Test
    fun unsavedActivityCountsOnceEvenIfAlsoSaved() {
        val draft = activity("d", 12.0, listOf("s"))
        assertEquals(62_000.0, GearMath.usage(shoes, listOf(draft), extra = draft).usedM, 1e-6)
        assertEquals(62_000.0, GearMath.usage(shoes, emptyList(), extra = draft).usedM, 1e-6)
        // Untagged draft adds nothing.
        assertEquals(50_000.0, GearMath.usage(shoes, emptyList(), extra = draft.copy(gearIds = emptyList())).usedM, 1e-6)
    }

    @Test
    fun statusWarnsNearTheLimitAndWhenWornOut() {
        val near = GearMath.usage(shoes, listOf(activity("a", 560.0, listOf("s")))) // 610 of 700
        assertEquals(GearStatus.NEARLY_DONE, near.status)
        val worn = GearMath.usage(shoes, listOf(activity("a", 660.0, listOf("s")))) // 710 of 700
        assertEquals(GearStatus.WORN_OUT, worn.status)
        assertEquals(0.0, worn.remainingM!!, 1e-6)
    }

    @Test
    fun noLimitMeansNoWarning() {
        val bike = Gear("b", "Bike", GearType.BIKE, limitM = null, createdMs = 0)
        val u = GearMath.usage(bike, listOf(activity("a", 5000.0, listOf("b"), Sport.RIDE)))
        assertNull(u.remainingM)
        assertNull(u.fraction)
        assertEquals(GearStatus.OK, u.status)
    }

    @Test
    fun defaultShoeLimitFollowsUnits() {
        assertEquals(700_000.0, GearMath.defaultLimitM(GearType.SHOES, imperial = false)!!, 1e-6)
        assertEquals(450 * 1609.344, GearMath.defaultLimitM(GearType.SHOES, imperial = true)!!, 1e-6)
        assertNull(GearMath.defaultLimitM(GearType.BIKE, imperial = false))
    }

    @Test
    fun suggestsGearFromLatestSameSportActivityAndSkipsRetired() {
        val retired = Gear("old", "Old", GearType.SHOES, 700_000.0, retired = true, createdMs = 0)
        val bike = Gear("b", "Bike", GearType.BIKE, null, createdMs = 0)
        val acts = listOf(
            activity("1", 5.0, listOf("old"), start = 1),
            activity("2", 5.0, listOf("s", "old"), start = 2),
            activity("3", 30.0, listOf("b"), Sport.RIDE, start = 3),
        )
        assertEquals(listOf("s"), GearMath.suggest(Sport.RUN, acts, listOf(shoes, retired, bike)))
        assertEquals(listOf("b"), GearMath.suggest(Sport.RIDE, acts, listOf(shoes, retired, bike)))
        assertTrue(GearMath.suggest(Sport.WALK, acts, listOf(shoes, retired, bike)).isEmpty())
        // The activity being edited isn't its own suggestion.
        assertTrue(GearMath.suggest(Sport.RIDE, acts, listOf(bike), excludeId = "3").isEmpty())
    }

    @Test
    fun parsesTypedDistancesSafely() {
        assertEquals(700_000.0, GearMath.parseDistance("700", imperial = false)!!, 1e-6)
        assertEquals(1_200_000.0, GearMath.parseDistance("1,200", imperial = false)!!, 1e-6)
        assertEquals(1609.344, GearMath.parseDistance("1", imperial = true)!!, 1e-6)
        assertNull(GearMath.parseDistance("abc", imperial = false))
        assertNull(GearMath.parseDistance("-5", imperial = false))
        assertNull(GearMath.parseDistance("NaN", imperial = false))
        assertNull(GearMath.parseDistance("1e9", imperial = false))
        assertEquals("Nike Pegasus 41", GearMath.cleanName("  Nike   Pegasus\n41 "))
        assertEquals(GearMath.MAX_NAME, GearMath.cleanName("x".repeat(500)).length)
    }
}
