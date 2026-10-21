package com.meridian.tracker.data

import com.meridian.tracker.tracking.Sport
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class BodySex(val label: String) { MALE("Male"), FEMALE("Female") }

/** What the calorie estimate knows about you. Everything but [weightKg] is optional. */
data class Body(
    val weightKg: Double = Calories.DEFAULT_WEIGHT_KG,
    /** True when the athlete entered their weight; otherwise [weightKg] is a typical 70 kg. */
    val weightKnown: Boolean = false,
    val birthYear: Int? = null,
    val sex: BodySex? = null,
)

/**
 * Calorie estimates. These are estimates only, good to roughly 15 to 20 percent:
 *  - With heart rate, age and sex it uses the Keytel formula, which is how hard the heart worked.
 *  - Otherwise it uses METs (energy per kilo per hour) for the sport and average speed,
 *    from the Compendium of Physical Activities.
 * Pure Kotlin, unit-tested.
 */
object Calories {

    const val DEFAULT_WEIGHT_KG = 70.0
    private const val SAMPLE_CAP_MS = 30_000L
    private const val MIN_MOVING_MS = 60_000L
    private const val MAX_KCAL_PER_MIN = 30.0

    enum class Method(val label: String) { HEART_RATE("Heart rate"), PACE("Pace") }

    data class Estimate(val kcal: Int, val method: Method)

    fun estimate(record: ActivityRecord, body: Body, year: Int = LocalDate.now().year): Estimate? {
        val activeMs = if (record.movingMs > 0) record.movingMs else record.elapsedMs
        if (activeMs < MIN_MOVING_MS) return null
        val weight = body.weightKg.takeIf { it in 30.0..300.0 } ?: DEFAULT_WEIGHT_KG

        val age = body.birthYear?.let { year - it }?.takeIf { it in 10..100 }
        val sex = body.sex
        if (record.hasHeartRate && age != null && sex != null) {
            val fromHeart = fromHeartRate(record.heartRate, weight, age, sex)
            // Only trust it when the heart-rate data covers most of the activity.
            if (fromHeart != null && fromHeart.second >= 0.6 * activeMs) {
                return Estimate(fromHeart.first.roundToInt(), Method.HEART_RATE)
            }
        }
        val hours = activeMs / 3_600_000.0
        val mets = met(record.sport, record.avgSpeed)
        return Estimate((mets * weight * hours).roundToInt(), Method.PACE)
    }

    /** Total kcal and the time (ms) the samples cover, or null with too few samples. */
    private fun fromHeartRate(samples: List<Sample>, weightKg: Double, age: Int, sex: BodySex): Pair<Double, Long>? {
        if (samples.size < 2) return null
        val restFloor = weightKg / 60.0 // about 1 MET, so low readings never go under resting burn
        var kcal = 0.0
        var covered = 0L
        for (i in 0 until samples.size - 1) {
            val dt = min(samples[i + 1].time - samples[i].time, SAMPLE_CAP_MS)
            if (dt <= 0) continue
            val hr = samples[i].value.toDouble()
            val perMin = when (sex) {
                BodySex.MALE -> (-55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * age) / 4.184
                BodySex.FEMALE -> (-20.4022 + 0.4472 * hr - 0.1263 * weightKg + 0.074 * age) / 4.184
            }
            kcal += perMin.coerceIn(restFloor, MAX_KCAL_PER_MIN) * (dt / 60_000.0)
            covered += dt
        }
        return if (covered > 0) kcal to covered else null
    }

    /** Energy cost in METs for [sport] at an average [speedMps]. */
    fun met(sport: Sport, speedMps: Double): Double {
        val kmh = (speedMps * 3.6).coerceAtLeast(0.0)
        return when (sport) {
            Sport.RUN -> max(5.5, min(kmh, 20.0))
            Sport.TRAIL_RUN -> max(6.0, min(kmh * 1.05, 20.0))
            Sport.WALK -> lerp(WALK_TABLE, kmh)
            Sport.HIKE -> lerp(HIKE_TABLE, kmh)
            Sport.RIDE -> lerp(RIDE_TABLE, kmh)
        }
    }

    // (km/h, METs) from the Compendium of Physical Activities, joined by straight lines.
    private val WALK_TABLE = listOf(2.0 to 2.0, 3.2 to 2.8, 4.0 to 3.0, 4.8 to 3.5, 5.6 to 4.3, 6.4 to 5.0, 8.0 to 7.0)
    private val HIKE_TABLE = listOf(2.0 to 3.5, 3.0 to 4.5, 4.0 to 5.3, 5.0 to 6.0, 6.0 to 6.8)
    private val RIDE_TABLE = listOf(8.0 to 3.0, 16.0 to 4.5, 19.0 to 6.8, 22.0 to 8.0, 25.0 to 10.0, 30.0 to 12.0, 35.0 to 15.0)

    private fun lerp(table: List<Pair<Double, Double>>, x: Double): Double {
        if (x <= table.first().first) return table.first().second
        if (x >= table.last().first) return table.last().second
        val i = table.indexOfLast { it.first <= x }
        val (x0, y0) = table[i]
        val (x1, y1) = table[i + 1]
        return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }
}
