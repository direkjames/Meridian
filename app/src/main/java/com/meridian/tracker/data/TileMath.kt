package com.meridian.tracker.data

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Web-Mercator tile counting, used to size offline map downloads before starting them. */
object TileMath {

    private fun tileX(lon: Double, z: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    private fun tileY(lat: Double, z: Int): Int {
        val clamped = lat.coerceIn(-85.0511, 85.0511)
        val rad = Math.toRadians(clamped)
        val y = (1.0 - ln(tan(rad) + 1.0 / kotlin.math.cos(rad)) / PI) / 2.0 * (1 shl z)
        return floor(y).toInt().coerceIn(0, (1 shl z) - 1)
    }

    fun tileCount(north: Double, south: Double, east: Double, west: Double, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val x0 = tileX(west, z)
            val x1 = tileX(east, z)
            val y0 = tileY(north, z)
            val y1 = tileY(south, z)
            total += (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
        }
        return total
    }

    /** Rough download size: dark vector tiles average around 25 KB each. */
    fun estimatedBytes(tiles: Long): Long = tiles * 25_000L
}
