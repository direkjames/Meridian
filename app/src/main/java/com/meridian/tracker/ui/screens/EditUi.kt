package com.meridian.tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import com.meridian.tracker.data.Indoor
import java.util.Locale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meridian.tracker.data.ActivityEdits
import com.meridian.tracker.data.ActivityEditing
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.RouteCanvas
import com.meridian.tracker.ui.components.SportSelector
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** Fix a saved activity: change its sport, trim a forgotten start or stop, or undo a trim. */
@Composable
fun EditActivityDialog(record: ActivityRecord, onDismiss: () -> Unit) {
    var trimming by remember { mutableStateOf(false) }
    var hasOriginal by remember { mutableStateOf(ActivityStore.hasOriginal(record.id)) }
    var confirmRestore by remember { mutableStateOf(false) }
    var settingDistance by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (trimming) {
        TrimDialog(
            record = record,
            onDone = { trimmed -> trimming = false; if (trimmed) hasOriginal = true },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MColors.Surface2,
        title = { Text("Edit activity", style = MType.Heading) },
        text = {
            Column {
                Text("SPORT", style = MType.Label)
                Spacer(Modifier.height(8.dp))
                SportSelector(
                    selected = record.sport,
                    onSelect = { sport -> scope.launch { ActivityEdits.setSport(record.id, sport) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Distance and time stay as recorded.",
                    style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f, color = MColors.Faint),
                )
                if (Indoor.canSetDistance(record)) {
                    Spacer(Modifier.height(18.dp))
                    ActionRow(
                        "Distance: ${Format.km(record.distanceM)} ${Format.distUnit}",
                        "Set it to what the treadmill showed.",
                    ) { settingDistance = true }
                }
                if (record.points.size >= TRIM_MIN_POINTS) {
                    Spacer(Modifier.height(18.dp))
                    ActionRow(
                        "Trim start or end",
                        "Cut off the part before you started or after you finished.",
                    ) { trimming = true }
                }
                if (hasOriginal) {
                    Spacer(Modifier.height(10.dp))
                    ActionRow("Restore untrimmed route", "Brings back the full route and its numbers.") {
                        confirmRestore = true
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", style = MType.Button.copy(color = MColors.Accent)) }
        },
    )

    if (settingDistance) DistanceDialog(record, onDismiss = { settingDistance = false })

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            containerColor = MColors.Surface2,
            title = { Text("Restore the full route?", style = MType.Heading) },
            text = { Text("Your title, notes, photos and gear stay as they are.", style = MType.Body) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    scope.launch {
                        ActivityStore.restoreOriginal(record.id)
                        hasOriginal = ActivityStore.hasOriginal(record.id)
                    }
                }) { Text("Restore", style = MType.Button.copy(color = MColors.Accent)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) }
            },
        )
    }
}

private const val TRIM_MIN_POINTS = 8

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MColors.Bg)
            .border(1.dp, MColors.Stroke, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(title, style = MType.Heading.copy(fontSize = MType.Heading.fontSize * 0.92f))
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimDialog(record: ActivityRecord, onDone: (trimmed: Boolean) -> Unit) {
    val points = remember(record.id) { record.points.toList() }
    val last = (points.size - 1).coerceAtLeast(1)
    var range by remember { mutableStateOf(0f..last.toFloat()) }
    val from = range.start.roundToInt().coerceIn(0, last)
    val to = range.endInclusive.roundToInt().coerceIn(from, last)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // The smallest slice that is still an activity.
    val minGap = min(5f, last.toFloat())

    val preview by produceState<ActivityRecord?>(null, from, to) {
        value = withContext(Dispatchers.Default) {
            ActivityEditing.trim(record, points[from].time, points[to].time)
        }
    }
    val unchanged = from == 0 && to == last

    AlertDialog(
        onDismissRequest = { if (!busy) onDone(false) },
        containerColor = MColors.Surface2,
        title = { Text("Trim activity", style = MType.Heading) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MColors.Bg)
                ) {
                    RouteCanvas(
                        points = points.subList(from, to + 1),
                        strokeWidth = 3.dp,
                        showGrid = false,
                        paddingFraction = 0.12f,
                        modifier = Modifier.fillMaxWidth().height(130.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                RangeSlider(
                    value = range,
                    onValueChange = { r ->
                        if (r.endInclusive - r.start >= minGap) range = r
                    },
                    valueRange = 0f..last.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = MColors.Accent,
                        activeTrackColor = MColors.Accent,
                        inactiveTrackColor = MColors.Stroke,
                    ),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val t0 = points.first().time
                    Text("START ${Format.duration(points[from].time - t0)}", style = MType.Label)
                    Text("END ${Format.duration(points[to].time - t0)}", style = MType.Label)
                }
                Spacer(Modifier.height(14.dp))
                val p = preview
                Text(
                    when {
                        failed -> "Couldn't trim. Try a longer section."
                        p == null -> "That's too short to keep."
                        else -> "${Format.km(p.distanceM)} ${Format.distUnit} · ${Format.duration(p.movingMs)} moving"
                    },
                    style = MType.Heading.copy(fontSize = MType.Heading.fontSize * 0.92f),
                )
                if (p != null && !unchanged) {
                    Text(
                        "was ${Format.km(record.distanceM)} ${Format.distUnit} · ${Format.duration(record.movingMs)}",
                        style = MType.Body.copy(color = MColors.Faint),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "You can restore the full route later from Edit activity.",
                    style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f, color = MColors.Faint),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !unchanged && preview != null,
                onClick = {
                    busy = true
                    scope.launch {
                        val result = ActivityStore.trim(record.id, points[from].time, points[to].time)
                        busy = false
                        if (result == null) failed = true else onDone(true)
                    }
                },
            ) {
                Text(
                    "Trim",
                    style = MType.Button.copy(color = if (!busy && !unchanged && preview != null) MColors.Accent else MColors.Faint),
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { onDone(false) }) {
                Text("Cancel", style = MType.Button.copy(color = MColors.Text))
            }
        },
    )
}

/**
 * Types in the distance a treadmill (or other indoor session) showed. Works on the unsaved
 * activity and on saved ones; says so when the number can't be right for the time spent.
 */
@Composable
fun DistanceDialog(record: ActivityRecord, onDismiss: () -> Unit) {
    var text by rememberSaveable {
        mutableStateOf(if (record.distanceM > 0) String.format(Locale.US, "%.2f", record.distanceM / Format.unitMeters) else "")
    }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MColors.Surface2,
        title = { Text("Distance (${Format.distUnit})", style = MType.Heading) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MColors.Bg)
                        .border(1.dp, MColors.Stroke, RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { v ->
                            if (v.length <= 7 && v.all { it.isDigit() || it == '.' || it == ',' }) { text = v; error = null }
                        },
                        textStyle = MType.Heading,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        cursorBrush = SolidColor(MColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    error ?: "Pace, splits and calories follow from this.",
                    style = MType.Body.copy(
                        fontSize = MType.Body.fontSize * 0.88f,
                        color = if (error != null) MColors.Danger else MColors.Faint,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val meters = text.replace(',', '.').toDoubleOrNull()?.times(Format.unitMeters)
                val updated = meters?.let { Indoor.withDistance(record, it) }
                if (updated == null) {
                    error = "That doesn't fit the time you spent. Check the number."
                } else {
                    scope.launch {
                        ActivityEdits.edit(record.id) { r -> Indoor.withDistance(r, meters) ?: r }
                        onDismiss()
                    }
                }
            }) { Text("Save", style = MType.Button.copy(color = MColors.Accent)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) }
        },
    )
}
