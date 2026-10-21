package com.meridian.tracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.OfflineArea
import com.meridian.tracker.data.OfflineMaps
import com.meridian.tracker.data.TileMath
import com.meridian.tracker.tracking.TrackingManager
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.DARK_STYLE_URL
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.PillButton
import com.meridian.tracker.ui.components.rememberMapView
import com.meridian.tracker.ui.shortDateLabel
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.util.Locale

/**
 * Pick an area on the map and save it for use without signal. Recording never needs this:
 * it only makes the map show streets when you're offline.
 */
@Composable
fun OfflineMapsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val areas by OfflineMaps.areas.collectAsStateWithLifecycle()
    val fix = TrackingManager.state.collectAsStateWithLifecycle().value.lastFix
    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) { OfflineMaps.ensureLoaded(context) }

    val mapView = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var bounds by remember { mutableStateOf<LatLngBounds?>(null) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.setStyle(Style.Builder().fromUri(DARK_STYLE_URL))
            fix?.let { m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lon), 12.5)) }
            m.addOnCameraIdleListener { bounds = m.projection.visibleRegion.latLngBounds }
            map = m
        }
    }

    val tiles = bounds?.let {
        TileMath.tileCount(it.latitudeNorth, it.latitudeSouth, it.longitudeEast, it.longitudeWest, OfflineMaps.MIN_ZOOM, OfflineMaps.MAX_ZOOM)
    }
    val tooBig = tiles != null && tiles > OfflineMaps.MAX_TILES

    Column(
        Modifier
            .fillMaxSize()
            .background(MColors.Bg)
    ) {
        // Map with framing corners
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            FrameCorners(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 110.dp, bottom = 18.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(MIcons.Back, "Back", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Offline maps",
                    style = MType.Heading,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MColors.Bg.copy(alpha = 0.8f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // Download panel
        Column(
            Modifier
                .fillMaxWidth()
                .background(MColors.Surface)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
        ) {
            Text(
                "Move the map to frame the area you want. Streets will show there even with no signal.",
                style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.92f),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        tiles == null -> "MOVE THE MAP TO START"
                        tooBig -> "AREA TOO LARGE · ZOOM IN"
                        else -> "≈ ${formatBytes(TileMath.estimatedBytes(tiles))} · $tiles TILES"
                    },
                    style = MType.Label.copy(color = if (tooBig) MColors.Danger else MColors.Muted),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            PillButton(
                if (tooBig) "Zoom in to download" else "Download this area",
                icon = MIcons.Download,
                filled = !tooBig && bounds != null,
                onClick = {
                    val b = bounds
                    if (b != null && !tooBig) {
                        OfflineMaps.download(context, b, "Area ${areas.size + 1}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (areas.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("SAVED AREAS", style = MType.Label)
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(areas, key = { it.id }) { area -> AreaRow(area, onDelete = { OfflineMaps.delete(area.id) }) }
                }
            }
        }
    }
}

@Composable
private fun AreaRow(area: OfflineArea, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MColors.Bg)
            .border(1.dp, MColors.Hairline, shape)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MIcons.Map, contentDescription = null, tint = if (area.complete) MColors.Accent else MColors.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(area.name, style = MType.Heading.copy(fontSize = MType.Heading.fontSize * 0.88f))
            val status = when {
                area.error != null -> area.error
                area.complete -> "READY · ${formatBytes(area.sizeBytes)}" +
                    if (area.createdMs > 0) " · ${shortDateLabel(area.createdMs).uppercase()}" else ""
                else -> "DOWNLOADING ${(area.progress * 100).toInt()}%"
            }
            Text(status.uppercase(), style = MType.Label.copy(color = if (area.error != null) MColors.Danger else MColors.Muted))
            if (!area.complete && area.error == null) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MColors.Surface2)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(area.progress.coerceIn(0.02f, 1f))
                            .height(4.dp)
                            .background(MColors.Accent)
                    )
                }
            }
        }
        CircleIconButton(MIcons.Trash, "Delete ${area.name}", onClick = onDelete, size = 40.dp)
    }
}

/** Four corner marks that frame the download area. */
@Composable
private fun FrameCorners(modifier: Modifier) {
    Canvas(modifier) {
        val len = 28.dp.toPx()
        val w = 3.dp.toPx()
        val c = MColors.Accent
        val r = size.width
        val b = size.height
        listOf(
            Offset(0f, 0f) to Offset(len, 0f), Offset(0f, 0f) to Offset(0f, len),
            Offset(r, 0f) to Offset(r - len, 0f), Offset(r, 0f) to Offset(r, len),
            Offset(0f, b) to Offset(len, b), Offset(0f, b) to Offset(0f, b - len),
            Offset(r, b) to Offset(r - len, b), Offset(r, b) to Offset(r, b - len),
        ).forEach { (a, z) -> drawLine(c, a, z, w, cap = StrokeCap.Round) }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
}
