package com.meridian.tracker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.meridian.tracker.data.io.FitParser
import com.meridian.tracker.data.io.ParsedTrack
import com.meridian.tracker.data.io.RecordBuilder
import com.meridian.tracker.data.io.StravaCsv
import com.meridian.tracker.data.io.TrackFormats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Live status of an import, for the Settings screen. */
data class ImportStatus(
    val running: Boolean = false,
    val processed: Int = 0,
    val total: Int? = null,
    val result: String? = null,
)

/**
 * Moving data in and out: Strava archives and GPX/TCX/FIT files in, GPX files out, and
 * backups to a file of your choosing (Google Drive included) with optional automatic updates.
 */
object DataTransfer {

    private const val TAG = "DataTransfer"
    private const val BACKUP_MANIFEST = "meridian-backup.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _importStatus = MutableStateFlow(ImportStatus())
    val importStatus: StateFlow<ImportStatus> = _importStatus.asStateFlow()

    private val _backupError = MutableStateFlow<String?>(null)
    val backupError: StateFlow<String?> = _backupError.asStateFlow()

    // =============================================================================================
    // Import
    // =============================================================================================

    private class Counts {
        var imported = 0
        var duplicates = 0
        var skipped = 0
    }

    /** Imports Strava export ZIPs and/or individual GPX, TCX and FIT files (optionally .gz). */
    suspend fun import(context: Context, uris: List<Uri>) = withContext(Dispatchers.IO) {
        if (_importStatus.value.running) return@withContext
        _importStatus.value = ImportStatus(running = true)
        val counts = Counts()
        val existing = ActivityStore.activities.value.map { it.startTimeMs }.toMutableList()
        val pending = ArrayList<ActivityRecord>()

        suspend fun accept(track: ParsedTrack?, csv: StravaCsv.Entry?) {
            bump()
            if (track == null) { counts.skipped++; return }
            val csvSport = csv?.type?.let { RecordBuilder.sportFrom(it) }
            if (csv?.type != null && csvSport == null) { counts.skipped++; return } // swim, yoga, kayak…
            val record = RecordBuilder.build(
                track.copy(sport = csvSport ?: track.sport),
                nameOverride = csv?.name,
            )
            if (record == null) { counts.skipped++; return }
            if (existing.any { abs(it - record.startTimeMs) < 60_000L }) { counts.duplicates++; return }
            existing.add(record.startTimeMs)
            pending.add(record)
            counts.imported++
            if (pending.size >= 25) {
                ActivityStore.saveAll(pending.toList())
                pending.clear()
            }
        }

        try {
            for (uri in uris) {
                val name = displayName(context, uri).lowercase()
                if (name.endsWith(".zip")) {
                    importZip(context, uri, ::accept)
                } else {
                    val track = context.contentResolver.openInputStream(uri)?.use { parseFile(name, it) }
                    accept(track, null)
                }
            }
            ActivityStore.saveAll(pending.toList())
            _importStatus.value = ImportStatus(
                running = false,
                processed = _importStatus.value.processed,
                result = buildString {
                    append("Imported ${counts.imported} ${if (counts.imported == 1) "activity" else "activities"}")
                    if (counts.duplicates > 0) append(" · ${counts.duplicates} already here")
                    if (counts.skipped > 0) append(" · ${counts.skipped} skipped (no GPS or other sport)")
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Import failed", e)
            ActivityStore.saveAll(pending.toList())
            _importStatus.value = ImportStatus(
                running = false,
                result = "Import stopped: ${e.message ?: "unreadable file"}. ${counts.imported} imported.",
            )
        }
    }

    private fun bump() {
        val s = _importStatus.value
        _importStatus.value = s.copy(processed = s.processed + 1)
    }

    fun clearImportResult() {
        if (!_importStatus.value.running) _importStatus.value = ImportStatus()
    }

    private suspend fun importZip(
        context: Context,
        uri: Uri,
        accept: suspend (ParsedTrack?, StravaCsv.Entry?) -> Unit,
    ) {
        // Pass 1: Strava's activities.csv, for names and sports.
        var csv: Map<String, StravaCsv.Entry> = emptyMap()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name.substringAfterLast('/').equals("activities.csv", ignoreCase = true)) {
                        csv = StravaCsv.parse(zip.readBytes().toString(Charsets.UTF_8))
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        }
        if (csv.isNotEmpty()) {
            _importStatus.value = _importStatus.value.copy(total = csv.size)
        }

        // Pass 2: the activity files themselves.
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val path = entry.name
                    val lower = path.lowercase()
                    if (!entry.isDirectory && isTrackFile(lower)) {
                        val meta = csv[path] ?: csv[path.removePrefix("./")]
                        val track = try {
                            parseFile(lower, NonClosing(zip))
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping $path", e)
                            null
                        }
                        accept(track, meta)
                    }
                    entry = zip.nextEntry
                }
            }
        }
    }

    private fun isTrackFile(name: String) =
        listOf(".gpx", ".tcx", ".fit").any { name.endsWith(it) || name.endsWith("$it.gz") }

    /** Picks the right reader from the file name; handles .gz compression. */
    fun parseFile(name: String, input: InputStream): ParsedTrack? {
        val lower = name.lowercase()
        val stream: InputStream = if (lower.endsWith(".gz")) GZIPInputStream(NonClosing(input)) else NonClosing(input)
        val base = lower.removeSuffix(".gz")
        return when {
            base.endsWith(".gpx") -> TrackFormats.parseGpx(stream)
            base.endsWith(".tcx") -> TrackFormats.parseTcx(TrimLeading(stream))
            base.endsWith(".fit") -> FitParser.parse(stream)
            else -> null
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.let { return it }
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    /** Lets parsers "close" a stream without closing the ZIP it came from. */
    private class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    /** Strava's TCX files often start with spaces before `<?xml`, which XML parsers reject. */
    private class TrimLeading(input: InputStream) : FilterInputStream(input.buffered()) {
        private var started = false
        override fun read(): Int {
            if (!started) {
                started = true
                var c: Int
                do { c = super.read() } while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code)
                return c
            }
            return super.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!started) {
                if (len == 0) return 0
                val first = read()
                if (first < 0) return -1
                b[off] = first.toByte()
                val rest = super.read(b, off + 1, len - 1)
                return if (rest < 0) 1 else rest + 1
            }
            return super.read(b, off, len)
        }
    }

    // =============================================================================================
    // Export
    // =============================================================================================

    suspend fun exportGpx(context: Context, record: ActivityRecord, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            openForWrite(context, uri).use { it.write(TrackFormats.writeGpx(PrivacyZones.apply(record, Prefs.zones.value)).toByteArray()) }
        }.isSuccess
    }

    /** Every activity as a GPX file inside one ZIP. */
    suspend fun exportAllGpx(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = ActivityStore.activities.value
        val zones = Prefs.zones.value
        openForWrite(context, uri).use { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                val used = HashSet<String>()
                all.forEach { r ->
                    var name = TrackFormats.fileName(r)
                    var n = 2
                    while (!used.add(name)) name = TrackFormats.fileName(r).removeSuffix(".gpx") + "-${n++}.gpx"
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(TrackFormats.writeGpx(PrivacyZones.apply(r, zones)).toByteArray())
                    zip.closeEntry()
                }
            }
        }
        all.size
    }

    // =============================================================================================
    // Backup and restore
    // =============================================================================================

    /**
     * Writes a complete backup (every activity, full detail, photos and videos) as a ZIP.
     * The ZIP is built and checked in the cache first, so a failure while building it never
     * touches the previous backup. [includeVideos] is off for automatic backups, which rewrite
     * the whole file after every activity.
     */
    suspend fun writeBackup(context: Context, uri: Uri, includeVideos: Boolean = true): Int = withContext(Dispatchers.IO) {
        val all = ActivityStore.activities.value
        val tmp = java.io.File(context.cacheDir, "backup-${System.nanoTime()}.zip")
        try {
            tmp.outputStream().buffered().use { out -> buildBackupZip(out, all, includeVideos) }
            verifyBackup(tmp, all.size)
            openForWrite(context, uri).use { out -> tmp.inputStream().use { it.copyTo(out) } }
        } finally {
            tmp.delete()
        }
        all.size
    }

    private fun buildBackupZip(out: OutputStream, all: List<ActivityRecord>, includeVideos: Boolean) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_MANIFEST))
            zip.write(
                JSONObject()
                    .put("format", "meridian-backup")
                    .put("version", 2)
                    .put("created", System.currentTimeMillis())
                    .put("count", all.size)
                    .toString().toByteArray()
            )
            zip.closeEntry()
            all.forEach { r ->
                zip.putNextEntry(ZipEntry("activities/${r.id}.json"))
                zip.write(ActivityStore.encode(r).toByteArray())
                zip.closeEntry()
            }
            // Photos and videos (already compressed, so stored as they are).
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            all.forEach { r ->
                ActivityMedia.present(r).forEach { m ->
                    if (m.kind == MediaKind.VIDEO && !includeVideos) return@forEach
                    val file = ActivityMedia.fileOf(r.id, m) ?: return@forEach
                    zip.putNextEntry(ZipEntry("media/${ActivityMedia.backupFolder(r.id)}/${r.id}/${m.file}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)
            // Training plan, goals, routes and badge history.
            ExtrasStore.exportFiles().forEach { (name, text) ->
                zip.putNextEntry(ZipEntry("${ExtrasStore.DIR}/$name"))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** Reads the finished ZIP back, so a broken file is never copied over a good backup. */
    private fun verifyBackup(file: java.io.File, expectedActivities: Int) {
        java.util.zip.ZipFile(file).use { zf ->
            if (zf.getEntry(BACKUP_MANIFEST) == null) throw java.io.IOException("Backup is missing its manifest")
            val found = zf.entries().asSequence().count { it.name.startsWith("activities/") && it.name.endsWith(".json") }
            if (found != expectedActivities) throw java.io.IOException("Backup is incomplete")
        }
    }

    /** Restores a Meridian backup. Activities already on the phone are kept. Returns how many were added. */
    suspend fun restore(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val have = ActivityStore.activities.value.map { it.id }.toSet()
        val restored = ArrayList<ActivityRecord>()
        val extras = HashMap<String, String>()
        var sawManifest = false
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (entry.name == BACKUP_MANIFEST) sawManifest = true
                    if (entry.name.startsWith("activities/") && entry.name.endsWith(".json")) {
                        runCatching { ActivityStore.decode(zip.readBytes().toString(Charsets.UTF_8)) }
                            .getOrNull()
                            ?.takeIf { it.id !in have }
                            ?.let { restored.add(it) }
                    } else if (entry.name.startsWith("media/")) {
                        // media/<folder>/<activity id>/<file>
                        val parts = entry.name.split('/')
                        if (parts.size == 4 && !entry.isDirectory) {
                            ActivityMedia.restoreFile(parts[2], parts[3], NonClosing(zip))
                        }
                    } else if (entry.name.startsWith("${ExtrasStore.DIR}/")) {
                        extras[entry.name.substringAfter('/')] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    entry = zip.nextEntry
                }
            }
        }
        if (!sawManifest) throw IllegalArgumentException("That file isn't a Meridian backup")
        ActivityStore.saveAll(restored)
        if (extras.isNotEmpty()) withContext(Dispatchers.Main) { ExtrasStore.importFiles(extras) }
        restored.size
    }

    // ---- Automatic backup to the chosen file --------------------------------------------------

    private var autoJob: Job? = null

    /** Rewrites the chosen backup file a few seconds after the last change. */
    fun scheduleAutoBackup(context: Context) {
        val target = Prefs.autoBackupUri.value ?: return
        val app = context.applicationContext
        autoJob?.cancel()
        autoJob = scope.launch {
            delay(4_000)
            backupNowTo(app, Uri.parse(target))
        }
    }

    suspend fun backupNowTo(context: Context, uri: Uri): Boolean = try {
        writeBackup(context, uri, includeVideos = Prefs.backupVideos.value)
        Prefs.setLastBackup(System.currentTimeMillis())
        _backupError.value = null
        true
    } catch (e: SecurityException) {
        // Access to the file was lost (e.g. it was deleted, or this is a new phone).
        Prefs.setAutoBackupUri(null)
        _backupError.value = "Lost access to the backup file. Choose it again in Settings."
        false
    } catch (e: Exception) {
        Log.w(TAG, "Backup failed", e)
        _backupError.value = "Last backup didn't finish. It will retry after your next activity."
        false
    }

    /** Keeps permission to the picked file across restarts, then backs up straight away. */
    suspend fun enableAutoBackup(context: Context, uri: Uri): Boolean {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        Prefs.setAutoBackupUri(uri.toString())
        return backupNowTo(context, uri)
    }

    fun disableAutoBackup(context: Context) {
        Prefs.autoBackupUri.value?.let { s ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(s),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        Prefs.setAutoBackupUri(null)
        _backupError.value = null
    }

    /** "wt" truncates the old file; some storage apps only accept "w". */
    private fun openForWrite(context: Context, uri: Uri): OutputStream =
        runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri, "w")
            ?: throw IllegalStateException("Can't write to that file")
}
