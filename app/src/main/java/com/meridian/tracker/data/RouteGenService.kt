package com.meridian.tracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the free OpenStreetMap routing service (routing.openstreetmap.de). Requests are spaced
 * about a second apart, as its usage policy asks, and carry only coordinates.
 */
object RouteGenService {

    private const val TIMEOUT_MS = 12_000
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val SPACING_MS = 1_100L

    suspend fun plan(request: RouteRequest): PlanResult {
        var first = true
        return RouteGen.plan(request) { waypoints ->
            if (!first) delay(SPACING_MS)
            first = false
            fetch(request.profile, waypoints)
        }
    }

    /** Up to four different routes, or the reason there are none. [onProgress] reports how many are found so far. */
    suspend fun planChoices(request: RouteRequest, onProgress: (Int) -> Unit = {}): RouteGen.Choices {
        var first = true
        return RouteGen.planChoices(request, { waypoints ->
            if (!first) delay(SPACING_MS)
            first = false
            fetch(request.profile, waypoints)
        }, onProgress)
    }

    private suspend fun fetch(profile: RouteProfile, waypoints: List<Pair<Double, Double>>): RouteAnswer = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(RouteGen.url(profile, waypoints)).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("User-Agent", "Meridian (personal fitness app)")
                // The service answers 400 with a JSON code for "no route", so read the body for that too.
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { it.readCapped() } ?: return@runCatching RouteAnswer.Unreachable
                RouteGen.parse(body) ?: if (code in 200..299) RouteAnswer.NoRoute else RouteAnswer.Unreachable
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(RouteAnswer.Unreachable)
    }

    private fun java.io.InputStream.readCapped(): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < MAX_BYTES) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
