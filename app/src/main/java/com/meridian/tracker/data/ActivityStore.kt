package com.meridian.tracker.data

import android.content.Context
import android.util.Log
import com.meridian.tracker.tracking.Sport
import com.meridian.tracker.tracking.TrackPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Stores every activity as a JSON file on this phone (app-private storage).
 * No accounts, no servers.
 */
object ActivityStore {

    private const val TAG = "ActivityStore"
    private lateinit var dir: File
    private lateinit var inProgressFile: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One thread for snapshot writes and deletes, so they always happen in order. */
    private val snapshotScope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    private val _activities = MutableStateFlow<List<ActivityRecord>>(emptyList())
    /** All saved activities, newest first. */
    val activities: StateFlow<List<ActivityRecord>> = _activities.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    /** Serialises writes, so an edit (photos, gear, rename) never overwrites another one. */
    private val writeLock = Mutex()

    fun init(context: Context) {
        dir = File(context.filesDir, "activities").apply { mkdirs() }
        inProgressFile = File(context.filesDir, "in_progress.json")
        scope.launch {
            _activities.value = loadAll()
            loaded.complete(Unit)
            // Photos left behind by an activity that no longer exists (e.g. the app was closed
            // while a discarded draft was on screen).
            runCatching {
                val keep = (dir.listFiles() ?: emptyArray())
                    .filter { it.name.endsWith(".json") }
                    .map { it.name.removeSuffix(".json") }
                    .toMutableSet()
                loadInProgress()?.let { keep.add(it.id) }
                ActivityMedia.cleanupOrphans(keep)
            }.onFailure { Log.w(TAG, "Media cleanup failed", it) }
        }
    }

    /** Suspends until saved activities have been read from disk. */
    suspend fun ready() = loaded.await()

    // ---- Crash safety --------------------------------------------------------------------------
    // While recording, a snapshot is written every few seconds. If Android kills the app
    // mid-activity, the snapshot is offered back as a draft the next time Meridian opens.

    private fun writeInProgressNow(record: ActivityRecord) {
        val tmp = File(inProgressFile.path + ".tmp")
        tmp.writeText(toJson(record).toString())
        tmp.renameTo(inProgressFile)
    }

    fun writeInProgress(record: ActivityRecord) {
        snapshotScope.launch {
            try {
                writeInProgressNow(record)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't write in-progress snapshot", e)
            }
        }
    }

    fun clearInProgress() {
        snapshotScope.launch { inProgressFile.delete() }
    }

    /** Reads a leftover in-progress activity, if the app was killed while recording. */
    suspend fun loadInProgress(): ActivityRecord? = withContext(Dispatchers.IO) {
        if (!inProgressFile.exists()) return@withContext null
        try {
            fromJson(JSONObject(inProgressFile.readText()))
        } catch (e: Exception) {
            Log.w(TAG, "Discarding unreadable in-progress snapshot", e)
            inProgressFile.delete()
            null
        }
    }

    suspend fun rename(id: String, title: String) = update(id) { it.copy(title = title) }

    /**
     * Changes one saved activity based on its latest version on disk. Returns the new version,
     * or null if the activity no longer exists.
     */
    suspend fun update(id: String, transform: (ActivityRecord) -> ActivityRecord): ActivityRecord? {
        val result = writeLock.withLock {
            withContext(Dispatchers.IO) {
                val current = _activities.value.firstOrNull { it.id == id } ?: return@withContext null
                val next = transform(current)
                if (next == current) return@withContext current
                writeLocked(listOf(next))
                next
            }
        }
        if (result != null) onChanged?.invoke()
        return result
    }

    /** Called after any saved activity changes (used for automatic backups). */
    @Volatile
    var onChanged: (() -> Unit)? = null

    suspend fun save(record: ActivityRecord) = saveAll(listOf(record))

    /** Saves many at once (imports, restores) with a single list update. */
    suspend fun saveAll(records: List<ActivityRecord>) {
        if (records.isEmpty()) return
        writeLock.withLock { withContext(Dispatchers.IO) { writeLocked(records) } }
        onChanged?.invoke()
    }

    private fun writeLocked(records: List<ActivityRecord>) {
        records.forEach { record ->
            val tmp = File(dir, "${record.id}.json.tmp")
            tmp.writeText(toJson(record).toString())
            tmp.renameTo(File(dir, "${record.id}.json"))
        }
        val ids = records.map { it.id }.toSet()
        _activities.update { list ->
            (list.filterNot { it.id in ids } + records).sortedByDescending { it.startTimeMs }
        }
    }

    suspend fun delete(id: String) {
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                File(dir, "$id.json").delete()
                _activities.update { list -> list.filterNot { it.id == id } }
                ActivityMedia.deleteAll(id)
            }
        }
        onChanged?.invoke()
    }

    /** JSON text for one activity (the same format used on disk and in backups). */
    fun encode(record: ActivityRecord): String = toJson(record).toString()

    fun decode(json: String): ActivityRecord = fromJson(JSONObject(json))

    /** Total size of saved activities on disk, in bytes. */
    fun storageBytes(): Long = (dir.listFiles() ?: emptyArray()).sumOf { it.length() }

    fun find(id: String): ActivityRecord? = _activities.value.firstOrNull { it.id == id }

    private fun loadAll(): List<ActivityRecord> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { file ->
                try {
                    fromJson(JSONObject(file.readText()))
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable activity ${file.name}", e)
                    null
                }
            }
            .sortedByDescending { it.startTimeMs }

    private fun toJson(r: ActivityRecord): JSONObject = JSONObject().apply {
        put("v", 1)
        put("id", r.id)
        put("title", r.title)
        put("sport", r.sport.name)
        put("start", r.startTimeMs)
        put("distance", r.distanceM)
        put("moving", r.movingMs)
        put("elapsed", r.elapsedMs)
        put("gain", r.elevationGainM)
        put("splits", JSONArray().apply { r.splitsMs.forEach { put(it) } })
        put("partialM", r.partialSplitM)
        put("partialMs", r.partialSplitMs)
        put("points", JSONArray().apply {
            r.points.forEach { p ->
                put(JSONArray().apply {
                    put(p.lat)
                    put(p.lon)
                    put(p.alt ?: JSONObject.NULL)
                    put(p.time)
                    put(p.segment)
                })
            }
        })
        // Heart rate and cadence as flat [offset ms, value, offset ms, value, …] to keep files small.
        if (r.heartRate.isNotEmpty()) put("hr", samplesToJson(r.heartRate, r.startTimeMs))
        if (r.cadence.isNotEmpty()) put("cad", samplesToJson(r.cadence, r.startTimeMs))
        r.steps?.let { put("steps", it) }
        r.source?.let { put("source", it) }
        if (r.media.isNotEmpty()) put("media", JSONArray().apply {
            r.media.forEach { m ->
                put(JSONObject().put("id", m.id).put("kind", m.kind.name).put("file", m.file).put("added", m.addedMs))
            }
        })
        if (r.gearIds.isNotEmpty()) put("gear", JSONArray(r.gearIds))
    }

    private fun mediaFromJson(o: JSONObject): List<MediaItem> {
        val a = o.optJSONArray("media") ?: return emptyList()
        return List(a.length()) { i ->
            runCatching {
                val m = a.getJSONObject(i)
                MediaItem(
                    id = m.getString("id"),
                    kind = MediaKind.valueOf(m.getString("kind")),
                    file = m.getString("file"),
                    addedMs = m.optLong("added", 0L),
                )
            }.getOrNull()
        }
            .filterNotNull()
            // A backup from somewhere else must never point outside the media folder.
            .filter { ActivityMedia.isSafeFileName(it.file) }
            .distinctBy { it.id }
            .take(ActivityMedia.MAX_PER_ACTIVITY)
    }

    private fun gearFromJson(o: JSONObject): List<String> {
        val a = o.optJSONArray("gear") ?: return emptyList()
        return List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }.distinct()
    }

    private fun samplesToJson(samples: List<Sample>, start: Long) = JSONArray().apply {
        samples.forEach {
            put(it.time - start)
            put(it.value)
        }
    }

    private fun samplesFromJson(o: JSONObject, key: String, start: Long): List<Sample> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return List(a.length() / 2) { i -> Sample(start + a.getLong(i * 2), a.getInt(i * 2 + 1)) }
    }

    private fun fromJson(o: JSONObject): ActivityRecord {
        val splits = o.getJSONArray("splits").let { a -> List(a.length()) { a.getLong(it) } }
        val pts = o.getJSONArray("points").let { a ->
            List(a.length()) { i ->
                val p = a.getJSONArray(i)
                TrackPoint(
                    lat = p.getDouble(0),
                    lon = p.getDouble(1),
                    alt = if (p.isNull(2)) null else p.getDouble(2),
                    time = p.getLong(3),
                    segment = p.getInt(4),
                )
            }
        }
        return ActivityRecord(
            id = o.getString("id"),
            title = o.getString("title"),
            sport = runCatching { Sport.valueOf(o.getString("sport")) }.getOrDefault(Sport.RUN),
            startTimeMs = o.getLong("start"),
            distanceM = o.getDouble("distance"),
            movingMs = o.getLong("moving"),
            elapsedMs = o.getLong("elapsed"),
            elevationGainM = o.getDouble("gain"),
            splitsMs = splits,
            partialSplitM = o.optDouble("partialM", 0.0),
            partialSplitMs = o.optLong("partialMs", 0L),
            points = pts,
            heartRate = samplesFromJson(o, "hr", o.getLong("start")),
            cadence = samplesFromJson(o, "cad", o.getLong("start")),
            steps = if (o.has("steps")) o.optInt("steps") else null,
            source = if (o.has("source")) o.optString("source") else null,
            media = mediaFromJson(o),
            gearIds = gearFromJson(o),
        )
    }
}
