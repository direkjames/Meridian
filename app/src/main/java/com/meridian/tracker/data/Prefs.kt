package com.meridian.tracker.data

import android.content.Context
import android.content.SharedPreferences
import com.meridian.tracker.ui.Format
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
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

    private val _body = MutableStateFlow(Body())
    /** Weight, birth year and sex, used only for calorie estimates. Stays on the phone. */
    val body: StateFlow<Body> = _body.asStateFlow()

    private val _zones = MutableStateFlow<List<PrivacyZone>>(emptyList())
    /** Places where the start and end of a route are hidden in shares and GPX exports. */
    val zones: StateFlow<List<PrivacyZone>> = _zones.asStateFlow()

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
        _zones.value = decodeZones(sp.getString("privacyZones", null))
        val kg = sp.getFloat("bodyWeightKg", 0f).toDouble()
        _body.value = Body(
            weightKg = if (kg in 30.0..300.0) kg else Calories.DEFAULT_WEIGHT_KG,
            weightKnown = kg in 30.0..300.0,
            birthYear = sp.getInt("bodyBirthYear", 0).takeIf { it in 1900..2100 },
            sex = sp.getString("bodySex", null)?.let { s -> runCatching { BodySex.valueOf(s) }.getOrNull() },
        )
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

    /** Pass null to clear a value. */
    fun setBodyWeight(kg: Double?) {
        val ok = kg?.takeIf { it in 30.0..300.0 }
        _body.value = _body.value.copy(weightKg = ok ?: Calories.DEFAULT_WEIGHT_KG, weightKnown = ok != null)
        sp.edit().putFloat("bodyWeightKg", (ok ?: 0.0).toFloat()).apply()
    }

    fun setBirthYear(year: Int?) {
        val ok = year?.takeIf { it in 1900..2100 }
        _body.value = _body.value.copy(birthYear = ok)
        sp.edit().putInt("bodyBirthYear", ok ?: 0).apply()
    }

    fun setSex(sex: BodySex?) {
        _body.value = _body.value.copy(sex = sex)
        sp.edit().putString("bodySex", sex?.name).apply()
    }

    /** Adds a zone (up to [PrivacyZones.MAX_ZONES]). Returns false when it was refused. */
    @Synchronized
    fun addZone(zone: PrivacyZone): Boolean {
        val ok = PrivacyZones.clean(zone) ?: return false
        if (_zones.value.size >= PrivacyZones.MAX_ZONES) return false
        saveZones(_zones.value.filterNot { it.id == ok.id } + ok)
        return true
    }

    @Synchronized
    fun removeZone(id: String) = saveZones(_zones.value.filterNot { it.id == id })

    private fun saveZones(list: List<PrivacyZone>) {
        _zones.value = list
        sp.edit().putString("privacyZones", encodeZones(list)).apply()
    }

    private fun encodeZones(list: List<PrivacyZone>): String = JSONArray().also { arr ->
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("r", it.radiusM))
        }
    }.toString()

    /** A damaged or missing value gives no zones rather than a crash. */
    private fun decodeZones(text: String?): List<PrivacyZone> = runCatching {
        val arr = JSONArray(text ?: return emptyList())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            PrivacyZones.clean(PrivacyZone(o.getString("id"), o.optString("name"), o.getDouble("lat"), o.getDouble("lon"), o.getDouble("r")))
        }.take(PrivacyZones.MAX_ZONES)
    }.getOrDefault(emptyList())

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
