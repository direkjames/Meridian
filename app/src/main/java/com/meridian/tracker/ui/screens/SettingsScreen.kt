package com.meridian.tracker.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.DataTransfer
import com.meridian.tracker.data.ActivityMedia
import com.meridian.tracker.data.Prefs
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.HorizontalHairline
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.SectionCard
import com.meridian.tracker.ui.components.Segmented
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenOfflineMaps: () -> Unit, onOpenSensors: () -> Unit, onOpenGear: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    val imperial by Prefs.imperial.collectAsStateWithLifecycle()
    val highAccuracy by Prefs.highAccuracy.collectAsStateWithLifecycle()
    val keepScreenOn by Prefs.keepScreenOn.collectAsStateWithLifecycle()
    val countdown by Prefs.countdown.collectAsStateWithLifecycle()
    val autoBackup by Prefs.autoBackupUri.collectAsStateWithLifecycle()
    val lastBackup by Prefs.lastBackupMs.collectAsStateWithLifecycle()
    val backupError by DataTransfer.backupError.collectAsStateWithLifecycle()
    val backupVideos by Prefs.backupVideos.collectAsStateWithLifecycle()
    // Reading the media folders is disk work, so it happens off the main thread.
    val mediaSizes by produceState(0L to 0L) {
        value = withContext(Dispatchers.IO) { ActivityMedia.sizes() }
    }
    val weatherTag by Prefs.weatherTag.collectAsStateWithLifecycle()
    val importStatus by DataTransfer.importStatus.collectAsStateWithLifecycle()
    val activities by ActivityStore.activities.collectAsStateWithLifecycle()

    var unrestricted by remember { mutableStateOf(isUnrestricted(context)) }
    var note by remember { mutableStateOf<String?>(null) }
    var showStravaHelp by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { unrestricted = isUnrestricted(context) }

    // ---- File pickers (Android's own picker: phone storage, Downloads, Google Drive…) --------
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { DataTransfer.import(context, uris) }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            note = runCatching { DataTransfer.exportAllGpx(context, uri) }
                .fold({ "Exported $it activities as GPX" }, { "Export failed" })
        }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            note = runCatching { DataTransfer.writeBackup(context, uri) }
                .fold({ "Backed up $it activities" }, { "Backup failed" })
        }
    }
    val autoBackupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            note = if (DataTransfer.enableAutoBackup(context, uri)) "Automatic backup is on" else "Couldn't write to that file"
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            note = runCatching { DataTransfer.restore(context, uri) }
                .fold({ if (it == 0) "Nothing new to restore" else "Restored $it activities" }, { it.message ?: "Restore failed" })
        }
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
            Text("Settings", style = MType.Title)
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
                        .clip(CircleShape)
                        .background(MColors.Accent)
                        .clickable { note = null }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }

            // ---- Units
            Section("Units")
            Segmented(
                options = listOf("Kilometres", "Miles"),
                selected = if (imperial) 1 else 0,
                onSelect = { Prefs.setImperial(it == 1) },
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- Body (for calorie estimates)
            Section("Body")
            BodyCard()
            Text(
                "Used only to estimate calories. It never leaves your phone.",
                style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f, color = MColors.Faint),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            )

            // ---- Privacy
            Section("Privacy")
            PrivacyZonesCard()
            Text(
                "Zones only change what you share or export as GPX. Your saved activities and backups keep the full route.",
                style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f, color = MColors.Faint),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            )

            // ---- Recording
            Section("Recording")
            SectionCard(Modifier.fillMaxWidth()) {
                Column {
                    ToggleRow(
                        "High-accuracy GPS",
                        if (highAccuracy) "Location every second. Most precise."
                        else "Location every 3 seconds. Easier on the battery for long rides.",
                        highAccuracy,
                    ) { Prefs.setHighAccuracy(it) }
                    HorizontalHairline()
                    ToggleRow("Keep screen on", "While recording, the screen stays awake.", keepScreenOn) {
                        Prefs.setKeepScreenOn(it)
                    }
                    HorizontalHairline()
                    ToggleRow("3-2-1 countdown", "A short countdown before recording starts.", countdown) {
                        Prefs.setCountdown(it)
                    }
                    HorizontalHairline()
                    ToggleRow(
                        "Weather on activities",
                        "Adds the conditions at the start, from Open-Meteo. Only the start hour and your area rounded to about 10 km are sent.",
                        weatherTag,
                    ) { Prefs.setWeatherTag(it) }
                    HorizontalHairline()
                    LinkRow(
                        title = "Background recording",
                        subtitle = if (unrestricted) "Unrestricted. Recording won't be stopped with the screen off."
                        else "Battery saving may stop recording on some phones. Tap, then set Battery to Unrestricted.",
                        badge = if (unrestricted) "OK" else "FIX",
                        badgeWarn = !unrestricted,
                        onClick = { openAppSettings(context) },
                    )
                }
            }

            // ---- Gear
            Section("Gear")
            SectionCard(Modifier.fillMaxWidth()) {
                LinkRow(
                    "Shoes, bikes & gear",
                    "Track how far your gear has gone and when it's time to replace it.",
                    icon = MIcons.Shoe,
                    onClick = onOpenGear,
                )
            }

            // ---- Watches & sensors
            Section("Watches & sensors")
            SectionCard(Modifier.fillMaxWidth()) {
                LinkRow(
                    "Heart rate, watches & steps",
                    "Bluetooth heart rate, Garmin, Fitbit and more via Health Connect, and step counting.",
                    icon = MIcons.Watch,
                    onClick = onOpenSensors,
                )
            }

            // ---- Maps
            Section("Maps")
            SectionCard(Modifier.fillMaxWidth()) {
                LinkRow("Offline maps", "Save areas so streets show with no signal.", icon = MIcons.Map, onClick = onOpenOfflineMaps)
            }

            // ---- Data
            Section("Your data")
            SectionCard(Modifier.fillMaxWidth()) {
                Column {
                    LinkRow(
                        "Import activities",
                        "Your Strava export ZIP, or GPX, TCX and FIT files.",
                        icon = MIcons.Download,
                        enabled = !importStatus.running,
                        onClick = { DataTransfer.clearImportResult(); importPicker.launch(arrayOf("*/*")) },
                    )
                    if (importStatus.running || importStatus.result != null) {
                        ImportProgress(importStatus.running, importStatus.processed, importStatus.total, importStatus.result)
                    }
                    Text(
                        if (showStravaHelp) "HIDE STRAVA STEPS" else "HOW TO GET YOUR STRAVA DATA",
                        style = MType.Label.copy(color = MColors.Accent),
                        modifier = Modifier
                            .clickable(role = Role.Button) { showStravaHelp = !showStravaHelp }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    if (showStravaHelp) {
                        Text(
                            "1. On strava.com, open Settings › My Account.\n" +
                                "2. Under Download or Delete Your Account, choose Get Started, then Request Your Archive.\n" +
                                "3. Strava emails you a ZIP. Download it to your phone (or Google Drive).\n" +
                                "4. Tap Import activities and pick the ZIP. Names and sports come across too.",
                            style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.92f),
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                        )
                    }
                    HorizontalHairline()
                    LinkRow("Export all as GPX", "A ZIP of every activity, for any other app.", icon = MIcons.Share) {
                        exportPicker.launch("Meridian GPX export.zip")
                    }
                }
            }

            // ---- Backup
            Section("Backup")
            SectionCard(Modifier.fillMaxWidth()) {
                Column {
                    ToggleRow(
                        "Automatic backup",
                        when {
                            backupError != null -> backupError!!
                            autoBackup == null -> "Pick a file once (Google Drive works) and Meridian keeps it up to date after every activity."
                            lastBackup > 0 -> "On · last backup ${backupTime(lastBackup)}"
                            else -> "On"
                        },
                        autoBackup != null,
                    ) { on ->
                        if (on) autoBackupPicker.launch("Meridian backup.zip") else DataTransfer.disableAutoBackup(context)
                    }
                    HorizontalHairline()
                    ToggleRow(
                        "Include videos in automatic backup",
                        if (mediaSizes.second > 0) {
                            "Your videos use ${formatBytes(mediaSizes.second)}, copied again after every activity. " +
                                "Photos are always included."
                        } else {
                            "Photos are always included. Turn this on to copy videos too."
                        },
                        backupVideos,
                    ) { on ->
                        Prefs.setBackupVideos(on)
                        if (autoBackup != null) DataTransfer.scheduleAutoBackup(context)
                    }
                    HorizontalHairline()
                    LinkRow("Back up now", "Save a full backup file anywhere you like.", icon = MIcons.Download) {
                        backupPicker.launch("Meridian backup ${java.time.LocalDate.now()}.zip")
                    }
                    HorizontalHairline()
                    LinkRow("Restore from backup", "Adds activities from a Meridian backup. Nothing is overwritten.", icon = MIcons.Record) {
                        restorePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                }
            }
            Text(
                "Android's Google backup also keeps a copy of your activities and settings automatically, " +
                    "and restores them when you set up a new phone.",
                style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f, color = MColors.Faint),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            )

            // ---- About
            Section("About")
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Meridian ${versionName(context)}", style = MType.Heading)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${activities.size} activities · ${formatBytes(ActivityStore.storageBytes() + mediaSizes.first + mediaSizes.second)} on this phone. " +
                            "No account, no feed, no tracking.",
                        style = MType.Body,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Map data © OpenStreetMap contributors · Basemap © CARTO · Maps by MapLibre\n" +
                            "Fonts: Instrument Sans and Geist Mono (SIL Open Font License)",
                        style = MType.Label.copy(color = MColors.Faint, lineHeight = MType.Label.lineHeight * 1.4f),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

// ---- Rows ----------------------------------------------------------------------------------------

@Composable
internal fun Section(title: String) {
    Text(title.uppercase(), style = MType.Label, modifier = Modifier.padding(start = 8.dp, top = 26.dp, bottom = 10.dp))
}

@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MType.Heading.copy(fontSize = MType.Heading.fontSize * 0.92f))
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f))
        }
        Spacer(Modifier.width(16.dp))
        MSwitch(checked)
    }
}

@Composable
internal fun MSwitch(checked: Boolean) {
    val track by animateColorAsState(if (checked) MColors.Accent else MColors.Surface2, label = "track")
    val thumb by animateColorAsState(if (checked) MColors.OnAccent else MColors.Muted, label = "thumb")
    val offset by animateDpAsState(if (checked) 22.dp else 2.dp, label = "thumbX")
    Box(
        Modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, if (checked) MColors.Accent else MColors.Stroke, CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(26.dp)
                .clip(CircleShape)
                .background(thumb)
        )
    }
}

@Composable
internal fun LinkRow(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    badge: String? = null,
    badgeWarn: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MColors.Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MType.Heading.copy(fontSize = MType.Heading.fontSize * 0.92f))
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f))
        }
        if (badge != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                badge,
                style = MType.Label.copy(color = if (badgeWarn) MColors.Bg else MColors.Accent),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (badgeWarn) MColors.Danger else MColors.Accent.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        } else {
            Icon(MIcons.ChevronRight, contentDescription = null, tint = MColors.Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ImportProgress(running: Boolean, processed: Int, total: Int?, result: String?) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) {
        if (running) {
            Text(
                if (total != null) "IMPORTING $processed OF $total" else "IMPORTING · $processed FILES READ",
                style = MType.Label.copy(color = MColors.Accent),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MColors.Surface2)
            ) {
                val fraction = if (total != null && total > 0) (processed.toFloat() / total).coerceIn(0.03f, 1f) else 0.3f
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(MColors.Accent)
                )
            }
        } else if (result != null) {
            Text(result.uppercase(), style = MType.Label.copy(color = MColors.Text))
        }
    }
}

// ---- Helpers -------------------------------------------------------------------------------------

private fun isUnrestricted(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

private val backupFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
private fun backupTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(backupFmt)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
}
