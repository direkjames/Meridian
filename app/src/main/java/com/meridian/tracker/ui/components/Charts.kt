package com.meridian.tracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meridian.tracker.data.ActivityProfile
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pace (or speed) and elevation charts that share one scrubber: drag across either chart
 * and both show the value at that point of the activity.
 */
@Composable
fun ProfileCharts(profile: ActivityProfile, sport: Sport, modifier: Modifier = Modifier) {
    var scrub by remember(profile) { mutableStateOf<Int?>(null) }
    val samples = profile.samples
    val haptics = LocalHapticFeedback.current

    fun distanceLabel(i: Int) = String.format(Locale.US, "%.2f ${Format.distUnit}", samples[i].distanceM / Format.unitMeters)

    Column(modifier) {
        if (profile.hasSpeed) {
            val speeds = samples.map { it.speed }
            val known = speeds.filterNotNull()
            val avg = known.average()
            val readout = scrub?.let { i ->
                val v = speeds[i]
                val value = when {
                    v == null -> "—"
                    sport.showsSpeed -> "${Format.speedKmh(v)} ${Format.speedUnit}"
                    else -> "${Format.paceFromSpeed(v)} ${Format.paceUnit}"
                }
                "$value  ·  ${distanceLabel(i)}"
            } ?: if (sport.showsSpeed) "AVG ${Format.speedKmh(avg)} ${Format.speedUnit}" else "AVG ${Format.paceFromSpeed(avg)} ${Format.paceUnit}"

            ChartHeader(if (sport.showsSpeed) "SPEED" else "PACE", readout, highlighted = scrub != null)
            Spacer(Modifier.height(10.dp))
            LineChart(
                values = speeds,
                lineColor = MColors.Accent,
                fillTop = MColors.Accent.copy(alpha = 0.28f),
                floorAtZero = false,
                scrub = scrub,
                onScrub = { i ->
                    if (i != null && i != scrub && i % 10 == 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scrub = i
                },
                description = if (sport.showsSpeed) "Speed chart" else "Pace chart",
            )
        }

        if (profile.hasElevation) {
            if (profile.hasSpeed) Spacer(Modifier.height(28.dp))
            val elev = samples.map { it.elevation }
            val readout = scrub?.let { i ->
                val v = elev[i]
                "${if (v == null) "—" else "${v.roundToInt()} m"}  ·  ${distanceLabel(i)}"
            } ?: "${profile.minElevation?.roundToInt()}–${profile.maxElevation?.roundToInt()} m"
            ChartHeader("ELEVATION", readout, highlighted = scrub != null)
            Spacer(Modifier.height(10.dp))
            LineChart(
                values = elev,
                lineColor = MColors.Text.copy(alpha = 0.85f),
                fillTop = MColors.Text.copy(alpha = 0.16f),
                floorAtZero = false,
                minSpan = 12.0,
                scrub = scrub,
                onScrub = { scrub = it },
                description = "Elevation chart",
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("0 ${Format.distUnit}", style = MType.Label, modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, "%.1f ${Format.distUnit}", profile.totalM / Format.unitMeters), style = MType.Label)
        }
    }
}

@Composable
internal fun ChartHeader(title: String, readout: String, highlighted: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MType.Label, modifier = Modifier.weight(1f))
        Text(
            readout.uppercase(),
            style = MType.Label.copy(color = if (highlighted) MColors.Accent else MColors.Text),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun LineChart(
    values: List<Double?>,
    lineColor: Color,
    fillTop: Color,
    floorAtZero: Boolean,
    scrub: Int?,
    onScrub: (Int?) -> Unit,
    description: String,
    minSpan: Double = 0.0,
) {
    val known = values.filterNotNull()
    if (known.isEmpty()) return
    var lo = if (floorAtZero) 0.0 else known.min()
    var hi = known.max()
    if (hi - lo < minSpan) {
        val mid = (hi + lo) / 2
        lo = mid - minSpan / 2
        hi = mid + minSpan / 2
    }
    val pad = max((hi - lo) * 0.12, 1e-6)
    lo -= pad
    hi += pad

    val n = values.size
    fun indexAt(x: Float, width: Float): Int = ((x / width) * n).toInt().coerceIn(0, n - 1)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(130.dp)
            .semantics { contentDescription = description }
            .pointerInput(values) {
                detectTapGestures(onPress = { pos ->
                    onScrub(indexAt(pos.x, size.width.toFloat()))
                    tryAwaitRelease()
                    onScrub(null)
                })
            }
            .pointerInput(values) {
                detectHorizontalDragGestures(
                    onDragStart = { pos -> onScrub(indexAt(pos.x, size.width.toFloat())) },
                    onDragEnd = { onScrub(null) },
                    onDragCancel = { onScrub(null) },
                ) { change, _ ->
                    change.consume()
                    onScrub(indexAt(change.position.x, size.width.toFloat()))
                }
            }
    ) {
        val w = size.width
        val h = size.height
        // grid
        for (k in 0..3) {
            val y = h * k / 3f
            drawLine(MColors.Hairline, Offset(0f, y), Offset(w, y), 1f)
        }

        fun x(i: Int) = (i + 0.5f) / n * w
        fun y(v: Double) = (h - ((v - lo) / (hi - lo)) * h).toFloat()

        val line = Path()
        val area = Path()
        var started = false
        var firstX = 0f
        var lastX = 0f
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val px = x(i)
            val py = y(v)
            if (!started) {
                line.moveTo(px, py)
                area.moveTo(px, h)
                area.lineTo(px, py)
                firstX = px
                started = true
            } else {
                line.lineTo(px, py)
                area.lineTo(px, py)
            }
            lastX = px
        }
        area.lineTo(lastX, h)
        area.lineTo(firstX, h)
        area.close()

        drawPath(area, Brush.verticalGradient(listOf(fillTop, fillTop.copy(alpha = 0f)), startY = 0f, endY = h))
        drawPath(line, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        if (scrub != null) {
            val sx = x(scrub)
            drawLine(MColors.Text.copy(alpha = 0.55f), Offset(sx, 0f), Offset(sx, h), 1.dp.toPx())
            values.getOrNull(scrub)?.let { v ->
                val sy = y(v)
                drawCircle(MColors.Bg, 6.dp.toPx(), Offset(sx, sy))
                drawCircle(lineColor, 4.dp.toPx(), Offset(sx, sy))
            }
        }
    }
}

/** Seven bars, Monday to Sunday. Today's bar is highlighted. */
@Composable
fun WeekBars(distancesM: List<Double>, todayIndex: Int, modifier: Modifier = Modifier) {
    val days = listOf("M", "T", "W", "T", "F", "S", "S")
    val maxDistance = max(distancesM.maxOrNull() ?: 0.0, 1.0)
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .semantics { contentDescription = "Distance per day this week" }
        ) {
            val slot = size.width / 7f
            val barW = slot * 0.42f
            distancesM.forEachIndexed { i, d ->
                val cx = slot * i + slot / 2
                val color = when {
                    i == todayIndex -> MColors.Accent
                    d > 0 -> MColors.Bar
                    else -> MColors.Hairline
                }
                val barH = if (d > 0) max((d / maxDistance * size.height).toFloat(), barW) else barW * 0.6f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(cx - barW / 2, size.height - barH),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2, barW / 2),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            days.forEachIndexed { i, d ->
                Text(
                    d,
                    style = MType.Label.copy(color = if (i == todayIndex) MColors.Accent else MColors.Faint),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Bars for a week, month or year. Tap or drag across to select a bar; [selected] is shown in
 * the accent colour and everything else steps back.
 */
@Composable
fun PeriodBars(
    values: List<Double>,
    labels: List<String>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    highlight: Int? = null,
    labelEvery: Int = 1,
    height: androidx.compose.ui.unit.Dp = 150.dp,
) {
    val n = values.size
    if (n == 0) return
    val maxValue = max(values.maxOrNull() ?: 0.0, 1.0)
    fun indexAt(x: Float, width: Float) = ((x / width) * n).toInt().coerceIn(0, n - 1)

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = "Distance chart" }
                .pointerInput(n) {
                    detectTapGestures(onTap = { pos ->
                        val i = indexAt(pos.x, size.width.toFloat())
                        onSelect(if (i == selected) null else i)
                    })
                }
                .pointerInput(n) {
                    detectHorizontalDragGestures(
                        onDragStart = { pos -> onSelect(indexAt(pos.x, size.width.toFloat())) },
                    ) { change, _ ->
                        change.consume()
                        onSelect(indexAt(change.position.x, size.width.toFloat()))
                    }
                }
        ) {
            val slot = size.width / n
            val barW = (slot * if (n > 14) 0.62f else 0.46f).coerceAtMost(28.dp.toPx())
            for (k in 1..3) {
                val y = size.height * k / 4f
                drawLine(MColors.Hairline, Offset(0f, y), Offset(size.width, y), 1f)
            }
            values.forEachIndexed { i, v ->
                val cx = slot * i + slot / 2
                val isSel = i == selected
                val color = when {
                    isSel -> MColors.Accent
                    selected == null && i == highlight -> MColors.Accent
                    selected != null -> MColors.Bar.copy(alpha = 0.55f)
                    v > 0 -> MColors.Bar
                    else -> MColors.Hairline
                }
                val minH = barW * 0.5f
                val barH = if (v > 0) max((v / maxValue * size.height).toFloat(), barW) else minH
                drawRoundRect(
                    color = color,
                    topLeft = Offset(cx - barW / 2, size.height - barH),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2, barW / 2),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                val show = i % labelEvery == 0 || i == labels.lastIndex
                Text(
                    if (show) l else "",
                    style = MType.Label.copy(
                        color = if (i == selected || (selected == null && i == highlight)) MColors.Accent else MColors.Faint,
                        fontSize = MType.Label.fontSize * 0.9f,
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
