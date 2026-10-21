package com.meridian.tracker.ui.screens

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.Prefs
import com.meridian.tracker.data.ExtrasStore
import com.meridian.tracker.data.PlannedRoute
import com.meridian.tracker.tracking.Guide
import com.meridian.tracker.tracking.GuideLive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.meridian.tracker.tracking.GpsQuality
import com.meridian.tracker.tracking.HeartRateMonitor
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.StepPermission
import com.meridian.tracker.data.HeartRate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.vector.ImageVector
import com.meridian.tracker.tracking.RecordStatus
import com.meridian.tracker.tracking.TrackingManager
import com.meridian.tracker.tracking.TrackingState
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.GpsPill
import com.meridian.tracker.ui.components.HoldButton
import com.meridian.tracker.ui.components.HorizontalHairline
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.MapMode
import com.meridian.tracker.ui.components.PillButton
import com.meridian.tracker.ui.components.RouteMap
import com.meridian.tracker.ui.components.SportSelector
import com.meridian.tracker.ui.components.StatCell
import com.meridian.tracker.ui.components.VerticalHairline
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@Composable
fun RecordScreen(onOpenSettings: () -> Unit, onOpenRouteBuilder: () -> Unit, onOpenSensors: () -> Unit = onOpenSettings) {
    val state by TrackingManager.state.collectAsStateWithLifecycle()
    val guide by TrackingManager.guide.collectAsStateWithLifecycle()
    val live by TrackingManager.live.collectAsStateWithLifecycle()
    val savedRoutes by ExtrasStore.routes.collectAsStateWithLifecycle()
    var pickingRoute by remember { mutableStateOf(false) }
    val activities by ActivityStore.activities.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    val hr by HeartRateMonitor.state.collectAsStateWithLifecycle()
    val countSteps by Prefs.countSteps.collectAsStateWithLifecycle()
    val askedSteps by Prefs.askedSteps.collectAsStateWithLifecycle()

    // Live heart rate on this screen too, so you can check the strap before you start.
    DisposableEffect(Unit) {
        HeartRateMonitor.acquire(context, "record-screen")
        onDispose { HeartRateMonitor.release("record-screen") }
    }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    var follow by rememberSaveable { mutableStateOf(true) }
    var locked by rememberSaveable { mutableStateOf(false) }
    // Drag the handle down (or tap it) for a smaller panel and more map; up to bring details back.
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var countdown by rememberSaveable { mutableStateOf<Int?>(null) }
    val countdownEnabled by Prefs.countdown.collectAsStateWithLifecycle()
    val keepScreenOn by Prefs.keepScreenOn.collectAsStateWithLifecycle()
    val view = LocalView.current
    val keepAwake = keepScreenOn && state.status != RecordStatus.IDLE
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(countdown) {
        val n = countdown ?: return@LaunchedEffect
        if (n <= 0) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(350) // let "GO" land
            TrackingManager.startRecording(context)
            countdown = null
        } else {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(900)
            countdown = n - 1
        }
    }
    fun beginStart() {
        follow = true
        if (countdownEnabled) countdown = 3 else TrackingManager.startRecording(context)
    }
    // Ask for "Physical activity" once, the first time you start a run or walk, so steps can be counted.
    val stepAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { beginStart() }
    fun onStartPressed() {
        val perm = StepPermission.permission
        val wantSteps = state.sport != Sport.RIDE && countSteps && !askedSteps && perm != null &&
            !StepPermission.granted(context) && StepPermission.hasSensor(context)
        if (wantSteps) {
            Prefs.setAskedSteps()
            stepAsk.launch(perm)
        } else {
            beginStart()
        }
    }

    var panelHeightPx by remember { mutableIntStateOf(0) }
    val panelHeight = with(density) { panelHeightPx.toDp() }

    LaunchedEffect(state.status) {
        if (state.status != RecordStatus.RECORDING) locked = false
    }

    GpsWarmup(enabled = state.status == RecordStatus.IDLE)

    val quality = state.gpsQuality(now)

    Box(
        Modifier
            .fillMaxSize()
            .background(MColors.Bg)
    ) {
        RouteMap(
            points = state.points,
            fix = state.lastFix,
            mode = MapMode.FOLLOW,
            follow = follow,
            onUserPan = { follow = false },
            controlsBottomPadding = panelHeight + 8.dp,
            controlsTopPadding = 96.dp,
            plannedRoute = guide.route?.points,
            ghost = live.ghostPos,
            modifier = Modifier.fillMaxSize(),
        )

        // Top scrim + bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Brush.verticalGradient(listOf(MColors.Bg, MColors.Bg.copy(alpha = 0f))))
        )
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "MERIDIAN",
                style = MType.Wordmark,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            if (state.status == RecordStatus.IDLE) {
                CircleIconButton(MIcons.Settings, "Settings", onClick = onOpenSettings, size = 40.dp)
                Spacer(Modifier.width(8.dp))
            }
            GpsPill(quality, state.lastFix?.accuracy)
        }

        // Recenter
        AnimatedVisibility(
            visible = !follow,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = panelHeight + 16.dp),
        ) {
            CircleIconButton(MIcons.Locate, "Center on my location", onClick = { follow = true })
        }

        // Bottom panel
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { panelHeightPx = it.height }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MColors.Surface)
                .border(1.dp, MColors.Hairline, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .navigationBarsPadding()
                .animateContentSize(tween(220))
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        ) {
            PanelHandle(collapsed = collapsed, onToggle = { collapsed = it })

            AnimatedContent(
                targetState = state.status == RecordStatus.IDLE,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
                label = "panel",
            ) { idle ->
                if (idle) {
                    IdlePanel(
                        state = state,
                        collapsed = collapsed,
                        quality = quality,
                        weekDistanceM = activities.filter { it.startTimeMs >= startOfWeekMs() }.sumOf { it.distanceM },
                        weekCount = activities.count { it.startTimeMs >= startOfWeekMs() },
                        guide = guide,
                        hr = hr,
                        onOpenSensors = onOpenSensors,
                        onPickRoute = { pickingRoute = true },
                        onStart = { onStartPressed() },
                    )
                } else {
                    LivePanel(
                        state = state,
                        collapsed = collapsed,
                        guide = guide,
                        live = live,
                        now = now,
                        locked = locked,
                        sensorPaired = hr.device != null,
                        onLock = { locked = true },
                    )
                }
            }
        }

        if (locked) LockOverlay(onUnlock = { locked = false })

        if (pickingRoute) {
            RoutePicker(
                routes = savedRoutes,
                onPick = { TrackingManager.setRoute(it); pickingRoute = false },
                onNew = { pickingRoute = false; onOpenRouteBuilder() },
                onDismiss = { pickingRoute = false },
            )
        }

        countdown?.let { n ->
            CountdownOverlay(
                value = n,
                sportLabel = state.sport.label,
                onSkip = { countdown = 0 },
                onCancel = { countdown = null },
            )
        }
    }
}

// ---- Panel handle ------------------------------------------------------------------------------

/** The grabber: tap it, or drag it down / up, to make the panel smaller or bigger. */
@Composable
private fun PanelHandle(collapsed: Boolean, onToggle: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(collapsed)
    var drag by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = if (collapsed) "Show more" else "Show less",
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggle(!collapsed)
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        val threshold = 24.dp.toPx()
                        if (drag > threshold && !current) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggle(true)
                        } else if (drag < -threshold && current) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggle(false)
                        }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        drag += amount
                    },
                )
            }
            .semantics { stateDescription = if (collapsed) "Compact" else "Expanded" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 4.dp)
                .clip(CircleShape)
                .background(if (collapsed) MColors.Accent.copy(alpha = 0.7f) else MColors.Stroke)
        )
    }
}

// ---- Idle --------------------------------------------------------------------------------------

@Composable
private fun IdlePanel(
    state: TrackingState,
    collapsed: Boolean,
    quality: GpsQuality,
    weekDistanceM: Double,
    weekCount: Int,
    guide: Guide,
    hr: HeartRateMonitor.HrState,
    onOpenSensors: () -> Unit,
    onPickRoute: () -> Unit,
    onStart: () -> Unit,
) {
    val ready = quality == GpsQuality.GOOD || quality == GpsQuality.STRONG
    Column(Modifier.fillMaxWidth()) {
        SportSelector(state.sport, onSelect = TrackingManager::setSport, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        // Status on the left, START on the right: everything within thumb reach, no scrolling.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (quality) {
                        GpsQuality.SEARCHING -> "FINDING GPS"
                        GpsQuality.WEAK -> "WEAK GPS"
                        else -> "GPS LOCKED"
                    },
                    style = MType.Label.copy(color = if (ready) MColors.Accent else MColors.Danger),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    when (quality) {
                        GpsQuality.SEARCHING -> "Step outside for a faster lock."
                        GpsQuality.WEAK -> "Recording tidies up once it improves."
                        else -> "You're ready to go."
                    },
                    style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.9f, lineHeight = MType.Body.lineHeight * 0.9f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text("THIS WEEK", style = MType.Label.copy(color = MColors.Faint))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Format.km(weekDistanceM), style = MType.Number)
                    Text(" ${Format.distUnit}", style = MType.Unit)
                    Text("  ·  $weekCount", style = MType.Number.copy(color = MColors.Muted))
                    Text(if (weekCount == 1) " activity" else " activities", style = MType.Unit, maxLines = 1)
                }
            }
            Spacer(Modifier.width(12.dp))
            StartButton(ready = ready, onStart = onStart)
        }
        if (!collapsed) {
            Spacer(Modifier.height(10.dp))
            GuideChips(guide, onPickRoute)
            if (hr.device != null) {
                Spacer(Modifier.height(8.dp))
                HeartChip(hr, onClick = onOpenSensors)
            }
        }
    }
}

@Composable
private fun StartButton(ready: Boolean, onStart: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val pulse = rememberInfiniteTransition(label = "start")
    val t by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
        label = "ring",
    )
    Box(
        Modifier
            .size(104.dp)
            .drawBehind {
                if (ready) {
                    val base = size.minDimension / 2 * 0.76f
                    drawCircle(
                        color = MColors.Accent.copy(alpha = 0.35f * (1f - t)),
                        radius = base + (size.minDimension / 2 - base) * t,
                        style = Stroke(2.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MColors.Accent)
                .clickable(role = Role.Button, onClickLabel = "Start recording") {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStart()
                }
                .semantics { contentDescription = "Start recording" },
            contentAlignment = Alignment.Center,
        ) {
            Text("START", style = MType.Button.copy(color = MColors.OnAccent, letterSpacing = MType.Wordmark.letterSpacing * 0.6f))
        }
    }
}

// ---- Recording / paused -------------------------------------------------------------------------

@Composable
private fun LivePanel(
    state: TrackingState,
    collapsed: Boolean,
    guide: Guide,
    live: GuideLive,
    now: Long,
    locked: Boolean,
    sensorPaired: Boolean,
    onLock: () -> Unit,
) {
    val paused = state.status == RecordStatus.PAUSED
    val avgPace = if (state.distanceM >= 50 && state.movingMs > 0) {
        (state.movingMs / 1000.0) / (state.distanceM / 1000.0)
    } else {
        null
    }
    val avgSpeed = if (state.movingMs > 0) state.distanceM / (state.movingMs / 1000.0) else 0.0
    val hero = MType.Hero.copy(fontSize = 68.sp, lineHeight = 70.sp, letterSpacing = (-2.5).sp)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(paused = paused, sportLabel = state.sport.label)
            Spacer(Modifier.weight(1f))
            if (!paused && !locked) {
                CircleIconButton(MIcons.Lock, "Lock screen", onClick = onLock, size = 40.dp)
            } else {
                Spacer(Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(4.dp))

        // Distance with the pause button beside it, so the big control sits right under your thumb.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(
                    Format.km(state.distanceM),
                    style = hero.copy(color = if (paused) MColors.Muted else MColors.Text),
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.width(6.dp))
                Text(Format.distUnit, style = MType.Unit.copy(fontSize = MType.Unit.fontSize * 1.2f), modifier = Modifier.padding(bottom = 10.dp))
            }
            if (!paused && !locked) {
                Spacer(Modifier.width(8.dp))
                PauseButton(onPause = TrackingManager::pause)
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalHairline()
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = 12.dp),
        ) {
            StatCell("Time", Format.duration(state.elapsedMs(now)), null, Modifier.weight(1f))
            VerticalHairline(Modifier.padding(horizontal = 10.dp))
            if (state.sport.showsSpeed) {
                StatCell("Speed", Format.speedKmh(state.currentSpeed), Format.speedUnit, Modifier.weight(1f))
            } else {
                StatCell("Pace", Format.paceFromSpeed(state.currentSpeed), Format.paceUnit, Modifier.weight(1f))
            }
            VerticalHairline(Modifier.padding(horizontal = 10.dp))
            StatCell("Climb", Format.meters(state.elevationGainM), "m", Modifier.weight(1f))
        }
        if (!collapsed) {
            HorizontalHairline()
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    if (state.sport.showsSpeed) "AVG ${Format.speedKmh(avgSpeed)} ${Format.speedUnit.uppercase()}" else "AVG ${Format.pace(avgPace)} ${Format.paceUnit.uppercase()}",
                    style = MType.Label,
                    modifier = Modifier.weight(1f),
                )
                Text("MOVING ${Format.duration(state.movingMs)}", style = MType.Label)
            }
            BodyRow(state, sensorPaired)
        }
        // The route / ghost / target guide stays visible even when compact: it's what you follow.
        LiveGuide(state, guide, live)

        if (paused && !locked) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PillButton(
                    "Resume",
                    onClick = TrackingManager::resume,
                    icon = MIcons.Play,
                    modifier = Modifier.weight(1f),
                    height = 58.dp,
                )
                HoldButton(
                    "Hold to finish",
                    onConfirm = TrackingManager::finish,
                    icon = MIcons.Flag,
                    modifier = Modifier.weight(1f),
                    height = 58.dp,
                )
            }
        }
        // Room for the lock screen's "Hold to unlock" button, so it never covers your stats.
        if (locked) Spacer(Modifier.height(88.dp))
    }
}

// ---- Heart rate and steps ----------------------------------------------------------------------

@Composable
private fun BodyRow(state: TrackingState, sensorPaired: Boolean) {
    val bpm = state.heartRate
    val steps = state.steps
    if (bpm == null && steps == null && !sensorPaired) return
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (bpm != null) {
            MetricPill(MIcons.HeartFilled, "$bpm", "BPM · Z${HeartRate.zoneOf(bpm)}", highlight = true)
        } else if (sensorPaired) {
            MetricPill(MIcons.Heart, "–", "BPM", highlight = false)
        }
        if (steps != null) {
            val cadence = state.cadence?.takeIf { it > 0 }
            MetricPill(MIcons.Steps, Format.count(steps), if (cadence != null) "STEPS · $cadence SPM" else "STEPS", highlight = false)
        }
    }
}

@Composable
private fun MetricPill(icon: ImageVector, value: String, unit: String, highlight: Boolean) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (highlight) MColors.Accent.copy(alpha = 0.12f) else MColors.Surface2)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(7.dp))
        Text(value, style = MType.Number.copy(fontSize = MType.Number.fontSize * 0.9f))
        Spacer(Modifier.width(5.dp))
        Text(unit, style = MType.Label.copy(color = if (highlight) MColors.Accent else MColors.Muted), maxLines = 1)
    }
}

@Composable
private fun HeartChip(hr: HeartRateMonitor.HrState, onClick: () -> Unit) {
    val name = hr.device?.name?.uppercase() ?: ""
    val bpm = hr.bpm
    val live = hr.status == HeartRateMonitor.Status.CONNECTED && bpm != null
    val label = when {
        live -> "$bpm BPM · $name"
        hr.status == HeartRateMonitor.Status.BLUETOOTH_OFF -> "BLUETOOTH OFF · $name"
        hr.status == HeartRateMonitor.Status.NO_PERMISSION -> "ALLOW NEARBY DEVICES · $name"
        hr.status == HeartRateMonitor.Status.NO_SIGNAL -> "NO HEART RATE · $name"
        else -> "CONNECTING · $name"
    }
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (live) MColors.Accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (live) MColors.Accent.copy(alpha = 0.6f) else MColors.Stroke, CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Heart-rate sensor settings", onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (live) MIcons.HeartFilled else MIcons.Heart, contentDescription = null, tint = if (live) MColors.Accent else MColors.Muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MType.Label.copy(color = if (live) MColors.Accent else MColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PauseButton(onPause: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MColors.Accent)
            .clickable(role = Role.Button, onClickLabel = "Pause") {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onPause()
            }
            .semantics { contentDescription = "Pause" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MIcons.Pause, contentDescription = null, tint = MColors.OnAccent, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun StatusChip(paused: Boolean, sportLabel: String) {
    val blink = rememberInfiniteTransition(label = "rec")
    val alpha by blink.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "recAlpha",
    )
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (paused) MColors.Surface2 else MColors.Accent.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (paused) MColors.Muted else MColors.Accent.copy(alpha = alpha))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (paused) "PAUSED" else "RECORDING · ${sportLabel.uppercase()}",
            style = MType.Label.copy(color = if (paused) MColors.Muted else MColors.Accent),
        )
    }
}

// ---- Guides: route, ghost and today's target --------------------------------------------------

@Composable
private fun GuideChips(guide: Guide, onPickRoute: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GuideChip(
            label = guide.route?.let { if (guide.ghost != null) "GHOST · ${it.name}" else "ROUTE · ${it.name}" } ?: "+ ROUTE",
            active = guide.route != null,
            onClick = onPickRoute,
            onClear = if (guide.route != null) ({ TrackingManager.setRoute(null) }) else null,
            modifier = Modifier.weight(1f, fill = false),
        )
        guide.target?.let { t ->
            val amount = t.distanceM?.let { "${Format.km(it)} ${Format.distUnit}" } ?: "${t.minutes} min"
            GuideChip(
                label = "TARGET · $amount",
                active = true,
                onClick = {},
                onClear = { TrackingManager.setTarget(null) },
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun GuideChip(label: String, active: Boolean, onClick: () -> Unit, onClear: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (active) MColors.Accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (active) MColors.Accent.copy(alpha = 0.6f) else MColors.Stroke, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = if (onClear != null) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = MType.Label.copy(color = if (active) MColors.Accent else MColors.Text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onClear != null) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Remove", onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MIcons.Close, contentDescription = "Remove", tint = MColors.Accent, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun LiveGuide(state: TrackingState, guide: Guide, live: GuideLive) {
    val target = guide.target
    val fraction = target?.let { t ->
        when {
            t.distanceM != null && t.distanceM > 0 -> state.distanceM / t.distanceM
            t.minutes != null && t.minutes > 0 -> state.movingMs / 60_000.0 / t.minutes
            else -> null
        }
    }
    val reached = fraction != null && fraction >= 1.0

    if (target == null && guide.route == null && guide.ghost == null) return
    Spacer(Modifier.height(14.dp))
    if (fraction != null) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                if (reached) "TARGET REACHED · ${target.title.uppercase()}" else "TARGET · ${target.title.uppercase()}",
                style = MType.Label.copy(color = MColors.Accent),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("${(fraction.coerceAtMost(1.0) * 100).toInt()}%", style = MType.Label.copy(color = MColors.Text))
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MColors.Surface2)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.toFloat().coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(MColors.Accent)
            )
        }
        Spacer(Modifier.height(10.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        live.route?.let { r ->
            StatusPill(
                if (live.offRoute) "OFF ROUTE · ${r.offRouteM.toInt()} M" else "${Format.km(r.remainingM)} ${Format.distUnit.uppercase()} TO GO",
                warn = live.offRoute,
            )
        }
        live.aheadSec?.let { s ->
            StatusPill(
                if (s >= 0) "GHOST · ${Format.duration(s * 1000)} AHEAD" else "GHOST · ${Format.duration(-s * 1000)} BEHIND",
                warn = false,
                good = s >= 0,
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, warn: Boolean, good: Boolean = false) {
    Text(
        text,
        style = MType.Label.copy(color = if (warn) MColors.Bg else if (good) MColors.OnAccent else MColors.Text),
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(
                when {
                    warn -> MColors.Danger
                    good -> MColors.Accent
                    else -> MColors.Surface2
                }
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun RoutePicker(
    routes: List<PlannedRoute>,
    onPick: (PlannedRoute) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MColors.Surface2,
        title = { Text("Follow a route", style = MType.Heading) },
        text = {
            Column {
                if (routes.isEmpty()) {
                    Text("No saved routes yet. Draw one on the map and it will show up here.", style = MType.Body)
                }
                routes.take(8).forEach { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button) { onPick(r) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(MIcons.Map, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(r.name, style = MType.Body.copy(color = MColors.Text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Format.km(r.distanceM)} ${Format.distUnit}", style = MType.Number)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNew) { Text("Draw a new route", style = MType.Button.copy(color = MColors.Accent)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) }
        },
    )
}

// ---- Countdown ---------------------------------------------------------------------------------

@Composable
private fun CountdownOverlay(value: Int, sportLabel: String, onSkip: () -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    Box(
        Modifier
            .fillMaxSize()
            .background(MColors.Bg.copy(alpha = 0.94f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Start now",
                onClick = onSkip,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GET READY · ${sportLabel.uppercase()}", style = MType.Label.copy(color = MColors.Accent))
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(tween(320), initialScale = 1.5f)) togetherWith
                        (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.7f))
                },
                label = "countdown",
            ) { n ->
                Text(
                    if (n > 0) "$n" else "GO",
                    style = MType.Hero.copy(fontSize = MType.Hero.fontSize * 2.2f, lineHeight = MType.Hero.lineHeight * 2.2f),
                )
            }
        }
        Text(
            "TAP TO START NOW",
            style = MType.Label,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp),
        )
    }
}

// ---- Lock --------------------------------------------------------------------------------------

@Composable
private fun LockOverlay(onUnlock: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MColors.Bg.copy(alpha = 0.18f))
            // Swallow every touch so pocket taps can't pause or pan.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp)
                .clip(CircleShape)
                .background(MColors.Bg.copy(alpha = 0.85f))
                .border(1.dp, MColors.Stroke, CircleShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MIcons.Lock, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("SCREEN LOCKED", style = MType.Label.copy(color = MColors.Text))
        }
        HoldButton(
            "Hold to unlock",
            onConfirm = onUnlock,
            icon = MIcons.Unlock,
            height = 64.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 30.dp)
                .fillMaxWidth(),
        )
    }
}

private fun startOfWeekMs(): Long =
    LocalDate.now()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
