package com.meridian.tracker.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.meridian.tracker.data.ActivityDetails
import com.meridian.tracker.data.ActivityEdits
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.Feeling
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lets the Save button grab a note that was typed a moment ago. [DetailsSection] fills in
 * [action]; the summary screen calls it just before saving a new activity.
 */
class DetailsFlush {
    var action: (() -> Unit)? = null
}

/** Survives leaving the screen, so a note typed just before going back still gets saved. */
private val detailsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** "How it went": how you felt, how hard it was, and a note. Works on a new draft and on saved activities. */
@Composable
fun DetailsSection(
    record: ActivityRecord,
    isNew: Boolean,
    flush: DetailsFlush,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var note by rememberSaveable(record.id) { mutableStateOf(record.note) }
    val currentRecord by rememberUpdatedState(record)
    val currentNote by rememberUpdatedState(note)

    /** Writes the note if it changed. For a new activity this lands in the draft immediately. */
    fun commitNote(background: Boolean) {
        val r = currentRecord
        val clean = ActivityDetails.cleanNote(currentNote)
        if (clean == r.note) return
        if (isNew && ActivityEdits.editDraftNow(r.id) { it.copy(note = clean) }) return
        (if (background) detailsScope else scope).launch { ActivityEdits.setNote(r.id, clean) }
    }

    // Save a moment after typing stops, and when leaving the screen.
    LaunchedEffect(note) {
        delay(500)
        commitNote(background = false)
    }
    DisposableEffect(record.id) {
        flush.action = { commitNote(background = true) }
        onDispose {
            commitNote(background = true)
            flush.action = null
        }
    }

    Column(modifier) {
        LabelText("HOW IT WENT")
        Spacer(Modifier.height(12.dp))

        // How you felt
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Feeling.entries.forEach { f ->
                Choice(
                    label = f.label,
                    selected = record.feeling == f,
                    modifier = Modifier.weight(1f),
                ) {
                    scope.launch { ActivityEdits.setFeeling(record.id, if (record.feeling == f) null else f) }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // How hard it was
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabelText("EFFORT", modifier = Modifier.weight(1f))
            LabelText(
                record.perceivedEffort?.let { "$it · ${ActivityDetails.effortWord(it).uppercase()}" } ?: "TAP TO RATE",
                color = if (record.perceivedEffort != null) MColors.Accent else MColors.Faint,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..10).forEach { n ->
                Choice(
                    label = "$n",
                    selected = (record.perceivedEffort ?: 0) >= n,
                    modifier = Modifier.weight(1f),
                    compact = true,
                    description = "Effort $n of 10",
                ) {
                    scope.launch { ActivityEdits.setEffort(record.id, if (record.perceivedEffort == n) null else n) }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Notes
        val shape = RoundedCornerShape(22.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MColors.Surface)
                .border(1.dp, MColors.Hairline, shape)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            if (note.isEmpty()) {
                Text("Add a note: route, weather, how the legs felt…", style = MType.Body.copy(color = MColors.Faint))
            }
            BasicTextField(
                value = note,
                onValueChange = { if (it.length <= ActivityDetails.MAX_NOTE) note = it },
                textStyle = MType.Body.copy(color = MColors.Text),
                cursorBrush = SolidColor(MColors.Accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
            )
        }
        if (note.length > ActivityDetails.MAX_NOTE - 80) {
            Spacer(Modifier.height(6.dp))
            LabelText("${note.length} / ${ActivityDetails.MAX_NOTE}", color = MColors.Faint)
        }
    }
}

@Composable
private fun LabelText(text: String, modifier: Modifier = Modifier, color: Color = MColors.Muted) {
    Text(text, style = MType.Label.copy(color = color), modifier = modifier)
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    description: String? = null,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (selected) MColors.Accent else MColors.Surface, label = "choiceBg")
    val fg by animateColorAsState(if (selected) MColors.OnAccent else MColors.Text, label = "choiceFg")
    Box(
        modifier
            .height(if (compact) 40.dp else 44.dp)
            .clip(if (compact) RoundedCornerShape(10.dp) else CircleShape)
            .background(bg)
            .border(1.dp, if (selected) MColors.Accent else MColors.Stroke, if (compact) RoundedCornerShape(10.dp) else CircleShape)
            .semantics { this.selected = selected; if (description != null) contentDescription = description }
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MType.Button.copy(color = fg, fontSize = MType.Button.fontSize * (if (compact) 0.85f else 0.8f)),
            maxLines = 1,
        )
    }
}
