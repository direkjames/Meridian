package com.meridian.tracker.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Step counting needs "Physical activity" permission on Android 10 and newer. */
object StepPermission {

    /** The permission to request, or null on Android 9 and older where none is needed. */
    val permission: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACTIVITY_RECOGNITION else null

    fun granted(context: Context): Boolean {
        val p = permission ?: return true
        return ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }

    /** True when this phone has a step counter or step detector. */
    fun hasSensor(context: Context): Boolean {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null ||
            sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
    }
}
