package com.meridian.tracker

import com.meridian.tracker.data.ActivityDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDetailsTest {

    @Test fun effortOutsideOneToTenIsDropped() {
        assertNull(ActivityDetails.cleanEffort(0))
        assertNull(ActivityDetails.cleanEffort(11))
        assertNull(ActivityDetails.cleanEffort(-3))
        assertNull(ActivityDetails.cleanEffort(null))
        assertEquals(1, ActivityDetails.cleanEffort(1))
        assertEquals(10, ActivityDetails.cleanEffort(10))
    }

    @Test fun notesAreTrimmedAndCapped() {
        assertEquals("hello", ActivityDetails.cleanNote("  hello \n"))
        assertEquals(ActivityDetails.MAX_NOTE, ActivityDetails.cleanNote("x".repeat(2_000)).length)
        assertEquals("", ActivityDetails.cleanNote("   "))
    }

    @Test fun everyRatingHasAWord() {
        (1..10).forEach { assertTrue(ActivityDetails.effortWord(it).isNotBlank()) }
        assertEquals("All out", ActivityDetails.effortWord(10))
        assertEquals("Very easy", ActivityDetails.effortWord(1))
    }
}
