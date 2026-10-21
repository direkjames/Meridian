package com.meridian.tracker.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.share.Ink
import com.meridian.tracker.share.ShareActions
import com.meridian.tracker.share.ShareLayout
import com.meridian.tracker.share.ShareOptions
import com.meridian.tracker.share.ShareRenderer
import com.meridian.tracker.share.InstagramResult
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.components.PillButton
import com.meridian.tracker.ui.components.Segmented
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class PreviewBg(val label: String) { PHOTO("Photo"), LIGHT("Light"), CHECKER("Clear") }

@Composable
fun ShareScreen(record: ActivityRecord, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    var layoutName by rememberSaveable { mutableStateOf(ShareLayout.STATS.name) }
    var inkName by rememberSaveable { mutableStateOf(Ink.WHITE.name) }
    var showRoute by rememberSaveable { mutableStateOf(true) }
    var shadow by rememberSaveable { mutableStateOf(true) }
    var previewName by rememberSaveable { mutableStateOf(PreviewBg.PHOTO.name) }
    var message by remember { mutableStateOf<String?>(null) }

    val options = ShareOptions(ShareLayout.valueOf(layoutName), Ink.valueOf(inkName), showRoute, shadow)
    val previewBg = PreviewBg.valueOf(previewName)

    val bitmap by produceState<Bitmap?>(initialValue = null, record.id, options) {
        value = withContext(Dispatchers.Default) { ShareRenderer.render(context, record, options) }
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(3200)
            message = null
        }
    }

    val fileName = "meridian-${record.startTimeMs}-${options.layout.name.lowercase()}"

    val transparent = options.layout.transparent

    /** Saves the PNG; optionally opens Instagram afterwards, ready for the Photo sticker. */
    fun save(bmp: Bitmap, thenOpenInstagram: Boolean = false) {
        if (!ShareActions.saveToGallery(context, bmp, fileName)) {
            message = "Couldn't save the image"
            return
        }
        message = if (transparent) "Saved. In your Story: sticker icon → Photo → pick it" else "Saved to Photos · Pictures/Meridian"
        if (thenOpenInstagram && ShareActions.openInstagram(context) == InstagramResult.NO_INSTAGRAM) {
            message = "Saved to Photos. Instagram isn't installed"
        }
    }

    var pendingOpenInstagram by remember { mutableStateOf(false) }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val bmp = bitmap
        if (granted && bmp != null) save(bmp, pendingOpenInstagram) else if (!granted) message = "Storage permission is needed to save"
        pendingOpenInstagram = false
    }

    fun saveWithPermission(bmp: Bitmap, thenOpenInstagram: Boolean = false) {
        val needs = ShareActions.needsStoragePermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needs) {
            pendingOpenInstagram = thenOpenInstagram
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            save(bmp, thenOpenInstagram)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MColors.Bg)
    ) {
        // Preview
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            PreviewBackground(previewBg, options.layout.transparent, Modifier.fillMaxSize())
            Crossfade(targetState = bitmap, label = "preview", modifier = Modifier.fillMaxSize()) { bmp ->
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Share image preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(start = 36.dp, end = 36.dp, top = 72.dp, bottom = 56.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(MIcons.Back, "Back", onClick = onBack)
                Spacer(Modifier.weight(1f))
                if (options.layout.transparent) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(MColors.Bg.copy(alpha = 0.8f))
                            .border(1.dp, MColors.Stroke, CircleShape)
                            .padding(4.dp),
                    ) {
                        PreviewBg.entries.forEach { bg ->
                            val sel = bg == previewBg
                            Text(
                                bg.label.uppercase(),
                                style = MType.Label.copy(color = if (sel) MColors.OnAccent else MColors.Text),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (sel) MColors.Accent else Color.Transparent)
                                    .clickable(role = Role.Tab) { previewName = bg.name }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = message != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp),
            ) {
                Text(
                    message.orEmpty(),
                    style = MType.Label.copy(color = MColors.OnAccent),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MColors.Accent)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // Controls
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MColors.Surface)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
        ) {
            Segmented(
                options = ShareLayout.entries.map { it.label },
                selected = options.layout.ordinal,
                onSelect = { layoutName = ShareLayout.entries[it].name },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("INK", style = MType.Label)
                Spacer(Modifier.width(12.dp))
                Ink.entries.forEach { ink ->
                    InkSwatch(ink, selected = ink == options.ink, onClick = { inkName = ink.name })
                    Spacer(Modifier.width(10.dp))
                }
                Spacer(Modifier.weight(1f))
                if (options.layout == ShareLayout.STATS) {
                    ToggleChip("Route", showRoute) { showRoute = !showRoute }
                    Spacer(Modifier.width(8.dp))
                }
                if (options.layout.transparent) ToggleChip("Shadow", shadow) { shadow = !shadow }
            }
            Spacer(Modifier.height(18.dp))
            PillButton(
                if (transparent) "Save transparent PNG" else "Save image",
                icon = MIcons.Download,
                onClick = { bitmap?.let { saveWithPermission(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    "Instagram",
                    icon = MIcons.Share,
                    filled = false,
                    onClick = {
                        val bmp = bitmap ?: return@PillButton
                        if (transparent) {
                            // Save first, then open Instagram so the Photo sticker can pick it up.
                            saveWithPermission(bmp, thenOpenInstagram = true)
                        } else {
                            val activity = context as? Activity ?: return@PillButton
                            message = when (ShareActions.toInstagram(activity, bmp, fileName)) {
                                InstagramResult.OPENED -> null
                                InstagramResult.NO_INSTAGRAM -> "Instagram isn't installed"
                                InstagramResult.FAILED -> "Couldn't open Instagram"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    "More",
                    filled = false,
                    onClick = { bitmap?.let { ShareActions.share(context, it, fileName) } },
                    modifier = Modifier.weight(1f),
                )
            }
            if (transparent) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Tip: in your Instagram Story, tap the sticker icon, choose Photo and pick this image. The background stays see-through.",
                    style = MType.Body.copy(fontSize = MType.Body.fontSize * 0.85f, color = MColors.Faint),
                )
            }
        }
    }
}

@Composable
private fun InkSwatch(ink: Ink, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(2.dp, if (selected) MColors.Accent else Color.Transparent, CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(Color(ink.argb))
            .border(1.dp, MColors.Stroke, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "${ink.label} ink" }
    )
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label.uppercase(),
        style = MType.Label.copy(color = if (on) MColors.OnAccent else MColors.Muted),
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) MColors.Accent else Color.Transparent)
            .border(1.dp, if (on) MColors.Accent else MColors.Stroke, CircleShape)
            .clickable(role = Role.Switch, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

/** Something to judge the sticker against: a moody "photo", a bright one, or a transparency grid. */
@Composable
private fun PreviewBackground(bg: PreviewBg, transparent: Boolean, modifier: Modifier) {
    if (!transparent) {
        Box(modifier.background(MColors.Surface2))
        return
    }
    when (bg) {
        PreviewBg.PHOTO -> Box(
            modifier.background(
                Brush.linearGradient(
                    listOf(Color(0xFF2B5C55), Color(0xFF3F5A6E), Color(0xFF6A4C78), Color(0xFFB36A4F)),
                )
            )
        )
        PreviewBg.LIGHT -> Box(
            modifier.background(Brush.verticalGradient(listOf(Color(0xFFE9EEF2), Color(0xFFC9D6DF))))
        )
        PreviewBg.CHECKER -> Canvas(modifier) {
            val s = 14.dp.toPx()
            drawRect(Color(0xFF2A2C30))
            var y = 0f
            var row = 0
            while (y < size.height) {
                var x = if (row % 2 == 0) 0f else s
                while (x < size.width) {
                    drawRect(Color(0xFF34373C), Offset(x, y), Size(s, s))
                    x += 2 * s
                }
                y += s
                row++
            }
        }
    }
}
