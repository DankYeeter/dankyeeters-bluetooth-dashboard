package dev.dankyeeter.btdashboard.system.airpods

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Nearest-AirPods reading, assembled from public BLE advertisements.
 *
 * Read-only by construction: we start a passive scan, filter for Apple's
 * manufacturer id and decode what the buds broadcast anyway. Nothing is ever
 * written to the device, and no pairing is involved.
 *
 * Permission: `BLUETOOTH_SCAN` with `neverForLocation`, so Android does not ask
 * the user for location. Without the grant the scanner reports
 * [AirPodsScanState.PermissionMissing] and stays inert — never crashes.
 *
 * Battery discipline: scanning is explicitly started and stopped by the screen
 * that shows the card, at [ScanSettings.SCAN_MODE_LOW_POWER], and the last
 * reading is kept for [STALE_AFTER_MILLIS] so the card does not flicker between
 * advertisements.
 */
class AirPodsScanner(context: Context) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<AirPodsScanState>(AirPodsScanState.Idle)
    val state: StateFlow<AirPodsScanState> = _state.asStateFlow()

    private var scanning = false
    private var lastResultUptime = 0L

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val record = result?.scanRecord ?: return
            val payload = record.getManufacturerSpecificData(
                AppleProximityBeaconParser.APPLE_COMPANY_ID,
            ) ?: return
            val beacon = AppleProximityBeaconParser.parse(payload) ?: return

            // Several Apple devices can be in range; prefer the strongest signal
            // while the previous reading is still fresh.
            val previous = _state.value as? AirPodsScanState.Found
            val fresh = SystemClock.uptimeMillis() - lastResultUptime < STALE_AFTER_MILLIS
            if (previous != null && fresh && previous.rssi > result.rssi) return

            lastResultUptime = SystemClock.uptimeMillis()
            _state.value = AirPodsScanState.Found(beacon, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed with code $errorCode")
            scanning = false
            _state.value = AirPodsScanState.Unavailable("Bluetooth scan failed (code $errorCode).")
        }
    }

    /** Idempotent; safe to call from every `onStart`. */
    fun start() {
        if (scanning) return
        if (!hasScanPermission()) {
            _state.value = AirPodsScanState.PermissionMissing
            return
        }
        val scanner = adapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            _state.value = AirPodsScanState.Unavailable("Bluetooth is off or unavailable.")
            return
        }
        val filter = ScanFilter.Builder()
            .setManufacturerData(AppleProximityBeaconParser.APPLE_COMPANY_ID, byteArrayOf(), byteArrayOf())
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            scanning = true
            if (_state.value !is AirPodsScanState.Found) _state.value = AirPodsScanState.Scanning
        } catch (e: SecurityException) {
            // The permission can be revoked between the check and the call.
            Log.w(TAG, "scan rejected", e)
            _state.value = AirPodsScanState.PermissionMissing
        } catch (e: IllegalStateException) {
            Log.w(TAG, "scan could not start", e)
            _state.value = AirPodsScanState.Unavailable("Bluetooth is off or unavailable.")
        }
    }

    /** Idempotent; safe to call from every `onStop`. */
    fun stop() {
        if (!scanning) return
        scanning = false
        try {
            adapter()?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan rejected", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopScan while Bluetooth was off", e)
        }
    }

    fun hasScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    private fun adapter(): BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private companion object {
        const val TAG = "AirPodsScanner"
        const val STALE_AFTER_MILLIS = 10_000L
    }
}

/** What the AirPods card should show. */
sealed interface AirPodsScanState {
    data object Idle : AirPodsScanState
    data object Scanning : AirPodsScanState
    data object PermissionMissing : AirPodsScanState
    data class Unavailable(val reason: String) : AirPodsScanState
    data class Found(val beacon: AirPodsBeacon, val rssi: Int) : AirPodsScanState
}
