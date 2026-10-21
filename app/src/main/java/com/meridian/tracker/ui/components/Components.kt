package com.meridian.tracker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meridian.tracker.tracking.GpsQuality
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---- Stats -----------------------------------------------------------------------------------

@Composable
fun StatCell(label: String, value: String, unit: String?, modifier: Modifier = Modifier, big: Boolean = false) {
    Column(modifier) {
        Text(label.uppercase(), style = MType.Label)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = if (big) MType.BigNumber else MType.BigNumber.copy(fontSize = MType.BigNumber.fontSize * 0.8f))
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MType.Unit, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
fun VerticalHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(MColors.Hairline)
    )
}

@Composable
fun HorizontalHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MColors.Hairline)
    )
}

// ---- GPS status --------------------------------------------------------------------------------

@Composable
fun GpsPill(quality: GpsQuality, accuracyM: Float?, modifier: Modifier = Modifier) {
    val (dotColor, label) = when (quality) {
        GpsQuality.SEARCHING -> MColors.Danger to "Finding GPS"
        GpsQuality.WEAK -> MColors.Danger to "Weak GPS"
        GpsQuality.GOOD -> MColors.Accent to "GPS ±${accuracyM?.toInt() ?: "–"} m"
        GpsQuality.STRONG -> MColors.Accent to "GPS ±${accuracyM?.toInt() ?: "–"} m"
    }
    val pulse = rememberInfiniteTransition(label = "gps")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "gpsAlpha",
    )
    val searching = quality == GpsQuality.SEARCHING || quality == GpsQuality.WEAK
    Row(
        modifier
            .clip(CircleShape)
            .background(MColors.Bg.copy(alpha = 0.78f))
            .border(1.dp, MColors.Stroke, CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = if (searching) alpha else 1f))
        )
        Spacer(Modifier.width(8.dp))
        Text(label.uppercase(), style = MType.Label.copy(color = MColors.Text))
    }
}

// ---- Sport selector ----------------------------------------------------------------------------

@Composable
fun SportSelector(selected: Sport, onSelect: (Sport) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(MColors.Surface2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Sport.entries.forEach { sport ->
            val isSelected = sport == selected
            val bg by animateColorAsState(if (isSelected) MColors.Accent else Color.Transparent, label = "sportBg")
            val fg by animateColorAsState(if (isSelected) MColors.OnAccent else MColors.Muted, label = "sportFg")
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable(role = Role.Tab) { onSelect(sport) },
                contentAlignment = Alignment.Center,
            ) {
                Text(sport.label, style = MType.Button.copy(color = fg))
            }
        }
    }
}

// ---- Buttons -----------------------------------------------------------------------------------

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 60.dp,
) {
    val shape = CircleShape
    Row(
        modifier
            .height(height)
            .clip(shape)
            .then(
                if (filled) Modifier.background(MColors.Accent)
                else Modifier.border(1.dp, MColors.Stroke, shape).background(MColors.Surface2)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val color = if (filled) MColors.OnAccent else MColors.Text
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MType.Button.copy(color = color))
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    filled: Boolean = false,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (filled) Modifier.background(MColors.Accent)
                else Modifier.background(MColors.Bg.copy(alpha = 0.78f)).border(1.dp, MColors.Stroke, CircleShape)
            )
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (filled) MColors.OnAccent else MColors.Text,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

/**
 * Press and hold to confirm (finish, unlock). A fill sweeps across while held;
 * letting go early cancels. TalkBack users can activate it with a normal double-tap.
 */
@Composable
fun HoldButton(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    holdMillis: Int = 900,
    height: Dp = 60.dp,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val confirm by rememberUpdatedState(onConfirm)

    Box(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(MColors.Surface2)
            .border(1.dp, MColors.Stroke, CircleShape)
            .drawBehind {
                val w = size.width * progress.value
                if (w > 0f) {
                    drawRoundRect(
                        color = MColors.Accent.copy(alpha = 0.9f),
                        size = Size(w, size.height),
                        cornerRadius = CornerRadius(size.height / 2, size.height / 2),
                    )
                }
            }
            .semantics {
                role = Role.Button
                onClick(label = text) { confirm(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val job = scope.launch {
                        progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        confirm()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    if (job.isActive) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(220)) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val fg = if (progress.value > 0.5f) MColors.OnAccent else MColors.Text
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MType.Button.copy(color = fg), textAlign = TextAlign.Center)
        }
    }
}

// ---- Route drawing without a map (offline fallback + list thumbnails) -------------------------

@Composable
fun RouteCanvas(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 4.dp,
    showGrid: Boolean = true,
    paddingFraction: Float = 0.12f,
) {
    Canvas(modifier) {
        if (showGrid) {
            val step = 28.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(MColors.Hairline, Offset(x, 0f), Offset(x, size.height), 1f)
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(MColors.Hairline, Offset(0f, y), Offset(size.width, y), 1f)
                y += step
            }
        }
        if (points.isEmpty()) return@Canvas

        val midLat = points.map { it.lat }.average()
        val kx = cos(midLat * PI / 180.0)
        val xs = points.map { it.lon * kx }
        val ys = points.map { it.lat }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val spanX = max(maxX - minX, 1e-6)
        val spanY = max(maxY - minY, 1e-6)
        val pad = min(size.width, size.height) * paddingFraction
        val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        val offX = (size.width - spanX * scale) / 2
        val offY = (size.height - spanY * scale) / 2
        fun project(i: Int) = Offset(
            (offX + (xs[i] - minX) * scale).toFloat(),
            (offY + (maxY - ys[i]) * scale).toFloat(),
        )

        val path = Path()
        var lastSegment = -1
        points.forEachIndexed { i, p ->
            val o = project(i)
            if (p.segment != lastSegment) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            lastSegment = p.segment
        }
        val sw = strokeWidth.toPx()
        drawPath(path, MColors.Accent.copy(alpha = 0.18f), style = Stroke(sw * 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, MColors.Accent, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

        val start = project(0)
        val end = project(points.lastIndex)
        drawCircle(MColors.Bg, sw * 1.6f, start)
        drawCircle(MColors.Text, sw * 1.6f, start, style = Stroke(sw * 0.8f))
        drawCircle(MColors.Accent, sw * 1.5f, end)
    }
}

// ---- Animated globe for onboarding -------------------------------------------------------------

@Composable
fun MeridianGlobe(modifier: Modifier = Modifier) {
    val spin = rememberInfiniteTransition(label = "globe")
    val phase by spin.animateFloat(
        initialValue = 0f,
        targetValue = (PI / 3).toFloat(),
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "phase",
    )
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2 * 0.92f
        val c = Offset(size.width / 2, size.height / 2)
        val line = 1.5.dp.toPx()
        drawCircle(MColors.Text.copy(alpha = 0.28f), r, c, style = Stroke(line))
        // latitude lines
        for (k in listOf(-0.6f, -0.3f, 0f, 0.3f, 0.6f)) {
            val y = c.y + r * k
            val half = r * kotlin.math.sqrt(1 - k * k)
            drawLine(MColors.Text.copy(alpha = 0.10f), Offset(c.x - half, y), Offset(c.x + half, y), line)
        }
        // rotating meridians
        for (i in 0 until 6) {
            val angle = phase + i * (PI / 6).toFloat()
            val w = abs(r * cos(angle))
            val front = sin(angle) > 0
            drawOval(
                MColors.Text.copy(alpha = if (front) 0.30f else 0.10f),
                topLeft = Offset(c.x - w, c.y - r),
                size = Size(2 * w, 2 * r),
                style = Stroke(line),
            )
        }
        // the Meridian: fixed, bold, yellow
        val mw = r * 0.44f
        drawOval(MColors.Accent, Offset(c.x - mw, c.y - r), Size(2 * mw, 2 * r), style = Stroke(line * 3.2f))
        drawCircle(MColors.Accent, line * 3.4f, Offset(c.x + mw * 0.98f, c.y - r * 0.18f))
    }
}

@Composable
fun FullscreenBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(MColors.Bg)) { content() }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MColors.Surface)
            .border(1.dp, MColors.Hairline, RoundedCornerShape(22.dp))
    ) { content() }
}

// ---- Generic segmented control and sport filter --------------------------------------------

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(MColors.Surface2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSelected = i == selected
            val bg by animateColorAsState(if (isSelected) MColors.Accent else Color.Transparent, label = "segBg")
            val fg by animateColorAsState(if (isSelected) MColors.OnAccent else MColors.Muted, label = "segFg")
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MType.Button.copy(color = fg))
            }
        }
    }
}

@Composable
fun SportFilterRow(selected: Sport?, onSelect: (Sport?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterPill("All", selected == null) { onSelect(null) }
        Sport.entries.forEach { sport -> FilterPill(sport.label, selected == sport) { onSelect(sport) } }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MColors.Text else Color.Transparent, label = "pillBg")
    val fg by animateColorAsState(if (selected) MColors.Bg else MColors.Text, label = "pillFg")
    Box(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (selected) MColors.Text else MColors.Stroke, CircleShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MType.Button.copy(color = fg, fontSize = MType.Button.fontSize * 0.85f))
    }
}
