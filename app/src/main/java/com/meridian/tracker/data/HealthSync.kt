package com.meridian.tracker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.meridian.tracker.data.io.ParsedTrack
import com.meridian.tracker.data.io.RawPoint
import com.meridian.tracker.data.io.RecordBuilder
import com.meridian.tracker.tracking.Sport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KClass

/**
 * Reads workouts from Android's Health Connect, where watch apps like Fitbit, Garmin Connect,
 * Samsung Health and Pixel Watch save them. Everything stays on the phone: Health Connect is
 * an on-device store, and Meridian only reads from it.
 *
 * - Watch workouts become Meridian activities (with the map when the watch app shares the route).
 * - Phone recordings that have no heart rate get it from the watch you wore at the same time.
 */
object HealthSync {

    private const val TAG = "HealthSync"
    private const val ROUTES_PERMISSION = "android.permission.health.READ_EXERCISE_ROUTES"
    private const val FIRST_SYNC_DAYS = 60L
    private const val AUTO_SYNC_EVERY_MS = 15 * 60_000L
    private const val PROVIDER = "com.google.android.apps.healthdata"

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        ROUTES_PERMISSION,
    )

    /** Routes are a bonus; the rest is needed. */
    private val required: Set<String> = permissions - ROUTES_PERMISSION

    enum class Availability { AVAILABLE, NEEDS_INSTALL, UNAVAILABLE }

    data class SyncState(val running: Boolean = false, val message: String? = null)

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val mutex = Mutex()

    fun availability(context: Context): Availability =
        when (runCatching { HealthConnectClient.getSdkStatus(context) }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NEEDS_INSTALL
            else -> Availability.UNAVAILABLE
        }

    /** Use with rememberLauncherForActivityResult, launching it with [permissions]. */
    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun isConnected(context: Context): Boolean {
        if (availability(context) != Availability.AVAILABLE) return false
        return runCatching {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(required)
        }.getOrDefault(false)
    }

    /** Opens Health Connect in the Play Store (Android 13 and older need the app). */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding"))
            .setPackage("com.android.vending")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installWebIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PROVIDER"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Health Connect's own settings, where people choose which apps share what. */
    fun settingsIntent(): Intent =
        Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun clearMessage() {
        if (!_state.value.running) _state.value = SyncState()
    }

    /** Syncs when the app opens, at most every 15 minutes, if the user switched it on. */
    suspend fun syncIfDue(context: Context) {
        if (!Prefs.healthAutoSync.value) return
        if (System.currentTimeMillis() - Prefs.healthLastSyncMs.value < AUTO_SYNC_EVERY_MS) return
        if (!isConnected(context)) return
        sync(context, quiet = true)
    }

    /** Reads new watch workouts. Returns a short result line for the UI. */
    suspend fun sync(context: Context, quiet: Boolean = false): String {
        if (mutex.isLocked) return "Already syncing"
        return mutex.withLock {
            if (!quiet) _state.value = SyncState(running = true)
            val message = try {
                withContext(Dispatchers.IO) { doSync(context.applicationContext) }
            } catch (e: Exception) {
                Log.w(TAG, "Health Connect sync failed", e)
                "Couldn't read Health Connect. Check that Meridian still has access."
            }
            _state.value = SyncState(running = false, message = if (quiet && message == UP_TO_DATE) null else message)
            message
        }
    }

    private const val UP_TO_DATE = "Up to date. No new watch workouts."

    private suspend fun doSync(context: Context): String {
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(required)) return "Connect Health Connect first."
        ActivityStore.ready()

        val now = Instant.now()
        val last = Prefs.healthLastSyncMs.value
        val since = if (last == 0L) now.minus(Duration.ofDays(FIRST_SYNC_DAYS)) else Instant.ofEpochMilli(last).minus(Duration.ofDays(3))
        val existing = ActivityStore.activities.value

        // ---- 1. Watch workouts become activities
        val sessions = readAll(client, ExerciseSessionRecord::class, TimeRangeFilter.after(since))
        val added = ArrayList<ActivityRecord>()
        var withoutMap = 0
        val sources = LinkedHashSet<String>()
        for (session in sessions) {
            val origin = session.metadata.dataOrigin.packageName
            if (origin == context.packageName) continue
            val sport = sportOf(session.exerciseType) ?: continue
            val start = session.startTime.toEpochMilli()
            val end = session.endTime.toEpochMilli()
            if (end - start < 3 * 60_000L) continue
            val id = "hc-${session.metadata.id}"
            if (existing.any { it.id == id } || added.any { it.id == id }) continue
            // Already in Meridian (recorded on the phone, imported from Strava, or shared by two apps).
            if (existing.any { overlaps(it, start, end) } || added.any { overlaps(it, start, end) }) continue

            val record = runCatching { buildRecord(client, session, sport, id) }
                .onFailure { Log.w(TAG, "Skipping workout $id", it) }
                .getOrNull() ?: continue
            if (record.points.isEmpty()) withoutMap++
            record.source?.let { sources.add(it) }
            added.add(record)
        }

        // ---- 2. Heart rate from the watch for phone recordings that have none
        val sinceMs = since.toEpochMilli()
        val enriched = existing
            .filter { it.source == null && !it.hasHeartRate && it.startTimeMs >= sinceMs && it.elapsedMs > 60_000L }
            .mapNotNull { r ->
                val hr = runCatching { readHeartRate(client, r.startTimeMs, r.startTimeMs + r.elapsedMs, emptySet()) }
                    .getOrDefault(emptyList())
                if (HeartRate.coverage(hr, r.elapsedMs) >= 0.3) r.id to hr else null
            }

        ActivityStore.saveAll(added)
        // Applied to the latest copy, so photos, gear or a rename made meanwhile are kept.
        enriched.forEach { (id, hr) -> ActivityStore.update(id) { it.copy(heartRate = hr) } }
        Prefs.setHealthLastSync(now.toEpochMilli())

        if (added.isEmpty() && enriched.isEmpty()) return UP_TO_DATE
        return buildString {
            if (added.isNotEmpty()) {
                append("Added ${added.size} ${if (added.size == 1) "workout" else "workouts"}")
                if (sources.isNotEmpty()) append(" from ${sources.joinToString(", ")}")
                if (withoutMap > 0) append(" ($withoutMap without a map)")
                append(".")
            }
            if (enriched.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("Watch heart rate added to ${enriched.size} ${if (enriched.size == 1) "recording" else "recordings"}.")
            }
        }
    }

    private suspend fun buildRecord(
        client: HealthConnectClient,
        session: ExerciseSessionRecord,
        sport: Sport,
        id: String,
    ): ActivityRecord {
        val start = session.startTime
        val end = session.endTime
        val startMs = start.toEpochMilli()
        val durationMs = end.toEpochMilli() - startMs
        val origin = session.metadata.dataOrigin
        val range = TimeRangeFilter.between(start, end)
        val title = session.title?.trim()?.takeIf { it.isNotEmpty() }

        val route = readRoute(client, session)
        val heartRate = readHeartRate(client, startMs, end.toEpochMilli(), setOf(origin))
            .ifEmpty { readHeartRate(client, startMs, end.toEpochMilli(), emptySet()) }
        val steps = runCatching {
            client.aggregate(AggregateRequest(metrics = setOf(StepsRecord.COUNT_TOTAL), timeRangeFilter = range, dataOriginFilter = setOf(origin)))[StepsRecord.COUNT_TOTAL]
        }.getOrNull()?.toInt()?.takeIf { it > 0 }
        val distance = runCatching {
            client.aggregate(AggregateRequest(metrics = setOf(DistanceRecord.DISTANCE_TOTAL), timeRangeFilter = range, dataOriginFilter = setOf(origin)))[DistanceRecord.DISTANCE_TOTAL]?.inMeters
        }.getOrNull()

        val fromRoute = if (route.size >= 2) RecordBuilder.build(ParsedTrack(route, title, sport), sport) else null
        val base = fromRoute ?: ActivityRecord(
            id = id,
            title = title ?: RecordBuilder.defaultTitle(sport, startMs),
            sport = sport,
            startTimeMs = startMs,
            distanceM = distance ?: 0.0,
            movingMs = durationMs,
            elapsedMs = durationMs,
            elevationGainM = 0.0,
            splitsMs = emptyList(),
            partialSplitM = 0.0,
            partialSplitMs = 0L,
            points = emptyList(),
        )
        return base.copy(
            id = id,
            title = title ?: base.title,
            startTimeMs = min(base.startTimeMs, startMs),
            elapsedMs = max(base.elapsedMs, durationMs),
            heartRate = heartRate,
            steps = steps,
            source = sourceName(origin.packageName),
        )
    }

    /** The GPS route, when the watch app shares it. Empty when there's none or no permission. */
    private suspend fun readRoute(client: HealthConnectClient, session: ExerciseSessionRecord): List<RawPoint> {
        val result = (session.exerciseRouteResult as? ExerciseRouteResult.Data)
            ?: runCatching { client.readRecord(ExerciseSessionRecord::class, session.metadata.id).record.exerciseRouteResult }
                .getOrNull() as? ExerciseRouteResult.Data
            ?: return emptyList()
        return result.exerciseRoute.route.map { loc ->
            RawPoint(loc.latitude, loc.longitude, loc.altitude?.inMeters, loc.time.toEpochMilli())
        }
    }

    private suspend fun readHeartRate(client: HealthConnectClient, startMs: Long, endMs: Long, origins: Set<DataOrigin>): List<Sample> {
        val records = readAll(
            client,
            HeartRateRecord::class,
            TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs)),
            origins,
        )
        val samples = records.flatMap { r -> r.samples }
            .map { Sample(it.time.toEpochMilli(), it.beatsPerMinute.toInt()) }
            .filter { it.time in startMs..endMs }
        return HeartRate.clean(samples)
    }

    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        type: KClass<T>,
        filter: TimeRangeFilter,
        origins: Set<DataOrigin> = emptySet(),
    ): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = filter,
                    dataOriginFilter = origins,
                    ascendingOrder = true,
                    pageSize = 1000,
                    pageToken = token,
                )
            )
            out.addAll(response.records)
            token = response.pageToken
        } while (token != null && out.size < 20_000)
        return out
    }

    private fun sportOf(type: Int): Sport? = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> Sport.RUN
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> Sport.WALK
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> Sport.HIKE
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> Sport.RIDE
        else -> null
    }

    /** True when an existing activity covers most of the same time. */
    private fun overlaps(r: ActivityRecord, start: Long, end: Long): Boolean {
        val rs = r.startTimeMs
        val re = r.startTimeMs + max(r.elapsedMs, r.movingMs)
        val overlap = min(re, end) - max(rs, start)
        if (overlap <= 0) return false
        return overlap >= 0.5 * min(re - rs, end - start).coerceAtLeast(1L)
    }

    /** Friendly names for the apps that usually write workouts. */
    fun sourceName(pkg: String): String = when {
        pkg.startsWith("com.fitbit") -> "Fitbit"
        pkg.startsWith("com.garmin") -> "Garmin"
        pkg.startsWith("com.sec.android.app.shealth") || pkg.startsWith("com.samsung") -> "Samsung Health"
        pkg.startsWith("com.google.android.apps.fitness") -> "Google Fit"
        pkg.startsWith("com.google.android.apps.healthdata") -> "Health Connect"
        pkg.startsWith("com.google.android.wearable") || pkg.startsWith("com.google.android.apps.wear") -> "Pixel Watch"
        pkg.startsWith("com.huawei") -> "Huawei Health"
        pkg.startsWith("com.huami") || pkg.startsWith("com.zepp") -> "Zepp"
        pkg.startsWith("com.xiaomi") || pkg.startsWith("com.mi.health") -> "Mi Fitness"
        pkg.startsWith("com.polar") -> "Polar"
        pkg.startsWith("com.coros") -> "Coros"
        pkg.startsWith("com.suunto") || pkg.startsWith("com.stt.android") -> "Suunto"
        pkg.startsWith("com.strava") -> "Strava"
        pkg.startsWith("com.withings") -> "Withings"
        pkg.startsWith("com.oura") -> "Oura"
        else -> "a health app"
    }
}
