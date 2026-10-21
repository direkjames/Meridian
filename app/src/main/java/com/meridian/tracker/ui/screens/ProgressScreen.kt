package com.meridian.tracker.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.Badges
import com.meridian.tracker.data.EarnedBadge
import com.meridian.tracker.data.Effort
import com.meridian.tracker.data.ExtrasStore
import com.meridian.tracker.data.Fitness
import com.meridian.tracker.data.FitnessDay
import com.meridian.tracker.data.Goal
import com.meridian.tracker.data.GoalMetric
import com.meridian.tracker.data.Goals
import com.meridian.tracker.data.Prediction
import com.meridian.tracker.data.RacePredictor
import com.meridian.tracker.ui.components.FitnessChart
import com.meridian.tracker.ui.components.LoadBars
import com.meridian.tracker.ui.components.ProgressRing
import com.meridian.tracker.data.BestResult
import com.meridian.tracker.data.Period
import com.meridian.tracker.data.PeriodSummary
import com.meridian.tracker.data.Stats
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.HorizontalHairline
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.PeriodBars
import com.meridian.tracker.ui.components.Segmented
import com.meridian.tracker.ui.components.SectionCard
import com.meridian.tracker.ui.components.SportFilterRow
import com.meridian.tracker.ui.components.StatCell
import com.meridian.tracker.ui.components.VerticalHairline
import com.meridian.tracker.ui.display
import com.meridian.tracker.ui.shortDateLabel
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ProgressScreen(
    onOpenActivity: (String) -> Unit,
    onOpenReview: (Period, LocalDate) -> Unit,
    onOpenBadges: () -> Unit,
    onOpenHeatmap: () -> Unit,
) {
    val all by ActivityStore.activities.collectAsStateWithLifecycle()
    val today = LocalDate.now()

    var period by rememberSaveable { mutableStateOf(Period.WEEK) }
    var startDay by rememberSaveable { mutableLongStateOf(Stats.periodStart(Period.WEEK, today).toEpochDay()) }
    var filterName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedBar by remember(period, startDay, filterName) { mutableStateOf<Int?>(null) }

    val start = LocalDate.ofEpochDay(startDay)
    val filter = filterName?.let { runCatching { Sport.valueOf(it) }.getOrNull() }
    val records = remember(all, filter) { if (filter == null) all else all.filter { it.sport == filter } }
    val summary = remember(records, period, startDay) { Stats.summarize(records, period, start) }
    val nextStart = Stats.shift(period, start, 1)
    val canGoNext = !nextStart.isAfter(today)

    val todayIndex = if (!today.isBefore(start) && today.isBefore(summary.endExclusive)) {
        when (period) {
            Period.YEAR -> today.monthValue - 1
            else -> (today.toEpochDay() - start.toEpochDay()).toInt()
        }
    } else {
        null
    }

    // Personal bests take a moment for long histories, so work them out off the main thread.
    val bests by produceState(initialValue = emptyList<Pair<Sport, List<BestResult>>>(), all, filter) {
        value = withContext(Dispatchers.Default) {
            (filter?.let { listOf(it) } ?: Sport.entries)
                .map { it to Stats.personalBests(all, it) }
                .filter { it.second.isNotEmpty() }
        }
    }
    val goals by ExtrasStore.goals.collectAsStateWithLifecycle()
    val planDone by ExtrasStore.planCompletedMs.collectAsStateWithLifecycle()
    var addingGoal by remember { mutableStateOf(false) }

    // Training insights are heavier to work out, so they're computed off the main thread.
    val insights by produceState<Insights?>(initialValue = null, all, filter, planDone) {
        value = withContext(Dispatchers.Default) {
            val thresholds = Effort.thresholds(all)
            val fitnessDays = Fitness.series(records, today, thresholds = thresholds)
            val predictSport = if (filter == Sport.WALK) Sport.WALK else Sport.RUN
            Insights(
                fitness = fitnessDays.takeLast(90),
                weekly = Fitness.weeklyLoads(fitnessDays, today, 8),
                predictions = RacePredictor.predict(all, predictSport, today),
                predictSport = predictSport,
                badges = Badges.earned(all, planCompletedMs = planDone),
            )
        }
    }
    val weekStreak = remember(all) { Stats.currentWeekStreak(all, today) }
    val dayStreak = remember(all) { Stats.longestDayStreak(all) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MColors.Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "Progress",
            style = MType.Title.copy(fontSize = MType.Title.fontSize * 1.15f),
            modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 14.dp),
        )

        Segmented(
            options = Period.entries.map { it.label },
            selected = period.ordinal,
            onSelect = { i ->
                val p = Period.entries[i]
                period = p
                startDay = Stats.periodStart(p, today).toEpochDay()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))

        // Period navigator
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(MIcons.ChevronLeft, "Previous ${period.label.lowercase()}", onClick = {
                startDay = Stats.shift(period, start, -1).toEpochDay()
            }, size = 44.dp)
            Text(
                periodTitle(summary).uppercase(),
                style = MType.Label.copy(color = MColors.Text, fontSize = MType.Label.fontSize * 1.1f),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            CircleIconButton(MIcons.ChevronRight, "Next ${period.label.lowercase()}", onClick = {
                if (canGoNext) startDay = nextStart.toEpochDay()
            }, size = 44.dp, modifier = Modifier.alpha(if (canGoNext) 1f else 0.3f))
        }
        Spacer(Modifier.height(14.dp))
        SportFilterRow(filter, onSelect = { filterName = it?.name }, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(16.dp))

        // Hero + chart
        SectionCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                val sel = selectedBar?.let { summary.buckets.getOrNull(it) }
                Text(
                    (sel?.fullLabel ?: "Total distance").uppercase(),
                    style = MType.Label.copy(color = if (sel != null) MColors.Accent else MColors.Muted),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Format.km(sel?.distanceM ?: summary.distanceM),
                        style = MType.Hero.copy(fontSize = MType.Hero.fontSize * 0.72f, lineHeight = MType.Hero.lineHeight * 0.72f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(Format.distUnit, style = MType.Unit, modifier = Modifier.padding(bottom = 8.dp))
                    Spacer(Modifier.weight(1f))
                    if (sel == null) ChangeChip(summary)
                    else Text(
                        "${sel.count} ${if (sel.count == 1) "ACTIVITY" else "ACTIVITIES"}",
                        style = MType.Label,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Spacer(Modifier.height(18.dp))
                PeriodBars(
                    values = summary.buckets.map { it.distanceM },
                    labels = summary.buckets.map { it.label },
                    selected = selectedBar,
                    onSelect = { selectedBar = it },
                    highlight = todayIndex,
                    labelEvery = if (period == Period.MONTH) 5 else 1,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // Totals
        SectionCard(Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(20.dp)) {
                    StatCell("Activities", "${summary.count}", null, Modifier.weight(1f))
                    VerticalHairline(Modifier.padding(horizontal = 14.dp))
                    StatCell("Moving time", Format.duration(summary.movingMs), null, Modifier.weight(1f))
                }
                HorizontalHairline()
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(20.dp)) {
                    StatCell("Climb", Format.meters(summary.elevationM), "m", Modifier.weight(1f))
                    VerticalHairline(Modifier.padding(horizontal = 14.dp))
                    StatCell("Active days", "${summary.activeDays}", null, Modifier.weight(1f))
                }
            }
        }

        // Goals
        SectionTitle("Goals")
        GoalsCard(goals, all, onAdd = { addingGoal = true }, onDelete = { ExtrasStore.deleteGoal(it) })

        // Training
        insights?.let { ins ->
            if (ins.fitness.size >= 2) {
                SectionTitle("Fitness & freshness")
                SectionCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        val form = ins.fitness.last().form
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(Fitness.formLabel(form), style = MType.Title)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "FORM ${if (form >= 0) "+" else ""}${form.roundToInt()}",
                                style = MType.Label.copy(color = MColors.Accent),
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        Text(
                            when (Fitness.formLabel(form)) {
                                "Fresh" -> "Well rested. A good time for a hard session or an event."
                                "Neutral" -> "Balanced. Training and recovery are in step."
                                "Building" -> "You're training hard, so fitness is building. Keep easy days easy."
                                else -> "Lots of load lately. A couple of easy or rest days will pay off."
                            },
                            style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.9f),
                        )
                        Spacer(Modifier.height(18.dp))
                        FitnessChart(ins.fitness)
                    }
                }
                SectionTitle("Weekly effort")
                SectionCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        val range = Fitness.targetRange(ins.weekly)
                        val thisWeek = ins.weekly.lastOrNull()?.second ?: 0.0
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${thisWeek.roundToInt()}", style = MType.BigNumber)
                            Spacer(Modifier.width(6.dp))
                            Text("relative effort this week", style = MType.Unit, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        if (range != null) {
                            Text(
                                "Suggested range ${range.start.roundToInt()}–${range.endInclusive.roundToInt()}, based on your last 3 weeks. " +
                                    when {
                                        thisWeek < range.start -> "Room for more."
                                        thisWeek > range.endInclusive -> "Above your usual: go easy."
                                        else -> "Right on track."
                                    },
                                style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.9f),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        LoadBars(ins.weekly, range)
                    }
                }
            }

            SectionTitle("Race predictor · ${ins.predictSport.label}")
            SectionCard(Modifier.fillMaxWidth()) {
                if (ins.predictions.isEmpty()) {
                    Text(
                        "Do a ${ins.predictSport.label.lowercase()} of 1 ${Format.distUnit} or more in the last 4 months and your predicted times appear here.",
                        style = MType.Body,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    Column {
                        ins.predictions.forEachIndexed { i, p ->
                            if (i > 0) HorizontalHairline()
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.label, style = MType.Heading.copy(fontSize = MType.Heading.fontSize * 0.92f))
                                    Text(p.basedOn.uppercase(), style = MType.Label)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(Format.duration(p.timeMs), style = MType.Number.copy(fontSize = MType.Number.fontSize * 1.2f))
                                    Text("${Format.pace((p.timeMs / 1000.0) / (p.distanceM / 1000.0))} ${Format.paceUnit}", style = MType.Label)
                                }
                            }
                        }
                    }
                }
            }

            // Badges + heatmap
            SectionTitle("Milestones")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReviewCard(
                    eyebrow = "BADGES",
                    title = "${ins.badges.size} / ${Badges.ALL.size}",
                    subtitle = ins.badges.maxByOrNull { it.dateMs }?.def?.title ?: "Earn your first",
                    filled = false,
                    onClick = onOpenBadges,
                    modifier = Modifier.weight(1f),
                )
                ReviewCard(
                    eyebrow = "HEATMAP",
                    title = "Your map",
                    subtitle = "Every route you've done",
                    filled = false,
                    onClick = onOpenHeatmap,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Reviews
        SectionTitle("Reviews")
        val reviewMonth = when (period) {
            Period.MONTH -> start
            Period.WEEK -> Stats.periodStart(Period.MONTH, start)
            // A past year opens its December; this year opens the current month.
            Period.YEAR -> if (start.year == today.year) Stats.periodStart(Period.MONTH, today) else start.plusMonths(11)
        }
        val reviewYear = if (period == Period.YEAR) start else Stats.periodStart(Period.YEAR, start)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReviewCard(
                eyebrow = "MONTH IN REVIEW",
                title = reviewMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                subtitle = "${reviewMonth.year}",
                filled = false,
                onClick = { onOpenReview(Period.MONTH, reviewMonth) },
                modifier = Modifier.weight(1f),
            )
            ReviewCard(
                eyebrow = "YEAR IN REVIEW",
                title = "${reviewYear.year}",
                subtitle = "Your year in motion",
                filled = true,
                onClick = { onOpenReview(Period.YEAR, reviewYear) },
                modifier = Modifier.weight(1f),
            )
        }

        // Streaks
        SectionTitle("Streaks")
        SectionCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(20.dp)) {
                StatCell("Week streak", "$weekStreak", if (weekStreak == 1) "week" else "weeks", Modifier.weight(1f))
                VerticalHairline(Modifier.padding(horizontal = 14.dp))
                StatCell("Best day streak", "$dayStreak", if (dayStreak == 1) "day" else "days", Modifier.weight(1f))
            }
        }

        // Personal bests
        if (bests.isEmpty()) {
            SectionTitle("Personal bests")
            Text(
                "Record a few activities and your bests will appear here.",
                style = MType.Body,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        bests.forEach { (sport, list) ->
            SectionTitle("${sport.label} bests")
            SectionCard(Modifier.fillMaxWidth()) {
                Column {
                    list.forEachIndexed { i, best ->
                        if (i > 0) HorizontalHairline()
                        BestRow(best, onClick = { onOpenActivity(best.recordId) })
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (addingGoal) {
        GoalDialog(onSave = { ExtrasStore.saveGoal(it); addingGoal = false }, onDismiss = { addingGoal = false })
    }
}

private data class Insights(
    val fitness: List<FitnessDay>,
    val weekly: List<Pair<LocalDate, Double>>,
    val predictions: List<Prediction>,
    val predictSport: Sport,
    val badges: List<EarnedBadge>,
)

@Composable
private fun GoalsCard(goals: List<Goal>, all: List<com.meridian.tracker.data.ActivityRecord>, onAdd: () -> Unit, onDelete: (String) -> Unit) {
    val today = LocalDate.now()
    SectionCard(Modifier.fillMaxWidth()) {
        Column {
            goals.forEachIndexed { i, g ->
                if (i > 0) HorizontalHairline()
                val p = Goals.progress(g, all, today)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        ProgressRing(p.fraction.toFloat(), Modifier.size(52.dp), done = p.done)
                        Text("${(p.fraction * 100).roundToInt()}%", style = MType.Label.copy(color = MColors.Text))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(goalTitle(g), style = MType.Heading.copy(fontSize = MType.Heading.fontSize * 0.92f))
                        Text(goalValue(g, p.value).uppercase() + " OF " + goalValue(g, g.target).uppercase() + if (p.done) " · DONE" else "", style = MType.Label.copy(color = if (p.done) MColors.Accent else MColors.Muted))
                    }
                    CircleIconButton(MIcons.Close, "Remove goal", onClick = { onDelete(g.id) }, size = 36.dp)
                }
            }
            if (goals.isNotEmpty()) HorizontalHairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onAdd)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("+ Add a goal", style = MType.Button.copy(color = MColors.Accent), modifier = Modifier.weight(1f))
                if (goals.isEmpty()) Text("WEEKLY, MONTHLY OR YEARLY", style = MType.Label)
            }
        }
    }
}

private fun goalTitle(g: Goal): String {
    val period = when (g.period) {
        Period.WEEK -> "This week"
        Period.MONTH -> "This month"
        Period.YEAR -> "This year"
    }
    val what = g.sport?.label?.lowercase()?.let { " · $it" } ?: ""
    return "$period$what"
}

private fun goalValue(g: Goal, v: Double): String = when (g.metric) {
    GoalMetric.DISTANCE -> "${Format.dist1(v)} ${Format.distUnit}"
    GoalMetric.TIME -> "${(v / 60).toInt()}h ${(v % 60).toInt()}m"
    GoalMetric.COUNT -> "${v.toInt()} ${if (v.toInt() == 1) "activity" else "activities"}"
}

@Composable
private fun GoalDialog(onSave: (Goal) -> Unit, onDismiss: () -> Unit) {
    var period by remember { mutableStateOf(Period.WEEK) }
    var metric by remember { mutableStateOf(GoalMetric.DISTANCE) }
    var sport by remember { mutableStateOf<Sport?>(null) }
    var amount by remember { mutableStateOf("20") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MColors.Surface2,
        title = { Text("New goal", style = MType.Heading) },
        text = {
            Column {
                Segmented(Period.entries.map { it.label }, period.ordinal, { period = Period.entries[it] }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Segmented(GoalMetric.entries.map { it.label }, metric.ordinal, { metric = GoalMetric.entries[it] }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                SportFilterRow(sport, onSelect = { sport = it })
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = amount,
                        onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() || it == '.' }) amount = v },
                        textStyle = MType.BigNumber,
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MColors.Accent),
                        modifier = Modifier.width(120.dp),
                    )
                    Text(
                        when (metric) {
                            GoalMetric.DISTANCE -> Format.distUnit
                            GoalMetric.TIME -> "hours"
                            GoalMetric.COUNT -> "activities"
                        },
                        style = MType.Unit,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val n = amount.toDoubleOrNull()
                if (n != null && n > 0) {
                    val target = when (metric) {
                        GoalMetric.DISTANCE -> n * Format.unitMeters
                        GoalMetric.TIME -> n * 60
                        GoalMetric.COUNT -> kotlin.math.ceil(n)
                    }
                    onSave(Goal(java.util.UUID.randomUUID().toString(), period, metric, target, sport))
                }
            }) { Text("Save", style = MType.Button.copy(color = MColors.Accent)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MType.Label,
        modifier = Modifier.padding(start = 8.dp, top = 28.dp, bottom = 12.dp),
    )
}

@Composable
private fun ChangeChip(summary: PeriodSummary) {
    val change = summary.change ?: return
    val pct = (abs(change) * 100).roundToInt()
    val up = change >= 0
    val vs = when (summary.period) {
        Period.WEEK -> "LAST WEEK"
        Period.MONTH -> "LAST MONTH"
        Period.YEAR -> "LAST YEAR"
    }
    Text(
        "${if (up) "▲" else "▼"} $pct% VS $vs",
        style = MType.Label.copy(color = if (up) MColors.Accent else MColors.Muted),
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clip(CircleShape)
            .background(if (up) MColors.Accent.copy(alpha = 0.12f) else MColors.Surface2)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun ReviewCard(
    eyebrow: String,
    title: String,
    subtitle: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val fg = if (filled) MColors.OnAccent else MColors.Text
    Column(
        modifier
            .height(150.dp)
            .clip(shape)
            .background(if (filled) MColors.Accent else MColors.Surface)
            .border(1.dp, if (filled) MColors.Accent else MColors.Accent.copy(alpha = 0.45f), shape)
            .clickable(role = Role.Button, onClickLabel = "Open $eyebrow", onClick = onClick)
            .padding(18.dp),
    ) {
        Text(eyebrow, style = MType.Label.copy(color = if (filled) MColors.OnAccent.copy(alpha = 0.7f) else MColors.Accent))
        Spacer(Modifier.weight(1f))
        Text(title, style = MType.Title.copy(color = fg), maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(subtitle, style = MType.Body.copy(color = fg.copy(alpha = 0.7f), fontSize = MType.Body.fontSize * 0.9f), modifier = Modifier.weight(1f), maxLines = 1)
            Icon(MIcons.ChevronRight, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun BestRow(best: BestResult, onClick: () -> Unit) {
    val (value, unit) = best.display()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open activity", onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MColors.Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MIcons.Trophy, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(best.spec.title, style = MType.Heading.copy(fontSize = MType.Heading.fontSize * 0.92f))
            Text(shortDateLabel(best.dateMs).uppercase(), style = MType.Label)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MType.Number.copy(fontSize = MType.Number.fontSize * 1.15f))
            if (unit != null) Text(" $unit", style = MType.Unit)
        }
    }
}

private val weekFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

private fun periodTitle(s: PeriodSummary): String = when (s.period) {
    Period.WEEK -> "${s.start.format(weekFmt)} – ${s.endExclusive.minusDays(1).format(weekFmt)}"
    Period.MONTH -> s.start.format(monthFmt)
    Period.YEAR -> "${s.start.year}"
}
