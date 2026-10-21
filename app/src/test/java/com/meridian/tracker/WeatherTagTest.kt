package com.meridian.tracker

import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.Weather
import com.meridian.tracker.data.WeatherTag
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WeatherTagTest {

    // 2026-10-21 00:40 UTC
    private val start = Instant.parse("2026-10-21T00:40:00Z").toEpochMilli()

    private fun record(weather: Weather? = null, source: String? = null, startMs: Long = start, withPoints: Boolean = true) = ActivityRecord(
        id = "w", title = "t", sport = Sport.RUN, startTimeMs = startMs, distanceM = 5000.0, movingMs = 1_800_000, elapsedMs = 1_800_000,
        elevationGainM = 0.0, splitsMs = emptyList(), partialSplitM = 0.0, partialSplitMs = 0,
        points = if (withPoints) listOf(TrackPoint(14.63, 121.07, null, startMs, 0)) else emptyList(),
        source = source, weather = weather,
    )

    private fun response(temp: String = "26.4", code: String = "2", wind: String = "13.2") = """
        {"hourly":{"time":["2026-10-21T00:00","2026-10-21T01:00","2026-10-21T02:00"],
         "temperature_2m":[25.0,$temp,27.0],"weather_code":[1,$code,3],"wind_speed_10m":[10.0,$wind,9.0]}}
    """.trimIndent()

    @Test fun coordinatesAreRoundedToAboutTenKilometres() {
        assertEquals(14.6, WeatherTag.roundCoordinate(14.6321), 1e-9)
        assertEquals(-122.4, WeatherTag.roundCoordinate(-122.4194), 1e-9)
        val url = WeatherTag.url(14.6321, 121.0743, start)
        assertTrue(url.contains("latitude=14.6&longitude=121.1"))
        assertTrue(url.contains("start_date=2026-10-21&end_date=2026-10-21"))
    }

    @Test fun theNearestHourIsUsed() {
        assertEquals("2026-10-21T01:00", WeatherTag.hourKey(start)) // 00:40 is closer to 01:00
        assertEquals("2026-10-21T00:00", WeatherTag.hourKey(Instant.parse("2026-10-21T00:29:00Z").toEpochMilli()))
        assertEquals("2026-10-22T00:00", WeatherTag.hourKey(Instant.parse("2026-10-21T23:45:00Z").toEpochMilli()))
    }

    @Test fun parsesTheMatchingHour() {
        val w = WeatherTag.parse(response(), start)!!
        assertEquals(2, w.code)
        assertEquals(26.4, w.tempC, 1e-9)
        assertEquals(13.2, w.windKmh, 1e-9)
    }

    @Test fun unusableAnswersGiveNoTag() {
        assertNull(WeatherTag.parse("not json", start))
        assertNull(WeatherTag.parse("{}", start))
        assertNull(WeatherTag.parse(response(), start + 3 * 3_600_000L)) // hour not in the answer
        assertNull(WeatherTag.parse(response(temp = "null"), start))       // no data is not 0 degrees
        assertNull(WeatherTag.parse(response(temp = "500"), start))        // absurd value
        assertNotNull(WeatherTag.parse(response(wind = "null"), start))    // wind is optional
    }

    @Test fun onlyRecentRecordedRoutesWithoutATagAreEligible() {
        val now = start + 3_600_000L
        assertTrue(WeatherTag.eligible(record(), now))
        assertFalse(WeatherTag.eligible(record(weather = Weather(1, 20.0, 5.0)), now))
        assertFalse(WeatherTag.eligible(record(source = "Fitbit"), now))
        assertFalse(WeatherTag.eligible(record(withPoints = false), now))
        assertFalse(WeatherTag.eligible(record(), start + 91L * 24 * 3_600_000L)) // too old
        assertFalse(WeatherTag.eligible(record(), start - 1)) // starts in the future
    }

    @Test fun wordingFollowsTheUnits() {
        val w = Weather(2, 26.4, 13.2)
        assertEquals("Partly cloudy · 26°C · wind 13 km/h", WeatherTag.describe(w, imperial = false))
        assertEquals("Partly cloudy · 80°F · wind 8 mph", WeatherTag.describe(w, imperial = true))
        assertEquals("Clear · 20°C", WeatherTag.describe(Weather(0, 20.0, 2.0), false)) // calm: no wind part
        assertEquals("Heavy rain", WeatherTag.condition(65))
        assertEquals("Thunderstorm", WeatherTag.condition(96))
        assertEquals("Unknown", WeatherTag.condition(42))
    }
}
