package com.meridian.tracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.meridian.tracker.data.ActivityAnalysis
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.Badges
import com.meridian.tracker.data.Calories
import com.meridian.tracker.data.Prefs
import com.meridian.tracker.data.EarnedBadge
import com.meridian.tracker.data.Effort
import com.meridian.tracker.data.ExtrasStore
import com.meridian.tracker.data.RouteMatch
import com.meridian.tracker.tracking.TrackingManager
import kotlin.math.roundToInt
import com.meridian.tracker.data.DataTransfer
import com.meridian.tracker.data.io.TrackFormats
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.meridian.tracker.data.BestResult
import com.meridian.tracker.data.Stats
import com.meridian.tracker.ui.display
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.meridian.tracker.ui.components.ProfileCharts
import com.meridian.tracker.ui.components.HeartRateSection
import com.meridian.tracker.ui.components.CadenceSection
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.HorizontalHairline
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.MapMode
import com.meridian.tracker.ui.components.PillButton
import com.meridian.tracker.ui.components.RouteMap
import com.meridian.tracker.ui.components.SectionCard
import com.meridian.tracker.ui.components.StatCell
import com.meridian.tracker.ui.components.VerticalHairline
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SummaryScreen(
    record: ActivityRecord,
    isNew: Boolean,
    onSave: (title: String) -> Unit,
    onRename: (title: String) -> Unit = {},
    onShare: () -> Unit = {},
    onRaceGhost: () -> Unit = {},
    recovered: Boolean = false,
    onDiscard: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    var title by rememberSaveable(record.id) { mutableStateOf(record.title) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var gpxNote by remember { mutableStateOf<String?>(null) }
    var mediaBusy by remember { mutableStateOf(false) }
    val detailsFlush = remember(record.id) { DetailsFlush() }
    var saveNote by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gpxPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) scope.launch {
            gpxNote = if (DataTransfer.exportGpx(context, record, uri)) "GPX FILE SAVED" else "COULDN'T SAVE THE GPX FILE"
        }
    }
    val profile = remember(record.id, record.points.size) { ActivityAnalysis.profile(record.points) }
    val saved by ActivityStore.activities.collectAsStateWithLifecycle()
    val body by Prefs.body.collectAsStateWithLifecycle()
    val calories = remember(record, body) { Calories.estimate(record, body) }
    val newBests by produceState(initialValue = emptyList<BestResult>(), record.id, saved.size) {
        value = withContext(Dispatchers.Default) { Stats.newBestsFor(record, saved) }
    }
    val extras by produceState<SummaryExtras?>(initialValue = null, record.id, saved.size) {
        value = withContext(Dispatchers.Default) {
            val withThis = if (saved.any { it.id == record.id }) saved else saved + record
            val planDone = ExtrasStore.planCompletedMs.value
            SummaryExtras(
                effort = Effort.load(record, Effort.thresholds(withThis)),
                effortFromHeart = Effort.fromHeartRate(record) != null,
                matches = RouteMatch.matches(record, saved),
                badges = Badges.earned(withThis, planCompletedMs = planDone).filter { it.recordId == record.id },
            )
        }
    }

    BackHandler {
        if (isNew) confirmDiscard = true else onClose()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MColors.Bg)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Map header
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(360.dp)
            ) {
                if (record.points.isNotEmpty()) {
                    RouteMap(
                        points = record.points,
                        fix = null,
                        mode = MapMode.FIT,
                        controlsBottomPadding = 40.dp,
                        controlsTopPadding = 72.dp,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(MColors.Surface), contentAlignment = Alignment.Center) {
                        Text("NO GPS ROUTE RECORDED", style = MType.Label)
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(Brush.verticalGradient(listOf(MColors.Bg, MColors.Bg.copy(alpha = 0f))))
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(Brush.verticalGradient(listOf(MColors.Bg.copy(alpha = 0f), MColors.Bg)))
                )
            }

            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    "${record.sport.label.uppercase()} · ${dateLabel(record.startTimeMs).uppercase()}",
                    style = MType.Label,
                )
                Spacer(Modifier.height(8.dp))
                if (isNew) {
                    BasicTextField(
                        value = title,
                        onValueChange = { if (it.length <= 60) title = it },
                        textStyle = MType.Title,
                        singleLine = true,
                        cursorBrush = SolidColor(MColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Tap the title to rename", style = MType.Label.copy(color = MColors.Faint))
                    record.source?.let { SourceLabel(it) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(record.title, style = MType.Title, modifier = Modifier.weight(1f))
                        CircleIconButton(MIcons.Edit, "Rename activity", onClick = { renaming = true }, size = 40.dp)
                    }
                    record.source?.let { SourceLabel(it) }
                }

                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Format.km(record.distanceM), style = MType.Hero)
                    Spacer(Modifier.width(8.dp))
                    Text(Format.distUnit, style = MType.Unit.copy(fontSize = MType.Unit.fontSize * 1.3f), modifier = Modifier.padding(bottom = 12.dp))
                }
                Spacer(Modifier.height(20.dp))
                record.steps?.let { steps ->
                    StepsBanner(steps, record.avgCadence, record.strideM)
                    Spacer(Modifier.height(12.dp))
                }

                if (newBests.isNotEmpty()) {
                    NewBests(newBests)
                    Spacer(Modifier.height(20.dp))
                }
                extras?.badges?.takeIf { it.isNotEmpty() }?.let { badges ->
                    NewBadges(badges)
                    Spacer(Modifier.height(20.dp))
                }

                SectionCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(20.dp)) {
                            StatCell("Moving time", Format.duration(record.movingMs), null, Modifier.weight(1f))
                            VerticalHairline(Modifier.padding(horizontal = 14.dp))
                            if (record.sport.showsSpeed) {
                                StatCell("Avg speed", Format.speedKmh(record.avgSpeed), Format.speedUnit, Modifier.weight(1f))
                            } else {
                                StatCell("Avg pace", Format.pace(record.avgPaceSecPerKm), Format.paceUnit, Modifier.weight(1f))
                            }
                        }
                        HorizontalHairline()
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(20.dp)) {
                            StatCell("Elapsed", Format.duration(record.elapsedMs), null, Modifier.weight(1f))
                            VerticalHairline(Modifier.padding(horizontal = 14.dp))
                            StatCell("Climb", Format.meters(record.elevationGainM), "m", Modifier.weight(1f))
                        }
                        if (record.hasHeartRate) {
                            HorizontalHairline()
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(20.dp)) {
                                StatCell("Avg heart rate", "${record.avgHeartRate ?: "–"}", "bpm", Modifier.weight(1f))
                                VerticalHairline(Modifier.padding(horizontal = 14.dp))
                                StatCell("Max heart rate", "${record.maxHeartRate ?: "–"}", "bpm", Modifier.weight(1f))
                            }
                        }
                        calories?.let { c ->
                            HorizontalHairline()
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(20.dp)) {
                                StatCell("Calories", "≈ ${Format.count(c.kcal)}", "kcal", Modifier.weight(1f))
                                VerticalHairline(Modifier.padding(horizontal = 14.dp))
                                StatCell("Based on", c.method.label, null, Modifier.weight(1f))
                            }
                        }
                        if (profile.hasSpeed || profile.hasElevation) {
                            HorizontalHairline()
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(20.dp)) {
                                if (record.sport.showsSpeed) {
                                    StatCell("Max speed", Format.speedKmh(profile.maxSpeed), Format.speedUnit, Modifier.weight(1f))
                                } else {
                                    StatCell(
                                        "Best pace",
                                        if (profile.maxSpeed > 0) Format.paceFromSpeed(profile.maxSpeed) else "–:––",
                                        Format.paceUnit,
                                        Modifier.weight(1f),
                                    )
                                }
                                VerticalHairline(Modifier.padding(horizontal = 14.dp))
                                StatCell(
                                    "Highest",
                                    profile.maxElevation?.let { Format.meters(it) } ?: "—",
                                    if (profile.maxElevation != null) "m" else null,
                                    Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                extras?.let { ex ->
                    Spacer(Modifier.height(12.dp))
                    EffortRow(ex.effort, ex.effortFromHeart)
                }

                Spacer(Modifier.height(28.dp))
                GearSection(record, isNew = isNew, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(28.dp))
                DetailsSection(record, isNew = isNew, flush = detailsFlush, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(28.dp))
                MediaSection(record, onBusyChange = { mediaBusy = it }, modifier = Modifier.fillMaxWidth())

                if (profile.samples.isNotEmpty()) {
                    Spacer(Modifier.height(32.dp))
                    ProfileCharts(profile, record.sport, Modifier.fillMaxWidth())
                }
                if (record.hasHeartRate) {
                    Spacer(Modifier.height(32.dp))
                    HeartRateSection(record, Modifier.fillMaxWidth())
                }
                if (record.cadence.size >= 3) {
                    Spacer(Modifier.height(32.dp))
                    CadenceSection(record, Modifier.fillMaxWidth())
                }

                extras?.matches?.takeIf { it.size >= 2 }?.let { MatchedRuns(record, it) }

                Splits(record)

                Spacer(Modifier.height(28.dp))
                if (isNew) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton(
                            "Discard",
                            onClick = { confirmDiscard = true },
                            filled = false,
                            modifier = Modifier.weight(1f),
                        )
                        PillButton(
                            if (mediaBusy) "Adding photos…" else "Save activity",
                            onClick = {
                                // Wait for photos/videos still being copied, so none are lost.
                                if (mediaBusy) saveNote = "Hang on, still adding your photos"
                                else {
                                    detailsFlush.action?.invoke() // a note typed a moment ago
                                    onSave(title.trim().ifEmpty { record.title })
                                }
                            },
                            icon = MIcons.Check,
                            modifier = Modifier.weight(1.6f),
                        )
                    }
                    saveNote?.takeIf { mediaBusy }?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it.uppercase(), style = MType.Label.copy(color = MColors.Accent))
                    }
                } else {
                    if (record.points.size >= 2) {
                        PillButton(
                            "Race your ghost on this route",
                            icon = MIcons.Record,
                            onClick = { TrackingManager.setGhost(record); onRaceGhost() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    PillButton(
                        "Edit activity",
                        icon = MIcons.Edit,
                        filled = false,
                        onClick = { editing = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    PillButton(
                        "Export GPX file",
                        icon = MIcons.Download,
                        filled = false,
                        onClick = { gpxPicker.launch(TrackFormats.fileName(record)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        gpxNote ?: "Saved on this phone only.",
                        style = MType.Label.copy(color = if (gpxNote != null) MColors.Accent else MColors.Faint),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }

        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isNew) {
                Text(
                    if (recovered) "RECOVERED ACTIVITY" else "ACTIVITY COMPLETE",
                    style = MType.Label.copy(color = MColors.Accent),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MColors.Bg.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                CircleIconButton(MIcons.Back, "Back", onClick = onClose)
            }
            Spacer(Modifier.weight(1f))
            if (record.points.isNotEmpty() || record.distanceM > 0) {
                CircleIconButton(MIcons.Share, "Share activity", onClick = onShare)
            }
            if (!isNew) {
                Spacer(Modifier.width(8.dp))
                CircleIconButton(MIcons.Trash, "Delete activity", onClick = { confirmDelete = true })
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard this activity?",
            text = "The route and stats will be deleted. This can't be undone.",
            confirm = "Discard",
            onConfirm = { confirmDiscard = false; onDiscard() },
            onDismiss = { confirmDiscard = false },
        )
    }
    if (renaming) {
        RenameDialog(
            initial = record.title,
            onConfirm = { newTitle -> renaming = false; onRename(newTitle) },
            onDismiss = { renaming = false },
        )
    }
    if (editing && !isNew) {
        EditActivityDialog(record = record, onDismiss = { editing = false })
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this activity?",
            text = "It will be removed from this phone. This can't be undone.",
            confirm = "Delete",
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun NewBests(bests: List<BestResult>) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MColors.Accent.copy(alpha = 0.08f))
            .border(1.dp, MColors.Accent.copy(alpha = 0.55f), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MIcons.Trophy, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (bests.size == 1) "NEW PERSONAL BEST" else "${bests.size} NEW PERSONAL BESTS",
                style = MType.Label.copy(color = MColors.Accent),
            )
        }
        Spacer(Modifier.height(8.dp))
        bests.forEach { best ->
            val (value, unit) = best.display()
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(best.spec.title, style = MType.Body.copy(color = MColors.Text), modifier = Modifier.weight(1f))
                Text(value, style = MType.Number)
                if (unit != null) Text(" $unit", style = MType.Unit)
            }
        }
    }
}

private data class SummaryExtras(
    val effort: Double,
    val effortFromHeart: Boolean,
    val matches: List<ActivityRecord>,
    val badges: List<EarnedBadge>,
)

@Composable
private fun EffortRow(effort: Double, fromHeart: Boolean) {
    SectionCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (fromHeart) "RELATIVE EFFORT · HEART RATE" else "RELATIVE EFFORT", style = MType.Label)
                    Spacer(Modifier.height(4.dp))
                    Text(Effort.label(effort), style = MType.Heading)
                }
                Text("${effort.roundToInt()}", style = MType.BigNumber.copy(color = MColors.Accent))
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth((effort / 200.0).toFloat().coerceIn(0.03f, 1f))
                    .height(3.dp)
                    .background(MColors.Accent)
            )
        }
    }
}

@Composable
private fun SourceLabel(source: String) {
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(MIcons.Watch, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("FROM ${source.uppercase()} · HEALTH CONNECT", style = MType.Label.copy(color = MColors.Accent))
    }
}

/** Steps for this activity, shown right under the distance. */
@Composable
private fun StepsBanner(steps: Int, cadence: Int?, strideM: Double?) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MColors.Surface)
            .border(1.dp, MColors.Hairline, shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MColors.Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MIcons.Steps, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("STEPS", style = MType.Label)
            Text(Format.count(steps), style = MType.BigNumber)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (cadence != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$cadence", style = MType.Number)
                    Text(" spm", style = MType.Unit)
                }
            }
            if (strideM != null) {
                Text(String.format(Locale.US, "%.2f M STRIDE", strideM), style = MType.Label)
            }
        }
    }
}

@Composable
private fun NewBadges(badges: List<EarnedBadge>) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MColors.Surface)
            .border(1.dp, MColors.Accent.copy(alpha = 0.55f), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(if (badges.size == 1) "BADGE EARNED" else "${badges.size} BADGES EARNED", style = MType.Label.copy(color = MColors.Accent))
        Spacer(Modifier.height(8.dp))
        badges.forEach { b ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(MColors.Accent), contentAlignment = Alignment.Center) {
                    Icon(MIcons.Trophy, contentDescription = null, tint = MColors.OnAccent, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(b.def.title, style = MType.Body.copy(color = MColors.Text))
                    Text(b.def.description.uppercase(), style = MType.Label)
                }
            }
        }
    }
}

@Composable
private fun MatchedRuns(record: ActivityRecord, matches: List<ActivityRecord>) {
    val byTime = matches.sortedBy { it.movingMs }
    val rank = byTime.indexOfFirst { it.id == record.id } + 1
    val best = byTime.first()
    val maxMs = matches.maxOf { it.movingMs }.toFloat()
    Spacer(Modifier.height(28.dp))
    Row(Modifier.fillMaxWidth()) {
        Text("SAME ROUTE · ${matches.size} TIMES", style = MType.Label, modifier = Modifier.weight(1f))
        Text("BEST ${Format.duration(best.movingMs)}", style = MType.Label.copy(color = MColors.Accent))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        when (rank) {
            1 -> "Your fastest time on this route."
            2 -> "2nd fastest of ${matches.size} on this route."
            3 -> "3rd fastest of ${matches.size} on this route."
            else -> "${rank}th of ${matches.size} on this route."
        },
        style = MType.Heading,
    )
    Spacer(Modifier.height(14.dp))
    Row(
        Modifier.fillMaxWidth().height(90.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        matches.takeLast(12).forEach { m ->
            val isThis = m.id == record.id
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((70 * (m.movingMs / maxMs)).coerceAtLeast(6f).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isThis) MColors.Accent else if (m.id == best.id) MColors.AccentDim else MColors.Bar)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    Format.duration(m.movingMs),
                    style = MType.Label.copy(fontSize = MType.Label.fontSize * 0.7f, color = if (isThis) MColors.Accent else MColors.Faint),
                    maxLines = 1,
                )
            }
        }
    }
    Text("SHORTER BAR = FASTER · OLDEST TO NEWEST", style = MType.Label.copy(color = MColors.Faint, fontSize = MType.Label.fontSize * 0.8f), modifier = Modifier.padding(top = 6.dp))
}

private data class SplitRow(val label: String, val secPerKm: Double)

@Composable
private fun Splits(record: ActivityRecord) {
    val rows = record.splitsMs.mapIndexed { i, ms -> SplitRow("${i + 1}", ms / 1000.0) }.toMutableList()
    if (record.partialSplitM >= 50 && record.partialSplitMs > 0) {
        rows += SplitRow(
            String.format(Locale.US, "%.2f", record.partialSplitM / 1000.0),
            (record.partialSplitMs / 1000.0) / (record.partialSplitM / 1000.0),
        )
    }
    if (rows.isEmpty()) return

    val fastest = rows.minOf { it.secPerKm }
    Spacer(Modifier.height(28.dp))
    Row(Modifier.fillMaxWidth()) {
        Text("SPLITS", style = MType.Label, modifier = Modifier.weight(1f))
        Text(if (record.sport.showsSpeed) Format.speedUnit.uppercase() else "PACE ${Format.paceUnit.uppercase()}", style = MType.Label)
    }
    Spacer(Modifier.height(12.dp))
    rows.forEach { row ->
        val isFastest = row.secPerKm == fastest && rows.size > 1
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.label, style = MType.Number.copy(color = MColors.Muted), modifier = Modifier.width(48.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(MColors.Surface2)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((fastest / row.secPerKm).toFloat().coerceIn(0.08f, 1f))
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(if (isFastest) MColors.Accent else MColors.Bar)
                )
            }
            Spacer(Modifier.width(14.dp))
            val value = if (record.sport.showsSpeed) Format.speedKmh(1000.0 / row.secPerKm) else Format.pace(row.secPerKm)
            Text(
                value,
                style = MType.Number.copy(color = if (isFastest) MColors.Accent else MColors.Text),
                modifier = Modifier.width(56.dp),
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MColors.Surface2,
        title = { Text(title, style = MType.Heading) },
        text = { Text(text, style = MType.Body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, style = MType.Button.copy(color = MColors.Danger))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = MType.Button.copy(color = MColors.Text))
            }
        },
    )
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MColors.Surface2,
        title = { Text("Rename activity", style = MType.Heading) },
        text = {
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
                    onValueChange = { if (it.length <= 60) text = it },
                    textStyle = MType.Heading,
                    singleLine = true,
                    cursorBrush = SolidColor(MColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim().ifEmpty { initial }) }) {
                Text("Save", style = MType.Button.copy(color = MColors.Accent))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = MType.Button.copy(color = MColors.Text))
            }
        },
    )
}

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", Locale.getDefault())

private fun dateLabel(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFormatter)
