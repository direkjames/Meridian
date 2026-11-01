package com.meridian.tracker.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.DayStatus
import com.meridian.tracker.data.ExtrasStore
import com.meridian.tracker.data.PlanConfig
import com.meridian.tracker.data.PlanDay
import com.meridian.tracker.data.PlanEdits
import com.meridian.tracker.data.PlanGenerator
import com.meridian.tracker.data.RouteIdea
import com.meridian.tracker.data.PlanMode
import com.meridian.tracker.data.SessionKind
import com.meridian.tracker.data.TargetType
import com.meridian.tracker.data.TrainingPlan
import com.meridian.tracker.tracking.SessionTarget
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackingManager
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.HorizontalHairline
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.PillButton
import com.meridian.tracker.ui.components.SectionCard
import com.meridian.tracker.ui.components.Segmented
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * The Plan tab: an event plan (train for a fun run or race) and a monthly plan (a month of mixed
 * sessions), kept separate so you can follow either or both.
 */
@Composable
fun PlanScreen(onStartSession: () -> Unit, onSuggestRoute: (RouteIdea) -> Unit = {}) {
    val plan by ExtrasStore.plan.collectAsStateWithLifecycle()
    val monthly by ExtrasStore.monthly.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var monthlySetup by rememberSaveable { mutableStateOf(false) }
    // -1 until you pick: then open whichever plan you have (event first).
    var tab by rememberSaveable { mutableIntStateOf(-1) }
    val selected = if (tab >= 0) tab else if (plan == null && monthly != null) 1 else 0
    val inSetup = if (selected == 0) editing else monthlySetup

    Box(
        Modifier
            .fillMaxSize()
            .background(MColors.Bg)
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!inSetup) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                    Text("Plan", style = MType.Title.copy(fontSize = MType.Title.fontSize * 1.15f), modifier = Modifier.padding(start = 8.dp, top = 8.dp))
                    Spacer(Modifier.height(14.dp))
                    Segmented(listOf("Event", "Monthly"), selected, { tab = it }, Modifier.fillMaxWidth())
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val current = plan
                if (selected == 0) {
                    when {
                        editing -> PlanSetup(
                            onCancel = { editing = false },
                            onCreate = { ExtrasStore.setPlan(it); editing = false },
                        )
                        current == null -> PlanIntro(onCreate = { editing = true })
                        else -> PlanView(current, onStartSession = onStartSession, onNewPlan = { editing = true })
                    }
                } else {
                    MonthlyPlanTab(
                        plan = monthly,
                        eventPlan = current,
                        setup = monthlySetup,
                        onSetup = { monthlySetup = it },
                        onStartSession = onStartSession,
                        onSuggestRoute = onSuggestRoute,
                    )
                }
            }
        }
    }
}

// =================================================================================================
// Intro
// =================================================================================================

@Composable
private fun PlanIntro(onCreate: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Spacer(Modifier.height(22.dp))
        Text("Train for your\nnext event.", style = MType.Hero.copy(fontSize = MType.Hero.fontSize * 0.55f, lineHeight = MType.Hero.lineHeight * 0.6f))
        Spacer(Modifier.height(16.dp))
        Text(
            "Pick your event and its date. Meridian builds a plan that grows gently week by week, " +
                "with rest days, lighter recovery weeks and an easy final week so you arrive fresh.",
            style = MType.Body,
        )
        Spacer(Modifier.height(24.dp))
        listOf(
            "Run, walk, or run-walk: run a little, walk the rest",
            "Targets by distance or by time",
            "A reminder every morning, and a nudge if you haven't gone yet",
            "Your activities tick off sessions automatically",
        ).forEach {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(MIcons.Check, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text(it, style = MType.Body.copy(color = MColors.Text))
            }
        }
        Spacer(Modifier.height(32.dp))
        PillButton("Create a training plan", onClick = onCreate, modifier = Modifier.fillMaxWidth())
    }
}

// =================================================================================================
// Setup
// =================================================================================================

private val EVENT_DISTANCES = listOf(3_000.0 to "3K", 5_000.0 to "5K", 10_000.0 to "10K", 21_097.5 to "21K", 42_195.0 to "42K")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanSetup(onCancel: () -> Unit, onCreate: (TrainingPlan) -> Unit) {
    val activities by ActivityStore.activities.collectAsStateWithLifecycle()
    val today = LocalDate.now()

    var name by rememberSaveable { mutableStateOf("Fun Run") }
    var eventM by rememberSaveable { mutableDoubleStateOf(10_000.0) }
    var customKm by rememberSaveable { mutableStateOf("") }
    var modeName by rememberSaveable { mutableStateOf(PlanMode.RUN_WALK.name) }
    var targetName by rememberSaveable { mutableStateOf(TargetType.DISTANCE.name) }
    var startDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var eventDay by rememberSaveable { mutableLongStateOf(today.plusWeeks(8).with(DayOfWeek.SUNDAY).toEpochDay()) }
    var daysPerWeek by rememberSaveable { mutableIntStateOf(4) }
    var longDayValue by rememberSaveable { mutableIntStateOf(DayOfWeek.SUNDAY.value) }
    var reminderMin by rememberSaveable { mutableIntStateOf(6 * 60) }
    var nudge by rememberSaveable { mutableStateOf(true) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEvent by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    val mode = PlanMode.valueOf(modeName)
    // Sensible defaults from your history: how far and how fast you usually go.
    val relevant = remember(activities, mode) {
        val since = System.currentTimeMillis() - 45L * 86_400_000L
        activities.filter { it.startTimeMs >= since && (if (mode == PlanMode.RUN) it.sport.isRunning else it.sport != Sport.RIDE) }
    }
    var currentLongM by rememberSaveable(modeName) {
        mutableDoubleStateOf((relevant.maxOfOrNull { it.distanceM } ?: 2_000.0).coerceIn(1_000.0, 30_000.0).let { (it / 500).toInt() * 500.0 })
    }
    val pace = relevant.mapNotNull { it.avgPaceSecPerKm }.takeIf { it.size >= 2 }?.average()?.times(1.05)
        ?: PlanGenerator.defaultPace(mode)

    val effectiveEventM = customKm.toDoubleOrNull()?.takeIf { it in 1.0..200.0 }?.let { it * Format.unitMeters } ?: eventM
    val config = PlanConfig(
        name = name.trim().ifEmpty { "My event" },
        mode = mode,
        targetType = TargetType.valueOf(targetName),
        eventDistanceM = effectiveEventM,
        startDate = LocalDate.ofEpochDay(startDay),
        eventDate = LocalDate.ofEpochDay(eventDay),
        daysPerWeek = daysPerWeek,
        longDay = DayOfWeek.of(longDayValue),
        currentLongM = currentLongM,
        paceSecPerKm = pace,
        reminderHour = reminderMin / 60,
        reminderMinute = reminderMin % 60,
        eveningNudge = nudge,
    )
    val valid = config.eventDate.isAfter(config.startDate.plusDays(6))
    val preview = remember(config) { if (valid) PlanGenerator.generate(config, "preview", 0L) else null }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(MIcons.Back, "Cancel", onClick = onCancel)
            Spacer(Modifier.width(14.dp))
            Text("New plan", style = MType.Title)
        }

        PlanLabel("Event")
        Field {
            BasicTextField(
                value = name,
                onValueChange = { if (it.length <= 40) name = it },
                textStyle = MType.Heading,
                singleLine = true,
                cursorBrush = SolidColor(MColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EVENT_DISTANCES.forEach { (m, label) ->
                PlanChip(label, selected = customKm.isBlank() && eventM == m, modifier = Modifier.weight(1f)) {
                    eventM = m
                    customKm = ""
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Field {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Other distance", style = MType.Body, modifier = Modifier.weight(1f))
                BasicTextField(
                    value = customKm,
                    onValueChange = { v -> if (v.length <= 5 && v.all { it.isDigit() || it == '.' }) customKm = v },
                    textStyle = MType.Number.copy(color = MColors.Text),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    cursorBrush = SolidColor(MColors.Accent),
                    modifier = Modifier.width(64.dp),
                )
                Text(Format.distUnit, style = MType.Unit)
            }
        }

        PlanLabel("How you'll do it")
        Segmented(PlanMode.entries.map { it.label }, mode.ordinal, { modeName = PlanMode.entries[it].name }, Modifier.fillMaxWidth())
        Text(
            when (mode) {
                PlanMode.RUN -> "Running sessions with easy runs, a weekly long run, and some faster workouts."
                PlanMode.WALK -> "Walking sessions: easy walks, brisk walks and a weekly long walk."
                PlanMode.RUN_WALK -> "Run a little, walk the rest. The run bits grow as you get fitter. Great for fun runs."
            },
            style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        Segmented(listOf("Distance targets", "Time targets"), if (targetName == TargetType.TIME.name) 1 else 0, {
            targetName = if (it == 1) TargetType.TIME.name else TargetType.DISTANCE.name
        }, Modifier.fillMaxWidth())

        PlanLabel("Dates")
        SectionCard(Modifier.fillMaxWidth()) {
            Column {
                PlanSettingRow("Start training", planDateLabel(config.startDate)) { pickStart = true }
                HorizontalHairline()
                PlanSettingRow("Event day", planDateLabel(config.eventDate)) { pickEvent = true }
            }
        }

        PlanLabel("Your week")
        Segmented(listOf("3 days", "4 days", "5 days", "6 days"), daysPerWeek - 3, { daysPerWeek = it + 3 }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text("Longest session on", style = MType.Body, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { d ->
                PlanChip(d.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3), selected = d.value == longDayValue, modifier = Modifier.weight(1f)) {
                    longDayValue = d.value
                }
            }
        }

        PlanLabel("Where you're starting")
        SectionCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Comfortable distance today", style = MType.Body.copy(color = MColors.Text))
                    Text("The furthest you can go now without struggling.", style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.85f))
                }
                CircleIconButton(MIcons.ChevronLeft, "Less", onClick = { currentLongM = (currentLongM - 500).coerceAtLeast(500.0) }, size = 40.dp)
                Text(
                    "${Format.dist1(currentLongM)} ${Format.distUnit}",
                    style = MType.Number,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                CircleIconButton(MIcons.ChevronRight, "More", onClick = { currentLongM = (currentLongM + 500).coerceAtMost(40_000.0) }, size = 40.dp)
            }
        }

        PlanLabel("Reminders")
        SectionCard(Modifier.fillMaxWidth()) {
            Column {
                PlanSettingRow("Morning reminder", planTimeLabel(reminderMin)) { pickTime = true }
                HorizontalHairline()
                PlanSettingRow("Evening nudge at 7 PM", if (nudge) "On" else "Off", accent = nudge) { nudge = !nudge }
            }
        }

        Spacer(Modifier.height(22.dp))
        if (!valid) {
            PlanNote("Pick an event day at least a week after you start.", warn = true)
        } else if (preview != null) {
            PlanNote(
                "${preview.weeks} weeks · ${preview.sessions.size} sessions · longest before the event: " +
                    "${Format.dist1(preview.longestM)} ${Format.distUnit}",
                warn = false,
            )
            if (preview.compressed) {
                Spacer(Modifier.height(8.dp))
                PlanNote(
                    "That's a short runway for this distance (ideally ${PlanGenerator.minWeeks(effectiveEventM)}+ weeks). " +
                        "The plan still works, but consider walking more of the event and taking it easy.",
                    warn = true,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        PillButton(
            "Create plan",
            icon = MIcons.Check,
            filled = valid,
            onClick = {
                if (valid) onCreate(PlanGenerator.generate(config, UUID.randomUUID().toString(), System.currentTimeMillis()))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "A general plan, not medical advice. If you're new to exercise or have health concerns, check with a doctor first. " +
                "Skip a day whenever your body asks for it.",
            style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.82f, color = MColors.Faint),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(28.dp))
    }

    if (pickStart) PlanDateDialog(config.startDate, { startDay = it.toEpochDay(); pickStart = false }, { pickStart = false })
    if (pickEvent) PlanDateDialog(config.eventDate, { eventDay = it.toEpochDay(); pickEvent = false }, { pickEvent = false })
    if (pickTime) PlanTimeDialog(reminderMin, { reminderMin = it; pickTime = false }, { pickTime = false })
}

// =================================================================================================
// Plan view
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanView(plan: TrainingPlan, onStartSession: () -> Unit, onNewPlan: () -> Unit) {
    val activities by ActivityStore.activities.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val status = remember(plan, activities, today) { PlanGenerator.status(plan, activities, today) }
    val days = remember(plan, status) { PlanGenerator.adjusted(plan, status) }
    val (done, total) = PlanGenerator.completion(plan, status)
    val cfg = plan.config
    val daysLeft = ChronoUnit.DAYS.between(today, cfg.eventDate)
    var confirmDelete by remember { mutableStateOf(false) }
    var editTime by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    // The day open in the editor (epoch day), or -1.
    var editingDay by rememberSaveable(plan.id) { mutableLongStateOf(-1L) }

    // Plan finished: award the badge when most sessions and the event itself were done.
    LaunchedEffect(status) {
        val eventDone = status[cfg.eventDate] == DayStatus.DONE
        if (eventDone && total > 0 && done >= total * 0.8) ExtrasStore.markPlanCompleted(System.currentTimeMillis())
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        // Hero
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(MColors.Accent)
                .padding(22.dp)
        ) {
            Text(
                "${cfg.mode.label.uppercase()} · ${Format.dist1(cfg.eventDistanceM)} ${Format.distUnit.uppercase()} · ${planDateLabel(cfg.eventDate).uppercase()}",
                style = MType.Label.copy(color = MColors.OnAccent.copy(alpha = 0.75f)),
            )
            Spacer(Modifier.height(6.dp))
            Text(cfg.name, style = MType.Title.copy(color = MColors.OnAccent), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    when {
                        daysLeft > 0 -> "$daysLeft"
                        daysLeft == 0L -> "Today"
                        else -> "Done"
                    },
                    style = MType.Hero.copy(color = MColors.OnAccent, fontSize = MType.Hero.fontSize * 0.8f, lineHeight = MType.Hero.lineHeight * 0.8f),
                )
                if (daysLeft > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(if (daysLeft == 1L) "day to go" else "days to go", style = MType.Unit.copy(color = MColors.OnAccent), modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MColors.OnAccent.copy(alpha = 0.15f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (total > 0) (done.toFloat() / total).coerceIn(0.02f, 1f) else 0.02f)
                        .height(8.dp)
                        .background(MColors.OnAccent)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("$done OF $total SESSIONS DONE", style = MType.Label.copy(color = MColors.OnAccent.copy(alpha = 0.8f)))
        }

        // Today
        val todayDay = days.firstOrNull { it.date == today }
        if (todayDay != null) {
            Spacer(Modifier.height(14.dp))
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("TODAY", style = MType.Label.copy(color = MColors.Accent))
                    Spacer(Modifier.height(6.dp))
                    Text(todayDay.title, style = MType.Title)
                    if (!todayDay.isRest) {
                        Text(targetLabel(plan, todayDay), style = MType.Number.copy(fontSize = MType.Number.fontSize * 1.3f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(todayDay.detail, style = MType.Body)
                    if (!todayDay.isRest) {
                        Spacer(Modifier.height(16.dp))
                        if (status[today] == DayStatus.DONE) {
                            Text("DONE. NICE WORK.", style = MType.Label.copy(color = MColors.Accent))
                        } else {
                            PillButton(
                                "Start today's session",
                                icon = MIcons.Record,
                                onClick = {
                                    TrackingManager.setSport(if (cfg.mode == PlanMode.RUN) Sport.RUN else Sport.WALK)
                                    TrackingManager.setTarget(
                                        SessionTarget(
                                            title = todayDay.title,
                                            distanceM = if (cfg.targetType == TargetType.DISTANCE) todayDay.distanceM else null,
                                            minutes = if (cfg.targetType == TargetType.TIME) todayDay.minutes else null,
                                        )
                                    )
                                    onStartSession()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        // Weeks
        days.groupBy { it.week }.forEach { (week, weekDays) ->
            val first = weekDays.first().date
            val isPast = weekDays.last().date.isBefore(today)
            Text(
                "WEEK ${week + 1} · ${planDateShort(first).uppercase()}" + if (weekDays.any { it.detail.contains("Recovery week") }) " · LIGHTER WEEK" else "",
                style = MType.Label,
                modifier = Modifier.padding(start = 8.dp, top = 26.dp, bottom = 10.dp),
            )
            SectionCard(Modifier.fillMaxWidth().alpha(if (isPast) 0.75f else 1f)) {
                Column {
                    weekDays.forEachIndexed { i, d ->
                        if (i > 0) HorizontalHairline()
                        val st = status[d.date] ?: DayStatus.UPCOMING
                        val canEdit = PlanEdits.canEdit(plan, d.date, today) && st != DayStatus.DONE
                        DayRow(
                            plan, d, st, isToday = d.date == today,
                            onClick = if (canEdit) ({ editingDay = d.date.toEpochDay() }) else null,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Reminder ${planTimeLabel(cfg.reminderHour * 60 + cfg.reminderMinute)}", onClick = { editTime = true }, filled = false, modifier = Modifier.weight(1f), height = 52.dp)
            PillButton("Delete plan", onClick = { confirmDelete = true }, filled = false, modifier = Modifier.weight(1f), height = 52.dp)
        }
        if (plan.originals.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            PillButton(
                "Reset ${plan.originals.size} edited ${if (plan.originals.size == 1) "day" else "days"}",
                onClick = { confirmResetAll = true },
                filled = false,
                modifier = Modifier.fillMaxWidth(),
                height = 52.dp,
            )
        }
        if (daysLeft < 0) {
            Spacer(Modifier.height(10.dp))
            PillButton("Plan your next event", onClick = onNewPlan, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(28.dp))
    }

    if (editTime) {
        PlanTimeDialog(cfg.reminderHour * 60 + cfg.reminderMinute, { minutes ->
            // Only the reminder changes: keep every day, including the ones you edited.
            ExtrasStore.setPlan(plan.copy(config = cfg.copy(reminderHour = minutes / 60, reminderMinute = minutes % 60)))
            editTime = false
        }, { editTime = false })
    }
    val editDate = if (editingDay >= 0) LocalDate.ofEpochDay(editingDay) else null
    // The day as shown (a missed long session may have been held back), so the editor starts from what you see.
    val editDay = editDate?.let { d -> days.firstOrNull { it.date == d } }
    if (editDate != null && editDay != null && PlanEdits.canEdit(plan, editDate, today)) {
        val kinds = PlanEdits.kinds(cfg.mode)
        val byTime = cfg.targetType == TargetType.TIME
        val model = remember(plan, editDate) {
            DayEditModel(
                date = editDate,
                title = editDay.title,
                isRest = editDay.isRest,
                edited = editDay.edited,
                byTime = byTime,
                sports = emptyList(),
                sportKey = null,
                kinds = { kinds.map { EditChoice(it.name, PlanEdits.label(it, cfg.mode)) } },
                kindKey = (editDay.kind.takeIf { it in kinds } ?: kinds.first()).name,
                amount = when {
                    editDay.isRest -> PlanEdits.defaultAmount(plan)
                    byTime -> editDay.minutes.toDouble()
                    else -> editDay.distanceM
                },
                range = { _, _ -> PlanEdits.range(plan) },
                moveTargets = PlanEdits.moveTargets(plan, editDate, today),
                warnings = { _, kind, amount ->
                    PlanEdits.setSession(plan, editDate, SessionKind.valueOf(kind), amount, today)
                        ?.let { PlanEdits.warnings(it, editDate) }.orEmpty()
                },
            )
        }
        DayEditSheet(
            model = model,
            onSave = { _, kind, amount ->
                PlanEdits.setSession(plan, editDate, SessionKind.valueOf(kind), amount, today)?.let { ExtrasStore.setPlan(it) }
                editingDay = -1L
            },
            onRest = { PlanEdits.makeRest(plan, editDate, today)?.let { ExtrasStore.setPlan(it) }; editingDay = -1L },
            onMove = { to -> PlanEdits.move(plan, editDate, to, today)?.let { ExtrasStore.setPlan(it) }; editingDay = -1L },
            onReset = { ExtrasStore.setPlan(PlanEdits.reset(plan, editDate)); editingDay = -1L },
            onDismiss = { editingDay = -1L },
        )
    }
    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            containerColor = MColors.Surface2,
            title = { Text("Reset all your edits?", style = MType.Heading) },
            text = { Text("Every edited day goes back to how Meridian planned it. Your activities stay.", style = MType.Body) },
            confirmButton = {
                TextButton(onClick = { ExtrasStore.setPlan(PlanEdits.resetAll(plan)); confirmResetAll = false }) {
                    Text("Reset", style = MType.Button.copy(color = MColors.Danger))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = MColors.Surface2,
            title = { Text("Delete this plan?", style = MType.Heading) },
            text = { Text("Your activities stay. Only the plan and its reminders are removed.", style = MType.Body) },
            confirmButton = {
                TextButton(onClick = { ExtrasStore.setPlan(null); confirmDelete = false }) {
                    Text("Delete", style = MType.Button.copy(color = MColors.Danger))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) }
            },
        )
    }
}

@Composable
private fun DayRow(plan: TrainingPlan, day: PlanDay, status: DayStatus, isToday: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isToday) MColors.Accent.copy(alpha = 0.08f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Edit this day", onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(46.dp)) {
            Text(day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(), style = MType.Label)
            Text("${day.date.dayOfMonth}", style = MType.Number.copy(color = if (isToday) MColors.Accent else MColors.Text))
        }
        Column(Modifier.weight(1f)) {
            Text(
                day.title,
                style = MType.Body.copy(color = if (day.isRest) MColors.Muted else MColors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!day.isRest) {
                Text(targetLabel(plan, day) + if (day.edited) " · EDITED" else "", style = MType.Label)
            } else if (day.edited) {
                Text("EDITED", style = MType.Label)
            }
        }
        StatusMark(status, day.kind == SessionKind.RACE)
        if (onClick != null) {
            Spacer(Modifier.width(10.dp))
            Icon(MIcons.Edit, contentDescription = "Edit", tint = MColors.Faint, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun StatusMark(status: DayStatus, race: Boolean) {
    val (text, color, bg) = when (status) {
        DayStatus.DONE -> Triple("DONE", MColors.OnAccent, MColors.Accent)
        DayStatus.PARTIAL -> Triple("PART", MColors.Text, MColors.Surface2)
        DayStatus.MISSED -> Triple("MISSED", MColors.Danger, Color.Transparent)
        DayStatus.TODAY -> Triple("TODAY", MColors.Accent, Color.Transparent)
        DayStatus.UPCOMING -> if (race) Triple("EVENT", MColors.Accent, Color.Transparent) else Triple("", MColors.Faint, Color.Transparent)
        DayStatus.REST -> Triple("REST", MColors.Faint, Color.Transparent)
        DayStatus.MOVED -> Triple("MOVED", MColors.Faint, Color.Transparent)
    }
    if (text.isNotEmpty()) {
        Text(
            text,
            style = MType.Label.copy(color = color),
            modifier = Modifier
                .clip(CircleShape)
                .background(bg)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

// =================================================================================================
// Pieces
// =================================================================================================

private fun targetLabel(plan: TrainingPlan, day: PlanDay): String =
    if (plan.config.targetType == TargetType.TIME) "${day.minutes} min" else "${Format.km(day.distanceM)} ${Format.distUnit}"

@Composable
internal fun PlanLabel(text: String) {
    Text(text.uppercase(), style = MType.Label, modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 10.dp))
}

@Composable
internal fun PlanField(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MColors.Surface)
            .border(1.dp, MColors.Hairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) { content() }
}

@Composable
internal fun PlanChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (selected) MColors.Accent else MColors.Surface)
            .border(1.dp, if (selected) MColors.Accent else MColors.Stroke, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MType.Label.copy(color = if (selected) MColors.OnAccent else MColors.Text), maxLines = 1)
    }
}

@Composable
internal fun PlanSettingRow(title: String, value: String, accent: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MType.Body.copy(color = MColors.Text), modifier = Modifier.weight(1f))
        Text(value, style = MType.Number.copy(color = if (accent) MColors.Accent else MColors.Muted))
    }
}

@Composable
internal fun PlanNote(text: String, warn: Boolean) {
    Text(
        text,
        style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.9f, color = if (warn) MColors.Danger else MColors.Text),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (warn) MColors.Danger.copy(alpha = 0.1f) else MColors.Surface)
            .padding(14.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanDateDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("OK", style = MType.Button.copy(color = MColors.Accent)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) } },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanTimeDialog(initialMinutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initialMinutes / 60, initialMinute = initialMinutes % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MColors.Surface2,
        title = { Text("Reminder time", style = MType.Heading) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK", style = MType.Button.copy(color = MColors.Accent)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) } },
    )
}

private val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
private val shortFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
internal fun planDateLabel(d: LocalDate) = d.format(dateFmt)
internal fun planDateShort(d: LocalDate) = d.format(shortFmt)
internal fun planTimeLabel(minutes: Int) = LocalTime.of(minutes / 60, minutes % 60).format(timeFmt)
