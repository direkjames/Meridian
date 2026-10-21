package com.meridian.tracker.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One heart-rate zone, as a share of your max heart rate. */
data class HrZone(val number: Int, val name: String, val fromPct: Int, val toPct: Int?)

/**
 * Heart-rate maths: Bluetooth packet parsing, averages, zones, training load and chart series.
 * Pure Kotlin, unit-tested.
 */
object HeartRate {

    /** Set from Settings › Watches & sensors. Used for zones and heart-rate Relative Effort. */
    @Volatile
    var maxHr: Int = DEFAULT_MAX

    const val DEFAULT_MAX = 190
    const val REST_HR = 60
    /** Readings outside this range are sensor glitches (strap not touching skin, etc.). */
    val VALID = 30..240
    /** Gaps longer than this aren't counted (strap dropped out, watch lost contact). */
    private const val MAX_GAP_MS = 30_000L

    val ZONES = listOf(
        HrZone(1, "Recovery", 0, 60),
        HrZone(2, "Endurance", 60, 70),
        HrZone(3, "Tempo", 70, 80),
        HrZone(4, "Threshold", 80, 90),
        HrZone(5, "Max", 90, null),
    )

    // ---- Bluetooth -----------------------------------------------------------------------------

    /**
     * Reads a Bluetooth "Heart Rate Measurement" (0x2A37) packet. The first byte holds flags;
     * bit 0 says whether the value is 8-bit or 16-bit (little-endian). Returns null for a bad packet.
     */
    fun parseMeasurement(bytes: ByteArray?): Int? {
        if (bytes == null || bytes.size < 2) return null
        val flags = bytes[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 != 0) {
            if (bytes.size < 3) return null
            (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        } else {
            bytes[1].toInt() and 0xFF
        }
        return bpm.takeIf { it in VALID }
    }

    /** False when the sensor reports that it isn't touching skin (flags bits 1–2 = 0b10). */
    fun skinContact(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty()) return true
        val status = (bytes[0].toInt() shr 1) and 0x03
        return status != 0x02
    }

    // ---- Samples -------------------------------------------------------------------------------

    /** Drops glitches and keeps at most one reading every [minGapMs]. Input is sorted by time first. */
    fun clean(samples: List<Sample>, minGapMs: Long = 2_000L): List<Sample> {
        val out = ArrayList<Sample>()
        for (s in samples.sortedBy { it.time }) {
            if (s.value !in VALID) continue
            val last = out.lastOrNull()
            if (last == null || s.time - last.time >= minGapMs) out.add(s)
        }
        return out
    }

    /** Time-weighted average, so a strap that sends more often doesn't skew it. */
    fun average(samples: List<Sample>): Int? {
        if (samples.isEmpty()) return null
        if (samples.size == 1) return samples[0].value
        var weighted = 0.0
        var total = 0.0
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = (b.time - a.time).toDouble()
            if (dt <= 0 || dt > MAX_GAP_MS) continue
            weighted += (a.value + b.value) / 2.0 * dt
            total += dt
        }
        return if (total > 0) (weighted / total).roundToInt() else samples.map { it.value }.average().roundToInt()
    }

    /** How much of [durationMs] the heart-rate data covers (0..1). */
    fun coverage(samples: List<Sample>, durationMs: Long): Double {
        if (samples.size < 2 || durationMs <= 0) return 0.0
        var covered = 0L
        for (i in 1 until samples.size) {
            val dt = samples[i].time - samples[i - 1].time
            if (dt in 1..MAX_GAP_MS) covered += dt
        }
        return (covered.toDouble() / durationMs).coerceIn(0.0, 1.0)
    }

    /** Which zone (1–5) a heart rate falls in. */
    fun zoneOf(bpm: Int, max: Int = maxHr): Int {
        val pct = bpm * 100.0 / max.coerceAtLeast(100)
        return ZONES.last { pct >= it.fromPct }.number
    }

    /** Time spent in each zone, in ms (index 0 = zone 1). */
    fun timeInZones(samples: List<Sample>, max: Int = maxHr): LongArray {
        val out = LongArray(ZONES.size)
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = b.time - a.time
            if (dt <= 0 || dt > MAX_GAP_MS) continue
            out[zoneOf((a.value + b.value) / 2, max) - 1] += dt
        }
        return out
    }

    /** Lower bpm of each zone for your max, e.g. "Zone 3 · 133–151". */
    fun zoneRange(zone: HrZone, max: Int = maxHr): String {
        val lo = (max * zone.fromPct / 100.0).roundToInt()
        return if (zone.toPct == null) "$lo+" else "$lo–${(max * zone.toPct / 100.0).roundToInt() - 1}"
    }

    /**
     * Heart-rate training load (hrTSS-style), on the same scale as Meridian's pace-based
     * Relative Effort: an hour at threshold (about 88% of max) scores 100.
     * Returns null when the data covers less than half of the activity.
     */
    fun load(samples: List<Sample>, durationMs: Long, max: Int = maxHr): Double? {
        if (samples.size < 10 || coverage(samples, durationMs) < 0.5) return null
        val threshold = max * 0.88
        var load = 0.0
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = b.time - a.time
            if (dt <= 0 || dt > MAX_GAP_MS) continue
            val hr = (a.value + b.value) / 2.0
            val intensity = ((hr - REST_HR) / (threshold - REST_HR)).coerceIn(0.0, 1.5)
            load += dt / 3_600_000.0 * intensity * intensity * 100.0
        }
        return load
    }

    /**
     * Evenly spaced averages over the activity, for drawing a chart. Empty buckets are null.
     */
    fun series(samples: List<Sample>, startMs: Long, endMs: Long, buckets: Int = 120): List<Double?> {
        if (samples.isEmpty() || endMs <= startMs) return emptyList()
        val n = buckets.coerceAtLeast(2)
        val width = (endMs - startMs).toDouble() / n
        val sum = DoubleArray(n)
        val count = IntArray(n)
        samples.forEach { s ->
            if (s.time < startMs || s.time > endMs) return@forEach
            val k = ((s.time - startMs) / width).toInt().coerceIn(0, n - 1)
            sum[k] += s.value.toDouble()
            count[k]++
        }
        return (0 until n).map { k -> if (count[k] > 0) sum[k] / count[k] else null }
    }

    /** Highest heart rate across activities, ignoring single-reading spikes. */
    fun highestRecorded(records: List<ActivityRecord>): Int? =
        records.mapNotNull { r ->
            if (r.heartRate.size < 10) null
            else r.heartRate.map { it.value }.sortedDescending().let { it[min(2, it.lastIndex)] }
        }.maxOrNull()

    /** Heart-rate value at a given wall-clock time (within [toleranceMs]), for GPX export. */
    fun at(samples: List<Sample>, timeMs: Long, toleranceMs: Long = 5_000L): Int? {
        if (samples.isEmpty()) return null
        var lo = 0
        var hi = samples.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (samples[mid].time < timeMs) lo = mid + 1 else hi = mid
        }
        val candidates = listOfNotNull(samples.getOrNull(lo), samples.getOrNull(lo - 1))
        val best = candidates.minByOrNull { kotlin.math.abs(it.time - timeMs) } ?: return null
        return if (kotlin.math.abs(best.time - timeMs) <= toleranceMs) best.value else null
    }

    /** Default zone setting from age-free history: your highest, but never below 170. */
    fun suggestedMax(records: List<ActivityRecord>): Int? = highestRecorded(records)?.let { max(it, 170) }
}
