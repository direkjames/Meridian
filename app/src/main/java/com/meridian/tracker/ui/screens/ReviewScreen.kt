package com.meridian.tracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import android.app.Activity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.meridian.tracker.share.ShareActions
import com.meridian.tracker.share.ShareRenderer
import com.meridian.tracker.share.InstagramResult
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.Period
import com.meridian.tracker.data.Review
import com.meridian.tracker.data.Stats
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.PeriodBars
import com.meridian.tracker.ui.components.PillButton
import com.meridian.tracker.ui.components.RouteCanvas
import com.meridian.tracker.ui.components.StatCell
import com.meridian.tracker.ui.components.VerticalHairline
import com.meridian.tracker.ui.display
import com.meridian.tracker.ui.shortDateLabel
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Month / Year in Review: a story you tap or swipe through.
 * Numbers count up as each card lands.
 */
@Composable
fun ReviewScreen(period: Period, start: LocalDate, onClose: () -> Unit, onOpenActivity: (String) -> Unit) {
    val all by ActivityStore.activities.collectAsStateWithLifecycle()
    val review by produceState<Review?>(initialValue = null, all, period, start) {
        value = withContext(Dispatchers.Default) { Stats.review(all, period, start) }
    }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(MColors.Bg)) {
        val r = review
        if (r != null) Story(r, onClose, onOpenActivity)
    }
}

private class StoryPage(val yellow: Boolean, val content: @Composable ColumnScope.(active: Boolean) -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Story(review: Review, onClose: () -> Unit, onOpenActivity: (String) -> Unit) {
    val pages = remember(review) { buildPages(review, onClose, onOpenActivity) }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pages.size) {
                    detectTapGestures(onTap = { pos ->
                        val target = if (pos.x < size.width * 0.3f) pager.currentPage - 1 else pager.currentPage + 1
                        if (target in pages.indices) scope.launch { pager.animateScrollToPage(target) }
                    })
                },
        ) { index ->
            val page = pages[index]
            val active = pager.settledPage == index
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (page.yellow) MColors.Accent else MColors.Bg),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(start = 28.dp, end = 28.dp, top = 84.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    page.content(this, active)
                }
            }
        }

        // Story chrome: progress segments + close
        val onYellow = pages.getOrNull(pager.currentPage)?.yellow == true
        val chrome = if (onYellow) MColors.OnAccent else MColors.Text
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pages.indices.forEach { i ->
                    val fill by animateFloatAsState(if (i <= pager.currentPage) 1f else 0f, tween(300), label = "seg")
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(chrome.copy(alpha = 0.25f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fill)
                                .background(chrome)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    reviewTitle(review).uppercase(),
                    style = MType.Wordmark.copy(color = chrome, fontSize = MType.Wordmark.fontSize * 0.9f),
                    modifier = Modifier.weight(1f),
                )
                CircleIconButton(MIcons.Close, "Close review", onClick = onClose, size = 40.dp)
            }
        }
    }
}

// =================================================================================================
// Pages
// =================================================================================================

private fun buildPages(r: Review, onClose: () -> Unit, onOpenActivity: (String) -> Unit): List<StoryPage> {
    val s = r.summary
    val isYear = r.period == Period.YEAR
    val periodWord = if (isYear) "year" else "month"
    val name = reviewName(r)

    if (r.isEmpty) {
        return listOf(
            StoryPage(yellow = true) {
                Eyebrow("MERIDIAN", onYellow = true)
                Spacer(Modifier.height(16.dp))
                Display(name, onYellow = true, big = !isYear)
                Spacer(Modifier.height(16.dp))
                BodyText("No activities this $periodWord yet. Your story starts with the next one.", onYellow = true)
                Spacer(Modifier.height(32.dp))
                DoneButton(onClose, onYellow = true)
            },
        )
    }

    val pages = mutableListOf<StoryPage>()

    // 1. Intro
    pages += StoryPage(yellow = true) { active ->
        Eyebrow("MERIDIAN", onYellow = true)
        Spacer(Modifier.height(18.dp))
        Rise(active) { Display(name, onYellow = true, big = isYear) }
        Spacer(Modifier.height(14.dp))
        BodyText("Your $periodWord in motion.", onYellow = true)
        Spacer(Modifier.height(48.dp))
        Eyebrow("TAP TO CONTINUE", onYellow = true, faint = true)
    }

    // 2. Distance
    pages += StoryPage(yellow = false) { active ->
        Eyebrow("YOU COVERED")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            CountUp(s.distanceM / Format.unitMeters, active, MType.Hero.copy(fontSize = MType.Hero.fontSize * 1.3f, lineHeight = MType.Hero.lineHeight * 1.3f)) {
                String.format(Locale.US, "%.1f", it)
            }
            Spacer(Modifier.width(8.dp))
            Text(Format.distUnit, style = MType.Unit.copy(fontSize = MType.Unit.fontSize * 1.6f), modifier = Modifier.padding(bottom = 18.dp))
        }
        Spacer(Modifier.height(20.dp))
        if (r.marathons >= 0.1) {
            BodyText("That's ${oneDecimal(r.marathons)} marathon${if (r.marathons >= 1.05 || r.marathons < 0.95) "s" else ""}.", strong = true)
        }
        s.change?.let { c ->
            val pct = (abs(c) * 100).roundToInt()
            val prev = if (isYear) "${r.start.year - 1}" else r.start.minusMonths(1).month.getDisplayName(JTextStyle.FULL, Locale.getDefault())
            Spacer(Modifier.height(8.dp))
            BodyText(if (c >= 0) "$pct% further than $prev." else "$pct% less than $prev. Rest counts too.")
        }
    }

    // 3. Time
    pages += StoryPage(yellow = false) { active ->
        Eyebrow("IN MOTION FOR")
        Spacer(Modifier.height(12.dp))
        CountUp(s.movingMs / 3_600_000.0, active, MType.Hero.copy(fontSize = MType.Hero.fontSize * 1.05f)) { hours ->
            val totalMin = (hours * 60).roundToInt()
            "${totalMin / 60}h ${String.format(Locale.US, "%02d", totalMin % 60)}m"
        }
        Spacer(Modifier.height(20.dp))
        BodyText(
            "Across ${s.count} ${if (s.count == 1) "activity" else "activities"} on ${s.activeDays} ${if (s.activeDays == 1) "day" else "days"}.",
            strong = true,
        )
        if (r.longestDayStreak >= 2) {
            Spacer(Modifier.height(8.dp))
            BodyText("Your longest streak was ${r.longestDayStreak} days in a row.")
        }
    }

    // 4. Rhythm
    r.peakBucket?.let { peak ->
        pages += StoryPage(yellow = false) {
            Eyebrow(if (isYear) "YOUR BIGGEST MONTH" else "YOUR BIGGEST DAY")
            Spacer(Modifier.height(12.dp))
            Display(peak.fullLabel, big = false)
            Spacer(Modifier.height(8.dp))
            BodyText("${Format.km(peak.distanceM)} ${Format.distUnit} across ${peak.count} ${if (peak.count == 1) "activity" else "activities"}.", strong = true)
            Spacer(Modifier.height(36.dp))
            val peakIndex = s.buckets.indexOf(peak)
            PeriodBars(
                values = s.buckets.map { it.distanceM },
                labels = s.buckets.map { it.label },
                selected = null,
                onSelect = {},
                highlight = peakIndex,
                labelEvery = if (isYear) 1 else 5,
                height = 180.dp,
            )
        }
    }

    // 5. Climb
    if (s.elevationM >= 10) {
        pages += StoryPage(yellow = true) { active ->
            Eyebrow("YOU CLIMBED", onYellow = true)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                CountUp(s.elevationM, active, MType.Hero.copy(color = MColors.OnAccent, fontSize = MType.Hero.fontSize * 1.2f, lineHeight = MType.Hero.lineHeight * 1.2f)) {
                    String.format(Locale.US, "%,d", it.roundToInt())
                }
                Spacer(Modifier.width(8.dp))
                Text("m", style = MType.Unit.copy(color = MColors.OnAccent, fontSize = MType.Unit.fontSize * 1.6f), modifier = Modifier.padding(bottom = 18.dp))
            }
            Spacer(Modifier.height(20.dp))
            BodyText(
                if (r.everests >= 1) "That's ${oneDecimal(r.everests)}× the height of Mount Everest."
                else "That's ${(r.everests * 100).roundToInt().coerceAtLeast(1)}% of the way up Mount Everest.",
                onYellow = true,
                strong = true,
            )
        }
    }

    // 6. Longest activity
    r.longest?.let { longest ->
        pages += StoryPage(yellow = false) {
            Eyebrow("YOUR LONGEST ACTIVITY")
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MColors.Surface)
                    .border(1.dp, MColors.Hairline, RoundedCornerShape(24.dp))
            ) {
                RouteCanvas(longest.points, Modifier.fillMaxSize(), strokeWidth = 4.dp)
            }
            Spacer(Modifier.height(18.dp))
            Text(longest.title, style = MType.Title)
            Spacer(Modifier.height(6.dp))
            BodyText("${Format.km(longest.distanceM)} ${Format.distUnit}  ·  ${Format.duration(longest.movingMs)}  ·  ${shortDateLabel(longest.startTimeMs)}")
            Spacer(Modifier.height(20.dp))
            PillButton("View activity", onClick = { onOpenActivity(longest.id) }, filled = false, height = 48.dp)
        }
    }

    // 7. Habits
    pages += StoryPage(yellow = false) {
        r.partOfDay?.let { part ->
            Eyebrow("YOUR RHYTHM")
            Spacer(Modifier.height(12.dp))
            Display(
                when (part) {
                    "morning" -> "Early riser."
                    "afternoon" -> "Afternoon mover."
                    "evening" -> "Evening regular."
                    else -> "Night owl."
                },
                big = false,
            )
            Spacer(Modifier.height(10.dp))
            BodyText("${(r.partOfDayShare * 100).roundToInt()}% of your activities started in the $part.", strong = true)
            Spacer(Modifier.height(36.dp))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            r.favoriteSport?.let {
                StatCell("Favourite", it.label, "${(r.favoriteSportShare * 100).roundToInt()}%", Modifier.weight(1f), big = true)
            }
            VerticalHairline(Modifier.padding(horizontal = 14.dp))
            r.favoriteWeekday?.let {
                StatCell("Go-to day", it.getDisplayName(JTextStyle.FULL, Locale.getDefault()), null, Modifier.weight(1f), big = true)
            }
        }
    }

    // 8. Personal bests
    pages += StoryPage(yellow = false) { active ->
        Eyebrow("PERSONAL BESTS SET")
        Spacer(Modifier.height(12.dp))
        CountUp(r.bestsSet.size.toDouble(), active, MType.Hero.copy(fontSize = MType.Hero.fontSize * 1.2f)) { "${it.roundToInt()}" }
        Spacer(Modifier.height(18.dp))
        if (r.bestsSet.isEmpty()) {
            BodyText("No new bests this $periodWord. The next one is closer than you think.")
        } else {
            r.bestsSet.take(5).forEach { best ->
                val (value, unit) = best.display()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(MIcons.Trophy, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(best.spec.title, style = MType.Body.copy(color = MColors.Text), modifier = Modifier.weight(1f))
                    Text(value + (unit?.let { " $it" } ?: ""), style = MType.Number)
                }
            }
            if (r.bestsSet.size > 5) BodyText("+ ${r.bestsSet.size - 5} more")
        }
    }

    // 9. Summary card
    pages += StoryPage(yellow = false) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MColors.Surface)
                .border(1.5.dp, MColors.Accent, RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            Column {
                Eyebrow("MERIDIAN · ${reviewTitle(r).uppercase()}", accent = true)
                Spacer(Modifier.height(10.dp))
                Display(name, big = false)
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    StatCell("Distance", Format.km(s.distanceM), Format.distUnit, Modifier.weight(1f), big = true)
                    VerticalHairline(Modifier.padding(horizontal = 12.dp))
                    StatCell("Time", Format.duration(s.movingMs), null, Modifier.weight(1f), big = true)
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    StatCell("Activities", "${s.count}", null, Modifier.weight(1f), big = true)
                    VerticalHairline(Modifier.padding(horizontal = 12.dp))
                    StatCell("Climb", Format.meters(s.elevationM), "m", Modifier.weight(1f), big = true)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        BodyText("See you out there.", strong = true)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShareReviewButton(r)
            DoneButton(onClose, outlined = true)
        }
    }

    return pages
}

// =================================================================================================
// Building blocks
// =================================================================================================

@Composable
private fun Eyebrow(text: String, onYellow: Boolean = false, faint: Boolean = false, accent: Boolean = false) {
    val color = when {
        onYellow -> MColors.OnAccent.copy(alpha = if (faint) 0.55f else 0.8f)
        accent -> MColors.Accent
        else -> MColors.Accent
    }
    Text(text, style = MType.Label.copy(color = color, fontSize = MType.Label.fontSize * 1.1f))
}

@Composable
private fun Display(text: String, onYellow: Boolean = false, big: Boolean = true) {
    val base = MType.Hero.copy(color = if (onYellow) MColors.OnAccent else MColors.Text)
    val scale = if (big) 1.45f else 0.68f
    Text(text, style = base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale * 1.02f))
}

@Composable
private fun BodyText(text: String, onYellow: Boolean = false, strong: Boolean = false) {
    val color = when {
        onYellow -> MColors.OnAccent.copy(alpha = if (strong) 1f else 0.75f)
        strong -> MColors.Text
        else -> MColors.Muted
    }
    Text(text, style = MType.Body.copy(color = color, fontSize = MType.Body.fontSize * 1.2f, lineHeight = MType.Body.lineHeight * 1.2f))
}

@Composable
private fun DoneButton(onClose: () -> Unit, onYellow: Boolean = false, outlined: Boolean = false) {
    Box(
        Modifier
            .height(56.dp)
            .clip(CircleShape)
            .then(
                when {
                    outlined -> Modifier.border(1.dp, MColors.Stroke, CircleShape).background(MColors.Surface2)
                    onYellow -> Modifier.background(MColors.OnAccent)
                    else -> Modifier.background(MColors.Accent)
                }
            )
            .clickable(role = Role.Button, onClick = onClose)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Done",
            style = MType.Button.copy(
                color = when {
                    outlined -> MColors.Text
                    onYellow -> MColors.Accent
                    else -> MColors.OnAccent
                }
            ),
        )
    }
}

/** Renders the review as a story-sized image and hands it to Instagram (or any app). */
@Composable
private fun ShareReviewButton(review: Review) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Row(
        Modifier
            .height(56.dp)
            .clip(CircleShape)
            .background(MColors.Accent)
            .clickable(role = Role.Button, enabled = !busy) {
                busy = true
                scope.launch {
                    val bmp = withContext(Dispatchers.Default) { ShareRenderer.renderReview(context, review) }
                    val name = "meridian-review-${review.period.name.lowercase()}-${review.start}"
                    val activity = context as? Activity
                    val result = if (activity != null) ShareActions.toInstagram(activity, bmp, name) else InstagramResult.NO_INSTAGRAM
                    if (result != InstagramResult.OPENED) ShareActions.share(context, bmp, name)
                    busy = false
                }
            }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MIcons.Share, contentDescription = null, tint = MColors.OnAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text("Share to Story", style = MType.Button.copy(color = MColors.OnAccent))
    }
}

/** Fades and lifts its content in when the page becomes active. */
@Composable
private fun Rise(active: Boolean, content: @Composable () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) t.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) else t.snapTo(0f)
    }
    Box(
        Modifier
            .alpha(t.value)
            .graphicsLayer { translationY = (1f - t.value) * 40.dp.toPx() }
    ) { content() }
}

/** A number that counts up from zero when its page lands. */
@Composable
private fun CountUp(target: Double, active: Boolean, style: TextStyle, format: (Double) -> String) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(active, target) {
        if (active) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
        }
    }
    Text(format(target * progress.value), style = style)
}

private fun oneDecimal(v: Double) = String.format(Locale.US, "%.1f", v)

private fun reviewName(r: Review): String =
    if (r.period == Period.YEAR) "${r.start.year}"
    else r.start.month.getDisplayName(JTextStyle.FULL, Locale.getDefault())

private fun reviewTitle(r: Review): String =
    if (r.period == Period.YEAR) "${r.start.year} in review"
    else "${r.start.month.getDisplayName(JTextStyle.FULL, Locale.getDefault())} ${r.start.year}"
