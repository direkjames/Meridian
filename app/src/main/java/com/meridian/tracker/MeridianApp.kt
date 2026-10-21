package com.meridian.tracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.hardware.Sensor
import android.hardware.SensorManager
import com.meridian.tracker.data.ActivityStore
import com.meridian.tracker.data.DataTransfer
import com.meridian.tracker.data.ExtrasStore
import com.meridian.tracker.data.PlanReminders
import com.meridian.tracker.data.Prefs
import com.meridian.tracker.tracking.HeartRateMonitor
import com.meridian.tracker.tracking.TrackingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class MeridianApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        MapLibre.getInstance(this)
        ActivityStore.init(this)
        ActivityStore.onChanged = { DataTransfer.scheduleAutoBackup(this) }
        ExtrasStore.init(this)
        ExtrasStore.onChanged = {
            DataTransfer.scheduleAutoBackup(this)
            PlanReminders.schedule(this)
        }

        HeartRateMonitor.init(this)

        val sensors = getSystemService(SENSOR_SERVICE) as? SensorManager
        TrackingManager.setBarometerAvailable(sensors?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null)

        val channel = NotificationChannel(
            CHANNEL_TRACKING,
            getString(R.string.channel_tracking),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_tracking_desc)
            setShowBadge(false)
        }
        val planChannel = NotificationChannel(
            CHANNEL_PLAN,
            getString(R.string.channel_plan),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.channel_plan_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, planChannel))
        PlanReminders.schedule(this)

        // A fresh process never has a recording running, so any leftover snapshot means
        // Android killed Meridian mid-activity. Offer it back instead of losing it.
        appScope.launch {
            ActivityStore.loadInProgress()?.let { TrackingManager.restoreDraft(it) }
        }
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_PLAN = "plan"
    }
}
