package com.meridian.tracker.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.Coach
import com.meridian.tracker.data.CoachTip
import com.meridian.tracker.data.FitnessDay
import com.meridian.tracker.data.Gear
import com.meridian.tracker.data.Readiness
import com.meridian.tracker.data.TipKind
import com.meridian.tracker.data.TipTone
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.HorizontalHairline
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.SectionCard
import com.meridian.tracker.ui.components.VerticalHairline
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import java.time.LocalDate

/**
 * "Coach" card under Fitness & freshness: how long to rest, when to start next, plus a few tips.
 * Tap "Another tip" for fresh wording. Everything is worked out on the phone.
 */
@Composable
fun CoachCard(
    records: List<ActivityRecord>,
    days: List<FitnessDay>,
    weekly: List<Pair<LocalDate, Double>>,
    gear: List<Gear>,
    thresholds: Map<Sport, Double>,
) {
    var shuffle by rememberSaveable { mutableIntStateOf(0) }
    val nowMs = System.currentTimeMillis()
    val dayKey = (nowMs / 86_400_000L).toInt()
    val imperial = Format.imperial
    val advice = remember(records, days, weekly, gear, thresholds, shuffle, dayKey, imperial) {
        runCatching {
            Coach.advise(records, days, weekly, gear, thresholds, nowMs, imperial = imperial, seed = dayKey + shuffle)
        }.getOrNull()
    } ?: return

    SectionCard(Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(MColors.Accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MIcons.Pulse, null, tint = MColors.OnAccent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("YOUR COACH", style = MType.Label, modifier = Modifier.weight(1f))
                    Text(
                        advice.readiness.label.uppercase(),
                        style = MType.Label.copy(color = readinessColor(advice.readiness)),
                        modifier = Modifier
                            .clip(CircleShape)
                            .border(1.dp, readinessColor(advice.readiness).copy(alpha = 0.5f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                AnimatedContent(
                    targetState = advice.headline to advice.detail,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "coach-main",
                ) { (headline, detail) ->
                    Column {
                        Text(headline, style = MType.Title.copy(fontSize = MType.Title.fontSize * 0.78f, lineHeight = MType.Title.lineHeight * 0.8f))
                        Spacer(Modifier.height(8.dp))
                        Text(detail, style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.93f))
                    }
                }
            }
            HorizontalHairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                Fact("REST LEFT", advice.restLeft, Modifier.weight(1f))
                VerticalHairline(Modifier.height(36.dp).padding(horizontal = 8.dp))
                Fact("NEXT START", advice.nextStart, Modifier.weight(1.3f))
                VerticalHairline(Modifier.height(36.dp).padding(horizontal = 8.dp))
                Fact("BEST FOR", advice.bestFor, Modifier.weight(1.1f))
            }
            if (advice.tips.isNotEmpty()) {
                HorizontalHairline()
                AnimatedContent(
                    targetState = advice.tips,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "coach-tips",
                ) { tips ->
                    Column {
                        tips.forEachIndexed { i, tip ->
                            if (i > 0) HorizontalHairline(Modifier.padding(start = 66.dp))
                            TipRow(tip)
                        }
                    }
                }
            }
            HorizontalHairline()
            Text(
                "ANOTHER TIP",
                style = MType.Label.copy(color = MColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Show different coach wording") { shuffle++ }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MType.Label)
        Text(value, style = MType.Number.copy(fontSize = MType.Number.fontSize * 0.88f), maxLines = 2)
    }
}

@Composable
private fun TipRow(tip: CoachTip) {
    val color = when (tip.tone) {
        TipTone.WARN -> MColors.Danger
        TipTone.GOOD -> MColors.Accent
        TipTone.INFO -> MColors.Muted
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(tip.kind), null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            tip.text,
            style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.93f, color = MColors.Text),
            modifier = Modifier.weight(1f).padding(top = 5.dp),
        )
    }
}

private fun iconFor(kind: TipKind): ImageVector = when (kind) {
    TipKind.OVERREACH, TipKind.RAMP_UP, TipKind.REST_DAY, TipKind.FEEL -> MIcons.Warning
    TipKind.GEAR -> MIcons.Shoe
    TipKind.STREAK -> MIcons.Trophy
    TipKind.WEEK -> MIcons.Chart
    TipKind.LONG -> MIcons.Flag
    TipKind.COMEBACK -> MIcons.Calendar
    TipKind.HYDRATION, TipKind.RECOVERY -> MIcons.Heart
    TipKind.SESSION, TipKind.LEARNING -> MIcons.Pulse
}

private fun readinessColor(r: Readiness) = when (r) {
    Readiness.REST -> MColors.Danger
    Readiness.GO, Readiness.STEADY, Readiness.NEW -> MColors.Accent
    Readiness.EASY -> MColors.Muted
}
