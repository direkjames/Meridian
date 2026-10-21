package com.meridian.tracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.HeartRate
import com.meridian.tracker.data.Sample
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlin.math.max
import kotlin.math.roundToInt

private const val BUCKETS = 120

/** Time range a chart covers: from the start to the later of the last reading or elapsed time. */
private fun chartEnd(record: ActivityRecord, samples: List<Sample>): Long =
    max(record.startTimeMs + record.elapsedMs, samples.lastOrNull()?.time ?: 0L)

/** Heart rate over time and time in each zone. */
@Composable
fun HeartRateSection(record: ActivityRecord, modifier: Modifier = Modifier) {
    val samples = record.heartRate
    if (samples.size < 2) return
    val maxHr = HeartRate.maxHr
    val end = chartEnd(record, samples)
    val values = remember(record.id, samples.size) { HeartRate.series(samples, record.startTimeMs, end, BUCKETS) }
    val zones = remember(record.id, samples.size, maxHr) { HeartRate.timeInZones(samples, maxHr) }
    var scrub by remember(record.id) { mutableStateOf<Int?>(null) }
    if (values.none { it != null }) return

    Column(modifier) {
        val readout = scrub?.let { i ->
            val v = values.getOrNull(i)
            val at = ((i + 0.5) / values.size * (end - record.startTimeMs)).toLong()
            "${v?.roundToInt() ?: "–"} BPM  ·  ${Format.duration(at)}"
        } ?: "AVG ${record.avgHeartRate ?: "–"} · MAX ${record.maxHeartRate ?: "–"} BPM"
        ChartHeader("HEART RATE", readout, highlighted = scrub != null)
        Spacer(Modifier.height(10.dp))
        LineChart(
            values = values,
            lineColor = MColors.Accent,
            fillTop = MColors.Accent.copy(alpha = 0.22f),
            floorAtZero = false,
            minSpan = 20.0,
            scrub = scrub,
            onScrub = { scrub = it },
            description = "Heart rate chart",
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("0:00", style = MType.Label, modifier = Modifier.weight(1f))
            Text(Format.duration(end - record.startTimeMs), style = MType.Label)
        }

        val total = zones.sum()
        if (total > 0) {
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("TIME IN ZONES", style = MType.Label, modifier = Modifier.weight(1f))
                Text("MAX HR $maxHr", style = MType.Label)
            }
            Spacer(Modifier.height(10.dp))
            HeartRate.ZONES.reversed().forEach { zone ->
                val ms = zones[zone.number - 1]
                val fraction = ms.toFloat() / total
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Z${zone.number}", style = MType.Number.copy(color = MColors.Muted), modifier = Modifier.width(34.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(zone.name.uppercase(), style = MType.Label, modifier = Modifier.weight(1f))
                            Text(HeartRate.zoneRange(zone, maxHr), style = MType.Label.copy(color = MColors.Faint))
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(MColors.Surface2)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction.coerceIn(0.015f, 1f))
                                    .height(8.dp)
                                    .clip(CircleShape)
                                    .background(if (ms > 0) zoneBarColor(zone.number) else Color.Transparent)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(64.dp)) {
                        Text(Format.duration(ms), style = MType.Number.copy(fontSize = MType.Number.fontSize * 0.9f))
                        Text("${(fraction * 100).roundToInt()}%", style = MType.Label)
                    }
                }
            }
        }
    }
}

/** Steps per minute over time (run-walk sessions show the switch clearly). */
@Composable
fun CadenceSection(record: ActivityRecord, modifier: Modifier = Modifier) {
    val samples = record.cadence
    if (samples.size < 3) return
    val end = chartEnd(record, samples)
    val values = remember(record.id, samples.size) { HeartRate.series(samples, record.startTimeMs, end, 60) }
    var scrub by remember(record.id) { mutableStateOf<Int?>(null) }
    if (values.none { it != null }) return
    Column(modifier) {
        val readout = scrub?.let { i ->
            val v = values.getOrNull(i)
            val at = ((i + 0.5) / values.size * (end - record.startTimeMs)).toLong()
            "${v?.roundToInt() ?: "–"} SPM  ·  ${Format.duration(at)}"
        } ?: "AVG ${record.avgCadence ?: "–"} SPM"
        ChartHeader("CADENCE", readout, highlighted = scrub != null)
        Spacer(Modifier.height(10.dp))
        LineChart(
            values = values,
            lineColor = MColors.Text.copy(alpha = 0.85f),
            fillTop = MColors.Text.copy(alpha = 0.14f),
            floorAtZero = true,
            scrub = scrub,
            onScrub = { scrub = it },
            description = "Cadence chart",
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("0:00", style = MType.Label, modifier = Modifier.weight(1f))
            Text(Format.duration(end - record.startTimeMs), style = MType.Label)
        }
    }
}

private fun zoneBarColor(zone: Int): Color = when (zone) {
    1 -> MColors.Bar
    2 -> MColors.Muted
    3 -> MColors.AccentDim
    4 -> MColors.Accent.copy(alpha = 0.8f)
    else -> MColors.Accent
}

/** Small dot used in the zone list. */
@Composable
fun ZoneSwatch(zone: Int) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(zoneBarColor(zone)))
}
