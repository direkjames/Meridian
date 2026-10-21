package com.meridian.tracker.data

import android.content.Context
import android.content.SharedPreferences
import com.meridian.tracker.ui.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App settings, stored on the phone and included in Android's Google backup. */
object Prefs {

    private const val FILE = "meridian"
    private lateinit var sp: SharedPreferences

    private val _imperial = MutableStateFlow(false)
    val imperial: StateFlow<Boolean> = _imperial.asStateFlow()

    private val _highAccuracy = MutableStateFlow(true)
    /** true: GPS every second. false: every 3 seconds, easier on the battery. */
    val highAccuracy: StateFlow<Boolean> = _highAccuracy.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(false)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _countdown = MutableStateFlow(true)
    val countdown: StateFlow<Boolean> = _countdown.asStateFlow()

    private val _autoBackupUri = MutableStateFlow<String?>(null)
    /** A backup file the user picked (can be in Google Drive). Rewritten after every change. */
    val autoBackupUri: StateFlow<String?> = _autoBackupUri.asStateFlow()

    private val _backupVideos = MutableStateFlow(false)
    /** Also copy videos into the automatic backup (they can be large). Manual backups always include them. */
    val backupVideos: StateFlow<Boolean> = _backupVideos.asStateFlow()

    private val _lastBackupMs = MutableStateFlow(0L)
    val lastBackupMs: StateFlow<Long> = _lastBackupMs.asStateFlow()

    // ---- Watches & sensors --------------------------------------------------------------------

    private val _countSteps = MutableStateFlow(true)
    /** Count steps with the phone's step sensor during runs and walks. */
    val countSteps: StateFlow<Boolean> = _countSteps.asStateFlow()

    private val _askedSteps = MutableStateFlow(false)
    /** Whether Meridian already asked for "Physical activity" once from the Record screen. */
    val askedSteps: StateFlow<Boolean> = _askedSteps.asStateFlow()

    private val _hrDevice = MutableStateFlow<HrDevice?>(null)
    /** The Bluetooth heart-rate sensor or watch to connect to. */
    val hrDevice: StateFlow<HrDevice?> = _hrDevice.asStateFlow()

    private val _maxHr = MutableStateFlow(HeartRate.DEFAULT_MAX)
    val maxHr: StateFlow<Int> = _maxHr.asStateFlow()

    private val _healthAutoSync = MutableStateFlow(true)
    /** Sync from Health Connect whenever Meridian opens. */
    val healthAutoSync: StateFlow<Boolean> = _healthAutoSync.asStateFlow()

    private val _healthLastSyncMs = MutableStateFlow(0L)
    val healthLastSyncMs: StateFlow<Long> = _healthLastSyncMs.asStateFlow()

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _imperial.value = sp.getBoolean("imperial", false)
        _highAccuracy.value = sp.getBoolean("highAccuracy", true)
        _keepScreenOn.value = sp.getBoolean("keepScreenOn", false)
        _countdown.value = sp.getBoolean("countdown", true)
        _autoBackupUri.value = sp.getString("autoBackupUri", null)
        _backupVideos.value = sp.getBoolean("backupVideos", false)
        _lastBackupMs.value = sp.getLong("lastBackupMs", 0L)
        _countSteps.value = sp.getBoolean("countSteps", true)
        _askedSteps.value = sp.getBoolean("askedSteps", false)
        val address = sp.getString("hrAddress", null)
        _hrDevice.value = address?.let { HrDevice(it, sp.getString("hrName", null) ?: "Heart-rate sensor") }
        _maxHr.value = sp.getInt("maxHr", HeartRate.DEFAULT_MAX)
        _healthAutoSync.value = sp.getBoolean("healthAutoSync", true)
        _healthLastSyncMs.value = sp.getLong("healthLastSyncMs", 0L)
        Format.imperial = _imperial.value
        HeartRate.maxHr = _maxHr.value
    }

    fun setCountSteps(v: Boolean) {
        _countSteps.value = v
        sp.edit().putBoolean("countSteps", v).apply()
    }

    fun setAskedSteps() {
        _askedSteps.value = true
        sp.edit().putBoolean("askedSteps", true).apply()
    }

    fun setHrDevice(device: HrDevice?) {
        _hrDevice.value = device
        sp.edit().putString("hrAddress", device?.address).putString("hrName", device?.name).apply()
    }

    fun setMaxHr(v: Int) {
        val clamped = v.coerceIn(140, 230)
        _maxHr.value = clamped
        HeartRate.maxHr = clamped
        sp.edit().putInt("maxHr", clamped).apply()
    }

    fun setHealthAutoSync(v: Boolean) {
        _healthAutoSync.value = v
        sp.edit().putBoolean("healthAutoSync", v).apply()
    }

    fun setHealthLastSync(ms: Long) {
        _healthLastSyncMs.value = ms
        sp.edit().putLong("healthLastSyncMs", ms).apply()
    }

    fun setImperial(v: Boolean) {
        _imperial.value = v
        Format.imperial = v
        sp.edit().putBoolean("imperial", v).apply()
    }

    fun setHighAccuracy(v: Boolean) {
        _highAccuracy.value = v
        sp.edit().putBoolean("highAccuracy", v).apply()
    }

    fun setKeepScreenOn(v: Boolean) {
        _keepScreenOn.value = v
        sp.edit().putBoolean("keepScreenOn", v).apply()
    }

    fun setCountdown(v: Boolean) {
        _countdown.value = v
        sp.edit().putBoolean("countdown", v).apply()
    }

    fun setAutoBackupUri(uri: String?) {
        _autoBackupUri.value = uri
        sp.edit().putString("autoBackupUri", uri).apply()
    }

    fun setBackupVideos(v: Boolean) {
        _backupVideos.value = v
        sp.edit().putBoolean("backupVideos", v).apply()
    }

    fun setLastBackup(ms: Long) {
        _lastBackupMs.value = ms
        sp.edit().putLong("lastBackupMs", ms).apply()
    }
}

/** A remembered Bluetooth heart-rate sensor. */
data class HrDevice(val address: String, val name: String)
