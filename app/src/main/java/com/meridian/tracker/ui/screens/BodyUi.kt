package com.meridian.tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.BodySex
import com.meridian.tracker.data.Prefs
import com.meridian.tracker.ui.components.HorizontalHairline
import com.meridian.tracker.ui.components.SectionCard
import com.meridian.tracker.ui.components.Segmented
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import java.util.Locale

private const val KG_PER_LB = 0.45359237

/** Weight, birth year and sex for calorie estimates. They stay on this phone. */
@Composable
fun BodyCard() {
    val body by Prefs.body.collectAsStateWithLifecycle()
    val imperial by Prefs.imperial.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }

    val weightText = if (imperial) {
        String.format(Locale.US, "%.1f lb", body.weightKg / KG_PER_LB)
    } else {
        String.format(Locale.US, "%.1f kg", body.weightKg)
    }

    SectionCard(Modifier.fillMaxWidth()) {
        Column {
            LinkRow(
                "Weight",
                if (body.weightKnown) "$weightText · tap to change" else "Not set. Calories use a typical 70 kg until you add yours.",
                badge = if (body.weightKnown) null else "ADD",
                badgeWarn = !body.weightKnown,
            ) { editing = "weight" }
            HorizontalHairline()
            LinkRow(
                "Birth year",
                body.birthYear?.let { "$it · tap to change" } ?: "Optional. Makes heart-rate calories more accurate.",
            ) { editing = "year" }
            HorizontalHairline()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("Sex", style = MType.Heading.copy(fontSize = MType.Heading.fontSize * 0.92f))
                Spacer(Modifier.height(2.dp))
                Text(
                    "Optional. Used only in the heart-rate calorie formula.",
                    style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.88f),
                )
                Spacer(Modifier.height(12.dp))
                Segmented(
                    options = listOf("Skip") + BodySex.entries.map { it.label },
                    selected = body.sex?.let { BodySex.entries.indexOf(it) + 1 } ?: 0,
                    onSelect = { i -> Prefs.setSex(if (i == 0) null else BodySex.entries[i - 1]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    when (editing) {
        "weight" -> NumberDialog(
            title = if (imperial) "Your weight (lb)" else "Your weight (kg)",
            initial = if (body.weightKnown) String.format(Locale.US, "%.1f", if (imperial) body.weightKg / KG_PER_LB else body.weightKg) else "",
            decimal = true,
            onConfirm = { text ->
                val v = text?.replace(',', '.')?.toDoubleOrNull()
                Prefs.setBodyWeight(v?.let { if (imperial) it * KG_PER_LB else it })
                editing = null
            },
            onDismiss = { editing = null },
        )
        "year" -> NumberDialog(
            title = "Your birth year",
            initial = body.birthYear?.toString() ?: "",
            decimal = false,
            onConfirm = { text -> Prefs.setBirthYear(text?.toIntOrNull()); editing = null },
            onDismiss = { editing = null },
        )
    }
}

/** A one-number input. Confirming an empty or invalid box clears the value. */
@Composable
private fun NumberDialog(
    title: String,
    initial: String,
    decimal: Boolean,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MColors.Surface2,
        title = { Text(title, style = MType.Heading) },
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
                    onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() || (decimal && (it == '.' || it == ',')) }) text = v },
                    textStyle = MType.Heading,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
                    cursorBrush = SolidColor(MColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim().ifEmpty { null }) }) {
                Text("Save", style = MType.Button.copy(color = MColors.Accent))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) }
        },
    )
}
