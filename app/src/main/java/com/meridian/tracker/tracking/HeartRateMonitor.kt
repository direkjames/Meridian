package com.meridian.tracker.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.meridian.tracker.data.HeartRate
import com.meridian.tracker.data.HrDevice
import com.meridian.tracker.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Connects to a Bluetooth heart-rate sensor using the standard Heart Rate profile.
 * Works with chest straps (Polar, Garmin HRM, Wahoo, Coospo…) and with watches that
 * broadcast heart rate (Garmin "Broadcast Heart Rate", Coros, Polar, Suunto, Amazfit and others).
 *
 * Screens and the recording service "hold" the connection while they need it; when nobody
 * holds it, Meridian disconnects so the sensor's battery isn't drained.
 */
@SuppressLint("MissingPermission") // every Bluetooth call checks permission first or catches SecurityException
object HeartRateMonitor {

    private const val TAG = "HeartRateMonitor"
    private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val SCAN_MS = 20_000L
    private const val RECONNECT_MS = 4_000L
    private const val NO_SIGNAL_MS = 10_000L

    enum class Status { NONE, IDLE, NO_PERMISSION, BLUETOOTH_OFF, CONNECTING, CONNECTED, NO_SIGNAL }

    data class HrState(
        val status: Status = Status.NONE,
        val device: HrDevice? = null,
        val bpm: Int? = null,
        /** False when the sensor says it isn't touching skin. */
        val contact: Boolean = true,
    )

    data class Found(val address: String, val name: String, val rssi: Int)

    private val _state = MutableStateFlow(HrState())
    val state: StateFlow<HrState> = _state.asStateFlow()

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val holders = HashSet<String>()
    private var gatt: BluetoothGatt? = null
    private var lastReadingAt = 0L
    private var scanCallback: ScanCallback? = null

    fun init(context: Context) {
        if (app == null) app = context.applicationContext
        publishIdle()
    }

    // ---- Permissions and adapter ---------------------------------------------------------------

    /** Android 12+: "Nearby devices". Older: location (already granted for GPS). */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun bluetoothOn(context: Context): Boolean =
        try {
            adapter(context)?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }

    // ---- Holding the connection ----------------------------------------------------------------

    /** Start (or keep) the connection while [tag] needs live heart rate. */
    @Synchronized
    fun acquire(context: Context, tag: String) {
        init(context)
        holders.add(tag)
        if (gatt == null) connect()
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, NO_SIGNAL_MS)
    }

    @Synchronized
    fun release(tag: String) {
        holders.remove(tag)
        if (holders.isEmpty()) {
            main.removeCallbacks(watchdog)
            main.removeCallbacks(reconnect)
            closeGatt()
            publishIdle()
            TrackingManager.onHeartRateLost()
        }
    }

    /** Try again after permission was granted or Bluetooth was switched on. */
    @Synchronized
    fun retry() {
        if (holders.isNotEmpty() && gatt == null) connect() else if (gatt == null) publishIdle()
    }

    private fun publishIdle() {
        val device = Prefs.hrDevice.value
        _state.value = HrState(if (device == null) Status.NONE else Status.IDLE, device)
    }

    private fun connect() {
        val context = app ?: return
        val device = Prefs.hrDevice.value
        if (device == null) {
            _state.value = HrState()
            return
        }
        if (!hasPermissions(context)) {
            _state.value = HrState(Status.NO_PERMISSION, device)
            return
        }
        val adapter = adapter(context)
        if (adapter == null || !bluetoothOn(context)) {
            _state.value = HrState(Status.BLUETOOTH_OFF, device)
            scheduleReconnect()
            return
        }
        main.removeCallbacks(reconnect)
        try {
            closeGatt()
            val remote = adapter.getRemoteDevice(device.address)
            _state.value = HrState(Status.CONNECTING, device)
            gatt = remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't connect to ${device.name}", e)
            gatt = null
            _state.value = HrState(Status.CONNECTING, device)
            scheduleReconnect()
        }
    }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        try {
            g.disconnect()
            g.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't close the connection", e)
        }
    }

    private fun scheduleReconnect() {
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, RECONNECT_MS)
    }

    private val reconnect = Runnable {
        synchronized(HeartRateMonitor) {
            if (holders.isNotEmpty() && gatt == null) connect()
        }
    }

    /** Notices when a connected sensor goes quiet (strap off, watch stopped broadcasting). */
    private val watchdog: Runnable = object : Runnable {
        override fun run() {
            synchronized(HeartRateMonitor) {
                if (holders.isEmpty()) return
                val s = _state.value
                if (s.status == Status.CONNECTED && SystemClock.elapsedRealtime() - lastReadingAt > NO_SIGNAL_MS) {
                    _state.value = s.copy(status = Status.NO_SIGNAL, bpm = null)
                    TrackingManager.onHeartRateLost()
                }
                main.postDelayed(this, 5_000L)
            }
        }
    }

    private fun onReading(value: ByteArray?) {
        val bpm = HeartRate.parseMeasurement(value) ?: return
        val contact = HeartRate.skinContact(value)
        lastReadingAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(status = Status.CONNECTED, bpm = bpm, contact = contact) }
        TrackingManager.onHeartRate(bpm)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            synchronized(HeartRateMonitor) {
                if (g != gatt) {
                    runCatching { g.close() }
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    main.post {
                        try {
                            g.discoverServices()
                        } catch (e: SecurityException) {
                            Log.w(TAG, "No permission to read the sensor", e)
                        }
                    }
                } else {
                    runCatching { g.close() }
                    gatt = null
                    TrackingManager.onHeartRateLost()
                    if (holders.isNotEmpty()) {
                        _state.update { it.copy(status = Status.CONNECTING, bpm = null) }
                        scheduleReconnect()
                    } else {
                        publishIdle()
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (ch == null) {
                _state.update { it.copy(status = Status.NO_SIGNAL) }
                return
            }
            try {
                g.setCharacteristicNotification(ch, true)
                val descriptor = ch.getDescriptor(CCCD)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(descriptor)
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission to subscribe to heart rate", e)
            }
            lastReadingAt = SystemClock.elapsedRealtime()
            _state.update { it.copy(status = Status.CONNECTED) }
        }

        // Android 13 and newer
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == HR_MEASUREMENT) onReading(value)
        }

        // Android 12 and older
        @Deprecated("Replaced on Android 13 by the overload that passes the value")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ch.uuid == HR_MEASUREMENT) onReading(ch.value)
        }
    }

    // ---- Finding a sensor ----------------------------------------------------------------------

    /** Looks for nearby heart-rate sensors for 20 seconds. Returns false if it couldn't start. */
    @Synchronized
    fun startScan(context: Context): Boolean {
        init(context)
        if (!hasPermissions(context) || !bluetoothOn(context)) return false
        val scanner = adapter(context)?.bluetoothLeScanner ?: return false
        stopScan()
        _found.value = emptyList()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = add(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { add(it) }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                _scanning.value = false
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, cb)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start scanning", e)
            return false
        }
        scanCallback = cb
        _scanning.value = true
        main.postDelayed(stopScanLater, SCAN_MS)
        return true
    }

    private val stopScanLater = Runnable { stopScan() }

    @Synchronized
    fun stopScan() {
        main.removeCallbacks(stopScanLater)
        val cb = scanCallback ?: return
        scanCallback = null
        _scanning.value = false
        val context = app ?: return
        try {
            adapter(context)?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't stop scanning", e)
        }
    }

    private fun add(result: ScanResult) {
        val address = result.device?.address ?: return
        val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
            ?: runCatching { result.device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Heart-rate sensor"
        _found.update { list ->
            val i = list.indexOfFirst { it.address == address }
            val item = Found(address, name, result.rssi)
            if (i >= 0) list.toMutableList().also { it[i] = item } else list + item
        }
    }

    /** Remember this sensor and connect to it. */
    @Synchronized
    fun choose(found: Found) {
        stopScan()
        Prefs.setHrDevice(HrDevice(found.address, found.name))
        closeGatt()
        if (holders.isNotEmpty()) connect() else publishIdle()
    }

    @Synchronized
    fun forget() {
        stopScan()
        closeGatt()
        Prefs.setHrDevice(null)
        TrackingManager.onHeartRateLost()
        _state.value = HrState()
    }
}
