package com.meridian.tracker.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.ActivityEdits
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.ExtrasStore
import com.meridian.tracker.data.Gear
import com.meridian.tracker.data.GearMath
import com.meridian.tracker.data.GearStatus
import com.meridian.tracker.data.GearType
import com.meridian.tracker.data.GearUsage
import com.meridian.tracker.data.Prefs
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.HorizontalHairline
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.PillButton
import com.meridian.tracker.ui.components.SectionCard
import com.meridian.tracker.ui.components.Segmented
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

// ---- Formatting ----------------------------------------------------------------------------------

/** "412" for big numbers, "38.6" for small ones, in km or miles. */
private fun gearDist(m: Double): String {
    val v = m / Format.unitMeters
    return if (v >= 100) String.format(Locale.US, "%,.0f", v) else String.format(Locale.US, "%.1f", v)
}

private fun GearType.icon(): ImageVector = when (this) {
    GearType.SHOES -> MIcons.Shoe
    GearType.BIKE -> MIcons.Bike
    GearType.OTHER -> MIcons.Check
}

// ---- On the activity screen ----------------------------------------------------------------------

/**
 * The GEAR card on Activity Complete (and on saved activities): what you used, how far it has
 * gone, how much is left, and a warning when it's time to retire it.
 */
@Composable
fun GearSection(record: ActivityRecord, isNew: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val allGear by ExtrasStore.gear.collectAsStateWithLifecycle()
    val saved by ActivityStore.activities.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    var suggested by rememberSaveable(record.id) { mutableStateOf(!isNew) }

    // New activity: tick what you used last time for this sport.
    LaunchedEffect(record.id, allGear.size, saved.size) {
        if (suggested) return@LaunchedEffect
        if (record.gearIds.isNotEmpty()) { suggested = true; return@LaunchedEffect }
        val pick = GearMath.suggest(record.sport, saved, allGear, excludeId = record.id)
        if (pick.isNotEmpty()) ActivityEdits.setGear(record.id, pick)
        suggested = true
    }

    val used = record.gearIds.mapNotNull { id -> allGear.firstOrNull { it.id == id } }
    val usages = remember(used, saved, record) {
        used.map { GearMath.usage(it, saved, extra = if (isNew) record else null) }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("GEAR", style = MType.Label, modifier = Modifier.weight(1f))
            if (used.isNotEmpty()) {
                Text(
                    "CHANGE",
                    style = MType.Label.copy(color = MColors.Accent),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { picking = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (used.isEmpty()) {
            val shape = RoundedCornerShape(22.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MColors.Surface)
                    .border(1.dp, MColors.Hairline, shape)
                    .clickable(role = Role.Button, onClickLabel = "Add gear") { picking = true }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(GearMath.typeFor(record.sport).icon())
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (record.sport == Sport.RIDE) "Which bike?" else "Which shoes?",
                        style = MType.Heading,
                    )
                    Text(
                        if (allGear.isEmpty()) "Add your gear to track its distance" else "Tap to pick your gear",
                        style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.9f),
                    )
                }
                Icon(MIcons.Plus, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(20.dp))
            }
        } else {
            SectionCard(Modifier.fillMaxWidth()) {
                Column {
                    usages.forEachIndexed { i, u ->
                        if (i > 0) HorizontalHairline()
                        GearUsageRow(
                            u,
                            note = if (isNew) "INCL. THIS ${record.sport.label.uppercase()}" else null,
                            onRetire = if (u.status == GearStatus.WORN_OUT && !u.gear.retired) ({
                                ExtrasStore.saveGear(u.gear.copy(retired = true))
                            }) else null,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        GearPickerDialog(
            sport = record.sport,
            selected = record.gearIds.filter { id -> allGear.any { it.id == id } },
            onDone = { ids ->
                picking = false
                scope.launch { ActivityEdits.setGear(record.id, ids) }
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun IconBadge(icon: ImageVector, warn: Boolean = false) {
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (warn) MColors.Danger.copy(alpha = 0.16f) else MColors.Accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (warn) MColors.Danger else MColors.Accent, modifier = Modifier.size(20.dp))
    }
}

/** Name, distance, a bar towards the limit, and the retire warning when it's due. */
@Composable
fun GearUsageRow(
    u: GearUsage,
    modifier: Modifier = Modifier,
    note: String? = null,
    onRetire: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val status = u.status
    val warn = status == GearStatus.WORN_OUT
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(u.gear.type.icon(), warn = warn)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(u.gear.name, style = MType.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(u.gear.type.label.uppercase())
                        if (u.gear.retired) append(" · RETIRED")
                        append(" · ${u.activities} ${if (u.activities == 1) "ACTIVITY" else "ACTIVITIES"}")
                    },
                    style = MType.Label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(gearDist(u.usedM), style = MType.Number)
                    Text(" ${Format.distUnit}", style = MType.Unit)
                }
                Text(
                    when {
                        u.remainingM == null -> "NO LIMIT"
                        warn -> "LIMIT REACHED"
                        else -> "${gearDist(u.remainingM!!)} ${Format.distUnit.uppercase()} LEFT"
                    },
                    style = MType.Label.copy(
                        color = when (status) {
                            GearStatus.WORN_OUT -> MColors.Danger
                            GearStatus.NEARLY_DONE -> MColors.Accent
                            GearStatus.OK -> MColors.Muted
                        },
                    ),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        u.fraction?.let { f ->
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(MColors.Surface2)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(f.toFloat().coerceIn(0.02f, 1f))
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            when (status) {
                                GearStatus.WORN_OUT -> MColors.Danger
                                GearStatus.NEARLY_DONE -> MColors.Accent
                                GearStatus.OK -> MColors.Text.copy(alpha = 0.8f)
                            }
                        )
                )
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text(note ?: "", style = MType.Label.copy(color = MColors.Faint), modifier = Modifier.weight(1f))
                Text(
                    "OF ${gearDist(u.gear.limitM ?: 0.0)} ${Format.distUnit.uppercase()}",
                    style = MType.Label.copy(color = MColors.Faint),
                )
            }
        }
        if (status != GearStatus.OK && !u.gear.retired) {
            Spacer(Modifier.height(12.dp))
            RetireBanner(u, onRetire)
        }
    }
}

@Composable
private fun RetireBanner(u: GearUsage, onRetire: (() -> Unit)?) {
    val worn = u.status == GearStatus.WORN_OUT
    val color = if (worn) MColors.Danger else MColors.Accent
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.5f), shape)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MIcons.Warning, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (worn) {
                if (u.gear.type == GearType.SHOES) "Time to retire these shoes. Worn cushioning can lead to aches and injuries."
                else "This has reached its limit. Time to replace or service it."
            } else {
                "Nearly done: ${gearDist(u.remainingM ?: 0.0)} ${Format.distUnit} left. Start thinking about a replacement."
            },
            style = MType.Body.copy(color = MColors.Text, fontSize = MType.Body.fontSize * 0.86f, lineHeight = MType.Body.lineHeight * 0.9f),
            modifier = Modifier.weight(1f),
        )
        if (onRetire != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "RETIRE",
                style = MType.Label.copy(color = MColors.Bg),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(color)
                    .clickable(role = Role.Button, onClick = onRetire)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

// ---- Picking gear for an activity --------------------------------------------------------------

@Composable
private fun GearPickerDialog(
    sport: Sport,
    selected: List<String>,
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val allGear by ExtrasStore.gear.collectAsStateWithLifecycle()
    val saved by ActivityStore.activities.collectAsStateWithLifecycle()
    var picked by rememberSaveable { mutableStateOf(selected) }
    var creating by rememberSaveable { mutableStateOf(allGear.isEmpty()) }
    var limitNote by remember { mutableStateOf(false) }

    // Retired gear only shows if it's already on this activity.
    val choices = allGear.filter { !it.retired || it.id in picked }
        .sortedWith(compareBy<Gear> { if (it.type == GearMath.typeFor(sport)) 0 else 1 }.thenByDescending { it.createdMs })

    if (creating) {
        GearEditorDialog(
            initial = null,
            defaultType = GearMath.typeFor(sport),
            onSave = { g ->
                ExtrasStore.saveGear(g)
                if (picked.size < GearMath.MAX_PER_ACTIVITY) picked = picked + g.id
                creating = false
            },
            onDismiss = {
                creating = false
                if (allGear.isEmpty()) onDismiss()
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MColors.Surface2,
        title = { Text("Gear for this ${sport.label.lowercase()}", style = MType.Heading) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (choices.isEmpty()) {
                    Text("No gear yet. Add your shoes or bike to see how far they've gone.", style = MType.Body)
                }
                choices.forEach { g ->
                    val on = g.id in picked
                    val u = GearMath.usage(g, saved)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.Checkbox) {
                                picked = when {
                                    on -> picked - g.id
                                    picked.size >= GearMath.MAX_PER_ACTIVITY -> { limitNote = true; picked }
                                    else -> picked + g.id
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(g.type.icon(), contentDescription = null, tint = if (on) MColors.Accent else MColors.Muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.name, style = MType.Body.copy(color = MColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${gearDist(u.usedM)} ${Format.distUnit.uppercase()}" +
                                    (u.remainingM?.let { " · ${gearDist(it)} LEFT" } ?: ""),
                                style = MType.Label.copy(color = if (u.status == GearStatus.WORN_OUT) MColors.Danger else MColors.Muted),
                            )
                        }
                        CheckDot(on)
                    }
                }
                if (limitNote) {
                    Text("Up to ${GearMath.MAX_PER_ACTIVITY} per activity.".uppercase(), style = MType.Label.copy(color = MColors.Accent))
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { creating = true }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MIcons.Plus, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New gear", style = MType.Button.copy(color = MColors.Accent))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(picked.filter { id -> allGear.any { it.id == id } }) }) {
                Text("Done", style = MType.Button.copy(color = MColors.Accent))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) }
        },
    )
}

@Composable
private fun CheckDot(on: Boolean) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (on) MColors.Accent else Color.Transparent)
            .border(1.5.dp, if (on) MColors.Accent else MColors.Stroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(MIcons.Check, contentDescription = null, tint = MColors.OnAccent, modifier = Modifier.size(14.dp))
    }
}

// ---- Adding and editing gear ---------------------------------------------------------------------

@Composable
fun GearEditorDialog(
    initial: Gear?,
    defaultType: GearType,
    onSave: (Gear) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val imperial by Prefs.imperial.collectAsStateWithLifecycle()
    fun inUnit(m: Double?): String = m?.let { String.format(Locale.US, "%.0f", it / Format.unitMeters) } ?: ""

    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var typeName by rememberSaveable { mutableStateOf((initial?.type ?: defaultType).name) }
    val type = GearType.valueOf(typeName)
    var limitText by rememberSaveable {
        mutableStateOf(inUnit(if (initial != null) initial.limitM else GearMath.defaultLimitM(defaultType, imperial)))
    }
    var startText by rememberSaveable { mutableStateOf(if (initial != null && initial.startM > 0) inUnit(initial.startM) else "") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MColors.Surface2,
        title = { Text(if (initial == null) "New gear" else "Edit gear", style = MType.Heading) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FieldLabel("Name")
                InputBox(name, { if (it.length <= GearMath.MAX_NAME) name = it }, placeholder = if (type == GearType.BIKE) "e.g. Canyon Endurace" else "e.g. Pegasus 41")
                FieldLabel("Type")
                Segmented(
                    options = GearType.entries.map { it.label },
                    selected = type.ordinal,
                    onSelect = { i ->
                        val next = GearType.entries[i]
                        // Switching type swaps in that type's usual limit, unless one was typed.
                        val oldDefault = inUnit(GearMath.defaultLimitM(type, imperial))
                        if (limitText.isBlank() || limitText == oldDefault) limitText = inUnit(GearMath.defaultLimitM(next, imperial))
                        typeName = next.name
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldLabel("Replace after (${Format.distUnit})")
                InputBox(limitText, { limitText = it.filter { c -> c.isDigit() || c == '.' }.take(7) }, placeholder = "No limit", number = true)
                if (type == GearType.SHOES) {
                    Text(
                        "Most running shoes last 500–800 km (300–500 mi). " +
                            "${if (imperial) "${GearMath.SHOE_LIMIT_MI.toInt()} mi" else "${GearMath.SHOE_LIMIT_KM.toInt()} km"} is a good middle.",
                        style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.82f, color = MColors.Faint),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                FieldLabel("Already used (${Format.distUnit})")
                InputBox(startText, { startText = it.filter { c -> c.isDigit() || c == '.' }.take(7) }, placeholder = "0", number = true)
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it.uppercase(), style = MType.Label.copy(color = MColors.Danger))
                }
                if (initial != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallChip(if (initial.retired) "Unretire" else "Retire") {
                            onSave(initial.copy(retired = !initial.retired))
                        }
                        if (onDelete != null) SmallChip("Delete", danger = true) { confirmDelete = true }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = GearMath.cleanName(name)
                val limit = if (limitText.isBlank()) null else GearMath.parseDistance(limitText, imperial)
                val start = if (startText.isBlank()) 0.0 else GearMath.parseDistance(startText, imperial)
                error = when {
                    clean.isEmpty() -> "Give it a name"
                    limitText.isNotBlank() && (limit == null || limit <= 0.0) -> "Check the limit"
                    start == null -> "Check the distance already used"
                    else -> null
                }
                if (error == null && start != null) {
                    onSave(
                        Gear(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = clean,
                            type = type,
                            limitM = limit,
                            startM = start,
                            retired = initial?.retired ?: false,
                            createdMs = initial?.createdMs ?: System.currentTimeMillis(),
                        )
                    )
                }
            }) { Text("Save", style = MType.Button.copy(color = MColors.Accent)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) }
        },
    )

    if (confirmDelete && initial != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = MColors.Surface2,
            title = { Text("Delete ${initial.name}?", style = MType.Heading) },
            text = { Text("It's removed from every activity. Your activities themselves are kept. To keep its history, retire it instead.", style = MType.Body) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
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
private fun FieldLabel(text: String) {
    Text(text.uppercase(), style = MType.Label, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
}

@Composable
private fun InputBox(value: String, onChange: (String) -> Unit, placeholder: String, number: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MColors.Bg)
            .border(1.dp, MColors.Stroke, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (value.isEmpty()) Text(placeholder, style = MType.Heading.copy(color = MColors.Faint))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = MType.Heading,
            singleLine = true,
            cursorBrush = SolidColor(MColors.Accent),
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SmallChip(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label.uppercase(),
        style = MType.Label.copy(color = if (danger) MColors.Danger else MColors.Text),
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, if (danger) MColors.Danger.copy(alpha = 0.6f) else MColors.Stroke, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

// ---- Gear screen (Settings › Gear) ------------------------------------------------------------

@Composable
fun GearScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val allGear by ExtrasStore.gear.collectAsStateWithLifecycle()
    val saved by ActivityStore.activities.collectAsStateWithLifecycle()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val usages = remember(allGear, saved) { allGear.map { GearMath.usage(it, saved) } }
    val active = usages.filterNot { it.gear.retired }
    val retired = usages.filter { it.gear.retired }

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
            Text("Gear", style = MType.Title)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "Distance is added from every activity your gear is on. You'll get a warning when shoes are close to worn out.",
                style = MType.Body,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            if (active.isNotEmpty()) {
                Section("In use")
                GearList(active) { editingId = it }
            }
            if (retired.isNotEmpty()) {
                Section("Retired")
                GearList(retired) { editingId = it }
            }
            Spacer(Modifier.height(20.dp))
            PillButton("Add gear", icon = MIcons.Plus, onClick = { creating = true }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (creating) {
        GearEditorDialog(
            initial = null,
            defaultType = GearType.SHOES,
            onSave = { ExtrasStore.saveGear(it); creating = false },
            onDismiss = { creating = false },
        )
    }
    allGear.firstOrNull { it.id == editingId }?.let { g ->
        GearEditorDialog(
            initial = g,
            defaultType = g.type,
            onSave = { ExtrasStore.saveGear(it); editingId = null },
            onDismiss = { editingId = null },
            onDelete = { ExtrasStore.deleteGear(g.id); editingId = null },
        )
    }
}

@Composable
private fun GearList(items: List<GearUsage>, onEdit: (String) -> Unit) {
    SectionCard(Modifier.fillMaxWidth()) {
        Column {
            items.forEachIndexed { i, u ->
                if (i > 0) HorizontalHairline()
                GearUsageRow(
                    u,
                    onRetire = if (u.status == GearStatus.WORN_OUT && !u.gear.retired) ({
                        ExtrasStore.saveGear(u.gear.copy(retired = true))
                    }) else null,
                    trailing = {
                        CircleIconButton(MIcons.Edit, "Edit ${u.gear.name}", onClick = { onEdit(u.gear.id) }, size = 36.dp)
                    },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                )
            }
        }
    }
}
