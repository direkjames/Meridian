package com.meridian.tracker.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.DayStatus
import com.meridian.tracker.data.ExtrasStore
import com.meridian.tracker.data.MonthConfig
import com.meridian.tracker.data.MonthDay
import com.meridian.tracker.data.MonthGoal
import com.meridian.tracker.data.MonthSport
import com.meridian.tracker.data.MonthKind
import com.meridian.tracker.data.CatchUp
import com.meridian.tracker.data.HoldWeek
import com.meridian.tracker.data.MonthlyCatchUp
import com.meridian.tracker.data.MonthlyEdits
import com.meridian.tracker.data.MonthlyProgress
import com.meridian.tracker.data.Pace
import com.meridian.tracker.data.PaceCheck
import com.meridian.tracker.data.WeekStat
import com.meridian.tracker.data.MonthTheme
import com.meridian.tracker.data.MonthlyPlan
import com.meridian.tracker.data.MonthlyPlanner
import com.meridian.tracker.data.MonthReview
import com.meridian.tracker.data.MonthReviews
import com.meridian.tracker.data.MonthlyRoutes
import com.meridian.tracker.data.RouteIdea
import com.meridian.tracker.data.TargetType
import com.meridian.tracker.data.TrainingPlan
import com.meridian.tracker.tracking.SessionTarget
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

/** The Monthly side of the Plan tab: a month of mixed sessions, different every month. */
@Composable
fun MonthlyPlanTab(
    plan: MonthlyPlan?,
    eventPlan: TrainingPlan?,
    setup: Boolean,
    onSetup: (Boolean) -> Unit,
    onStartSession: () -> Unit,
    onSuggestRoute: (RouteIdea) -> Unit = {},
) {
    when {
        setup -> MonthlySetup(
            previous = plan,
            onCancel = { onSetup(false) },
            onCreate = { ExtrasStore.setMonthly(it); onSetup(false) },
        )
        plan == null -> MonthlyIntro(onCreate = { onSetup(true) })
        else -> MonthlyView(plan, eventPlan, onStartSession = onStartSession, onSuggestRoute = onSuggestRoute, onNewMonth = { onSetup(true) })
    }
}

// =================================================================================================
// Intro
// =================================================================================================

@Composable
private fun MonthlyIntro(onCreate: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Spacer(Modifier.height(22.dp))
        Text("A fresh plan,\nevery month.", style = MType.Hero.copy(fontSize = MType.Hero.fontSize * 0.55f, lineHeight = MType.Hero.lineHeight * 0.6f))
        Spacer(Modifier.height(16.dp))
        Text(
            "No event needed. Pick what you like to do and how often, and Meridian plans a month of walks, runs, " +
                "hikes and rides that fits your level, with an easier week to recover.",
            style = MType.Body,
        )
        Spacer(Modifier.height(24.dp))
        listOf(
            "Every month has its own theme, so it never feels the same",
            "Mix sports: walk, run, run-walk, hike and ride",
            "A calendar that fills in as you go",
            "Runs alongside your event plan",
        ).forEach {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(MIcons.Check, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text(it, style = MType.Body.copy(color = MColors.Text))
            }
        }
        Spacer(Modifier.height(32.dp))
        PillButton("Create a monthly plan", onClick = onCreate, modifier = Modifier.fillMaxWidth())
    }
}

// =================================================================================================
// Setup
// =================================================================================================

private val THEME_CHIPS = listOf<Pair<String, MonthTheme?>>(
    "Auto" to null,
    "Base" to MonthTheme.BASE,
    "Speed" to MonthTheme.SPEED,
    "Long" to MonthTheme.DISTANCE,
    "Mixed" to MonthTheme.MIXED,
)

@Composable
private fun MonthlySetup(previous: MonthlyPlan?, onCancel: () -> Unit, onCreate: (MonthlyPlan) -> Unit) {
    val activities by ActivityStore.activities.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val prevCfg = previous?.config

    // This month (from today) or next month.
    val thisLeft = MonthlyPlanner.daysLeft(today)
    val canThis = thisLeft >= MonthlyPlanner.MIN_DAYS
    val nextStart = MonthlyPlanner.monthEnd(today).plusDays(1)
    var useNext by rememberSaveable {
        // After a finished or ending month, the next one is the natural choice.
        mutableStateOf(!canThis || thisLeft < 10 || (previous != null && !previous.config.endDate.isAfter(MonthlyPlanner.monthEnd(today))))
    }

    var goalName by rememberSaveable { mutableStateOf(prevCfg?.goal?.name ?: MonthGoal.FITTER.name) }
    var sportNames by rememberSaveable { mutableStateOf(prevCfg?.sports?.joinToString(",") { it.name } ?: "${MonthSport.WALK.name},${MonthSport.RUN.name}") }
    var themeName by rememberSaveable { mutableStateOf("") } // blank = auto
    var daysPerWeek by rememberSaveable { mutableIntStateOf(prevCfg?.daysPerWeek ?: 4) }
    var freeMask by rememberSaveable { mutableIntStateOf(prevCfg?.freeDays?.takeIf { it.isNotEmpty() }?.fold(0) { m, d -> m or (1 shl d.value) } ?: ALL_DAYS) }
    var longDayValue by rememberSaveable { mutableIntStateOf(prevCfg?.longDay?.value ?: DayOfWeek.SUNDAY.value) }
    var targetName by rememberSaveable { mutableStateOf(prevCfg?.targetType?.name ?: TargetType.DISTANCE.name) }
    var targetText by rememberSaveable { mutableStateOf("") }
    var seed by rememberSaveable { mutableLongStateOf(Random.nextLong()) }
    var remindOn by rememberSaveable { mutableStateOf(prevCfg?.remind ?: true) }
    var reminderMin by rememberSaveable { mutableIntStateOf((prevCfg?.reminderHour ?: 7) * 60 + (prevCfg?.reminderMinute ?: 0)) }
    var nudge by rememberSaveable { mutableStateOf(prevCfg?.eveningNudge ?: true) }
    var pickTime by remember { mutableStateOf(false) }

    val sports = sportNames.split(',').mapNotNull { n -> runCatching { MonthSport.valueOf(n) }.getOrNull() }.distinct()
    val goal = MonthGoal.valueOf(goalName)
    val targetType = TargetType.valueOf(targetName)
    val freeDays = DayOfWeek.entries.filter { freeMask and (1 shl it.value) != 0 }.toSet()

    val nowMs = remember { System.currentTimeMillis() }
    // How the last month went nudges the size of the next one (only once it's nearly over).
    val prevReview = remember(previous, activities, today) {
        previous?.let { MonthReviews.review(it, activities, MonthlyPlanner.status(it, activities, today)) }
    }
    val usual = remember(activities, sportNames, daysPerWeek, prevReview) {
        MonthReviews.nextWeekMinutes(MonthlyPlanner.usualWeekMinutes(activities, sports, daysPerWeek, nowMs), previous, prevReview, today)
    }
    var weeklyMin by rememberSaveable(sportNames) { mutableDoubleStateOf(usual) }
    val paces = remember(activities) { MonthlyPlanner.paces(activities, nowMs) }

    val start = if (useNext) nextStart else today
    val end = MonthlyPlanner.monthEnd(start)
    val monthIndex = (prevCfg?.monthIndex ?: -1) + 1
    val autoTheme = MonthlyPlanner.themeFor(goal, monthIndex, prevCfg?.theme)
    val theme = themeName.takeIf { it.isNotEmpty() }?.let { MonthTheme.valueOf(it) } ?: autoTheme
    // Hours for time targets, km or miles for distance targets.
    val target = targetText.toDoubleOrNull()?.takeIf { it > 0 && it < 10_000 }?.let {
        if (targetType == TargetType.TIME) it * 60.0 else it * Format.unitMeters
    }
    val longDay = DayOfWeek.of(longDayValue).let { if (freeDays.isEmpty() || it in freeDays) it else freeDays.max() }
    val valid = sports.isNotEmpty() && freeDays.isNotEmpty()

    val config = MonthConfig(
        startDate = start,
        endDate = end,
        goal = goal,
        theme = theme,
        sports = sports,
        daysPerWeek = daysPerWeek,
        freeDays = if (freeDays.size == 7) emptySet() else freeDays,
        longDay = longDay,
        targetType = targetType,
        weeklyMinutes = weeklyMin,
        monthTarget = target,
        paces = paces,
        monthIndex = monthIndex,
        seed = seed,
        prevTotalMin = previous?.totalMinutes?.toDouble()?.takeIf { it > 0 },
        remind = remindOn,
        reminderHour = reminderMin / 60,
        reminderMinute = reminderMin % 60,
        eveningNudge = nudge,
    )
    val preview = remember(config, valid) { if (valid) MonthlyPlanner.generate(config, "preview", 0L) else null }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(MIcons.Back, "Cancel", onClick = onCancel)
            Spacer(Modifier.width(14.dp))
            Text(if (previous == null) "New monthly plan" else "Plan a new month", style = MType.Title)
        }

        PlanLabel("Month")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceCard(
                title = "Rest of ${monthName(today)}",
                subtitle = if (canThis) "From today · $thisLeft days" else "Too few days left",
                selected = !useNext,
                enabled = canThis,
                modifier = Modifier.weight(1f),
            ) { useNext = false }
            ChoiceCard(
                title = monthName(nextStart),
                subtitle = "${nextStart.lengthOfMonth()} days",
                selected = useNext,
                modifier = Modifier.weight(1f),
            ) { useNext = true }
        }

        PlanLabel("Your goal")
        MonthGoal.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { g ->
                    ChoiceCard(g.label, g.blurb, selected = g == goal, modifier = Modifier.weight(1f)) {
                        goalName = g.name
                    }
                }
            }
        }

        PlanLabel("Sports")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MonthSport.entries.forEach { s ->
                val on = s in sports
                PlanChip(s.label, selected = on, modifier = Modifier.weight(1f)) {
                    val next = if (on) sports - s else sports + s
                    if (next.isNotEmpty()) sportNames = next.joinToString(",") { it.name }
                }
            }
        }
        Text(
            if (sports.size > 1) "Main sport: ${sports.first().label}. It gets the long session; the others mix in on easy days."
            else "Add a second sport for some cross-training. The first one you pick is your main sport.",
            style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )

        PlanLabel("Theme")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            THEME_CHIPS.forEach { (label, t) ->
                PlanChip(label, selected = (t?.name ?: "") == themeName, modifier = Modifier.weight(1f)) { themeName = t?.name ?: "" }
            }
        }
        Text(
            (if (themeName.isEmpty()) "Auto picks ${autoTheme.label.lowercase()} this time. " else "") + theme.tagline,
            style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )

        PlanLabel("Your week")
        Segmented(listOf("2", "3", "4", "5", "6"), daysPerWeek - 2, { daysPerWeek = it + 2 }, Modifier.fillMaxWidth())
        Text("Sessions per week", style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f), modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
        Text("Days you're free", style = MType.Body, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { d ->
                val on = d in freeDays
                PlanChip(shortDay(d), selected = on, modifier = Modifier.weight(1f)) {
                    val next = freeMask xor (1 shl d.value)
                    if (next != 0) freeMask = next
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Longest session on", style = MType.Body, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { d ->
                val free = d in freeDays
                Box(Modifier.weight(1f).alpha(if (free) 1f else 0.35f)) {
                    PlanChip(shortDay(d), selected = d == longDay, modifier = Modifier.fillMaxWidth()) { if (free) longDayValue = d.value }
                }
            }
        }
        if (daysPerWeek > minOf(6, freeDays.size)) {
            Spacer(Modifier.height(10.dp))
            PlanNote("You're free ${freeDays.size} days, so the plan uses ${minOf(6, freeDays.size)} sessions a week and keeps a rest day.", warn = false)
        }

        PlanLabel("Where you're starting")
        if (previous != null && prevReview != null && MonthReviews.due(previous, today)) {
            PlanNote("${monthName(previous.config.startDate)}: ${prevReview.percent}% of sessions done. ${prevReview.note}", warn = false)
            Spacer(Modifier.height(8.dp))
        }
        SectionCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("A usual week now", style = MType.Body.copy(color = MColors.Text))
                    Text(
                        if (activities.isEmpty()) "Your best guess. Be honest, it keeps the plan safe." else "From your last 4 weeks. Change it if that's off.",
                        style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.85f),
                    )
                }
                CircleIconButton(MIcons.ChevronLeft, "Less", onClick = { weeklyMin = (weeklyMin - 15).coerceAtLeast(40.0) }, size = 40.dp)
                Text(hoursMinutes(weeklyMin.roundToInt()), style = MType.Number, modifier = Modifier.padding(horizontal = 8.dp))
                CircleIconButton(MIcons.ChevronRight, "More", onClick = { weeklyMin = (weeklyMin + 15).coerceAtMost(900.0) }, size = 40.dp)
            }
        }

        PlanLabel("Targets")
        Segmented(listOf("Distance targets", "Time targets"), if (targetType == TargetType.TIME) 1 else 0, {
            targetName = if (it == 1) TargetType.TIME.name else TargetType.DISTANCE.name
            targetText = ""
        }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        PlanField {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Month target", style = MType.Body.copy(color = MColors.Text))
                    Text("Optional. Leave empty and Meridian decides.", style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.85f))
                }
                BasicTextField(
                    value = targetText,
                    onValueChange = { v -> if (v.length <= 5 && v.count { it == '.' } <= 1 && v.all { it.isDigit() || it == '.' }) targetText = v },
                    textStyle = MType.Number.copy(color = MColors.Text, textAlign = TextAlign.End),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    cursorBrush = SolidColor(MColors.Accent),
                    modifier = Modifier.width(64.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (targetType == TargetType.TIME) "h" else Format.distUnit, style = MType.Unit)
            }
        }

        PlanLabel("Reminders")
        SectionCard(Modifier.fillMaxWidth()) {
            Column {
                PlanSettingRow("Daily reminder", if (remindOn) "On" else "Off", accent = remindOn) { remindOn = !remindOn }
                if (remindOn) {
                    HorizontalHairline()
                    PlanSettingRow("Morning reminder", planTimeLabel(reminderMin)) { pickTime = true }
                    HorizontalHairline()
                    PlanSettingRow("Evening nudge at 7 PM", if (nudge) "On" else "Off", accent = nudge) { nudge = !nudge }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        if (!valid) {
            PlanNote("Pick at least one sport and one free day.", warn = true)
        } else if (preview != null) {
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("${preview.config.theme.label.uppercase()} · ${monthName(start).uppercase()}", style = MType.Label.copy(color = MColors.Accent))
                    Spacer(Modifier.height(10.dp))
                    Row {
                        PreviewStat("${preview.sessions.size}", "sessions", Modifier.weight(1f))
                        PreviewStat(Format.dist1(preview.totalM), Format.distUnit, Modifier.weight(1f))
                        PreviewStat(hoursMinutes(preview.totalMinutes), "moving", Modifier.weight(1.3f))
                    }
                    Spacer(Modifier.height(12.dp))
                    MiniWeeks(preview)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Shuffle for a different mix",
                        style = MType.Label.copy(color = MColors.Accent),
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { seed = Random.nextLong() }
                            .padding(vertical = 6.dp),
                    )
                }
            }
            preview.targetShare?.takeIf { it < 0.97 }?.let { share ->
                Spacer(Modifier.height(8.dp))
                PlanNote(
                    "To keep it safe, this month reaches about ${(share * 100).roundToInt()}% of your target. " +
                        "Next month can go further.",
                    warn = true,
                )
            }
            if (previous != null && !today.isAfter(previous.config.endDate)) {
                Spacer(Modifier.height(8.dp))
                PlanNote("This replaces your current monthly plan right away, including any days it has left. Your activities stay.", warn = true)
            }
        }
        Spacer(Modifier.height(16.dp))
        PillButton(
            "Create plan",
            icon = MIcons.Check,
            filled = valid,
            onClick = {
                if (valid) onCreate(MonthlyPlanner.generate(config, UUID.randomUUID().toString(), System.currentTimeMillis()))
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
    if (pickTime) PlanTimeDialog(reminderMin, { reminderMin = it; pickTime = false }, { pickTime = false })
}

private const val ALL_DAYS = 0b1111_1110 // bits 1..7, Monday to Sunday

@Composable
private fun ChoiceCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (selected) MColors.Accent.copy(alpha = 0.12f) else MColors.Surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) MColors.Accent else MColors.Hairline, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(title, style = MType.Body.copy(color = if (selected) MColors.Accent else MColors.Text), maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.82f), maxLines = 2)
    }
}

@Composable
private fun PreviewStat(value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MType.Number.copy(fontSize = MType.Number.fontSize * 1.2f))
        Text(unit.uppercase(), style = MType.Label)
    }
}

/** Little bars showing how each week's load rises and falls. */
@Composable
private fun MiniWeeks(plan: MonthlyPlan) {
    val weeks = plan.days.groupBy { it.week }.toSortedMap().values.map { w -> w.sumOf { it.minutes } }
    val max = (weeks.maxOrNull() ?: 1).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        weeks.forEachIndexed { i, m ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((34f * m / max).coerceAtLeast(3f).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (i == plan.easyWeek) MColors.Bar else MColors.Accent)
                )
                Spacer(Modifier.height(4.dp))
                Text(if (i == plan.easyWeek) "EASY" else "W${i + 1}", style = MType.Label.copy(fontSize = MType.Label.fontSize * 0.85f))
            }
        }
    }
}

// =================================================================================================
// Plan view
// =================================================================================================

@Composable
private fun MonthlyView(plan: MonthlyPlan, eventPlan: TrainingPlan?, onStartSession: () -> Unit, onSuggestRoute: (RouteIdea) -> Unit, onNewMonth: () -> Unit) {
    val activities by ActivityStore.activities.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val status = remember(plan, activities, today) { MonthlyPlanner.status(plan, activities, today) }
    val progress = remember(plan, activities) { MonthlyPlanner.progress(plan, activities) }
    val (done, total) = MonthlyPlanner.completion(plan, status)
    val cfg = plan.config
    val weekStats = remember(plan, activities, status) { MonthlyProgress.weeks(plan, activities, status) }
    val pace = remember(plan, activities, status, today) { MonthlyProgress.paceCheck(plan, activities, status, today) }
    val catchUps = remember(plan, status, today) { MonthlyCatchUp.suggestions(plan, status, today) }
    val hold = remember(plan, status, today) { MonthlyCatchUp.hold(plan, status, today) }
    var selectedDay by rememberSaveable(plan.id) {
        mutableLongStateOf(today.clampTo(cfg.startDate, cfg.endDate).toEpochDay())
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    var editingDay by rememberSaveable(plan.id) { mutableLongStateOf(-1L) }
    val ended = today.isAfter(cfg.endDate)
    val reviewDue = MonthReviews.due(plan, today) && !today.isBefore(cfg.startDate)
    val review = remember(plan, activities, status, reviewDue) { if (reviewDue) MonthReviews.review(plan, activities, status) else null }
    val upcoming = today.isBefore(cfg.startDate)

    // Days the event plan also has a session: it comes first.
    val eventDays = remember(eventPlan, plan) {
        eventPlan?.days.orEmpty().filter { !it.isRest && !it.date.isBefore(cfg.startDate) && !it.date.isAfter(cfg.endDate) }
            .associateBy { it.date }
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
                "${cfg.theme.label.uppercase()} · ${cfg.goal.label.uppercase()}",
                style = MType.Label.copy(color = MColors.OnAccent.copy(alpha = 0.75f)),
            )
            Spacer(Modifier.height(6.dp))
            Text(monthName(cfg.startDate), style = MType.Title.copy(color = MColors.OnAccent))
            Spacer(Modifier.height(4.dp))
            Text(cfg.theme.tagline, style = MType.Body.copy(color = MColors.OnAccent.copy(alpha = 0.85f)))
            Spacer(Modifier.height(16.dp))
            Row {
                HeroStat(Format.dist1(plan.totalM), Format.distUnit.uppercase(), Modifier.weight(1f))
                HeroStat(hoursMinutes(plan.totalMinutes), "PLANNED", Modifier.weight(1.3f))
                HeroStat("${plan.sessions.size}", "SESSIONS", Modifier.weight(1f))
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
            Text(
                when {
                    upcoming -> "STARTS ${planDateShort(cfg.startDate).uppercase()}"
                    else -> "$done OF $total SESSIONS DONE"
                },
                style = MType.Label.copy(color = MColors.OnAccent.copy(alpha = 0.8f)),
            )
        }

        if (eventDays.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            PlanNote(
                "Your event plan runs this month too. On the ${eventDays.size} days with a dot in the corner, do the event session; " +
                    "the monthly one is optional.",
                warn = false,
            )
        }

        if (review != null) {
            Spacer(Modifier.height(14.dp))
            ReviewCard(review, ended, onNext = onNewMonth)
        }

        // Today
        val todayDay = plan.day(today)
        if (todayDay != null) {
            Spacer(Modifier.height(14.dp))
            DayCard(
                plan, todayDay, status[today], eventDays[today]?.title, isToday = true, onStartSession = onStartSession,
                onSuggestRoute = onSuggestRoute,
                movedTo = movedTo(plan, todayDay),
                onEdit = if (MonthlyEdits.canEdit(plan, today, today) && status[today] != DayStatus.DONE) ({ editingDay = today.toEpochDay() }) else null,
            )
        }

        if (catchUps.isNotEmpty() || hold != null) {
            Spacer(Modifier.height(14.dp))
            CatchUpCard(
                items = catchUps,
                hold = hold,
                onMove = { c -> MonthlyCatchUp.apply(plan, c, today)?.let { ExtrasStore.setMonthly(it) } },
                onSkip = { c -> ExtrasStore.setMonthly(MonthlyCatchUp.dismiss(plan, c.missed.date)) },
                onHold = { h -> ExtrasStore.setMonthly(MonthlyCatchUp.applyHold(plan, h, today)) },
                onKeep = { h -> ExtrasStore.setMonthly(MonthlyCatchUp.dismiss(plan, h.key)) },
            )
        }

        // Calendar
        PlanLabel("Calendar")
        SectionCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                MonthCalendar(
                    plan = plan,
                    status = status,
                    progress = progress,
                    eventDays = eventDays.keys,
                    today = today,
                    selected = LocalDate.ofEpochDay(selectedDay),
                    onSelect = { selectedDay = it.toEpochDay() },
                )
                Spacer(Modifier.height(12.dp))
                HorizontalHairline()
                Spacer(Modifier.height(10.dp))
                Legend(cfg.sports)
            }
        }
        val picked = plan.day(LocalDate.ofEpochDay(selectedDay))
        if (picked != null && picked.date != today) {
            Spacer(Modifier.height(10.dp))
            DayCard(
                plan, picked, status[picked.date], eventDays[picked.date]?.title, isToday = false, onStartSession = onStartSession,
                onSuggestRoute = onSuggestRoute,
                movedTo = movedTo(plan, picked),
                onEdit = if (MonthlyEdits.canEdit(plan, picked.date, today) && status[picked.date] != DayStatus.DONE) ({ editingDay = picked.date.toEpochDay() }) else null,
            )
        }

        PlanLabel("Planned vs done")
        PlannedVsActual(weekStats, pace, cfg.targetType, today)

        // Weeks
        PlanLabel("Weeks")
        SectionCard(Modifier.fillMaxWidth()) {
            Column {
                plan.days.groupBy { it.week }.toSortedMap().entries.forEachIndexed { i, (week, days) ->
                    if (i > 0) HorizontalHairline()
                    val sessions = days.filter { !it.isRest && it.date !in plan.movedAway }
                    val doneCount = sessions.count { status[it.date] == DayStatus.DONE }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Week ${week + 1}" + if (week == plan.easyWeek) " · lighter week" else "",
                                style = MType.Body.copy(color = MColors.Text),
                            )
                            Text(
                                "${planDateShort(days.first().date)} to ${planDateShort(days.last().date)} · $doneCount of ${sessions.size} done",
                                style = MType.Label,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (cfg.targetType == TargetType.TIME) hoursMinutes(sessions.sumOf { it.minutes })
                                else "${Format.dist1(sessions.sumOf { it.distanceM })} ${Format.distUnit}",
                                style = MType.Number,
                            )
                            Text(sessions.mapNotNull { it.sport }.distinct().joinToString(" · ") { it.label }.uppercase(), style = MType.Label)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        if (plan.originals.isNotEmpty()) {
            PillButton(
                "Reset ${plan.originals.size} edited ${if (plan.originals.size == 1) "day" else "days"}",
                onClick = { confirmResetAll = true },
                filled = false,
                modifier = Modifier.fillMaxWidth(),
                height = 52.dp,
            )
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(if (reviewDue) "Plan next month" else "New month", onClick = onNewMonth, filled = reviewDue, modifier = Modifier.weight(1f), height = 52.dp)
            PillButton("Delete plan", onClick = { confirmDelete = true }, filled = false, modifier = Modifier.weight(1f), height = 52.dp)
        }
        Spacer(Modifier.height(28.dp))
    }

    val editDate = if (editingDay >= 0) LocalDate.ofEpochDay(editingDay) else null
    val editDay = editDate?.let { plan.day(it) }
    if (editDate != null && editDay != null && MonthlyEdits.canEdit(plan, editDate, today)) {
        val byTime = cfg.targetType == TargetType.TIME
        val main = cfg.sports.first()
        val startSport = editDay.sport ?: main
        val model = remember(plan, editDate) {
            DayEditModel(
                date = editDate,
                title = editDay.title,
                isRest = editDay.isRest,
                edited = editDay.edited,
                byTime = byTime,
                sports = cfg.sports.map { EditChoice(it.name, it.label) },
                sportKey = startSport.name,
                kinds = { key ->
                    MonthlyEdits.kinds(key?.let { MonthSport.valueOf(it) } ?: main).map { EditChoice(it.name, MonthlyEdits.label(it)) }
                },
                kindKey = MonthlyEdits.editableKind(editDay, startSport).name,
                amount = when {
                    editDay.isRest -> MonthlyEdits.defaultAmount(plan)
                    byTime -> editDay.minutes.toDouble()
                    else -> editDay.distanceM
                },
                range = { key, kind ->
                    MonthlyEdits.range(plan, key?.let { MonthSport.valueOf(it) } ?: main, MonthKind.valueOf(kind))
                },
                moveTargets = MonthlyEdits.moveTargets(plan, editDate, today),
                warnings = { key, kind, amount ->
                    MonthlyEdits.setSession(plan, editDate, key?.let { MonthSport.valueOf(it) } ?: main, MonthKind.valueOf(kind), amount, today)
                        ?.let { MonthlyEdits.warnings(it, editDate) }.orEmpty()
                },
            )
        }
        DayEditSheet(
            model = model,
            onSave = { key, kind, amount ->
                MonthlyEdits.setSession(plan, editDate, key?.let { MonthSport.valueOf(it) } ?: main, MonthKind.valueOf(kind), amount, today)
                    ?.let { ExtrasStore.setMonthly(it) }
                editingDay = -1L
            },
            onRest = { MonthlyEdits.makeRest(plan, editDate, today)?.let { ExtrasStore.setMonthly(it) }; editingDay = -1L },
            onMove = { to -> MonthlyEdits.move(plan, editDate, to, today)?.let { ExtrasStore.setMonthly(it) }; editingDay = -1L },
            onReset = { ExtrasStore.setMonthly(MonthlyEdits.reset(plan, editDate)); editingDay = -1L },
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
                TextButton(onClick = { ExtrasStore.setMonthly(MonthlyEdits.resetAll(plan)); confirmResetAll = false }) {
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
            title = { Text("Delete this monthly plan?", style = MType.Heading) },
            text = { Text("Your activities and your event plan stay. Only this month's plan is removed.", style = MType.Body) },
            confirmButton = {
                TextButton(onClick = { ExtrasStore.setMonthly(null); confirmDelete = false }) {
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
private fun HeroStat(value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MType.Number.copy(color = MColors.OnAccent, fontSize = MType.Number.fontSize * 1.25f), maxLines = 1)
        Text(unit, style = MType.Label.copy(color = MColors.OnAccent.copy(alpha = 0.7f)))
    }
}

@Composable
private fun DayCard(
    plan: MonthlyPlan,
    day: MonthDay,
    status: DayStatus?,
    eventTitle: String?,
    isToday: Boolean,
    onStartSession: () -> Unit,
    onSuggestRoute: (RouteIdea) -> Unit = {},
    onEdit: (() -> Unit)? = null,
    movedTo: LocalDate? = null,
) {
    SectionCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                day.sport?.let {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(sportColor(it)))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (isToday) "TODAY" else planDateLabel(day.date).uppercase(),
                    style = MType.Label.copy(color = MColors.Accent),
                    modifier = Modifier.weight(1f),
                )
                if (day.edited) {
                    Text("EDITED", style = MType.Label, modifier = Modifier.padding(end = 10.dp))
                }
                statusText(status)?.let { (t, c) -> Text(t, style = MType.Label.copy(color = c)) }
            }
            Spacer(Modifier.height(6.dp))
            Text(day.title, style = MType.Title)
            if (!day.isRest) {
                Text(target(plan, day), style = MType.Number.copy(fontSize = MType.Number.fontSize * 1.3f))
            }
            Spacer(Modifier.height(8.dp))
            Text(day.detail, style = MType.Body)
            if (movedTo != null) {
                Spacer(Modifier.height(10.dp))
                PlanNote("Moved to ${planDateLabel(movedTo)}. It won't count as missed.", warn = false)
            }
            if (eventTitle != null) {
                Spacer(Modifier.height(10.dp))
                PlanNote("Event plan today: $eventTitle. Do that one first; this is a bonus if you feel fresh.", warn = false)
            }
            val sport = day.sport
            if (isToday && !day.isRest && sport != null) {
                Spacer(Modifier.height(16.dp))
                if (status == DayStatus.DONE) {
                    Text("DONE. NICE WORK.", style = MType.Label.copy(color = MColors.Accent))
                } else {
                    PillButton(
                        "Start today's ${sport.noun}",
                        icon = MIcons.Record,
                        onClick = {
                            TrackingManager.setSport(sport.sport)
                            TrackingManager.setTarget(
                                SessionTarget(
                                    title = day.title,
                                    distanceM = if (plan.config.targetType == TargetType.DISTANCE) day.distanceM else null,
                                    minutes = if (plan.config.targetType == TargetType.TIME) day.minutes else null,
                                )
                            )
                            onStartSession()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // Not for sessions already done or days gone by: there's no point planning a route for those.
            val idea = if (status == DayStatus.DONE || day.date.isBefore(LocalDate.now())) null else MonthlyRoutes.idea(plan, day)
            if (idea != null && day.sport != null) {
                Spacer(Modifier.height(if (isToday) 10.dp else 16.dp))
                PillButton(
                    "Suggest a route",
                    icon = MIcons.Map,
                    onClick = {
                        TrackingManager.setSport(day.sport.sport)
                        onSuggestRoute(idea)
                    },
                    filled = false,
                    modifier = Modifier.fillMaxWidth(),
                    height = 52.dp,
                )
            }
            if (onEdit != null) {
                Spacer(Modifier.height(14.dp))
                PillButton(
                    if (day.isRest) "Add a session" else "Edit this day",
                    icon = if (day.isRest) MIcons.Plus else MIcons.Edit,
                    onClick = onEdit,
                    filled = false,
                    modifier = Modifier.fillMaxWidth(),
                    height = 52.dp,
                )
            }
        }
    }
}

@Composable
private fun ReviewCard(review: MonthReview, ended: Boolean, onNext: () -> Unit) {
    SectionCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(if (ended) "MONTH REVIEW" else "MONTH SO FAR", style = MType.Label.copy(color = MColors.Accent))
            Spacer(Modifier.height(6.dp))
            Text(review.verdict.label, style = MType.Title)
            Spacer(Modifier.height(12.dp))
            Row {
                PreviewStat("${review.percent}%", "sessions done", Modifier.weight(1f))
                PreviewStat(hoursMinutes(review.minutes), "moving", Modifier.weight(1f))
                PreviewStat(Format.dist1(review.distanceM), Format.distUnit, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text(review.note, style = MType.Body)
            Spacer(Modifier.height(14.dp))
            PillButton("Plan next month", icon = MIcons.Plus, onClick = onNext, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun statusText(status: DayStatus?): Pair<String, Color>? = when (status) {
    DayStatus.DONE -> "DONE" to MColors.Accent
    DayStatus.PARTIAL -> "PART DONE" to MColors.Text
    DayStatus.MISSED -> "MISSED" to MColors.Danger
    DayStatus.REST -> "REST" to MColors.Faint
    DayStatus.MOVED -> "MOVED" to MColors.Faint
    else -> null
}

/** A month grid. Each session day has a ring in its sport's colour that fills as you do it. */
@Composable
private fun MonthCalendar(
    plan: MonthlyPlan,
    status: Map<LocalDate, DayStatus>,
    progress: Map<LocalDate, Float>,
    eventDays: Set<LocalDate>,
    today: LocalDate,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    val first = plan.config.startDate.withDayOfMonth(1)
    val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val lead = Math.floorMod(first.dayOfWeek.value - firstDow.value, 7)
    val length = first.lengthOfMonth()
    val cells = lead + length
    val rows = (cells + 6) / 7
    val byDate = remember(plan) { plan.days.associateBy { it.date } }

    Text(first.format(monthYearFmt), style = MType.Heading, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
    Row(Modifier.fillMaxWidth()) {
        (0 until 7).forEach { i ->
            Text(
                shortDay(firstDow.plus(i.toLong())).take(2).uppercase(),
                style = MType.Label,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    for (r in 0 until rows) {
        Row(Modifier.fillMaxWidth()) {
            for (c in 0 until 7) {
                val index = r * 7 + c - lead
                Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                    if (index in 0 until length) {
                        val date = first.plusDays(index.toLong())
                        val day = byDate[date]
                        CalendarCell(
                            date = date,
                            day = day,
                            status = status[date],
                            progress = progress[date] ?: 0f,
                            event = date in eventDays,
                            isToday = date == today,
                            isSelected = date == selected,
                            onClick = { if (day != null) onSelect(date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarCell(
    date: LocalDate,
    day: MonthDay?,
    status: DayStatus?,
    progress: Float,
    event: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val sport = day?.sport
    val ring = sport?.let { sportColor(it) }
    val track = MColors.Stroke
    val missed = status == DayStatus.MISSED
    val label = buildString {
        append(planDateLabel(date))
        append(": ")
        append(day?.title ?: "Not in this plan")
        statusText(status)?.let { append(", ").append(it.first.lowercase()) }
    }
    Box(
        Modifier
            .padding(3.dp)
            .fillMaxSize()
            .clip(CircleShape)
            .background(
                when {
                    isSelected -> MColors.Accent.copy(alpha = 0.16f)
                    else -> Color.Transparent
                }
            )
            .clickable(enabled = day != null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (ring != null) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val stroke = size.minDimension * 0.09f
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                val sweep = if (status == DayStatus.DONE) 360f else 360f * progress
                if (sweep > 0f) {
                    drawArc(ring, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
        Text(
            "${date.dayOfMonth}",
            style = MType.Number.copy(
                fontSize = MType.Number.fontSize * 0.85f,
                color = when {
                    isToday -> MColors.Accent
                    day == null -> MColors.Faint.copy(alpha = 0.5f)
                    day.isRest -> MColors.Faint
                    missed -> MColors.Danger
                    else -> MColors.Text
                },
            ),
        )
        if (event) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MColors.Danger)
            )
        }
        if (isToday) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MColors.Accent)
            )
        }
    }
}

@Composable
private fun Legend(sports: List<MonthSport>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        sports.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(sportColor(s)))
                Spacer(Modifier.width(6.dp))
                Text(s.label.uppercase(), style = MType.Label)
            }
        }
    }
}

// =================================================================================================
// Catch-up and planned vs done
// =================================================================================================

/** The day a missed session was moved to, if it was. */
private fun movedTo(plan: MonthlyPlan, day: MonthDay): LocalDate? =
    plan.days.firstOrNull { !it.isRest && it.catchUpOf == day.date }?.date

@Composable
private fun CatchUpCard(
    items: List<CatchUp>,
    hold: HoldWeek?,
    onMove: (CatchUp) -> Unit,
    onSkip: (CatchUp) -> Unit,
    onHold: (HoldWeek) -> Unit,
    onKeep: (HoldWeek) -> Unit,
) {
    SectionCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("CATCH UP", style = MType.Label.copy(color = MColors.Accent))
            items.forEachIndexed { i, c ->
                if (i > 0) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalHairline()
                }
                Spacer(Modifier.height(10.dp))
                Text("You missed ${c.missed.title.lowercase()} on ${planDateLabel(c.missed.date)}.", style = MType.Heading)
                Spacer(Modifier.height(4.dp))
                Text("Do it on ${planDateLabel(c.target)} instead?", style = MType.Body)
                if (c.nextToHardDay) {
                    Spacer(Modifier.height(8.dp))
                    PlanNote("That day is next to another hard session. Skip it if your legs are tired.", warn = true)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Move it", icon = MIcons.Check, onClick = { onMove(c) }, modifier = Modifier.weight(1f), height = 48.dp)
                    PillButton("Skip it", onClick = { onSkip(c) }, filled = false, modifier = Modifier.weight(1f), height = 48.dp)
                }
            }
            if (hold != null) {
                if (items.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalHairline()
                }
                Spacer(Modifier.height(10.dp))
                Text("You missed ${hold.missedCount} sessions in week ${hold.week + 1}.", style = MType.Heading)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Week ${hold.nextWeek + 1} is planned at ${hoursMinutes(hold.toMin)}, more than that week's ${hoursMinutes(hold.fromMin)}. " +
                        "Keep it closer to the same level so you don't jump?",
                    style = MType.Body,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Keep it lighter", icon = MIcons.Check, onClick = { onHold(hold) }, modifier = Modifier.weight(1f), height = 48.dp)
                    PillButton("No thanks", onClick = { onKeep(hold) }, filled = false, modifier = Modifier.weight(1f), height = 48.dp)
                }
            }
        }
    }
}

private fun amountText(type: TargetType, v: Double): String =
    if (type == TargetType.TIME) hoursMinutes(v.roundToInt()) else "${Format.dist1(v)} ${Format.distUnit}"

@Composable
private fun PlannedVsActual(weeks: List<WeekStat>, pace: PaceCheck, type: TargetType, today: LocalDate) {
    val chartHeight = 96
    val max = weeks.maxOfOrNull { maxOf(it.planned(type), it.actual(type)) }?.takeIf { it > 0.0 } ?: 1.0
    val current = weeks.firstOrNull { !today.isBefore(it.start) && !today.isAfter(it.end) }
    val paceColor = when (pace.pace) {
        Pace.AHEAD, Pace.ON_TRACK -> MColors.Accent
        Pace.BEHIND, Pace.WAY_BEHIND -> MColors.Danger
        Pace.NOT_STARTED -> MColors.Muted
    }
    SectionCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pace.pace.label.uppercase(),
                    style = MType.Label.copy(color = paceColor),
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(1.dp, paceColor.copy(alpha = 0.5f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (pace.pace == Pace.NOT_STARTED) "Nothing is due yet. Your first session sets the pace."
                else "${amountText(type, pace.actual)} done, with ${amountText(type, pace.planned)} planned so far.",
                style = MType.Body.copy(color = MColors.Text),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                weeks.forEach { w ->
                    val planned = w.planned(type)
                    val actual = w.actual(type)
                    val isNow = w == current
                    Column(
                        Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "Week ${w.week + 1}: ${amountText(type, actual)} done of ${amountText(type, planned)} planned"
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            Modifier.height(chartHeight.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Box(
                                Modifier
                                    .width(14.dp)
                                    .height((chartHeight * (planned / max)).toFloat().coerceAtLeast(if (planned > 0) 3f else 0f).dp)
                                    .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                    .background(MColors.Bar)
                            )
                            Box(
                                Modifier
                                    .width(14.dp)
                                    .height((chartHeight * (actual / max)).toFloat().coerceAtLeast(if (actual > 0) 3f else 0f).dp)
                                    .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                    .background(MColors.Accent)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (w.isEasy) "EASY" else "W${w.week + 1}",
                            style = MType.Label.copy(
                                fontSize = MType.Label.fontSize * 0.85f,
                                color = if (isNow) MColors.Accent else MColors.Muted,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MColors.Bar))
                    Spacer(Modifier.width(6.dp))
                    Text("PLANNED", style = MType.Label)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MColors.Accent))
                    Spacer(Modifier.width(6.dp))
                    Text("DONE", style = MType.Label)
                }
            }
            if (current != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalHairline()
                Spacer(Modifier.height(10.dp))
                Text(
                    "This week: ${amountText(type, current.actual(type))} of ${amountText(type, current.planned(type))} · " +
                        "${current.done} of ${current.sessions} sessions" + if (current.missed > 0) " · ${current.missed} missed" else "",
                    style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.9f),
                )
            }
        }
    }
}

// =================================================================================================
// Pieces
// =================================================================================================

/** One colour per sport, deeper on the light theme so the rings stay visible on white. */
@Composable
private fun sportColor(s: MonthSport): Color {
    val dark = MColors.palette.dark
    return when (s) {
        MonthSport.RUN -> MColors.Accent
        MonthSport.RUN_WALK -> if (dark) Color(0xFFC6A8FF) else Color(0xFF6E45C9)
        MonthSport.WALK -> if (dark) Color(0xFF6FD3F2) else Color(0xFF0D7FA6)
        MonthSport.HIKE -> if (dark) Color(0xFF8EDB8A) else Color(0xFF2E7D32)
        MonthSport.RIDE -> if (dark) Color(0xFFFFAA6B) else Color(0xFFC25400)
    }
}

private fun target(plan: MonthlyPlan, day: MonthDay): String =
    if (plan.config.targetType == TargetType.TIME) "${day.minutes} min" else "${Format.km(day.distanceM)} ${Format.distUnit}"

private fun hoursMinutes(totalMin: Int): String {
    val m = totalMin.coerceAtLeast(0)
    return when {
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}

private fun shortDay(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3)

private val monthFmt = DateTimeFormatter.ofPattern("LLLL", Locale.getDefault())
private val monthYearFmt = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
private fun monthName(d: LocalDate) = d.format(monthFmt).replaceFirstChar { it.titlecase(Locale.getDefault()) }

private fun LocalDate.clampTo(min: LocalDate, max: LocalDate): LocalDate = when {
    isBefore(min) -> min
    isAfter(max) -> max
    else -> this
}
