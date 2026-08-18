package dev.dankyeeter.btdashboard.monitor.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Source of the always-on event stream (hierarchy level 2 in PLAN.md). */
interface MonitorEventSource {
    fun events(): Flow<MonitorEvent>
}

/**
 * Registers for the ACL / A2DP broadcasts and turns them into [MonitorEvent]s.
 *
 * Two of the three A2DP actions are `@SystemApi`. A normal app may *register*
 * for them; whether the system actually delivers them depends on the build, so
 * a silent stream is a supported outcome — the dumpsys fallback exists for
 * exactly that case.
 */
class BluetoothBroadcastSource(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : MonitorEventSource {

    override fun events(): Flow<MonitorEvent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val raw = intent?.let(::toRaw) ?: return
                val event = runCatching { BroadcastEventMapper.map(raw, clock()) }
                    .onFailure { Log.w(TAG, "event mapping failed for ${raw.action}", it) }
                    .getOrNull() ?: return
                trySend(event)
            }
        }

        val filter = IntentFilter().apply { BtActions.all.forEach(::addAction) }
        // Bluetooth broadcasts are system-sent; RECEIVER_NOT_EXPORTED is correct.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    @SuppressLint("MissingPermission") // name access is guarded by runCatching
    private fun toRaw(intent: Intent): RawBroadcast? {
        val action = intent.action ?: return null
        val device: BluetoothDevice? = runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        }.getOrNull()

        val ints = buildMap {
            if (intent.hasExtra(BtActions.EXTRA_STATE)) {
                put(BtActions.EXTRA_STATE, intent.getIntExtra(BtActions.EXTRA_STATE, -1))
            }
        }
        // The codec extra is a BluetoothCodecStatus parcelable whose class is
        // hidden; toString() is the only thing we can rely on across builds.
        val strings = buildMap {
            runCatching {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.os.Parcelable>(EXTRA_CODEC_STATUS)
            }.getOrNull()?.let { put("codec", it.toString()) }
        }

        return RawBroadcast(
            action = action,
            deviceAddress = device?.address,
            deviceName = runCatching { device?.name }.getOrNull() ?: device?.address,
            ints = ints,
            strings = strings,
        )
    }

    private companion object {
        const val TAG = "BtBroadcastSource"
        const val EXTRA_CODEC_STATUS = "android.bluetooth.extra.CODEC_STATUS"
    }
}
