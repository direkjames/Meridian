package com.meridian.tracker

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.Effort
import com.meridian.tracker.data.HeartRate
import com.meridian.tracker.data.Sample
import com.meridian.tracker.data.io.ParsedTrack
import com.meridian.tracker.data.io.RawPoint
import com.meridian.tracker.data.io.RecordBuilder
import com.meridian.tracker.data.io.TrackFormats
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.StepCounter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant

class HeartRateTest {

    private val t0 = Instant.parse("2026-10-14T22:30:00Z").toEpochMilli()

    private fun steady(bpm: Int, minutes: Int, everyMs: Long = 1_000L): List<Sample> =
        (0 until (minutes * 60_000L / everyMs).toInt()).map { Sample(t0 + it * everyMs, bpm) }

    // ---- Bluetooth packets

    @Test
    fun parsesEightAndSixteenBitPackets() {
        assertEquals(72, HeartRate.parseMeasurement(byteArrayOf(0x00, 72)))
        // Polar style: flags 0x16 (contact detected, RR present), 8-bit value 145, then RR bytes
        assertEquals(145, HeartRate.parseMeasurement(byteArrayOf(0x16, 145.toByte(), 0x10, 0x02)))
        // 16-bit little-endian: 0x00B4 = 180
        assertEquals(180, HeartRate.parseMeasurement(byteArrayOf(0x01, 0xB4.toByte(), 0x00)))
        assertNull(HeartRate.parseMeasurement(byteArrayOf(0x00, 0)))      // strap not on
        assertNull(HeartRate.parseMeasurement(byteArrayOf(0x01, 0x10)))  // truncated 16-bit
        assertNull(HeartRate.parseMeasurement(null))
    }

    @Test
    fun readsSkinContact() {
        assertTrue(HeartRate.skinContact(byteArrayOf(0x06, 70)))  // supported + detected
        assertFalse(HeartRate.skinContact(byteArrayOf(0x04, 70))) // supported, not detected
        assertTrue(HeartRate.skinContact(byteArrayOf(0x00, 70)))  // not supported: assume fine
    }

    // ---- Samples

    @Test
    fun cleanDropsGlitchesAndThins() {
        val raw = listOf(Sample(t0 + 3000, 150), Sample(t0, 140), Sample(t0 + 1000, 141), Sample(t0 + 2000, 255), Sample(t0 + 4000, 20))
        val clean = HeartRate.clean(raw)
        assertEquals(listOf(Sample(t0, 140), Sample(t0 + 3000, 150)), clean)
    }

    @Test
    fun averageIsTimeWeighted() {
        // 10 minutes at 120 sampled every 10 s, then 10 minutes at 160 sampled every second.
        val slow = steady(120, 10, 10_000L)
        val fast = (0 until 600).map { Sample(t0 + 600_000L + it * 1000L, 160) }
        val avg = HeartRate.average(slow + fast)!!
        assertEquals(140.0, avg.toDouble(), 2.0)
        assertNull(HeartRate.average(emptyList()))
    }

    @Test
    fun gapsDontCount() {
        val a = steady(130, 5)
        val b = (0 until 300).map { Sample(t0 + 3_600_000L + it * 1000L, 170) } // an hour later
        assertEquals(150.0, HeartRate.average(a + b)!!.toDouble(), 1.0)
        assertEquals(10 * 60_000.0 / (65 * 60_000.0), HeartRate.coverage(a + b, 65 * 60_000L), 0.01)
    }

    @Test
    fun zonesFollowMaxHeartRate() {
        assertEquals(1, HeartRate.zoneOf(100, 190))
        assertEquals(2, HeartRate.zoneOf(120, 190)) // 63%
        assertEquals(3, HeartRate.zoneOf(140, 190)) // 74%
        assertEquals(4, HeartRate.zoneOf(160, 190)) // 84%
        assertEquals(5, HeartRate.zoneOf(175, 190)) // 92%
        val zones = HeartRate.timeInZones(steady(140, 10) + steady(175, 5).map { it.copy(time = it.time + 600_000L) }, 190)
        assertEquals(10 * 60_000.0, zones[2].toDouble(), 2_000.0)
        assertEquals(5 * 60_000.0, zones[4].toDouble(), 2_000.0)
        assertEquals("114–132", HeartRate.zoneRange(HeartRate.ZONES[1], 190))
        assertEquals("171+", HeartRate.zoneRange(HeartRate.ZONES[4], 190))
    }

    @Test
    fun loadScalesWithIntensityAndTime() {
        val max = 190
        val threshold = (max * 0.88).toInt()
        val hourAtThreshold = HeartRate.load(steady(threshold, 60), 3_600_000L, max)!!
        assertEquals(100.0, hourAtThreshold, 3.0)
        val easy = HeartRate.load(steady(120, 60), 3_600_000L, max)!!
        assertTrue(easy < 40)
        val halfHour = HeartRate.load(steady(threshold, 30), 1_800_000L, max)!!
        assertEquals(hourAtThreshold / 2, halfHour, 2.0)
        // Too little data for the activity's length: no heart-rate effort.
        assertNull(HeartRate.load(steady(threshold, 10), 3_600_000L, max))
    }

    @Test
    fun effortPrefersHeartRate() {
        HeartRate.maxHr = 190
        val base = ActivityRecord("a", "Run", Sport.RUN, t0, 10_000.0, 3_600_000L, 3_600_000L, 0.0, emptyList(), 0.0, 0L, emptyList())
        val withHr = base.copy(heartRate = steady(167, 60, 2_000L))
        val paceLoad = Effort.load(base, mapOf(Sport.RUN to 3.0))
        val hrLoad = Effort.load(withHr, mapOf(Sport.RUN to 3.0))
        assertEquals(100.0, hrLoad, 4.0)
        assertTrue(paceLoad != hrLoad)
    }

    @Test
    fun seriesBucketsForCharts() {
        val s = HeartRate.series(steady(150, 10), t0, t0 + 600_000L, buckets = 10)
        assertEquals(10, s.size)
        assertTrue(s.all { it == 150.0 })
        assertTrue(HeartRate.series(emptyList(), t0, t0 + 1000).isEmpty())
    }

    @Test
    fun valueAtTimeForExport() {
        val samples = listOf(Sample(t0, 100), Sample(t0 + 10_000, 110), Sample(t0 + 20_000, 120))
        assertEquals(110, HeartRate.at(samples, t0 + 11_000))
        assertEquals(120, HeartRate.at(samples, t0 + 24_000))
        assertNull(HeartRate.at(samples, t0 + 60_000))
    }

    @Test
    fun highestRecordedIgnoresSpikes() {
        val r = ActivityRecord("a", "Run", Sport.RUN, t0, 1.0, 1L, 1L, 0.0, emptyList(), 0.0, 0L, emptyList(),
            heartRate = steady(150, 1) + Sample(t0 + 70_000, 230))
        assertEquals(150, HeartRate.highestRecorded(listOf(r)))
        assertEquals(170, HeartRate.suggestedMax(listOf(r)))
    }

    // ---- Files

    @Test
    fun gpxKeepsHeartRate() {
        val mLat = 1.0 / 111_195.0
        val pts = (0 until 300).map { i -> RawPoint(14.6 + i * 3.0 * mLat, 121.05, 20.0, t0 + i * 1000L, 140 + i % 20) }
        val r = RecordBuilder.build(ParsedTrack(pts, "Easy", Sport.RUN))!!
        assertEquals(150, r.heartRate.size) // one every 2 s
        val xml = TrackFormats.writeGpx(r)
        assertTrue(xml.contains("<gpxtpx:hr>"))
        val back = RecordBuilder.build(TrackFormats.parseGpx(ByteArrayInputStream(xml.toByteArray())))!!
        assertTrue(back.hasHeartRate)
        assertEquals(r.avgHeartRate!!.toDouble(), back.avgHeartRate!!.toDouble(), 2.0)
    }

    @Test
    fun tcxHeartRate() {
        val tcx = """<?xml version="1.0"?>
<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
 <Activities><Activity Sport="Running"><Lap><Track>
  <Trackpoint><Time>2026-10-14T22:30:00Z</Time><Position><LatitudeDegrees>14.6</LatitudeDegrees><LongitudeDegrees>121.05</LongitudeDegrees></Position><HeartRateBpm><Value>132</Value></HeartRateBpm></Trackpoint>
  <Trackpoint><Time>2026-10-14T22:30:05Z</Time><Position><LatitudeDegrees>14.6001</LatitudeDegrees><LongitudeDegrees>121.05</LongitudeDegrees></Position><HeartRateBpm><Value>136</Value></HeartRateBpm></Trackpoint>
 </Track></Lap></Activity></Activities>
</TrainingCenterDatabase>"""
        val parsed = TrackFormats.parseTcx(ByteArrayInputStream(tcx.toByteArray()))
        assertEquals(listOf(132, 136), parsed.points.map { it.hr })
    }

    // ---- Steps

    @Test
    fun countsStepsFromTheCounter() {
        val c = StepCounter()
        c.onCounter(10_000f, 0, counting = true)      // starting point only
        assertEquals(0, c.steps)
        c.onCounter(10_030f, 10_000, counting = true)
        c.onCounter(10_060f, 20_000, counting = true)
        assertEquals(60, c.steps)
        assertEquals(180, c.cadence(20_000))          // 60 steps in 20 s
        c.onCounter(10_090f, 30_000, counting = false) // paused: not counted
        assertEquals(60, c.steps)
        c.onCounter(50f, 40_000, counting = true)     // phone restarted: ignore the drop
        c.onCounter(80f, 50_000, counting = true)
        assertEquals(90, c.steps)
        assertEquals(0, c.cadence(70_000))            // standing still
    }

    @Test
    fun stepDetectorFallback() {
        val c = StepCounter()
        repeat(30) { c.onStep(it * 400L, counting = true) } // 150 spm
        assertEquals(30, c.steps)
        assertEquals(150.0, c.cadence(29 * 400L)!!.toDouble(), 6.0)
        c.onStep(20_000, counting = false)
        assertEquals(30, c.steps)
    }

    @Test
    fun cadenceNeedsAFewSeconds() {
        val c = StepCounter()
        assertNull(c.cadence(0))
        c.onCounter(0f, 0, true)
        c.onCounter(5f, 2_000, true)
        assertNull(c.cadence(2_000))
    }

    @Test
    fun recordDerivedStepStats() {
        val r = ActivityRecord("a", "Walk", Sport.WALK, t0, 3_000.0, 30 * 60_000L, 32 * 60_000L, 0.0, emptyList(), 0.0, 0L, emptyList(), steps = 3_900)
        assertEquals(130, r.avgCadence)
        assertEquals(0.77, r.strideM!!, 0.01)
        assertNull(r.copy(steps = null).avgCadence)
        assertArrayEquals(longArrayOf(0, 0, 0, 0, 0), HeartRate.timeInZones(emptyList()))
        assertNotNull(r)
    }
}
