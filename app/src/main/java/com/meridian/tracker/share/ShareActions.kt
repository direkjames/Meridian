package com.meridian.tracker.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

enum class InstagramResult { OPENED, NO_INSTAGRAM, FAILED }

/** Saving and sharing images. Everything is written to the phone; nothing is uploaded by Meridian. */
object ShareActions {

    private const val INSTAGRAM = "com.instagram.android"

    /** Writes the image to the app's private cache and returns a shareable content:// Uri. */
    private fun cacheUri(context: Context, bitmap: Bitmap, name: String): Uri {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Android's share sheet: Messenger, Telegram, Instagram, Files, anywhere. */
    fun share(context: Context, bitmap: Bitmap, name: String) {
        val uri = cacheUri(context, bitmap, name)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share"))
    }

    fun needsStoragePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Saves a PNG (transparency kept) to Pictures/Meridian. Returns true on success. */
    fun saveToGallery(context: Context, bitmap: Bitmap, name: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Meridian")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("No gallery uri")
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Meridian")
            dir.mkdirs()
            val file = File(dir, "$name.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/png"), null)
        }
        true
    } catch (e: Exception) {
        false
    }

    fun isInstagramInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(INSTAGRAM, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Hands a full image (Card layout, reviews) to Instagram's own share screen, where Story,
     * Feed and Messages are the choices. No developer setup needed.
     */
    fun toInstagram(activity: Activity, bitmap: Bitmap, name: String): InstagramResult {
        if (!isInstagramInstalled(activity)) return InstagramResult.NO_INSTAGRAM
        val uri = cacheUri(activity, bitmap, name)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            setPackage(INSTAGRAM)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.grantUriPermission(INSTAGRAM, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return launch(activity, intent)
    }

    /** Opens Instagram (after a transparent PNG has been saved, ready for the Photo sticker). */
    fun openInstagram(context: Context): InstagramResult {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(INSTAGRAM)
            ?: return InstagramResult.NO_INSTAGRAM
        return try {
            context.startActivity(launchIntent)
            InstagramResult.OPENED
        } catch (_: ActivityNotFoundException) {
            InstagramResult.FAILED
        }
    }

    private fun launch(activity: Activity, intent: Intent): InstagramResult = try {
        activity.startActivity(intent)
        InstagramResult.OPENED
    } catch (e: ActivityNotFoundException) {
        InstagramResult.FAILED
    } catch (e: SecurityException) {
        InstagramResult.FAILED
    }
}
