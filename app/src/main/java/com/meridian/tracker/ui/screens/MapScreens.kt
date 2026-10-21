package com.meridian.tracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.Badges
import com.meridian.tracker.data.ExtrasStore
import com.meridian.tracker.data.PlannedRoute
import com.meridian.tracker.tracking.Geo
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackingManager
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.DARK_STYLE_URL
import com.meridian.tracker.ui.components.HorizontalHairline
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.PillButton
import com.meridian.tracker.ui.components.SectionCard
import com.meridian.tracker.ui.components.SportFilterRow
import com.meridian.tracker.ui.components.rememberMapView
import com.meridian.tracker.ui.shortDateLabel
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** A map style with our own sources added, ready for drawing. */
@Composable
private fun rememberStyledMap(onReady: (MapLibreMap, Style) -> Unit): Pair<MapLibreMap?, Style?> {
    val mapView = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.setStyle(Style.Builder().fromUri(DARK_STYLE_URL)) { s ->
                onReady(m, s)
                style = s
            }
            map = m
        }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    return map to style
}

// =================================================================================================
// Personal heatmap
// =================================================================================================

@Composable
fun HeatmapScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val all by ActivityStore.activities.collectAsStateWithLifecycle()
    var sportName by rememberSaveable { mutableStateOf<String?>(null) }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    val sport = sportName?.let { runCatching { Sport.valueOf(it) }.getOrNull() }
    val zone = ZoneId.systemDefault()
    val years = remember(all) { all.map { Instant.ofEpochMilli(it.startTimeMs).atZone(zone).year }.distinct().sortedDescending() }
    val shown = remember(all, sport, year) {
        all.filter { r ->
            (sport == null || r.sport == sport) &&
                (year == null || Instant.ofEpochMilli(r.startTimeMs).atZone(zone).year == year) &&
                r.points.size >= 2
        }
    }
    val features by produceState<FeatureCollection?>(initialValue = null, shown) {
        value = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(
                shown.flatMap { r ->
                    val step = maxOf(1, r.points.size / 300)
                    r.points.filterIndexed { i, _ -> i % step == 0 || i == r.points.lastIndex }
                        .groupBy { it.segment }.values
                        .filter { it.size >= 2 }
                        .map { seg -> Feature.fromGeometry(LineString.fromLngLats(seg.map { Point.fromLngLat(it.lon, it.lat) })) }
                }
            )
        }
    }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().background(MColors.Bg)) {
        val (map, style) = rememberStyledMap { _, s ->
            s.addSource(GeoJsonSource("heat"))
            val accent = MColors.Dark.accent.toArgb()
            s.addLayer(
                LineLayer("heat-glow", "heat").withProperties(
                    PropertyFactory.lineColor(accent),
                    PropertyFactory.lineWidth(9f),
                    PropertyFactory.lineOpacity(0.06f),
                    PropertyFactory.lineBlur(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            )
            s.addLayer(
                LineLayer("heat-line", "heat").withProperties(
                    PropertyFactory.lineColor(accent),
                    PropertyFactory.lineWidth(2.5f),
                    PropertyFactory.lineOpacity(0.32f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            )
        }
        LaunchedEffect(style, features) {
            val s = style ?: return@LaunchedEffect
            features?.let { s.getSourceAs<GeoJsonSource>("heat")?.setGeoJson(it) }
        }
        LaunchedEffect(map, shown) {
            val m = map ?: return@LaunchedEffect
            val pts = shown.flatMap { r -> r.points.filterIndexed { i, _ -> i % 25 == 0 } + r.points.last() }
            if (pts.size >= 2) {
                val bounds = LatLngBounds.from(pts.maxOf { it.lat }, pts.maxOf { it.lon }, pts.minOf { it.lat }, pts.minOf { it.lon })
                val pad = with(density) { 48.dp.roundToPx() }
                runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad, pad * 3, pad, pad * 4)) }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(MColors.Bg, MColors.Bg.copy(alpha = 0f))))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(MIcons.Back, "Back", onClick = onBack)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Your heatmap", style = MType.Title)
                    Text(
                        "${shown.size} ACTIVITIES · ${Format.dist1(shown.sumOf { it.distanceM })} ${Format.distUnit.uppercase()}",
                        style = MType.Label.copy(color = MColors.Accent),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SportFilterRow(sport, onSelect = { sportName = it?.name })
            if (years.size > 1) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YearChip("All years", year == null) { year = null }
                    years.forEach { y -> YearChip("$y", year == y) { year = y } }
                }
            }
        }
        if (all.isEmpty()) {
            Text(
                "Your routes will glow here once you've recorded or imported a few.",
                style = MType.Body,
                modifier = Modifier.align(Alignment.Center).padding(40.dp),
            )
        }
    }
}

@Composable
private fun YearChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label.uppercase(),
        style = MType.Label.copy(color = if (selected) MColors.OnAccent else MColors.Text),
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MColors.Accent else MColors.Bg.copy(alpha = 0.8f))
            .border(1.dp, if (selected) MColors.Accent else MColors.Stroke, CircleShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

// =================================================================================================
// Route builder
// =================================================================================================

@Composable
fun RouteBuilderScreen(onBack: () -> Unit, onFollow: () -> Unit) {
    BackHandler(onBack = onBack)
    val routes by ExtrasStore.routes.collectAsStateWithLifecycle()
    val fix = TrackingManager.state.collectAsStateWithLifecycle().value.lastFix
    val points = remember { mutableStateListOf<Pair<Double, Double>>() }
    var name by rememberSaveable { mutableStateOf("") }
    var savedNote by remember { mutableStateOf<String?>(null) }
    var generating by rememberSaveable { mutableStateOf(false) }
    var planNote by remember { mutableStateOf<String?>(null) }
    var fitRequest by remember { mutableStateOf(0) }
    val distance = points.zipWithNext().sumOf { (a, b) -> Geo.distanceM(a.first, a.second, b.first, b.second) }

    if (generating) {
        GenerateRouteDialog(
            onDismiss = { generating = false },
            onRoute = { r ->
                points.clear()
                points.addAll(r.points)
                if (name.isEmpty()) name = r.name
                planNote = r.summary
                savedNote = null
                generating = false
                fitRequest++
            },
        )
    }

    Column(Modifier.fillMaxSize().background(MColors.Bg)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val (map, style) = rememberStyledMap { m, s ->
                s.addSource(GeoJsonSource("draft-line"))
                s.addSource(GeoJsonSource("draft-pts"))
                s.addLayer(
                    LineLayer("draft-line", "draft-line").withProperties(
                        PropertyFactory.lineColor(MColors.Dark.accent.toArgb()),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    )
                )
                s.addLayer(
                    CircleLayer("draft-pts", "draft-pts").withProperties(
                        PropertyFactory.circleRadius(5f),
                        PropertyFactory.circleColor(MColors.Dark.bg.toArgb()),
                        PropertyFactory.circleStrokeColor(MColors.Dark.accent.toArgb()),
                        PropertyFactory.circleStrokeWidth(2.5f),
                    )
                )
                fix?.let { m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lon), 15.0)) }
                m.addOnMapClickListener { ll ->
                    points.add(ll.latitude to ll.longitude)
                    true
                }
            }
            // After a route is planned, zoom the map to show all of it.
            LaunchedEffect(map, fitRequest) {
                val m = map ?: return@LaunchedEffect
                if (fitRequest == 0 || points.size < 2) return@LaunchedEffect
                val bounds = LatLngBounds.Builder()
                    .includes(points.map { LatLng(it.first, it.second) })
                    .build()
                m.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120), 600)
            }
            LaunchedEffect(style, points.toList()) {
                val s = style ?: return@LaunchedEffect
                val line = if (points.size >= 2) {
                    listOf(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) })))
                } else {
                    emptyList()
                }
                s.getSourceAs<GeoJsonSource>("draft-line")?.setGeoJson(FeatureCollection.fromFeatures(line))
                s.getSourceAs<GeoJsonSource>("draft-pts")?.setGeoJson(
                    FeatureCollection.fromFeatures(points.map { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)) })
                )
            }
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
                    if (points.isEmpty()) "TAP THE MAP TO START YOUR ROUTE" else "TAP TO ADD POINTS",
                    style = MType.Label.copy(color = MColors.Text),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MColors.Bg.copy(alpha = 0.85f))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MColors.Surface)
                .navigationBarsPadding()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.km(distance), style = MType.BigNumber.copy(fontSize = MType.BigNumber.fontSize * 1.3f))
                Spacer(Modifier.width(6.dp))
                Text(Format.distUnit, style = MType.Unit, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.weight(1f))
                Text("${points.size} POINTS", style = MType.Label)
            }
            Text("Straight lines between taps. Add more points along curves for accuracy.", style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.85f))
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(CircleShape)
                    .border(1.dp, MColors.Accent.copy(alpha = 0.6f), CircleShape)
                    .clickable(role = Role.Button) { generating = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("PLAN A ROUTE FROM A DISTANCE", style = MType.Label.copy(color = MColors.Accent), maxLines = 1)
            }
            planNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it.uppercase(), style = MType.Label.copy(color = MColors.Accent))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction("Undo", points.isNotEmpty(), Modifier.weight(1f)) { points.removeAt(points.lastIndex) }
                SmallAction("Loop back", points.size >= 2, Modifier.weight(1f)) { points.add(points.first()) }
                SmallAction("Out & back", points.size >= 2, Modifier.weight(1f)) { points.addAll(points.dropLast(1).reversed()) }
                SmallAction("Clear", points.isNotEmpty(), Modifier.weight(1f)) { points.clear(); planNote = null }
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MColors.Bg)
                    .border(1.dp, MColors.Stroke, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                if (name.isEmpty()) Text("Name this route", style = MType.Body.copy(color = MColors.Faint))
                BasicTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    textStyle = MType.Body.copy(color = MColors.Text),
                    singleLine = true,
                    cursorBrush = SolidColor(MColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            PillButton(
                "Save route",
                icon = MIcons.Check,
                filled = points.size >= 2,
                onClick = {
                    if (points.size >= 2) {
                        ExtrasStore.saveRoute(
                            PlannedRoute(
                                UUID.randomUUID().toString(),
                                name.trim().ifEmpty { "Route ${routes.size + 1}" },
                                points.toList(),
                                System.currentTimeMillis(),
                            )
                        )
                        savedNote = "Saved. Pick it from + ROUTE on the Record screen."
                        points.clear()
                        name = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            savedNote?.let {
                Spacer(Modifier.height(8.dp))
                Text(it.uppercase(), style = MType.Label.copy(color = MColors.Accent))
            }

            if (routes.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("SAVED ROUTES", style = MType.Label)
                Spacer(Modifier.height(8.dp))
                SectionCard(Modifier.fillMaxWidth()) {
                    Column {
                        routes.forEachIndexed { i, r ->
                            if (i > 0) HorizontalHairline()
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MType.Body.copy(color = MColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${Format.km(r.distanceM)} ${Format.distUnit.uppercase()} · ${shortDateLabel(r.createdMs).uppercase()}", style = MType.Label)
                                }
                                Text(
                                    "FOLLOW",
                                    style = MType.Label.copy(color = MColors.Accent),
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable(role = Role.Button) { TrackingManager.setRoute(r); onFollow() }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                                CircleIconButton(MIcons.Trash, "Delete ${r.name}", onClick = { ExtrasStore.deleteRoute(r.id) }, size = 36.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(MColors.Surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = MType.Label.copy(color = if (enabled) MColors.Text else MColors.Faint), maxLines = 1)
    }
}

// =================================================================================================
// Badges
// =================================================================================================

@Composable
fun BadgesScreen(onBack: () -> Unit, onOpenActivity: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val all by ActivityStore.activities.collectAsStateWithLifecycle()
    val planDone by ExtrasStore.planCompletedMs.collectAsStateWithLifecycle()
    val earned by produceState(initialValue = emptyMap<String, com.meridian.tracker.data.EarnedBadge>(), all, planDone) {
        value = withContext(Dispatchers.Default) { Badges.earned(all, planCompletedMs = planDone).associateBy { it.def.id } }
    }
    LaunchedEffect(earned) { if (earned.isNotEmpty()) ExtrasStore.markBadgesSeen(earned.keys) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MColors.Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(MIcons.Back, "Back", onClick = onBack)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Badges", style = MType.Title)
                Text("${earned.size} OF ${Badges.ALL.size} EARNED", style = MType.Label.copy(color = MColors.Accent))
            }
        }
        Badges.ALL.groupBy { it.group }.forEach { (group, defs) ->
            Text(group.uppercase(), style = MType.Label, modifier = Modifier.padding(start = 8.dp, top = 26.dp, bottom = 10.dp))
            defs.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { def ->
                        val e = earned[def.id]
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (e != null) MColors.Surface else MColors.Bg)
                                .border(1.dp, if (e != null) MColors.Accent.copy(alpha = 0.5f) else MColors.Hairline, RoundedCornerShape(20.dp))
                                .clickable(enabled = e?.recordId != null, role = Role.Button) { e?.recordId?.let(onOpenActivity) }
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(if (e != null) MColors.Accent else MColors.Surface2),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (e != null) MIcons.Trophy else MIcons.Lock,
                                    contentDescription = null,
                                    tint = if (e != null) MColors.OnAccent else MColors.Faint,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(def.title, style = MType.Body.copy(color = if (e != null) MColors.Text else MColors.Muted, fontSize = MType.Body.fontSize * 0.85f), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Text(
                                (e?.let { shortDateLabel(it.dateMs) } ?: def.description).uppercase(),
                                style = MType.Label.copy(color = if (e != null) MColors.Accent else MColors.Faint, fontSize = MType.Label.fontSize * 0.8f),
                                maxLines = 3,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}
