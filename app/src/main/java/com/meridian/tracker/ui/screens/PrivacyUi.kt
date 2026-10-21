package com.meridian.tracker.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.Prefs
import com.meridian.tracker.data.PrivacyZone
import com.meridian.tracker.data.PrivacyZones
import com.meridian.tracker.ui.components.HorizontalHairline
import com.meridian.tracker.ui.components.SectionCard
import com.meridian.tracker.ui.components.Segmented
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import java.util.UUID
import kotlin.math.roundToInt

/** Places (home, work) whose surroundings are cut from the start and end of shared routes and GPX files. */
@Composable
fun PrivacyZonesCard() {
    val zones by Prefs.zones.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<PrivacyZone?>(null) }

    SectionCard(Modifier.fillMaxWidth()) {
        Column {
            zones.forEach { z ->
                LinkRow(z.name, "Hides ${z.radiusM.roundToInt()} m around it · tap to remove") { removing = z }
                HorizontalHairline()
            }
            LinkRow(
                "Add a privacy zone",
                if (zones.isEmpty()) "Hide your home or work from shared routes and exported files."
                else "${zones.size} of ${PrivacyZones.MAX_ZONES} used",
                badge = if (zones.size < PrivacyZones.MAX_ZONES) "ADD" else null,
                enabled = zones.size < PrivacyZones.MAX_ZONES,
            ) { adding = true }
        }
    }

    if (adding) AddZoneDialog(onDismiss = { adding = false })
    removing?.let { z ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = MColors.Surface2,
            title = { Text("Remove ${z.name}?", style = MType.Heading) },
            text = { Text("Routes you share or export will show everything near it again.", style = MType.Body) },
            confirmButton = {
                TextButton(onClick = { Prefs.removeZone(z.id); removing = null }) {
                    Text("Remove", style = MType.Button.copy(color = MColors.Accent))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("Keep", style = MType.Button.copy(color = MColors.Text)) }
            },
        )
    }
}

@SuppressLint("MissingPermission")
private fun currentLocation(context: android.content.Context, onResult: (Location?) -> Unit) {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!granted) return onResult(null)
    LocationServices.getFusedLocationProviderClient(context)
        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
        .addOnSuccessListener { onResult(it) }
        .addOnFailureListener { onResult(null) }
}

@Composable
private fun AddZoneDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("Home") }
    var radiusIndex by rememberSaveable { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Home is usually where an activity starts, so offer the latest route's first point too.
    val latestStart = remember {
        ActivityStore.activities.value.filter { it.points.isNotEmpty() }.maxByOrNull { it.startTimeMs }?.points?.firstOrNull()
    }

    fun save(lat: Double, lon: Double) {
        val zone = PrivacyZone(UUID.randomUUID().toString(), name, lat, lon, PrivacyZones.RADII_M[radiusIndex])
        if (Prefs.addZone(zone)) onDismiss() else error = "Couldn't add the zone."
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MColors.Surface2,
        title = { Text("New privacy zone", style = MType.Heading) },
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
                        value = name,
                        onValueChange = { if (it.length <= PrivacyZones.MAX_NAME) name = it },
                        textStyle = MType.Heading,
                        singleLine = true,
                        cursorBrush = SolidColor(MColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("RADIUS", style = MType.Label)
                Spacer(Modifier.height(8.dp))
                Segmented(
                    options = PrivacyZones.RADII_M.map { if (it >= 1000) "${(it / 1000).roundToInt()} km" else "${it.roundToInt()} m" },
                    selected = radiusIndex,
                    onSelect = { radiusIndex = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                latestStart?.let { start ->
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MColors.Bg)
                            .border(1.dp, MColors.Stroke, RoundedCornerShape(16.dp))
                            .clickable(enabled = !busy, role = Role.Button) { save(start.lat, start.lon) }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text("Use where my last activity started", style = MType.Heading.copy(fontSize = MType.Heading.fontSize * 0.92f))
                        Spacer(Modifier.height(2.dp))
                        Text("Works when you're not at the place right now.", style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        busy -> "Finding your location…"
                        error != null -> error!!
                        else -> "The zone is saved on this phone only."
                    },
                    style = MType.Body.copy(
                        fontSize = MType.Body.fontSize * 0.88f,
                        color = if (error != null) MColors.Danger else MColors.Faint,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    currentLocation(context) { loc ->
                        busy = false
                        if (loc == null) error = "Couldn't get your location. Try again outside."
                        else save(loc.latitude, loc.longitude)
                    }
                },
            ) { Text("Use my location", style = MType.Button.copy(color = MColors.Accent)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) }
        },
    )
}
