package com.meridian.tracker.data

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Conditions when an activity started. [code] is a WMO weather code. */
data class Weather(val code: Int, val tempC: Double, val windKmh: Double)

/**
 * The weather tag: building the request, reading the answer and wording it. No network here,
 * so it's unit-tested; [WeatherLookup] does the actual call.
 */
object WeatherTag {

    /** Open-Meteo keeps about this many days of past weather available on the forecast endpoint. */
    const val MAX_AGE_DAYS = 90
    private const val HOUR_MS = 3_600_000L
    private val hourFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.US).withZone(ZoneOffset.UTC)
    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(ZoneOffset.UTC)

    /** About 11 km of precision: enough for the weather, not enough to say where you live. */
    fun roundCoordinate(value: Double): Double = Math.round(value * 10.0) / 10.0

    /** The hour (UTC, ISO text) nearest to [startMs], the way the service labels its hourly values. */
    fun hourKey(startMs: Long): String {
        val t = startMs + HOUR_MS / 2
        return hourFormat.format(Instant.ofEpochMilli(t - t.mod(HOUR_MS)))
    }

    fun url(lat: Double, lon: Double, startMs: Long): String {
        val day = dayFormat.format(Instant.ofEpochMilli(startMs))
        return String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.1f&longitude=%.1f" +
                "&hourly=temperature_2m,weather_code,wind_speed_10m&timezone=GMT&start_date=%s&end_date=%s",
            roundCoordinate(lat), roundCoordinate(lon), day, day,
        )
    }

    /** Only recorded-by-Meridian activities with a route, recent enough, and not tagged yet. */
    fun eligible(r: ActivityRecord, nowMs: Long): Boolean =
        r.weather == null && r.points.isNotEmpty() && r.source == null &&
            r.startTimeMs in (nowMs - MAX_AGE_DAYS * 24 * HOUR_MS)..nowMs

    /** The weather for the hour [startMs] falls in, or null for an answer that isn't usable. */
    fun parse(json: String, startMs: Long): Weather? = runCatching {
        val hourly = JSONObject(json).getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val key = hourKey(startMs)
        val i = (0 until times.length()).firstOrNull { times.getString(it) == key } ?: return null
        // JSON null (no data for that hour) must not turn into 0.
        if (hourly.getJSONArray("temperature_2m").isNull(i) || hourly.getJSONArray("weather_code").isNull(i)) return null
        val temp = hourly.getJSONArray("temperature_2m").getDouble(i)
        val code = hourly.getJSONArray("weather_code").getInt(i)
        val wind = hourly.optJSONArray("wind_speed_10m")?.takeIf { !it.isNull(i) }?.getDouble(i) ?: 0.0
        Weather(code, temp, wind).takeIf { valid(it) }
    }.getOrNull()

    fun valid(w: Weather): Boolean = w.code in 0..99 && w.tempC in -90.0..60.0 && w.windKmh in 0.0..400.0

    /** WMO weather code in plain words. */
    fun condition(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> if (code == 65 || code == 67) "Heavy rain" else "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Rain showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"
        else -> "Unknown"
    }

    /** "Partly cloudy · 24°C · wind 12 km/h", in the athlete's units. */
    fun describe(w: Weather, imperial: Boolean): String {
        val temp = if (imperial) "${(w.tempC * 9 / 5 + 32).roundToInt()}°F" else "${w.tempC.roundToInt()}°C"
        val wind = if (imperial) "${(w.windKmh / 1.609344).roundToInt()} mph" else "${w.windKmh.roundToInt()} km/h"
        return "${condition(w.code)} · $temp" + if (w.windKmh >= 5) " · wind $wind" else ""
    }
}
