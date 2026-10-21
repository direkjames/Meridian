package com.meridian.tracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up the weather for an activity from Open-Meteo (free, no account). Only the start
 * hour and a coordinate rounded to about 11 km are sent. Any failure just means no tag.
 */
object WeatherLookup {

    private const val TIMEOUT_MS = 8_000
    internal const val MAX_BYTES = 64 * 1024

    suspend fun lookup(record: ActivityRecord): Weather? {
        if (!WeatherTag.eligible(record, System.currentTimeMillis())) return null
        val start = record.points.first()
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(WeatherTag.url(start.lat, start.lon, record.startTimeMs)).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.setRequestProperty("User-Agent", "Meridian")
                    if (conn.responseCode != 200) return@runCatching null
                    val body = conn.inputStream.use { it.readBounded() }
                    WeatherTag.parse(body, record.startTimeMs)
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
    }
}

/** Reads at most 64 KB, so a misbehaving server can't fill memory. */
private fun java.io.InputStream.readBounded(): String {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (out.size() < WeatherLookup.MAX_BYTES) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}
