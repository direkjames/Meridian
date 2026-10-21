package com.meridian.tracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meridian.tracker.data.FitnessDay
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val dayFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

/**
 * Fitness (yellow) and fatigue (grey) over time. Drag across to read any day.
 */
@Composable
fun FitnessChart(days: List<FitnessDay>, modifier: Modifier = Modifier) {
    if (days.size < 2) return
    var scrub by remember(days) { mutableStateOf<Int?>(null) }
    val maxV = max(days.maxOf { max(it.fitness, it.fatigue) }, 10.0) * 1.1
    val shown = scrub?.let { days[it] } ?: days.last()

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend(MColors.Accent, "FITNESS ${shown.fitness.roundToInt()}")
            Spacer(Modifier.width(14.dp))
            Legend(MColors.Muted, "FATIGUE ${shown.fatigue.roundToInt()}")
            Spacer(Modifier.weight(1f))
            Text(
                if (scrub != null) shown.date.format(dayFmt).uppercase() else "TODAY",
                style = MType.Label.copy(color = if (scrub != null) MColors.Accent else MColors.Muted),
            )
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .semantics { contentDescription = "Fitness and fatigue chart" }
                .pointerInput(days) {
                    detectHorizontalDragGestures(
                        onDragStart = { scrub = index(it.x, size.width.toFloat(), days.size) },
                        onDragEnd = { scrub = null },
                        onDragCancel = { scrub = null },
                    ) { change, _ ->
                        change.consume()
                        scrub = index(change.position.x, size.width.toFloat(), days.size)
                    }
                }
                .pointerInput(days) {
                    detectTapGestures(onPress = {
                        scrub = index(it.x, size.width.toFloat(), days.size)
                        tryAwaitRelease()
                        scrub = null
                    })
                }
        ) {
            val w = size.width
            val h = size.height
            for (k in 1..3) drawLine(MColors.Hairline, Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1f)
            fun x(i: Int) = i.toFloat() / (days.size - 1) * w
            fun y(v: Double) = (h - v / maxV * h).toFloat()

            val fit = Path()
            val area = Path()
            val fat = Path()
            days.forEachIndexed { i, d ->
                if (i == 0) {
                    fit.moveTo(x(i), y(d.fitness)); fat.moveTo(x(i), y(d.fatigue))
                    area.moveTo(x(i), h); area.lineTo(x(i), y(d.fitness))
                } else {
                    fit.lineTo(x(i), y(d.fitness)); fat.lineTo(x(i), y(d.fatigue)); area.lineTo(x(i), y(d.fitness))
                }
            }
            area.lineTo(w, h); area.close()
            drawPath(area, Brush.verticalGradient(listOf(MColors.Accent.copy(alpha = 0.25f), Color.Transparent)))
            drawPath(fat, MColors.Muted, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(fit, MColors.Accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            scrub?.let { i ->
                drawLine(MColors.Text.copy(alpha = 0.5f), Offset(x(i), 0f), Offset(x(i), h), 1.dp.toPx())
                drawCircle(MColors.Accent, 4.dp.toPx(), Offset(x(i), y(days[i].fitness)))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(days.first().date.format(dayFmt).uppercase(), style = MType.Label.copy(color = MColors.Faint), modifier = Modifier.weight(1f))
            Text(days.last().date.format(dayFmt).uppercase(), style = MType.Label.copy(color = MColors.Faint))
        }
    }
}

private fun index(x: Float, width: Float, n: Int) = ((x / width) * (n - 1)).roundToInt().coerceIn(0, n - 1)

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MType.Label.copy(color = MColors.Text))
    }
}

/** Weekly training load with this week's suggested range shaded. */
@Composable
fun LoadBars(weekly: List<Pair<LocalDate, Double>>, range: ClosedFloatingPointRange<Double>?, modifier: Modifier = Modifier) {
    if (weekly.isEmpty()) return
    val maxV = max(max(weekly.maxOf { it.second }, range?.endInclusive ?: 0.0), 10.0) * 1.1
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .semantics { contentDescription = "Weekly training load" }
        ) {
            val n = weekly.size
            val slot = size.width / n
            val barW = slot * 0.5f
            fun y(v: Double) = (size.height - v / maxV * size.height).toFloat()
            range?.let { r ->
                val top = y(r.endInclusive)
                val bottom = y(r.start)
                drawRect(
                    MColors.Accent.copy(alpha = 0.08f),
                    topLeft = Offset(size.width - slot, top),
                    size = Size(slot, bottom - top),
                )
                drawLine(MColors.Accent.copy(alpha = 0.4f), Offset(size.width - slot, top), Offset(size.width, top), 1.dp.toPx())
                drawLine(MColors.Accent.copy(alpha = 0.4f), Offset(size.width - slot, bottom), Offset(size.width, bottom), 1.dp.toPx())
            }
            weekly.forEachIndexed { i, (_, v) ->
                val cx = slot * i + slot / 2
                val bh = if (v > 0) max(size.height - y(v), barW * 0.6f) else barW * 0.3f
                drawRoundRect(
                    color = if (i == n - 1) MColors.Accent else if (v > 0) MColors.Bar else MColors.Hairline,
                    topLeft = Offset(cx - barW / 2, size.height - bh),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(barW / 2, barW / 2),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            weekly.forEachIndexed { i, (d, _) ->
                Text(
                    if (i == weekly.lastIndex) "NOW" else d.format(dayFmt).uppercase(),
                    style = MType.Label.copy(color = if (i == weekly.lastIndex) MColors.Accent else MColors.Faint, fontSize = MType.Label.fontSize * 0.8f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** A circular progress ring for goals. */
@Composable
fun ProgressRing(fraction: Float, modifier: Modifier = Modifier, done: Boolean = false) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.12f
        val inset = stroke / 2
        drawArc(
            MColors.Surface2, 0f, 360f, false,
            topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke),
        )
        drawArc(
            if (done) MColors.Accent else MColors.Accent.copy(alpha = 0.9f), -90f, 360f * fraction.coerceIn(0f, 1f), false,
            topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
