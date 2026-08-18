package dev.dankyeeter.btdashboard.system.devices

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Listens for `ACTION_ACL_CONNECTED` and hands the address to
 * [DeviceProfileApplier].
 *
 * Registered at runtime from `Application.onCreate` rather than in the
 * manifest: since Android 8 an implicit manifest receiver would not be
 * delivered anyway, and a profile only needs applying while the app process is
 * around to own the EQ effect.
 *
 * ACL connect fires before A2DP is fully negotiated. That is fine for what we
 * do here (volume, EQ, a global setting) and is the earliest honest signal that
 * *this* headphone is the one in the user's ears.
 */
class DeviceConnectionWatcher(
    private val context: Context,
    private val store: DeviceProfileStore,
    private val applier: DeviceProfileApplier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _lastResult = MutableStateFlow<ApplyResult?>(null)

    /** The most recent auto-apply outcome; the dashboard reports it verbatim. */
    val lastResult: StateFlow<ApplyResult?> = _lastResult.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
                val device = intent.device() ?: return
                handleConnect(device)
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        // System-sent broadcast: NOT_EXPORTED is the correct registration flag.
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(created, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(created, filter)
            }
        }.isSuccess
        if (registered) receiver = created
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    @SuppressLint("MissingPermission") // name access is guarded by runCatching
    private fun handleConnect(device: BluetoothDevice) {
        val address = device.address ?: return
        val key = DeviceKey.fromAddress(address) ?: return
        val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address
        scope.launch {
            runCatching {
                // Remember the device first, so an unknown headphone shows up in
                // the profile editor even though nothing was applied to it.
                store.noteSeen(key, name, clock())
                _lastResult.value = applier.onDeviceConnected(address)
            }.onFailure { Log.w(TAG, "profile auto-apply failed", it) }
        }
    }

    private fun Intent.device(): BluetoothDevice? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }.getOrNull()

    private companion object {
        const val TAG = "DeviceConnectionWatcher"
    }
}
