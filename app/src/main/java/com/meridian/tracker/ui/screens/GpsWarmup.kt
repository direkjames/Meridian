package com.meridian.tracker.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.meridian.tracker.tracking.TrackingManager

/**
 * Before recording starts, keep GPS warm while the Record screen is visible so the
 * status pill can show "GPS ready" and the map can centre on you.
 */
@SuppressLint("MissingPermission")
@Composable
fun GpsWarmup(enabled: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(enabled, lifecycleOwner) {
        if (!enabled) return@DisposableEffect onDispose { }

        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_500L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                TrackingManager.onLocation(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    accuracy = if (loc.hasAccuracy()) loc.accuracy else 999f,
                    altitude = if (loc.hasAltitude()) loc.altitude else null,
                    monotonicMs = loc.elapsedRealtimeNanos / 1_000_000L,
                )
            }
        }
        var active = false

        fun startUpdates() {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!active && granted) {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                active = true
            }
        }

        fun stopUpdates() {
            if (active) {
                client.removeLocationUpdates(callback)
                active = false
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startUpdates()
                Lifecycle.Event.ON_PAUSE -> stopUpdates()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopUpdates()
        }
    }
}
