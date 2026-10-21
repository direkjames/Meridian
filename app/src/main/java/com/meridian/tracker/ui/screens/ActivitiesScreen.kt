package com.meridian.tracker.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.RouteCanvas
import com.meridian.tracker.ui.components.SectionCard
import com.meridian.tracker.ui.components.WeekBars
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Activity history: filters, this week at a glance, and every activity grouped by month. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivitiesScreen(onOpen: (String) -> Unit) {
    val all by ActivityStore.activities.collectAsStateWithLifecycle()
    var filterName by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = filterName?.let { runCatching { Sport.valueOf(it) }.getOrNull() }

    val visible = remember(all, filter) { if (filter == null) all else all.filter { it.sport == filter } }
    val months = remember(visible) { groupByMonth(visible) }
    val week = remember(visible) { weekSummary(visible) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MColors.Bg)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("Activities", style = MType.Title.copy(fontSize = MType.Title.fontSize * 1.15f), modifier = Modifier.weight(1f))
            Text("${all.size} TOTAL", style = MType.Label, modifier = Modifier.padding(bottom = 6.dp))
        }

        FilterRow(
            selected = filter,
            onSelect = { filterName = it?.name },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "week") { WeekCard(week) }

            if (visible.isEmpty()) {
                item(key = "empty") { EmptyState(filter) }
            }

            months.forEach { group ->
                stickyHeader(key = "h-${group.month}") { MonthHeader(group) }
                items(group.records, key = { it.id }) { record ->
                    ActivityRow(record, onClick = { onOpen(record.id) })
                }
            }
        }
    }
}

// ---- Filters -----------------------------------------------------------------------------------

@Composable
private fun FilterRow(selected: Sport?, onSelect: (Sport?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip("All", selected == null) { onSelect(null) }
        Sport.entries.forEach { sport ->
            FilterChip(sport.label, selected == sport) { onSelect(sport) }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MColors.Accent else Color.Transparent, label = "chipBg")
    val fg by animateColorAsState(if (selected) MColors.OnAccent else MColors.Text, label = "chipFg")
    Box(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (selected) MColors.Accent else MColors.Stroke, CircleShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MType.Button.copy(color = fg, fontSize = MType.Button.fontSize * 0.9f))
    }
}

// ---- This week ---------------------------------------------------------------------------------

private data class WeekSummary(
    val perDayM: List<Double>,
    val todayIndex: Int,
    val totalM: Double,
    val count: Int,
    val movingMs: Long,
)

private fun weekSummary(records: List<ActivityRecord>): WeekSummary {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val perDay = DoubleArray(7)
    var count = 0
    var moving = 0L
    records.forEach { r ->
        val day = Instant.ofEpochMilli(r.startTimeMs).atZone(zone).toLocalDate()
        val idx = ChronoUnit.DAYS.between(monday, day).toInt()
        if (idx in 0..6) {
            perDay[idx] += r.distanceM
            count++
            moving += r.movingMs
        }
    }
    return WeekSummary(perDay.toList(), today.dayOfWeek.value - 1, perDay.sum(), count, moving)
}

@Composable
private fun WeekCard(week: WeekSummary) {
    SectionCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("THIS WEEK", style = MType.Label)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.km(week.totalM), style = MType.BigNumber.copy(fontSize = MType.BigNumber.fontSize * 1.5f))
                Spacer(Modifier.width(6.dp))
                Text(Format.distUnit, style = MType.Unit, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${week.count} ${if (week.count == 1) "ACTIVITY" else "ACTIVITIES"}",
                        style = MType.Label.copy(color = MColors.Text),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(Format.duration(week.movingMs), style = MType.Label)
                }
            }
            Spacer(Modifier.height(18.dp))
            WeekBars(week.perDayM, week.todayIndex)
        }
    }
}

// ---- Month groups ------------------------------------------------------------------------------

private data class MonthGroup(val month: YearMonth, val records: List<ActivityRecord>) {
    val totalM: Double get() = records.sumOf { it.distanceM }
}

private fun groupByMonth(records: List<ActivityRecord>): List<MonthGroup> {
    val zone = ZoneId.systemDefault()
    return records
        .groupBy { YearMonth.from(Instant.ofEpochMilli(it.startTimeMs).atZone(zone)) }
        .map { (month, list) -> MonthGroup(month, list.sortedByDescending { it.startTimeMs }) }
        .sortedByDescending { it.month }
}

private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

@Composable
private fun MonthHeader(group: MonthGroup) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MColors.Bg)
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(group.month.format(monthFormatter).uppercase(), style = MType.Label.copy(color = MColors.Text), modifier = Modifier.weight(1f))
        Text("${Format.km(group.totalM)} ${Format.distUnit.uppercase()}  ·  ${group.records.size}", style = MType.Label)
    }
}

// ---- Rows --------------------------------------------------------------------------------------

private val rowFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", Locale.getDefault())

@Composable
private fun ActivityRow(record: ActivityRecord, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MColors.Surface)
            .border(1.dp, MColors.Hairline, shape)
            .clickable(role = Role.Button, onClickLabel = "Open ${record.title}", onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteCanvas(
            points = record.points,
            strokeWidth = 2.5.dp,
            showGrid = false,
            paddingFraction = 0.18f,
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MColors.Bg),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(record.title, style = MType.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                "${record.sport.label.uppercase()} · ${
                    Instant.ofEpochMilli(record.startTimeMs).atZone(ZoneId.systemDefault()).format(rowFormatter).uppercase()
                }" + (record.feeling?.let { " · ${it.label.uppercase()}" } ?: ""),
                style = MType.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniStat(Format.km(record.distanceM), Format.distUnit)
                MiniStat(Format.duration(record.movingMs), null)
                if (record.sport.showsSpeed) {
                    MiniStat(Format.speedKmh(record.avgSpeed), Format.speedUnit)
                } else {
                    MiniStat(Format.pace(record.avgPaceSecPerKm), Format.paceUnit)
                }
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, unit: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = MType.Number)
        if (unit != null) Text(" $unit", style = MType.Unit.copy(fontSize = MType.Unit.fontSize * 0.85f))
    }
}

@Composable
private fun EmptyState(filter: Sport?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (filter == null) "No activities yet" else "No ${filter.label.lowercase()}s yet",
            style = MType.Heading,
        )
        Spacer(Modifier.height(8.dp))
        Text("Record one and it will appear here.", style = MType.Body)
    }
}
