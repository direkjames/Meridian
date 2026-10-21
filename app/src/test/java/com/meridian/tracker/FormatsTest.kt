package com.meridian.tracker

import com.meridian.tracker.data.io.FitParser
import com.meridian.tracker.data.io.ParsedTrack
import com.meridian.tracker.data.io.RawPoint
import com.meridian.tracker.data.io.RecordBuilder
import com.meridian.tracker.data.io.StravaCsv
import com.meridian.tracker.data.io.TrackFormats
import com.meridian.tracker.tracking.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

class FormatsTest {

    private val mLat = 1.0 / 111_195.0
    private val t0 = Instant.parse("2026-03-14T22:30:00Z").toEpochMilli()

    private fun straight(n: Int, speed: Double, ele: (Int) -> Double? = { null }) =
        (0 until n).map { i -> RawPoint(14.6 + i * speed * mLat, 121.05, ele(i), t0 + i * 1000L) }

    @Test
    fun buildsRecordWithSplitsClimbAndPauses() {
        val first = straight(700, 3.0) { 10.0 + it * 0.05 } // 2.1 km, +35 m
        // 10-minute pause, then 300 more seconds
        val second = (0 until 300).map { i ->
            RawPoint(14.6 + (2100 + i * 3.0) * mLat, 121.05, 45.0, t0 + 700_000L + 600_000L + i * 1000L)
        }
        val r = RecordBuilder.build(ParsedTrack(first + second, "Saturday long run", Sport.RUN))!!
        assertEquals("Saturday long run", r.title)
        assertEquals(3000.0, r.distanceM, 30.0)
        assertEquals(998_000.0, r.movingMs.toDouble(), 5_000.0) // pause not counted
        assertEquals(2, r.splitsMs.size) // 2.99 km: two full kilometres
        assertEquals(2, r.points.map { it.segment }.distinct().size)
        assertEquals(35.0, r.elevationGainM, 5.0)
        assertEquals("imp-$t0", r.id)
    }

    @Test
    fun tooShortTracksAreSkipped() {
        assertNull(RecordBuilder.build(ParsedTrack(straight(1, 3.0))))
        assertNull(RecordBuilder.build(ParsedTrack(straight(3, 1.0))))
    }

    @Test
    fun gpxRoundTrip() {
        val r = RecordBuilder.build(ParsedTrack(straight(400, 3.0) { 20.0 }, "Tempo & hills", Sport.RUN))!!
        val xml = TrackFormats.writeGpx(r)
        assertTrue(xml.contains("Tempo &amp; hills"))
        val parsed = TrackFormats.parseGpx(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(400, parsed.points.size)
        assertEquals(Sport.RUN, parsed.sport)
        assertEquals("Tempo & hills", parsed.name)
        val again = RecordBuilder.build(parsed)!!
        assertEquals(r.distanceM, again.distanceM, 2.0)
        assertEquals(r.startTimeMs, again.startTimeMs)
    }

    @Test
    fun parsesStravaStyleGpx() {
        val gpx = """<?xml version="1.0" encoding="UTF-8"?>
<gpx creator="StravaGPX" version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
 <metadata><time>2024-05-01T22:10:00Z</time></metadata>
 <trk><name>Morning Ride</name><type>cycling</type><trkseg>
  <trkpt lat="14.6000000" lon="121.0500000"><ele>12.4</ele><time>2024-05-01T22:10:00Z</time></trkpt>
  <trkpt lat="14.6010000" lon="121.0500000"><ele>12.8</ele><time>2024-05-01T22:10:20Z</time></trkpt>
  <trkpt lat="14.6020000" lon="121.0500000"><ele>13.1</ele><time>2024-05-01T22:10:40Z</time></trkpt>
 </trkseg></trk></gpx>"""
        val p = TrackFormats.parseGpx(ByteArrayInputStream(gpx.toByteArray()))
        assertEquals(3, p.points.size)
        assertEquals(Sport.RIDE, p.sport)
        assertEquals("Morning Ride", p.name)
        assertEquals(12.4, p.points[0].ele!!, 0.01)
    }

    @Test
    fun parsesTcx() {
        val tcx = """<?xml version="1.0" encoding="UTF-8"?>
<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
 <Activities><Activity Sport="Running"><Id>2024-05-01T22:10:00Z</Id><Lap><Track>
  <Trackpoint><Time>2024-05-01T22:10:00.000Z</Time><Position><LatitudeDegrees>14.6</LatitudeDegrees><LongitudeDegrees>121.05</LongitudeDegrees></Position><AltitudeMeters>10</AltitudeMeters></Trackpoint>
  <Trackpoint><Time>2024-05-01T22:10:05.000Z</Time></Trackpoint>
  <Trackpoint><Time>2024-05-01T22:10:10.000Z</Time><Position><LatitudeDegrees>14.6003</LatitudeDegrees><LongitudeDegrees>121.05</LongitudeDegrees></Position></Trackpoint>
 </Track></Lap></Activity></Activities></TrainingCenterDatabase>"""
        val p = TrackFormats.parseTcx(ByteArrayInputStream(tcx.toByteArray()))
        assertEquals(2, p.points.size) // the point without a position is skipped
        assertEquals(Sport.RUN, p.sport)
    }

    @Test
    fun parsesFit() {
        val bytes = SyntheticFit.build(
            sport = 2, // cycling
            points = (0 until 120).map { i -> Triple(14.6 + i * 6.0 * mLat, 121.05, 30.0 + i * 0.1) },
            startUnixS = t0 / 1000,
        )
        val p = FitParser.parse(ByteArrayInputStream(bytes))
        assertEquals(120, p.points.size)
        assertEquals(Sport.RIDE, p.sport)
        assertEquals(14.6, p.points[0].lat, 1e-6)
        assertEquals(121.05, p.points[0].lon, 1e-6)
        assertEquals(30.0, p.points[0].ele!!, 0.3)
        assertEquals(t0, p.points[0].timeMs)
        assertEquals(t0 + 119_000L, p.points.last().timeMs)
        val r = RecordBuilder.build(p)!!
        assertEquals(714.0, r.distanceM, 10.0)
    }

    @Test
    fun milesSetting() {
        com.meridian.tracker.ui.Format.imperial = true
        try {
            val f = com.meridian.tracker.ui.Format
            assertEquals("1.00", f.km(1609.344))
            assertEquals("8:03", f.pace(300.0)) // 5:00 /km is 8:03 /mi
            assertEquals("22.4", f.speedKmh(10.0))
            assertEquals("/mi", f.paceUnit)
        } finally {
            com.meridian.tracker.ui.Format.imperial = false
        }
    }

    @Test
    fun stravaCsvWithQuotedCommas() {
        val csv = "Activity ID,Activity Date,Activity Name,Activity Type,Activity Description,Filename\n" +
            "111,\"May 1, 2024, 6:10:00 AM\",\"Run, then coffee\",Run,\"Line one\nline two\",activities/111.gpx\n" +
            "222,\"May 2, 2024, 6:10:00 AM\",Commute,Ride,,activities/222.fit.gz\n" +
            "333,\"May 3, 2024, 6:10:00 AM\",Yoga,Yoga,,\n"
        val map = StravaCsv.parse(csv)
        assertEquals(2, map.size)
        assertEquals("Run, then coffee", map["activities/111.gpx"]!!.name)
        assertEquals("Ride", map["activities/222.fit.gz"]!!.type)
        assertEquals(Sport.RIDE, RecordBuilder.sportFrom("E-Bike Ride"))
        assertEquals(Sport.HIKE, RecordBuilder.sportFrom("Hike"))
        assertEquals(Sport.WALK, RecordBuilder.sportFrom("Walking"))
        assertNull(RecordBuilder.sportFrom("Yoga"))
        assertEquals(Sport.TRAIL_RUN, RecordBuilder.sportFrom("Trail Run"))
        assertEquals(Sport.RUN, RecordBuilder.sportFrom("Running"))
    }
}

/** Writes a minimal valid FIT file: sport message, then record messages. */
object SyntheticFit {
    private const val FIT_EPOCH_S = 631_065_600L

    fun build(sport: Int, points: List<Triple<Double, Double, Double>>, startUnixS: Long): ByteArray {
        val body = ByteArrayOutputStream()
        fun u8(v: Int) = body.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }

        // Definition: local 0 = sport (global 12), field 0 sport enum(1 byte)
        u8(0x40); u8(0); u8(0); u16(12); u8(1); u8(0); u8(1); u8(0x00)
        u8(0x00); u8(sport)

        // Definition: local 1 = record (global 20): timestamp, lat, lon, enhanced_altitude
        u8(0x41); u8(0); u8(0); u16(20); u8(4)
        u8(253); u8(4); u8(0x86)
        u8(0); u8(4); u8(0x85)
        u8(1); u8(4); u8(0x85)
        u8(78); u8(4); u8(0x86)
        points.forEachIndexed { i, (lat, lon, alt) ->
            u8(0x01)
            u32(startUnixS - FIT_EPOCH_S + i)
            u32((lat / (180.0 / 2147483648.0)).toLong() and 0xFFFFFFFFL)
            u32((lon / (180.0 / 2147483648.0)).toLong() and 0xFFFFFFFFL)
            u32(((alt + 500.0) * 5).toLong())
        }

        val data = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(14) // header size
        out.write(0x10) // protocol
        out.write(0x08); out.write(0x08) // profile
        for (i in 0 until 4) out.write((data.size shr (8 * i)) and 0xFF)
        out.write(".FIT".toByteArray())
        out.write(0); out.write(0) // header CRC (unchecked)
        out.write(data)
        out.write(0); out.write(0) // file CRC (unchecked)
        return out.toByteArray()
    }
}
