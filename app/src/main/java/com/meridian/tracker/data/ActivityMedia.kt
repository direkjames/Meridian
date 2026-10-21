package com.meridian.tracker.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photos and videos added to an activity. Kept in app storage (files/media/<activity>/), so
 * they stay private to Meridian. Photos are resized to a sensible size so five of them don't
 * fill the phone; videos are copied as they are, up to [MAX_VIDEO_BYTES].
 */
object ActivityMedia {

    private const val TAG = "ActivityMedia"
    const val MAX_PER_ACTIVITY = 5
    const val MAX_VIDEO_BYTES = 500L * 1024 * 1024
    private const val PHOTO_MAX_EDGE = 2560
    private const val DIR = "media"
    private const val CAPTURE_DIR = "capture"

    private lateinit var root: File
    private lateinit var captureDir: File

    /** Small previews for the UI, keyed by file path. */
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    class TooLargeException : IOException("That video is too large (over ${MAX_VIDEO_BYTES / (1024 * 1024)} MB)")

    fun init(context: Context) {
        root = File(context.filesDir, DIR).apply { mkdirs() }
        captureDir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
    }

    /** Only names Meridian itself writes are accepted, so nothing can point outside the folder. */
    fun isSafeFileName(name: String): Boolean = SAFE_NAME.matches(name)

    private val SAFE_NAME = Regex("^[A-Za-z0-9_-]{1,64}\\.(jpg|mp4|webm|3gp|mkv|mov)$")

    /** Activity ids become folder names; anything unusual is replaced. */
    private fun folderName(activityId: String) = activityId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120)

    private fun folder(activityId: String) = File(root, folderName(activityId))

    fun fileOf(activityId: String, item: MediaItem): File? =
        if (isSafeFileName(item.file)) File(folder(activityId), item.file) else null

    /** Media whose files are still on this phone (a restored backup has none). */
    fun present(record: ActivityRecord): List<MediaItem> =
        record.media.filter { fileOf(record.id, it)?.exists() == true }

    fun photos(record: ActivityRecord): List<MediaItem> = present(record).filter { it.kind == MediaKind.PHOTO }

    // ---- Adding ------------------------------------------------------------------------------

    /** A file for the camera app to write into; hand [uri] to TakePicture / CaptureVideo. */
    fun newCaptureTarget(context: Context, video: Boolean): Pair<File, Uri> {
        captureDir.mkdirs()
        // Old captures that were never used (camera cancelled, app closed).
        captureDir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
        val file = File(captureDir, "${UUID.randomUUID()}.${if (video) "mp4" else "jpg"}")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    /**
     * Copies a photo or video (gallery Uri or a camera capture) into the activity's folder.
     * Returns null when it can't be read. Throws [TooLargeException] for huge videos.
     */
    suspend fun import(context: Context, activityId: String, uri: Uri, videoHint: Boolean? = null): MediaItem? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
            val isVideo = when {
                mime.startsWith("video/") -> true
                mime.startsWith("image/") -> false
                else -> videoHint ?: false
            }
            val dir = folder(activityId).apply { mkdirs() }
            val id = UUID.randomUUID().toString().replace("-", "").take(20)
            try {
                if (isVideo) {
                    val ext = when {
                        mime.contains("webm") -> "webm"
                        mime.contains("3gpp") -> "3gp"
                        mime.contains("matroska") -> "mkv"
                        mime.contains("quicktime") -> "mov"
                        else -> "mp4"
                    }
                    val out = File(dir, "$id.$ext")
                    copyCapped(context, uri, out)
                    MediaItem(id, MediaKind.VIDEO, out.name, System.currentTimeMillis())
                } else {
                    val out = File(dir, "$id.jpg")
                    val bitmap = decodePhoto(context, uri, PHOTO_MAX_EDGE) ?: return@withContext null
                    try {
                        val tmp = File(dir, "$id.jpg.tmp")
                        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                        if (!tmp.renameTo(out)) { tmp.delete(); return@withContext null }
                    } finally {
                        bitmap.recycle()
                    }
                    MediaItem(id, MediaKind.PHOTO, out.name, System.currentTimeMillis())
                }
            } catch (e: TooLargeException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't add media", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Photo too large to decode", e)
                null
            }
        }

    private fun copyCapped(context: Context, uri: Uri, out: File) {
        val tmp = File(out.path + ".tmp")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open video")
            input.use { ins ->
                FileOutputStream(tmp).use { os ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_VIDEO_BYTES) throw TooLargeException()
                        os.write(buf, 0, n)
                    }
                }
            }
            if (tmp.length() == 0L) throw IOException("Empty video")
            if (!tmp.renameTo(out)) throw IOException("Couldn't store video")
        } finally {
            tmp.delete()
        }
    }

    /** Deletes a camera capture once it has been copied (or cancelled). */
    fun discardCapture(file: File?) {
        runCatching { file?.delete() }
    }

    // ---- Removing ----------------------------------------------------------------------------

    fun delete(activityId: String, item: MediaItem) {
        fileOf(activityId, item)?.let { f ->
            evict(f)
            f.delete()
        }
    }

    fun deleteAll(activityId: String) {
        if (!::root.isInitialized) return
        val dir = folder(activityId)
        dir.listFiles()?.forEach { evict(it) }
        dir.deleteRecursively()
    }

    private fun evict(file: File) {
        thumbs.snapshot().keys.filter { it.startsWith("${file.path}@") }.forEach { thumbs.remove(it) }
    }

    /** Removes folders of activities that no longer exist. [keepIds] are activity ids. */
    fun cleanupOrphans(keepIds: Set<String>) {
        if (!::root.isInitialized) return
        val keep = keepIds.map { folderName(it) }.toSet()
        root.listFiles()?.forEach { if (it.isDirectory && it.name !in keep) it.deleteRecursively() }
    }

    // ---- Reading ------------------------------------------------------------------------------

    /** A preview no larger than [maxPx] on its long edge (first frame for videos). Cached. */
    fun thumbnail(activityId: String, item: MediaItem, maxPx: Int = 480): Bitmap? {
        val file = fileOf(activityId, item) ?: return null
        val key = "${file.path}@$maxPx"
        thumbs.get(key)?.let { return it }
        val bmp = try {
            if (item.kind == MediaKind.VIDEO) videoFrame(file, maxPx) else decodeFile(file, maxPx)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        thumbs.put(key, bmp)
        return bmp
    }

    /** Loads a stored photo, scaled down to fit [maxPx] on its long edge. */
    fun decodeFile(file: File, maxPx: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        return scaleDown(bmp, maxPx)
    }

    private fun videoFrame(file: File, maxPx: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it, maxPx) }
        } finally {
            runCatching { r.release() }
        }
    }

    /** Duration of a stored video in ms, or null. */
    fun videoDurationMs(activityId: String, item: MediaItem): Long? {
        val file = fileOf(activityId, item) ?: return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Opens a stored video (or photo) in the phone's own viewer. */
    fun open(context: Context, activityId: String, item: MediaItem): Boolean {
        val file = fileOf(activityId, item)?.takeIf { it.exists() } ?: return false
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, if (item.kind == MediaKind.VIDEO) "video/*" else "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Decodes a picked/captured photo the right way up, at most [maxEdge] on its long side. */
    private fun decodePhoto(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // With inJustDecodeBounds the decode always returns null; only the sizes in [bounds] matter.
        val input = resolver.openInputStream(uri) ?: return null
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ins ->
                when (ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        val scaled = scaleDown(decoded, maxEdge)
        if (rotation == 0f) return scaled
        val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(rotation) }, true)
        if (rotated !== scaled) scaled.recycle()
        return rotated
    }

    private fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxPx) s *= 2
        return s
    }

    private fun scaleDown(bmp: Bitmap, maxPx: Int): Bitmap {
        val long = max(bmp.width, bmp.height)
        if (long <= maxPx) return bmp
        val k = maxPx.toFloat() / long
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * k).roundToInt().coerceAtLeast(1), (bmp.height * k).roundToInt().coerceAtLeast(1), true)
        if (out !== bmp) bmp.recycle()
        return out
    }
}
