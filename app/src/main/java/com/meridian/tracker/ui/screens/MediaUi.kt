package com.meridian.tracker.ui.screens

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meridian.tracker.data.ActivityEdits
import com.meridian.tracker.data.ActivityMedia
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.MediaItem
import com.meridian.tracker.data.MediaKind
import com.meridian.tracker.ui.Format
import com.meridian.tracker.ui.components.CircleIconButton
import com.meridian.tracker.ui.components.MIcons
import com.meridian.tracker.ui.theme.MColors
import com.meridian.tracker.ui.theme.MType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Photos and videos for an activity: take them with the camera or pick them from the gallery,
 * up to five. Tap a photo to see it full screen, a video to play it.
 */
@Composable
fun MediaSection(record: ActivityRecord, onBusyChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember(record.id, record.media) { ActivityMedia.present(record) }
    val room = (ActivityMedia.MAX_PER_ACTIVITY - record.media.size).coerceAtLeast(0)

    var choosing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<MediaItem?>(null) }
    var confirmRemove by remember { mutableStateOf<MediaItem?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableIntStateOf(0) }
    // The camera writes here; kept across rotation / process death while the camera is open.
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(busy) { onBusyChange(busy > 0) }
    LaunchedEffect(note) {
        if (note != null) {
            delay(3500)
            note = null
        }
    }

    fun addAll(uris: List<Uri>, videoHint: Boolean? = null, afterEach: () -> Unit = {}) {
        if (uris.isEmpty()) { afterEach(); return }
        val free = (ActivityMedia.MAX_PER_ACTIVITY - record.media.size).coerceAtLeast(0)
        val take = uris.take(free)
        if (uris.size > take.size) note = if (free == 0) "5 is the limit for one activity" else "Only $free more fit. Added the first $free."
        if (take.isEmpty()) { afterEach(); return }
        busy++
        scope.launch {
            var failed = 0
            try {
                for (uri in take) {
                    val item = try {
                        ActivityMedia.import(context, record.id, uri, videoHint)
                    } catch (e: ActivityMedia.TooLargeException) {
                        note = e.message
                        null
                    }
                    if (item == null || !ActivityEdits.addMedia(record.id, item)) failed++
                }
            } finally {
                busy--
                afterEach()
            }
            if (failed > 0 && note == null) note = if (failed == 1) "Couldn't add that file" else "Couldn't add $failed files"
        }
    }

    val pickMany = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ActivityMedia.MAX_PER_ACTIVITY),
    ) { uris -> addAll(uris) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        addAll(listOfNotNull(uri))
    }

    fun finishCapture(ok: Boolean, video: Boolean) {
        val file = capturePath?.let { File(it) }
        capturePath = null
        if (file == null) return
        if (ok && file.exists() && file.length() > 0) {
            addAll(listOf(Uri.fromFile(file)), videoHint = video) { scope.launch(Dispatchers.IO) { ActivityMedia.discardCapture(file) } }
        } else {
            ActivityMedia.discardCapture(file)
        }
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> finishCapture(ok, video = false) }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok -> finishCapture(ok, video = true) }

    fun capture(video: Boolean) {
        choosing = false
        try {
            val (file, uri) = ActivityMedia.newCaptureTarget(context, video)
            capturePath = file.path
            if (video) takeVideo.launch(uri) else takePhoto.launch(uri)
        } catch (e: ActivityNotFoundException) {
            capturePath = null
            note = "No camera app found"
        } catch (e: Exception) {
            capturePath = null
            note = "Couldn't open the camera"
        }
    }

    fun pickFromGallery() {
        choosing = false
        try {
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            // The multi-picker needs room for at least two.
            if (room >= 2) pickMany.launch(request) else pickOne.launch(request)
        } catch (e: ActivityNotFoundException) {
            note = "No gallery app found"
        }
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("PHOTOS & VIDEOS", style = MType.Label, modifier = Modifier.weight(1f))
            Text(
                "${record.media.size} / ${ActivityMedia.MAX_PER_ACTIVITY}",
                style = MType.Label.copy(color = if (room == 0) MColors.Accent else MColors.Faint),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items.forEach { item ->
                MediaTile(
                    activityId = record.id,
                    item = item,
                    onOpen = {
                        if (item.kind == MediaKind.VIDEO) {
                            if (!ActivityMedia.open(context, record.id, item)) note = "No video player found"
                        } else {
                            viewing = item
                        }
                    },
                    onRemove = { confirmRemove = item },
                )
            }
            repeat(busy.coerceAtMost(room)) { LoadingTile() }
            if (room > busy) {
                AddTile(empty = items.isEmpty(), onClick = { choosing = true })
            }
        }
        AnimatedVisibility(visible = note != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                (note ?: "").uppercase(),
                style = MType.Label.copy(color = MColors.Accent),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            containerColor = MColors.Surface2,
            title = { Text("Add to this activity", style = MType.Heading) },
            text = {
                Column {
                    Text(
                        if (room == 1) "1 more photo or video fits." else "Up to $room more photos or videos.",
                        style = MType.Body,
                    )
                    Spacer(Modifier.height(12.dp))
                    SourceRow(MIcons.Camera, "Take a photo") { capture(video = false) }
                    SourceRow(MIcons.Video, "Record a video") { capture(video = true) }
                    SourceRow(MIcons.Gallery, "Choose from gallery") { pickFromGallery() }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choosing = false }) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) }
            },
        )
    }

    viewing?.let { item ->
        PhotoViewer(record.id, item, onClose = { viewing = null }, onRemove = { confirmRemove = item })
    }

    // After the viewer, so the question shows on top of it.
    confirmRemove?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            containerColor = MColors.Surface2,
            title = { Text(if (item.kind == MediaKind.VIDEO) "Remove this video?" else "Remove this photo?", style = MType.Heading) },
            text = { Text("It's removed from Meridian. Copies in your gallery aren't touched.", style = MType.Body) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    if (viewing?.id == item.id) viewing = null
                    scope.launch { ActivityEdits.removeMedia(record.id, item) }
                }) { Text("Remove", style = MType.Button.copy(color = MColors.Danger)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Cancel", style = MType.Button.copy(color = MColors.Text)) }
            },
        )
    }
}

@Composable
private fun SourceRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MColors.Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MType.Body.copy(color = MColors.Text))
    }
}

private val TILE = 96.dp

@Composable
private fun MediaTile(activityId: String, item: MediaItem, onOpen: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val thumb by produceState<Bitmap?>(initialValue = null, activityId, item.id) {
        value = withContext(Dispatchers.IO) { ActivityMedia.thumbnail(activityId, item) }
    }
    val duration by produceState<Long?>(initialValue = null, activityId, item.id) {
        if (item.kind == MediaKind.VIDEO) value = withContext(Dispatchers.IO) { ActivityMedia.videoDurationMs(activityId, item) }
    }
    Box(
        Modifier
            .size(TILE)
            .clip(shape)
            .background(MColors.Surface2)
            .border(1.dp, MColors.Hairline, shape)
            .clickable(role = Role.Button, onClickLabel = if (item.kind == MediaKind.VIDEO) "Play video" else "View photo", onClick = onOpen)
            .semantics { contentDescription = if (item.kind == MediaKind.VIDEO) "Video" else "Photo" },
    ) {
        thumb?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (item.kind == MediaKind.VIDEO) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(MColors.Bg.copy(alpha = 0.75f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MIcons.Play, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(10.dp))
                duration?.let {
                    Spacer(Modifier.width(4.dp))
                    Text(Format.duration(it), style = MType.Label.copy(color = MColors.Text, fontSize = MType.Label.fontSize * 0.85f))
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(MColors.Bg.copy(alpha = 0.75f))
                .clickable(role = Role.Button, onClickLabel = "Remove", onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MIcons.Close, contentDescription = "Remove", tint = MColors.Text, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun AddTile(empty: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .size(width = if (empty) 200.dp else TILE, height = TILE)
            .clip(shape)
            .background(MColors.Accent.copy(alpha = 0.06f))
            .border(1.dp, MColors.Accent.copy(alpha = 0.45f), shape)
            .clickable(role = Role.Button, onClickLabel = "Add photos or videos", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(MIcons.Plus, contentDescription = null, tint = MColors.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(if (empty) "ADD PHOTOS OR VIDEOS" else "ADD", style = MType.Label.copy(color = MColors.Accent))
    }
}

@Composable
private fun LoadingTile() {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .size(TILE)
            .clip(shape)
            .background(MColors.Surface2)
            .border(1.dp, MColors.Hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text("ADDING…", style = MType.Label.copy(color = MColors.Faint))
    }
}

@Composable
private fun PhotoViewer(activityId: String, item: MediaItem, onClose: () -> Unit, onRemove: () -> Unit) {
    val photo by produceState<Bitmap?>(initialValue = null, activityId, item.id) {
        value = withContext(Dispatchers.IO) {
            ActivityMedia.fileOf(activityId, item)?.let { runCatching { ActivityMedia.decodeFile(it, 2048) }.getOrNull() }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            photo?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(MIcons.Close, "Close", onClick = onClose)
                Spacer(Modifier.weight(1f))
                CircleIconButton(MIcons.Trash, "Remove photo", onClick = onRemove)
            }
        }
    }
}
