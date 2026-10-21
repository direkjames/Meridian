package com.meridian.tracker.ui.components

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meridian.tracker.tracking.Fix
import com.meridian.tracker.tracking.TrackPoint
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/** Dark basemap built on OpenStreetMap data (CARTO Dark Matter). Free, no API key. */
internal const val DARK_STYLE_URL = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"

private const val SRC_ROUTE = "route"
private const val SRC_START = "start"
private const val SRC_ME = "me"
private const val SRC_PLANNED = "planned"
private const val SRC_GHOST = "ghost"

enum class MapMode {
    /** Live recording: camera follows the athlete. */
    FOLLOW,
    /** Finished activity: camera frames the whole route. */
    FIT,
}

/**
 * The route on a real map. Viewed areas are cached by MapLibre, so familiar routes still
 * show offline. If the map can't load at all, it falls back to drawing the route on its own.
 */
@Composable
fun RouteMap(
    points: List<TrackPoint>,
    fix: Fix?,
    mode: MapMode,
    modifier: Modifier = Modifier,
    follow: Boolean = true,
    onUserPan: () -> Unit = {},
    controlsBottomPadding: Dp = 12.dp,
    controlsTopPadding: Dp = 12.dp,
    plannedRoute: List<Pair<Double, Double>>? = null,
    ghost: Pair<Double, Double>? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val panCallback by rememberUpdatedState(onUserPan)

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var failed by remember { mutableStateOf(false) }
    var hasCentered by remember { mutableStateOf(false) }

    val mapView = rememberMapView()

    LaunchedEffect(mapView) {
        mapView.addOnDidFailLoadingMapListener { failed = true }
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                attributionGravity = Gravity.BOTTOM or Gravity.START
                logoGravity = Gravity.BOTTOM or Gravity.START
                setAttributionTintColor(MColors.Dark.muted.toArgb())
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) panCallback()
            }
            m.setStyle(Style.Builder().fromUri(DARK_STYLE_URL)) { s ->
                addRouteLayers(s)
                style = s
            }
            map = m
        }
    }

    // Keep the map's logo and attribution visible above whatever panel covers the bottom.
    LaunchedEffect(map, controlsBottomPadding) {
        val m = map ?: return@LaunchedEffect
        val bottom = with(density) { controlsBottomPadding.roundToPx() }
        val side = with(density) { 12.dp.roundToPx() }
        m.uiSettings.setLogoMargins(side, 0, 0, bottom)
        m.uiSettings.setAttributionMargins(side + with(density) { 92.dp.roundToPx() }, 0, 0, bottom)
    }

    // Route line + start marker
    LaunchedEffect(style, points) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeFeatures(points))
        val first = points.firstOrNull()
        s.getSourceAs<GeoJsonSource>(SRC_START)?.setGeoJson(
            FeatureCollection.fromFeatures(
                if (first != null) listOf(Feature.fromGeometry(Point.fromLngLat(first.lon, first.lat))) else emptyList()
            )
        )
    }

    // Planned route (dashed) and the ghost runner
    LaunchedEffect(style, plannedRoute) {
        val s = style ?: return@LaunchedEffect
        val line = plannedRoute?.takeIf { it.size >= 2 }?.let { pts ->
            listOf(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.second, it.first) })))
        } ?: emptyList()
        s.getSourceAs<GeoJsonSource>(SRC_PLANNED)?.setGeoJson(FeatureCollection.fromFeatures(line))
    }
    LaunchedEffect(style, ghost) {
        val s = style ?: return@LaunchedEffect
        val features = ghost?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.second, it.first))) } ?: emptyList()
        s.getSourceAs<GeoJsonSource>(SRC_GHOST)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // "You are here" dot (live only)
    LaunchedEffect(style, fix, mode) {
        val s = style ?: return@LaunchedEffect
        val features = if (mode == MapMode.FOLLOW && fix != null) {
            listOf(Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat)))
        } else {
            emptyList()
        }
        s.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Camera
    LaunchedEffect(map, fix, follow, mode) {
        val m = map ?: return@LaunchedEffect
        if (mode != MapMode.FOLLOW || !follow || fix == null) return@LaunchedEffect
        val target = LatLng(fix.lat, fix.lon)
        if (!hasCentered) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 16.0))
            hasCentered = true
        } else {
            m.animateCamera(CameraUpdateFactory.newLatLng(target), 700)
        }
    }

    LaunchedEffect(map, points, mode) {
        val m = map ?: return@LaunchedEffect
        if (mode != MapMode.FIT || points.isEmpty()) return@LaunchedEffect
        val distinct = points.map { LatLng(it.lat, it.lon) }.distinct()
        if (distinct.size < 2) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(distinct.first(), 15.0))
        } else {
            val bounds = LatLngBounds.Builder().includes(distinct).build()
            val pad = with(density) { 56.dp.roundToPx() }
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad, pad, pad, pad + with(density) { controlsBottomPadding.roundToPx() }))
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (failed) {
            Box(Modifier.fillMaxSize().background(MColors.Bg)) {
                RouteCanvas(
                    points = points,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = controlsTopPadding, bottom = controlsBottomPadding),
                )
                Text(
                    "OFFLINE · MAP UNAVAILABLE",
                    style = MType.Label,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = controlsBottomPadding + 8.dp)
                        .clip(CircleShape)
                        .background(MColors.Bg.copy(alpha = 0.8f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** A MapView wired to the screen's lifecycle. Shared by every map in the app. */
@Composable
fun rememberMapView(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        val options = MapLibreMapOptions.createFromAttributes(context, null)
            .textureMode(true) // fades smoothly with the rest of the UI
            .foregroundLoadColor(MColors.Dark.bg.toArgb()) // no white flash while tiles load
        MapView(context, options).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

private fun addRouteLayers(style: Style) {
    // The map is always dark, so its lines and markers use the dark palette in every theme.
    val accent = MColors.Dark.accent.toArgb()
    val bg = MColors.Dark.bg.toArgb()
    val ink = MColors.Dark.text.toArgb()

    style.addSource(GeoJsonSource(SRC_ROUTE))
    style.addSource(GeoJsonSource(SRC_START))
    style.addSource(GeoJsonSource(SRC_ME))
    style.addSource(GeoJsonSource(SRC_PLANNED))
    style.addSource(GeoJsonSource(SRC_GHOST))

    style.addLayer(
        LineLayer("planned-line", SRC_PLANNED).withProperties(
            PropertyFactory.lineColor(ink),
            PropertyFactory.lineOpacity(0.55f),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineDasharray(arrayOf(1.2f, 1.4f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )

    style.addLayer(
        LineLayer("route-glow", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(accent),
            PropertyFactory.lineWidth(16f),
            PropertyFactory.lineOpacity(0.16f),
            PropertyFactory.lineBlur(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(
        LineLayer("route-line", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(accent),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(
        CircleLayer("start-dot", SRC_START).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(bg),
            PropertyFactory.circleStrokeColor(ink),
            PropertyFactory.circleStrokeWidth(3f),
        )
    )
    style.addLayer(
        CircleLayer("ghost-dot", SRC_GHOST).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(ink),
            PropertyFactory.circleOpacity(0.75f),
            PropertyFactory.circleStrokeColor(bg),
            PropertyFactory.circleStrokeWidth(3f),
        )
    )
    style.addLayer(
        CircleLayer("me-halo", SRC_ME).withProperties(
            PropertyFactory.circleRadius(18f),
            PropertyFactory.circleColor(accent),
            PropertyFactory.circleOpacity(0.22f),
        )
    )
    style.addLayer(
        CircleLayer("me-dot", SRC_ME).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(accent),
            PropertyFactory.circleStrokeColor(bg),
            PropertyFactory.circleStrokeWidth(3f),
        )
    )
}

private fun routeFeatures(points: List<TrackPoint>): FeatureCollection {
    val lines = points
        .groupBy { it.segment }
        .values
        .filter { it.size >= 2 }
        .map { seg -> seg.map { Point.fromLngLat(it.lon, it.lat) } }
    if (lines.isEmpty()) return FeatureCollection.fromFeatures(emptyList<Feature>())
    return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(MultiLineString.fromLngLats(lines))))
}
