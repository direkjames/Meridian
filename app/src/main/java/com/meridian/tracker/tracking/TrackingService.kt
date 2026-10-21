package com.meridian.tracker.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.meridian.tracker.MainActivity
import com.meridian.tracker.MeridianApp
import com.meridian.tracker.R
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.Prefs
import com.meridian.tracker.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps GPS running while the screen is off or the app is in the
 * background. Android requires the persistent notification; it doubles as a live stats readout.
 */
class TrackingService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private var sensorManager: SensorManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    private var tracking = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                TrackingManager.onLocation(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    accuracy = if (loc.hasAccuracy()) loc.accuracy else 999f,
                    altitude = if (loc.hasAltitude()) loc.altitude else null,
                    monotonicMs = loc.elapsedRealtimeNanos / 1_000_000L,
                )
            }
        }
    }

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0])
            TrackingManager.onBarometerAltitude(altitude.toDouble())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Delivery time, not event.timestamp: its clock base differs between phones.
            val mono = SystemClock.elapsedRealtime()
            when (event.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> TrackingManager.onStepCounter(event.values[0], mono)
                Sensor.TYPE_STEP_DETECTOR -> TrackingManager.onStepDetected(mono)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Listens to the phone's step sensor for runs and walks, when allowed. */
    private fun startSteps() {
        val sport = TrackingManager.state.value.sport
        if (sport == Sport.RIDE || !Prefs.countSteps.value || !StepPermission.granted(this)) return
        val sm = sensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            ?: sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            ?: return
        // No batching delay, so cadence stays live.
        if (sm.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0)) {
            TrackingManager.enableSteps()
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
            else -> if (!tracking) stopTracking()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        // Must call startForeground promptly after startForegroundService.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        if (!hasLocationPermission()) {
            TrackingManager.finish()
            stopTracking()
            return
        }
        if (tracking) return
        tracking = true

        // Settings › GPS: every second, or every 3 seconds to save battery on long activities.
        val interval = if (Prefs.highAccuracy.value) 1_000L else 3_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval)
            .setWaitForAccurateLocation(false)
            .build()
        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

        sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sensor ->
            sensorManager?.registerListener(pressureListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        startSteps()
        HeartRateMonitor.acquire(this, HR_TAG)

        tickerJob = scope.launch {
            var tick = 0
            while (isActive) {
                if (TrackingManager.state.value.status == RecordStatus.IDLE) {
                    stopTracking()
                    break
                }
                TrackingManager.tick()
                updateNotification()
                // Crash-safe snapshot every ~15 s.
                if (tick % SNAPSHOT_EVERY_TICKS == 0) {
                    TrackingManager.snapshot()?.let { ActivityStore.writeInProgress(it) }
                }
                tick++
                delay(NOTIFICATION_REFRESH_MS)
            }
        }
    }

    private fun stopTracking() {
        if (tracking) {
            fused.removeLocationUpdates(locationCallback)
            sensorManager?.unregisterListener(pressureListener)
            sensorManager?.unregisterListener(stepListener)
            HeartRateMonitor.release(HR_TAG)
            tracking = false
        }
        tickerJob?.cancel()
        tickerJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (tracking) {
            fused.removeLocationUpdates(locationCallback)
            sensorManager?.unregisterListener(pressureListener)
            sensorManager?.unregisterListener(stepListener)
            HeartRateMonitor.release(HR_TAG)
            tracking = false
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateNotification() {
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val s = TrackingManager.state.value
        val now = SystemClock.elapsedRealtime()
        val paused = s.status == RecordStatus.PAUSED
        val title = if (paused) "${s.sport.label} paused" else "Recording ${s.sport.label.lowercase()}"
        val pace = if (s.sport.showsSpeed) {
            "${Format.speedKmh(s.currentSpeed)} ${Format.speedUnit}"
        } else {
            "${Format.paceFromSpeed(s.currentSpeed)} ${Format.paceUnit}"
        }
        val live = TrackingManager.live.value
        val body = buildString {
            s.heartRate?.let { append("  ·  ♥ $it") }
            s.steps?.takeIf { it > 0 }?.let { append("  ·  ${Format.count(it)} steps") }
        }
        val extra = when {
            live.offRoute -> "  ·  OFF ROUTE"
            live.aheadSec != null -> if (live.aheadSec >= 0) "  ·  ${live.aheadSec}s ahead" else "  ·  ${-live.aheadSec}s behind"
            else -> ""
        }
        val text = "${Format.km(s.distanceM)} ${Format.distUnit}  ·  ${Format.duration(s.elapsedMs(now))}  ·  $pace$body$extra"

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, MeridianApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_meridian)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.meridian.tracker.action.START"
        const val ACTION_STOP = "com.meridian.tracker.action.STOP"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_REFRESH_MS = 3_000L
        private const val SNAPSHOT_EVERY_TICKS = 5
        private const val HR_TAG = "recording"
    }
}
