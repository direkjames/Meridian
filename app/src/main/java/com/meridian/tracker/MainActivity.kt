package com.meridian.tracker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.tracking.RecordStatus
import com.meridian.tracker.ui.components.MeridianNavBar
import com.meridian.tracker.ui.components.Tab
import com.meridian.tracker.tracking.TrackingManager
import com.meridian.tracker.ui.components.FullscreenBox
import com.meridian.tracker.data.Period
import com.meridian.tracker.ui.screens.ActivitiesScreen
import com.meridian.tracker.ui.screens.OfflineMapsScreen
import com.meridian.tracker.ui.screens.ProgressScreen
import com.meridian.tracker.ui.screens.ReviewScreen
import com.meridian.tracker.ui.screens.ShareScreen
import com.meridian.tracker.ui.screens.SettingsScreen
import com.meridian.tracker.ui.screens.SensorsScreen
import com.meridian.tracker.ui.screens.GearScreen
import com.meridian.tracker.data.HealthSync
import com.meridian.tracker.ui.screens.PlanScreen
import com.meridian.tracker.ui.screens.RouteBuilderScreen
import com.meridian.tracker.ui.screens.HeatmapScreen
import com.meridian.tracker.ui.screens.BadgesScreen
import java.time.LocalDate
import com.meridian.tracker.ui.screens.PermissionScreen
import com.meridian.tracker.ui.screens.RecordScreen
import com.meridian.tracker.ui.screens.SummaryScreen
import com.meridian.tracker.ui.theme.MeridianTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            MeridianTheme {
                MeridianRoot()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeridianRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPrecise by remember { mutableStateOf(hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) }
    var hasApproximate by remember { mutableStateOf(hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    var needsSettings by remember { mutableStateOf(false) }

    fun refresh() {
        hasPrecise = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        hasApproximate = hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val activity = context as? Activity
        needsSettings = askedOnce && !hasPrecise && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        refresh()
    }

    if (!hasPrecise) {
        PermissionScreen(
            onAllow = {
                val perms = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                launcher.launch(perms.toTypedArray())
            },
            needsSettings = needsSettings,
            approximateOnly = hasApproximate && askedOnce,
            onOpenSettings = { openAppSettings(context) },
        )
        return
    }

    val draft by TrackingManager.draft.collectAsStateWithLifecycle()
    val recovered by TrackingManager.draftRecovered.collectAsStateWithLifecycle()
    val tracking by TrackingManager.state.collectAsStateWithLifecycle()
    val saved by ActivityStore.activities.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.RECORD) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewKey by rememberSaveable { mutableStateOf<String?>(null) } // e.g. "YEAR:2026-01-01"
    var showOffline by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSensors by rememberSaveable { mutableStateOf(false) }
    var showGear by rememberSaveable { mutableStateOf(false) }
    var shareTarget by rememberSaveable { mutableStateOf<String?>(null) } // "draft" or an activity id
    var showRouteBuilder by rememberSaveable { mutableStateOf(false) }
    var showHeatmap by rememberSaveable { mutableStateOf(false) }
    var showBadges by rememberSaveable { mutableStateOf(false) }

    // Keep showing the last draft while the screen fades out after save/discard.
    var lastDraft by remember { mutableStateOf<ActivityRecord?>(null) }
    LaunchedEffect(draft) { draft?.let { lastDraft = it } }

    val screenKey = when {
        shareTarget != null -> "share"
        draft != null -> "draft"
        reviewKey != null -> "review"
        showOffline -> "offline"
        showSensors -> "sensors"
        showGear -> "gear"
        showSettings -> "settings"
        showRouteBuilder -> "routes"
        showHeatmap -> "heatmap"
        showBadges -> "badges"
        openId != null -> "detail"
        tab == Tab.ACTIVITIES -> "list"
        tab == Tab.PLAN -> "plan"
        tab == Tab.PROGRESS -> "progress"
        else -> "record"
    }
    val showNav = screenKey == "record" && tracking.status == RecordStatus.IDLE ||
        screenKey == "list" || screenKey == "plan" || screenKey == "progress"

    BackHandler(enabled = screenKey == "list" || screenKey == "plan" || screenKey == "progress") { tab = Tab.RECORD }

    // New watch workouts from Health Connect, whenever Meridian comes to the front (at most every 15 min).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { runCatching { HealthSync.syncIfDue(context) } }
    }

    /** Leave whatever is open and go to the Record tab (plan session, ghost race, route follow). */
    fun goRecord() {
        openId = null
        showRouteBuilder = false
        tab = Tab.RECORD
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (showNav) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)
        ) {
            Crossfade(targetState = screenKey, animationSpec = tween(280), label = "screens") { key ->
                FullscreenBox {
                    when (key) {
                        "draft" -> (draft ?: lastDraft)?.let { record ->
                            SummaryScreen(
                                record = record,
                                isNew = true,
                                recovered = recovered,
                                onShare = { shareTarget = "draft" },
                                onSave = { title ->
                                    scope.launch {
                                        // The latest draft, with every photo and gear pick made on this screen.
                                        val latest = TrackingManager.draft.value?.takeIf { it.id == record.id } ?: record
                                        ActivityStore.save(latest.copy(title = title))
                                        TrackingManager.clearDraft()
                                    }
                                },
                                onDiscard = { TrackingManager.discardDraft() },
                                onClose = { TrackingManager.discardDraft() },
                                onDelete = { TrackingManager.discardDraft() },
                            )
                        }
                        "detail" -> {
                            val record = saved.firstOrNull { it.id == openId }
                            if (record != null) {
                                SummaryScreen(
                                    record = record,
                                    isNew = false,
                                    onSave = {},
                                    onRename = { title -> scope.launch { ActivityStore.rename(record.id, title) } },
                                    onShare = { shareTarget = record.id },
                                    onRaceGhost = { goRecord() },
                                    onDiscard = {},
                                    onClose = { openId = null },
                                    onDelete = {
                                        val id = record.id
                                        openId = null
                                        scope.launch { ActivityStore.delete(id) }
                                    },
                                )
                            }
                        }
                        "list" -> ActivitiesScreen(onOpen = { id -> openId = id })
                        "progress" -> ProgressScreen(
                            onOpenActivity = { id -> openId = id },
                            onOpenReview = { period, start -> reviewKey = "${period.name}:$start" },
                            onOpenBadges = { showBadges = true },
                            onOpenHeatmap = { showHeatmap = true },
                        )
                        "plan" -> PlanScreen(onStartSession = { goRecord() })
                        "routes" -> RouteBuilderScreen(onBack = { showRouteBuilder = false }, onFollow = { goRecord() })
                        "heatmap" -> HeatmapScreen(onBack = { showHeatmap = false })
                        "badges" -> BadgesScreen(
                            onBack = { showBadges = false },
                            onOpenActivity = { id ->
                                showBadges = false
                                openId = id
                            },
                        )
                        "review" -> {
                            val parts = reviewKey?.split(":")
                            val period = parts?.getOrNull(0)?.let { runCatching { Period.valueOf(it) }.getOrNull() }
                            val start = parts?.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                            if (period != null && start != null) {
                                ReviewScreen(
                                    period = period,
                                    start = start,
                                    onClose = { reviewKey = null },
                                    onOpenActivity = { id ->
                                        reviewKey = null
                                        openId = id
                                    },
                                )
                            }
                        }
                        "offline" -> OfflineMapsScreen(onBack = { showOffline = false })
                        "share" -> {
                            val target = shareTarget
                            val record = if (target == "draft") draft ?: lastDraft else saved.firstOrNull { it.id == target }
                            if (record != null) ShareScreen(record = record, onBack = { shareTarget = null })
                        }
                        "sensors" -> SensorsScreen(onBack = { showSensors = false })
                        "gear" -> GearScreen(onBack = { showGear = false })
                        "settings" -> SettingsScreen(
                            onBack = { showSettings = false },
                            onOpenOfflineMaps = { showOffline = true },
                            onOpenSensors = { showSensors = true },
                            onOpenGear = { showGear = true },
                        )
                        else -> RecordScreen(
                            onOpenSettings = { showSettings = true },
                            onOpenRouteBuilder = { showRouteBuilder = true },
                            onOpenSensors = { showSensors = true },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showNav,
            enter = expandVertically(tween(260)) + fadeIn(tween(260)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(180)),
        ) {
            MeridianNavBar(selected = tab, onSelect = { tab = it })
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
