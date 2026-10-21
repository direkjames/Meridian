package com.meridian.tracker.tracking

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.meridian.tracker.data.ActivityRecord
import com.meridian.tracker.data.ActivityMedia
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.HeartRate
import com.meridian.tracker.data.Indoor
import com.meridian.tracker.data.PlannedRoute
import com.meridian.tracker.data.Sample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Calendar
import java.util.UUID

/**
 * Single source of truth for the recording in progress.
 * The foreground service feeds it GPS and barometer readings; the UI observes [state].
 */
object TrackingManager {

    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    /** A finished activity waiting to be saved or discarded on the summary screen. */
    private val _draft = MutableStateFlow<ActivityRecord?>(null)
    val draft: StateFlow<ActivityRecord?> = _draft.asStateFlow()

    /** True when the draft was rescued from a recording the system interrupted. */
    private val _draftRecovered = MutableStateFlow(false)
    val draftRecovered: StateFlow<Boolean> = _draftRecovered.asStateFlow()

    // ---- Guides: ghost, planned route and today's target -------------------------------------

    private val _guide = MutableStateFlow(Guide())
    /** What this recording is following (set before starting, cleared when it finishes). */
    val guide: StateFlow<Guide> = _guide.asStateFlow()

    private val _live = MutableStateFlow(GuideLive())
    /** Live ghost gap and route progress while recording. */
    val live: StateFlow<GuideLive> = _live.asStateFlow()

    private var ghostTracker: GhostTracker? = null
    private var follower: RouteFollower? = null

    /** Race a previous activity: its route becomes the planned route too. */
    @Synchronized
    fun setGhost(record: ActivityRecord?) {
        if (_state.value.status != RecordStatus.IDLE) return
        ghostTracker = record?.let { GhostTracker(it.points) }
        val route = record?.let {
            val step = maxOf(1, it.points.size / 1500)
            PlannedRoute("ghost-${it.id}", it.title, it.points.filterIndexed { i, _ -> i % step == 0 || i == it.points.lastIndex }.map { p -> p.lat to p.lon }, it.startTimeMs)
        }
        follower = route?.let { RouteFollower(it.points) }
        _guide.update { it.copy(ghost = record, route = route ?: it.route) }
        _live.value = GuideLive()
        record?.let { setSport(it.sport) }
    }

    @Synchronized
    fun setRoute(route: PlannedRoute?) {
        if (_state.value.status != RecordStatus.IDLE) return
        follower = route?.let { RouteFollower(it.points) }
        _guide.update { it.copy(route = route, ghost = if (route == null) null else it.ghost) }
        if (route == null) ghostTracker = null
        _live.value = GuideLive()
    }

    @Synchronized
    fun setTarget(target: SessionTarget?) {
        if (_state.value.status != RecordStatus.IDLE) return
        _guide.update { it.copy(target = target) }
    }

    @Synchronized
    fun clearGuide() {
        ghostTracker = null
        follower = null
        _guide.value = Guide()
        _live.value = GuideLive()
    }

    private fun updateLive(lat: Double, lon: Double) {
        val ghost = ghostTracker
        val progress = follower?.update(lat, lon)
        val wasOff = _live.value.offRoute
        val off = progress != null && if (wasOff) progress.offRouteM > 35 else progress.offRouteM > 60
        _live.value = GuideLive(
            aheadSec = ghost?.secondsAhead(processor.distanceM, processor.movingMs),
            ghostPos = ghost?.positionAt(processor.movingMs),
            route = progress,
            offRoute = off,
        )
    }

    private var processor = TrackProcessor(Sport.RUN)
    private var baroElevation: ElevationTracker? = null
    private var gpsElevation = ElevationTracker.gps()
    private var hasBarometer = false
    private var startedAtRealtime = 0L
    private var recordId = UUID.randomUUID().toString()

    // Heart rate and steps for the recording in progress.
    private val hrSamples = ArrayList<Sample>()
    private val cadenceSamples = ArrayList<Sample>()
    private var stepCounter: StepCounter? = null
    private var lastHrRealtime = 0L
    private var lastCadenceSampleRealtime = 0L

    /** Step length used to turn steps into distance on indoor sports (no GPS). */
    private var indoorStrideM = Indoor.DEFAULT_STRIDE_M

    // ---- Commands from the UI -------------------------------------------------------------

    @Synchronized
    fun setSport(sport: Sport) {
        if (_state.value.status == RecordStatus.IDLE) _state.update { it.copy(sport = sport) }
    }

    fun startRecording(context: Context) {
        start()
        val intent = Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    @Synchronized
    private fun start() {
        val current = _state.value
        if (current.status != RecordStatus.IDLE) return
        processor = TrackProcessor(current.sport)
        baroElevation = if (hasBarometer) ElevationTracker.barometer() else null
        gpsElevation = ElevationTracker.gps()
        startedAtRealtime = SystemClock.elapsedRealtime()
        recordId = UUID.randomUUID().toString()
        hrSamples.clear()
        cadenceSamples.clear()
        stepCounter = null
        lastCadenceSampleRealtime = startedAtRealtime
        indoorStrideM = if (current.sport.usesGps) Indoor.DEFAULT_STRIDE_M else Indoor.stride(ActivityStore.activities.value)
        _state.value = TrackingState(
            status = RecordStatus.RECORDING,
            sport = current.sport,
            runningSince = startedAtRealtime,
            startedAtWallMs = System.currentTimeMillis(),
            lastFix = current.lastFix,
            hasBarometer = hasBarometer,
            heartRate = current.heartRate,
        )
    }

    @Synchronized
    fun pause() {
        val s = _state.value
        if (s.status != RecordStatus.RECORDING) return
        val now = SystemClock.elapsedRealtime()
        processor.startNewSegment()
        _state.value = s.copy(
            status = RecordStatus.PAUSED,
            elapsedBankedMs = s.elapsedMs(now),
            runningSince = null,
            currentSpeed = 0.0,
            cadence = null,
        )
    }

    @Synchronized
    fun resume() {
        val s = _state.value
        if (s.status != RecordStatus.PAUSED) return
        stepCounter?.resetCadence()
        _state.value = s.copy(status = RecordStatus.RECORDING, runningSince = SystemClock.elapsedRealtime(), cadence = null)
    }

    /** Stops recording and turns it into a draft for the summary screen. The service stops itself. */
    @Synchronized
    fun finish() {
        val s = _state.value
        if (s.status == RecordStatus.IDLE) return
        val record = buildRecord(s, SystemClock.elapsedRealtime())
        ActivityStore.writeInProgress(record) // survives a crash until it's saved or discarded
        _draftRecovered.value = false
        _draft.value = record
        _state.value = TrackingState(sport = s.sport, lastFix = s.lastFix, hasBarometer = hasBarometer, heartRate = s.heartRate)
        stepCounter = null
        clearGuide()
    }

    /** A copy of the activity so far, for crash-safe snapshots. Null when there's nothing to keep. */
    @Synchronized
    fun snapshot(): ActivityRecord? {
        val s = _state.value
        if (s.status == RecordStatus.IDLE || (s.sport.usesGps && processor.points.isEmpty())) return null
        return buildRecord(s, SystemClock.elapsedRealtime())
    }

    /** Offers an activity recovered after the app was killed mid-recording. */
    @Synchronized
    fun restoreDraft(record: ActivityRecord) {
        if (_state.value.status != RecordStatus.IDLE || _draft.value != null) return
        _draftRecovered.value = true
        _draft.value = record
    }

    fun clearDraft() {
        _draft.value = null
        _draftRecovered.value = false
        ActivityStore.clearInProgress()
    }

    /** Changes the unsaved activity (photos, gear) and keeps the crash-safe copy in step. */
    @Synchronized
    fun updateDraft(transform: (ActivityRecord) -> ActivityRecord): ActivityRecord? {
        val current = _draft.value ?: return null
        val next = transform(current)
        if (next == current) return current
        _draft.value = next
        ActivityStore.writeInProgress(next)
        return next
    }

    /** Throws the unsaved activity away, with any photos or videos already added to it. */
    @Synchronized
    fun discardDraft() {
        val id = _draft.value?.id
        clearDraft()
        if (id != null && ActivityStore.find(id) == null) ActivityMedia.deleteAll(id)
    }

    private fun buildRecord(s: TrackingState, now: Long): ActivityRecord {
        val indoor = !s.sport.usesGps
        val elapsed = s.elapsedMs(now)
        val distance = if (indoor) s.distanceM else processor.distanceM
        val moving = if (indoor) elapsed else processor.movingMs
        val (partialM, partialMs) = if (indoor) Indoor.splits(distance, moving).let { it.second to it.third } else processor.partialSplit()
        // Convert monotonic point times to wall-clock times for storage and export.
        val wallPoints = processor.points.map { p ->
            p.copy(time = s.startedAtWallMs + (p.time - startedAtRealtime))
        }
        return ActivityRecord(
            id = recordId,
            title = defaultTitle(s.sport, s.startedAtWallMs),
            sport = s.sport,
            startTimeMs = s.startedAtWallMs,
            distanceM = distance,
            movingMs = moving,
            elapsedMs = elapsed,
            elevationGainM = s.elevationGainM,
            splitsMs = if (indoor) Indoor.splits(distance, moving).first else processor.splitsMs.toList(),
            partialSplitM = partialM,
            partialSplitMs = partialMs,
            points = wallPoints,
            heartRate = HeartRate.clean(hrSamples),
            cadence = cadenceSamples.toList(),
            steps = stepCounter?.steps,
        )
    }

    // ---- Sensor input ---------------------------------------------------------------------

    fun setBarometerAvailable(available: Boolean) {
        hasBarometer = available
    }

    /**
     * @param monotonicMs a monotonic timestamp of the fix (Location.elapsedRealtimeNanos / 1e6).
     */
    @Synchronized
    fun onLocation(lat: Double, lon: Double, accuracy: Float, altitude: Double?, monotonicMs: Long) {
        val fix = Fix(lat, lon, accuracy, SystemClock.elapsedRealtime())
        val s = _state.value
        if (s.status != RecordStatus.RECORDING || !s.sport.usesGps) {
            if (altitude != null) gpsElevation.onAltitude(altitude, counting = false)
            _state.value = s.copy(lastFix = fix)
            return
        }

        if (baroElevation == null && altitude != null && accuracy <= TrackProcessor.GOOD_ACCURACY_M) {
            gpsElevation.onAltitude(altitude, counting = true)
        }
        val alt = baroElevation?.current ?: gpsElevation.current
        val accepted = processor.onFix(lat, lon, alt, accuracy, monotonicMs)
        if (accuracy <= TrackProcessor.MAX_ACCURACY_M) updateLive(lat, lon)

        _state.value = s.copy(
            lastFix = fix,
            points = if (accepted) processor.points.toList() else s.points,
            distanceM = processor.distanceM,
            movingMs = processor.movingMs,
            splitsMs = if (accepted) processor.splitsMs.toList() else s.splitsMs,
            currentSpeed = processor.currentSpeed,
            elevationGainM = baroElevation?.gainM ?: gpsElevation.gainM,
        )
    }

    @Synchronized
    fun onBarometerAltitude(altitude: Double) {
        val baro = baroElevation ?: return
        val s = _state.value
        val before = baro.gainM
        baro.onAltitude(altitude, counting = s.status == RecordStatus.RECORDING)
        if (baro.gainM != before) _state.value = s.copy(elevationGainM = baro.gainM)
    }

    // ---- Heart rate and steps ------------------------------------------------------------------

    /** A reading from the Bluetooth heart-rate sensor. Kept only while recording (not paused). */
    @Synchronized
    fun onHeartRate(bpm: Int) {
        if (bpm !in HeartRate.VALID) return
        val now = SystemClock.elapsedRealtime()
        lastHrRealtime = now
        val s = _state.value
        if (s.status == RecordStatus.RECORDING) {
            val wall = System.currentTimeMillis()
            val last = hrSamples.lastOrNull()
            if (last == null || wall - last.time >= 2_000L) hrSamples.add(Sample(wall, bpm))
        }
        if (s.heartRate != bpm) _state.value = s.copy(heartRate = bpm)
    }

    /** The sensor disconnected or stopped sending. */
    @Synchronized
    fun onHeartRateLost() {
        val s = _state.value
        if (s.heartRate != null) _state.value = s.copy(heartRate = null)
    }

    /** Called by the service once the phone's step sensor is listening for this recording. */
    @Synchronized
    fun enableSteps() {
        val s = _state.value
        if (s.status == RecordStatus.IDLE || stepCounter != null) return
        stepCounter = StepCounter()
        _state.value = s.copy(steps = 0)
    }

    @Synchronized
    fun onStepCounter(total: Float, monoMs: Long) {
        val counter = stepCounter ?: return
        val s = _state.value
        counter.onCounter(total, monoMs, counting = s.status == RecordStatus.RECORDING)
        if (s.steps != counter.steps) _state.value = withSteps(s, counter.steps)
    }

    @Synchronized
    fun onStepDetected(monoMs: Long) {
        val counter = stepCounter ?: return
        val s = _state.value
        counter.onStep(monoMs, counting = s.status == RecordStatus.RECORDING)
        if (s.steps != counter.steps) _state.value = withSteps(s, counter.steps)
    }

    /** New step count; on indoor sports the distance follows from it. */
    private fun withSteps(s: TrackingState, steps: Int): TrackingState =
        if (s.sport.usesGps) s.copy(steps = steps)
        else s.copy(steps = steps, distanceM = Indoor.distanceFromSteps(steps, indoorStrideM))

    /** Called every few seconds by the service: live cadence, cadence history, stale heart rate. */
    @Synchronized
    fun tick() {
        val s = _state.value
        if (s.status == RecordStatus.IDLE) return
        val now = SystemClock.elapsedRealtime()
        val hr = if (s.heartRate != null && now - lastHrRealtime > HR_STALE_MS) null else s.heartRate
        val cadence = if (s.status == RecordStatus.RECORDING) stepCounter?.cadence(now) else null
        if (cadence != null && s.status == RecordStatus.RECORDING && now - lastCadenceSampleRealtime >= CADENCE_EVERY_MS) {
            cadenceSamples.add(Sample(System.currentTimeMillis(), cadence))
            lastCadenceSampleRealtime = now
        }
        if (!s.sport.usesGps) {
            // No GPS: time on the machine counts as moving time, and pace follows from cadence × stride.
            val moving = if (s.status == RecordStatus.RECORDING) s.elapsedMs(now) else s.movingMs
            val speed = cadence?.let { it * indoorStrideM / 60.0 } ?: 0.0
            _state.value = s.copy(heartRate = hr, cadence = cadence, movingMs = moving, currentSpeed = speed)
            return
        }
        if (hr != s.heartRate || cadence != s.cadence) _state.value = s.copy(heartRate = hr, cadence = cadence)
    }

    private const val HR_STALE_MS = 10_000L
    private const val CADENCE_EVERY_MS = 10_000L

    private fun defaultTitle(sport: Sport, wallMs: Long): String {
        val hour = Calendar.getInstance().apply { timeInMillis = wallMs }.get(Calendar.HOUR_OF_DAY)
        val part = when (hour) {
            in 5..10 -> "Morning"
            in 11..13 -> "Lunch"
            in 14..17 -> "Afternoon"
            in 18..21 -> "Evening"
            else -> "Night"
        }
        return "$part ${sport.label}"
    }
}

/** Today's plan session, shown as a progress bar while recording. */
data class SessionTarget(val title: String, val distanceM: Double?, val minutes: Int?)

data class Guide(
    val ghost: ActivityRecord? = null,
    val route: PlannedRoute? = null,
    val target: SessionTarget? = null,
)

data class GuideLive(
    /** Seconds ahead of the ghost (negative = behind). */
    val aheadSec: Long? = null,
    val ghostPos: Pair<Double, Double>? = null,
    val route: RouteFollower.Progress? = null,
    val offRoute: Boolean = false,
)
