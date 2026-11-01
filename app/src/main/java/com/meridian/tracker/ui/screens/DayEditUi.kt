package com.meridian.tracker.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.PillButton
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** A choice in the editor: a stable key and what the user reads. */
data class EditChoice(val key: String, val label: String)

/**
 * Everything the editor needs to show one day, so the event plan and the monthly plan share it.
 * Distances are metres and times are minutes; [byTime] says which the plan uses.
 */
class DayEditModel(
    val date: LocalDate,
    /** What the day is now, e.g. "Easy run". */
    val title: String,
    val isRest: Boolean,
    val edited: Boolean,
    val byTime: Boolean,
    /** Sports to choose from; hidden when there's only one. */
    val sports: List<EditChoice>,
    val sportKey: String?,
    val kinds: (sportKey: String?) -> List<EditChoice>,
    val kindKey: String,
    val amount: Double,
    val range: (sportKey: String?, kindKey: String) -> ClosedFloatingPointRange<Double>,
    val moveTargets: List<LocalDate>,
    val warnings: (sportKey: String?, kindKey: String, amount: Double) -> List<String>,
)

/** A bottom sheet to change one day: type, sport, distance or time, rest, move or reset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayEditSheet(
    model: DayEditModel,
    onSave: (sportKey: String?, kindKey: String, amount: Double) -> Unit,
    onRest: () -> Unit,
    onMove: (LocalDate) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var sport by remember(model.date) { mutableStateOf(model.sportKey) }
    var kind by remember(model.date) { mutableStateOf(model.kindKey) }
    var amount by remember(model.date) { mutableDoubleStateOf(model.amount) }
    var showMove by remember(model.date) { mutableStateOf(false) }

    val kinds = model.kinds(sport)
    // Switching sport can make the current type or amount invalid: land on something valid.
    val safeKind = if (kinds.any { it.key == kind }) kind else kinds.first().key
    val range = model.range(sport, safeKind)
    val safeAmount = amount.coerceIn(range)
    val step = if (model.byTime) 5.0 else 0.5 * Format.unitMeters
    val warnings = model.warnings(sport, safeKind, safeAmount)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MColors.Surface2,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).uppercase() + " · " + planDateShort(model.date).uppercase(),
                    style = MType.Label.copy(color = MColors.Accent),
                    modifier = Modifier.weight(1f),
                )
                if (model.edited) Text("EDITED", style = MType.Label)
            }
            Spacer(Modifier.height(6.dp))
            Text(if (model.isRest) "Add a session" else "Edit ${model.title.lowercase()}", style = MType.Title)

            if (model.sports.size > 1) {
                PlanLabel("Sport")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.sports.forEach { s ->
                        PlanChip(s.label, selected = s.key == sport, modifier = Modifier.weight(1f)) { sport = s.key }
                    }
                }
            }

            PlanLabel("Type")
            kinds.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { k ->
                        PlanChip(k.label, selected = k.key == safeKind, modifier = Modifier.weight(1f)) { kind = k.key }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            PlanLabel(if (model.byTime) "How long" else "How far")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                CircleIconButton(MIcons.ChevronLeft, "Less", onClick = { amount = (safeAmount - step).coerceIn(range) }, size = 48.dp)
                Box(Modifier.width(150.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (model.byTime) "${safeAmount.roundToInt()} min" else "${Format.dist1(safeAmount)} ${Format.distUnit}",
                        style = MType.Number.copy(fontSize = MType.Number.fontSize * 1.4f),
                    )
                }
                CircleIconButton(MIcons.ChevronRight, "More", onClick = { amount = (safeAmount + step).coerceIn(range) }, size = 48.dp)
            }

            warnings.forEach {
                Spacer(Modifier.height(10.dp))
                PlanNote(it, warn = true)
            }

            Spacer(Modifier.height(20.dp))
            PillButton(
                if (model.isRest) "Add session" else "Save changes",
                icon = MIcons.Check,
                onClick = { onSave(sport, safeKind, safeAmount) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (model.moveTargets.isNotEmpty()) {
                    PillButton(
                        if (showMove) "Hide days" else "Move to…",
                        onClick = { showMove = !showMove },
                        filled = false,
                        modifier = Modifier.weight(1f),
                        height = 52.dp,
                    )
                }
                if (!model.isRest) {
                    PillButton("Make it rest", onClick = onRest, filled = false, modifier = Modifier.weight(1f), height = 52.dp)
                }
            }
            if (showMove) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Pick a day. The two days swap places, so your week keeps its rest.",
                    style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.moveTargets.forEach { d ->
                        PlanChip(
                            d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3) + " ${d.dayOfMonth}",
                            selected = false,
                            modifier = Modifier.width(76.dp),
                        ) { onMove(d) }
                    }
                }
            }
            if (model.edited) {
                Spacer(Modifier.height(10.dp))
                PillButton("Reset to the original", onClick = onReset, filled = false, modifier = Modifier.fillMaxWidth(), height = 52.dp)
            }
        }
    }
}
