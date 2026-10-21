package com.meridian.tracker.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.HealthSync
import com.meridian.tracker.data.HeartRate
import com.meridian.tracker.data.Prefs
import com.meridian.tracker.tracking.HeartRateMonitor
import com.meridian.tracker.tracking.StepPermission
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.HorizontalHairline
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.PillButton
import com.meridian.tracker.ui.components.SectionCard
import com.meridian.tracker.ui.components.ZoneSwatch
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val HOLD_TAG = "sensors-screen"

/** Settings › Watches & sensors: Bluetooth heart rate, Health Connect and step counting. */
@Composable
fun SensorsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    val hr by HeartRateMonitor.state.collectAsStateWithLifecycle()
    val found by HeartRateMonitor.found.collectAsStateWithLifecycle()
    val scanning by HeartRateMonitor.scanning.collectAsStateWithLifecycle()
    val maxHr by Prefs.maxHr.collectAsStateWithLifecycle()
    val countSteps by Prefs.countSteps.collectAsStateWithLifecycle()
    val autoSync by Prefs.healthAutoSync.collectAsStateWithLifecycle()
    val lastSync by Prefs.healthLastSyncMs.collectAsStateWithLifecycle()
    val sync by HealthSync.state.collectAsStateWithLifecycle()
    val activities by ActivityStore.activities.collectAsStateWithLifecycle()

    var note by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var stepsAllowed by remember { mutableStateOf(StepPermission.granted(context)) }
    var healthConnected by remember { mutableStateOf(false) }
    val healthAvailability = remember(refresh) { HealthSync.availability(context) }
    val hasStepSensor = remember { StepPermission.hasSensor(context) }
    val suggestedMax = remember(activities) { HeartRate.suggestedMax(activities) }

    LaunchedEffect(refresh) { healthConnected = HealthSync.isConnected(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        stepsAllowed = StepPermission.granted(context)
        HeartRateMonitor.retry()
        refresh++
    }

    // Keep the sensor connected while this screen is open, so you can see it working.
    DisposableEffect(Unit) {
        HeartRateMonitor.acquire(context, HOLD_TAG)
        onDispose {
            HeartRateMonitor.stopScan()
            HeartRateMonitor.release(HOLD_TAG)
        }
    }

    // ---- Bluetooth: permission → Bluetooth on → search ----------------------------------------
    fun findSensor() {
        note = when {
            !HeartRateMonitor.isSupported(context) -> "This phone doesn't support Bluetooth heart-rate sensors"
            !HeartRateMonitor.bluetoothOn(context) -> "Turn on Bluetooth, then search again"
            !HeartRateMonitor.startScan(context) -> "Couldn't search. Try again in a moment"
            else -> null
        }
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (HeartRateMonitor.bluetoothOn(context)) {
            HeartRateMonitor.retry()
            findSensor()
        }
    }
    fun startSearch() {
        if (!HeartRateMonitor.bluetoothOn(context) && HeartRateMonitor.isSupported(context)) {
            val launched = runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.isSuccess
            if (!launched) note = "Turn on Bluetooth, then search again"
        } else {
            findSensor()
        }
    }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            HeartRateMonitor.retry()
            startSearch()
        } else {
            note = "Meridian needs Nearby devices permission to find heart-rate sensors"
        }
    }
    fun search() {
        if (!HeartRateMonitor.hasPermissions(context)) bluetoothPermission.launch(HeartRateMonitor.requiredPermissions())
        else startSearch()
    }

    // ---- Health Connect and steps permissions -------------------------------------------------
    val healthPermission = rememberLauncherForActivityResult(HealthSync.permissionContract()) { granted ->
        refresh++
        if (granted.isNotEmpty()) scope.launch {
            healthConnected = HealthSync.isConnected(context)
            if (healthConnected) HealthSync.sync(context)
        }
    }
    val stepPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        stepsAllowed = ok
        if (!ok) note = "Allow Physical activity in Android settings to count steps"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MColors.Bg)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(MIcons.Back, "Back", onClick = onBack)
            Spacer(Modifier.width(14.dp))
            Text("Watches & sensors", style = MType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            note?.let {
                Text(
                    it.uppercase(),
                    style = MType.Label.copy(color = MColors.OnAccent),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MColors.Accent)
                        .clickable { note = null }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }

            // ---- Live heart rate
            Section("Live heart rate · Bluetooth")
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    HeartReadout(hr)
                    Spacer(Modifier.height(18.dp))
                    if (hr.device == null) {
                        PillButton(
                            if (scanning) "Searching…" else "Find a sensor",
                            icon = MIcons.Bluetooth,
                            onClick = { if (!scanning) search() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(
                                if (scanning) "Searching…" else "Change",
                                filled = false,
                                height = 48.dp,
                                onClick = { if (!scanning) search() },
                                modifier = Modifier.weight(1f),
                            )
                            PillButton(
                                "Forget",
                                filled = false,
                                height = 48.dp,
                                onClick = { HeartRateMonitor.forget() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (hr.status == HeartRateMonitor.Status.NO_PERMISSION) {
                            Spacer(Modifier.height(10.dp))
                            PillButton("Allow Nearby devices", onClick = { search() }, modifier = Modifier.fillMaxWidth(), height = 48.dp)
                        }
                    }
                    if (scanning || found.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (scanning) "NEARBY SENSORS · SEARCHING" else "NEARBY SENSORS · TAP ONE TO CONNECT",
                            style = MType.Label.copy(color = MColors.Accent),
                        )
                        Spacer(Modifier.height(6.dp))
                        if (found.isEmpty()) {
                            Text("Looking for heart-rate sensors and watches that broadcast…", style = MType.Body)
                        }
                        found.forEach { device ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(role = Role.Button) {
                                        HeartRateMonitor.choose(device)
                                        note = "Connecting to ${device.name}"
                                    }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(MIcons.Heart, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(device.name, style = MType.Body.copy(color = MColors.Text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                SignalBars(device.rssi)
                            }
                        }
                    }
                }
            }
            Help(
                "Works with heart-rate chest straps (Polar, Garmin HRM, Wahoo, Coospo…) and watches that " +
                    "broadcast heart rate: Garmin (Broadcast Heart Rate), Coros, Polar, Suunto, Amazfit and some " +
                    "Huawei models. Turn broadcasting on in the watch first, then tap Find. Once paired, Meridian " +
                    "connects by itself every time you record."
            )

            // ---- Zones
            Section("Heart-rate zones")
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("MAX HEART RATE", style = MType.Label)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("$maxHr", style = MType.BigNumber)
                                Spacer(Modifier.width(4.dp))
                                Text("bpm", style = MType.Unit, modifier = Modifier.padding(bottom = 3.dp))
                            }
                        }
                        CircleIconButton(MIcons.ChevronLeft, "Lower max heart rate", onClick = { Prefs.setMaxHr(maxHr - 1) }, size = 44.dp)
                        Spacer(Modifier.width(8.dp))
                        CircleIconButton(MIcons.ChevronRight, "Raise max heart rate", onClick = { Prefs.setMaxHr(maxHr + 1) }, size = 44.dp)
                    }
                    if (suggestedMax != null && suggestedMax > maxHr) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "USE YOUR HIGHEST RECORDED · $suggestedMax BPM",
                            style = MType.Label.copy(color = MColors.Accent),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button) { Prefs.setMaxHr(suggestedMax) }
                                .padding(vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalHairline()
                    Spacer(Modifier.height(8.dp))
                    HeartRate.ZONES.reversed().forEach { zone ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            ZoneSwatch(zone.number)
                            Spacer(Modifier.width(10.dp))
                            Text("Z${zone.number} · ${zone.name}", style = MType.Body.copy(color = MColors.Text), modifier = Modifier.weight(1f))
                            Text(HeartRate.zoneRange(zone, maxHr), style = MType.Number)
                        }
                    }
                }
            }
            Help(
                "Zones and heart-rate Relative Effort use your max heart rate. If you don't know it, the " +
                    "highest number you see during a hard all-out effort is a good guide."
            )

            // ---- Health Connect
            Section("Fitbit, Garmin & more · Health Connect")
            SectionCard(Modifier.fillMaxWidth()) {
                Column {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MColors.Accent.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MIcons.Watch, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Health Connect", style = MType.Heading)
                                Text(
                                    when {
                                        healthAvailability == HealthSync.Availability.UNAVAILABLE -> "Not available on this phone"
                                        healthAvailability == HealthSync.Availability.NEEDS_INSTALL -> "Install Health Connect to continue"
                                        !healthConnected -> "Not connected"
                                        lastSync > 0 -> "Connected · synced ${syncTime(lastSync)}"
                                        else -> "Connected"
                                    }.uppercase(),
                                    style = MType.Label.copy(color = if (healthConnected) MColors.Accent else MColors.Muted),
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Your watch app saves workouts to Health Connect on this phone. Meridian reads them, " +
                                "with the map when the app shares it, plus heart rate and steps. It also adds your " +
                                "watch's heart rate to runs you record with Meridian.",
                            style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.92f),
                        )
                        Spacer(Modifier.height(16.dp))
                        when {
                            healthAvailability == HealthSync.Availability.UNAVAILABLE -> Unit
                            healthAvailability == HealthSync.Availability.NEEDS_INSTALL -> PillButton(
                                "Get Health Connect",
                                icon = MIcons.Download,
                                onClick = { openPlayStore(context) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            !healthConnected -> PillButton(
                                "Connect Health Connect",
                                icon = MIcons.Watch,
                                onClick = {
                                    runCatching { healthPermission.launch(HealthSync.permissions) }
                                        .onFailure { note = "Couldn't open Health Connect" }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            else -> PillButton(
                                if (sync.running) "Syncing…" else "Sync now",
                                icon = MIcons.Sync,
                                onClick = { if (!sync.running) scope.launch { HealthSync.sync(context) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        sync.message?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, style = MType.Body.copy(color = MColors.Text))
                        }
                    }
                    if (healthConnected) {
                        HorizontalHairline()
                        ToggleRow("Sync when Meridian opens", "New watch workouts show up by themselves.", autoSync) {
                            Prefs.setHealthAutoSync(it)
                        }
                        HorizontalHairline()
                        LinkRow("Choose what's shared", "Opens Health Connect's own settings.", icon = MIcons.Settings) {
                            runCatching { context.startActivity(HealthSync.settingsIntent()) }
                                .onFailure { note = "Couldn't open Health Connect" }
                        }
                    }
                }
            }
            WatchSetupHelp()

            // ---- Steps
            Section("Steps")
            SectionCard(Modifier.fillMaxWidth()) {
                Column {
                    ToggleRow(
                        "Count steps",
                        when {
                            !hasStepSensor -> "This phone has no step sensor. Steps from a watch still come through Health Connect."
                            else -> "Steps and cadence for runs and walks, from your phone's step sensor. Uses almost no battery."
                        },
                        countSteps && hasStepSensor,
                    ) { on ->
                        Prefs.setCountSteps(on)
                        if (on && !stepsAllowed) StepPermission.permission?.let { stepPermission.launch(it) }
                    }
                    if (countSteps && hasStepSensor && !stepsAllowed) {
                        HorizontalHairline()
                        LinkRow(
                            "Allow Physical activity",
                            "Android asks once before apps can count steps.",
                            badge = "ALLOW",
                            badgeWarn = true,
                        ) { StepPermission.permission?.let { stepPermission.launch(it) } }
                    }
                }
            }
            Help("Keep your phone in a pocket or armband for the most accurate count. Steps aren't counted on rides or while paused.")

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun HeartReadout(hr: HeartRateMonitor.HrState) {
    val live = hr.status == HeartRateMonitor.Status.CONNECTED && hr.bpm != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (live) MColors.Accent else MColors.Surface2)
                .border(1.dp, if (live) MColors.Accent else MColors.Stroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (live) MIcons.HeartFilled else MIcons.Heart,
                contentDescription = null,
                tint = if (live) MColors.OnAccent else MColors.Muted,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (hr.device?.name ?: "No sensor yet").uppercase(),
                style = MType.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            if (live) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${hr.bpm}", style = MType.BigNumber.copy(color = MColors.Accent))
                    Spacer(Modifier.width(4.dp))
                    Text("bpm", style = MType.Unit, modifier = Modifier.padding(bottom = 3.dp))
                }
                if (!hr.contact) Text("Strap isn't touching skin. Wet the contacts.", style = MType.Body)
            } else {
                Text(
                    when (hr.status) {
                        HeartRateMonitor.Status.NONE -> "Pair a strap or watch to see live heart rate while you record."
                        HeartRateMonitor.Status.IDLE -> "Not connected"
                        HeartRateMonitor.Status.NO_PERMISSION -> "Meridian needs Nearby devices permission."
                        HeartRateMonitor.Status.BLUETOOTH_OFF -> "Bluetooth is off."
                        HeartRateMonitor.Status.CONNECTING -> "Connecting… Make sure it's on and nearby."
                        HeartRateMonitor.Status.CONNECTED -> "Connected. Waiting for heart rate…"
                        HeartRateMonitor.Status.NO_SIGNAL -> "Connected, but no heart rate. Is broadcasting on?"
                    },
                    style = MType.Body.copy(color = MColors.Text),
                )
            }
        }
    }
}

@Composable
private fun SignalBars(rssi: Int) {
    val level = when {
        rssi >= -60 -> 3
        rssi >= -75 -> 2
        else -> 1
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (1..3).forEach { i ->
            Box(
                Modifier
                    .width(4.dp)
                    .height((4 + i * 4).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= level) MColors.Accent else MColors.Stroke)
            )
        }
    }
}

@Composable
private fun Help(text: String) {
    Text(
        text,
        style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f, color = MColors.Faint),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

@Composable
private fun WatchSetupHelp() {
    var open by remember { mutableStateOf(false) }
    Text(
        if (open) "HIDE WATCH SETUP" else "HOW TO SET UP YOUR WATCH APP",
        style = MType.Label.copy(color = MColors.Accent),
        modifier = Modifier
            .clickable(role = Role.Button) { open = !open }
            .padding(horizontal = 8.dp, vertical = 12.dp),
    )
    if (open) {
        SectionCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                WatchLine("Fitbit & Pixel Watch", "In the Fitbit app, find Health Connect in its settings and allow exercise, heart rate and steps.")
                WatchLine("Garmin", "In Garmin Connect, open settings › Connected Apps › Health Connect and allow sharing. For live heart rate, also turn on Broadcast Heart Rate on the watch.")
                WatchLine("Samsung Galaxy Watch", "In Samsung Health, open settings › Health Connect and allow exercise, heart rate and steps.")
                WatchLine("Huawei & others", "If the watch app doesn't support Health Connect, use Bluetooth heart rate (if the watch can broadcast), or sync it to Strava and import your Strava archive.")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Menus differ between app versions. Look for \"Health Connect\" in your watch app's settings.",
                    style = MType.Label.copy(color = MColors.Faint, lineHeight = MType.Label.lineHeight * 1.4f),
                )
            }
        }
    }
}

@Composable
private fun WatchLine(title: String, text: String) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title, style = MType.Heading.copy(fontSize = MType.Heading.fontSize * 0.9f))
        Spacer(Modifier.height(2.dp))
        Text(text, style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.9f))
    }
}

private fun openPlayStore(context: Context) {
    runCatching { context.startActivity(HealthSync.installIntent()) }
        .onFailure { runCatching { context.startActivity(HealthSync.installWebIntent()) } }
}

private val syncFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
private fun syncTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(syncFmt)
