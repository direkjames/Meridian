package com.meridian.tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meridian.tracker.data.PlanResult
import com.meridian.tracker.data.RouteGen
import com.meridian.tracker.data.RouteGenService
import com.meridian.tracker.data.RouteProfile
import com.meridian.tracker.data.RouteRequest
import com.meridian.tracker.data.RouteShape
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.Segmented
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One planned route: its points, a name, the real distance and whether it landed within 10% of the target. */
class GeneratedRoute(val points: List<Pair<Double, Double>>, val name: String, val distanceM: Double, val onTarget: Boolean)

/**
 * "Plan a route from a distance": asks how far, loop or out-and-back, on foot or by bike, and
 * says plainly that the location is sent to the routing service before anything is sent.
 */
@Composable
fun GenerateRouteDialog(onDismiss: () -> Unit, onRoutes: (List<GeneratedRoute>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("5") }
    var shapeIndex by rememberSaveable { mutableIntStateOf(0) }
    var profileIndex by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    // Bumped by every plan and every cancel, so a late location fix or a cancelled run can't act.
    var attempt by remember { mutableIntStateOf(0) }

    val shape = RouteShape.entries[shapeIndex]
    val profile = RouteProfile.entries[profileIndex]

    fun plan() {
        val distanceM = text.replace(',', '.').toDoubleOrNull()?.times(Format.unitMeters)
        val range = RouteGen.distanceRange(profile)
        if (distanceM == null || distanceM !in range) {
            message = "Enter a distance from ${Format.km(range.start)} to ${Format.km(range.endInclusive)} ${Format.distUnit}."
            return
        }
        busy = true
        found = 0
        message = null
        val mine = ++attempt
        currentLocation(context) { loc ->
            if (mine != attempt) return@currentLocation
            if (loc == null) {
                busy = false
                message = "Couldn't get your location. Try again outside."
                return@currentLocation
            }
            job = scope.launch {
                try {
                    val request = RouteRequest(loc.latitude, loc.longitude, distanceM, shape, profile, bearingDeg = (0 until 360).random().toDouble())
                    val result = RouteGenService.planChoices(request) { found = it }
                    if (result.choices.isNotEmpty()) {
                        val label = "${Format.km(distanceM).trimEnd('0').trimEnd('.')} ${Format.distUnit} ${shape.label.lowercase()}"
                        onRoutes(result.choices.map {
                            GeneratedRoute(it.points, label.replaceFirstChar { c -> c.uppercase() }, it.distanceM, abs(it.distanceM - distanceM) <= distanceM * RouteGen.TOLERANCE)
                        })
                    } else {
                        message = when (result.failure) {
                            PlanResult.Offline -> "Couldn't reach the routing service. Check your connection and try again."
                            PlanResult.Invalid -> "That request doesn't look right. Check the distance."
                            else -> "No path found from here. Try a different distance or start somewhere with more roads or paths."
                        }
                    }
                } finally {
                    if (mine == attempt) {
                        busy = false
                        job = null
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MColors.Surface2,
        title = { Text("Plan a route", style = MType.Heading) },
        text = {
            Column {
                Text("DISTANCE (${Format.distUnit.uppercase()})", style = MType.Label)
                Spacer(Modifier.height(8.dp))
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
                        onValueChange = { v -> if (v.length <= 5 && v.all { it.isDigit() || it == '.' || it == ',' }) text = v },
                        textStyle = MType.Heading,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        cursorBrush = SolidColor(MColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Segmented(RouteShape.entries.map { it.label }, shapeIndex, { shapeIndex = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Segmented(RouteProfile.entries.map { it.label }, profileIndex, { profileIndex = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, MColors.Stroke, RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Text("YOUR LOCATION IS SENT", style = MType.Label.copy(color = MColors.Accent))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "To find paths, Meridian sends your current location and the route shape to the free OpenStreetMap " +
                            "routing service (routing.openstreetmap.de). Nothing else is sent, and the route is saved only on this phone.",
                        style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        busy && found > 0 -> "Found $found of ${RouteGen.WANTED_CHOICES} routes… this takes a few seconds."
                        busy -> "Finding your location and planning…"
                        message != null -> message!!
                        else -> "Starts from where you are now."
                    },
                    style = MType.Body.copy(
                        fontSize = MType.Body.fontSize * 0.88f,
                        color = if (message != null) MColors.Danger else MColors.Faint,
                    ),
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { plan() }) {
                Text("Send & plan", style = MType.Button.copy(color = if (busy) MColors.Faint else MColors.Accent))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (busy) { attempt++; job?.cancel(); busy = false } else onDismiss() }) {
                Text("Cancel", style = MType.Button.copy(color = MColors.Text))
            }
        },
    )
}
