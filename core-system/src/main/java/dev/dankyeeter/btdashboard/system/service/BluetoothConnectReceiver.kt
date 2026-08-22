package dev.dankyeeter.btdashboard.system.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the EQ service when a Bluetooth device connects.
 *
 * ## Why this exists next to the boot receiver
 *
 * `BOOT_COMPLETED` is throttled. Android queues it for background apps behind
 * everything else the phone is doing at startup, and on a real reboot here the
 * service still had not been started two minutes in, with a load average of 23.
 * It probably arrives eventually — but "eventually" is a poor contract for the
 * thing that makes audio audible.
 *
 * This receiver is the better trigger anyway, and not merely a workaround: the
 * correction only matters once audio is going somewhere over Bluetooth. Tying
 * the service to that moment means it starts when it is needed and stays out of
 * the way when it is not.
 *
 * ## Why it is in the manifest rather than registered at runtime
 *
 * `DeviceConnectionWatcher` already listens for the same broadcast, and dies
 * with the process — which is exactly the hole this closes. A manifest receiver
 * is instantiated by the system, so it works when nothing of ours is running.
 *
 * ## Why starting a foreground service from here is allowed
 *
 * `ACTION_ACL_CONNECTED` is one of the broadcasts that grant a short
 * while-in-use window, so the `startForegroundService` below is not a
 * background start. The service itself declares
 * `foregroundServiceType="connectedDevice"`, which is the honest description of
 * what just happened.
 */
class BluetoothConnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return

        // Only audio devices are worth a cold start. This receiver fires for
        // *every* ACL connect — car head unit, watch, gamepad, mouse — and an
        // unfiltered version paid a full process start (all three graphs, the
        // EQ attach, the DataStore read) several times per car ride for
        // devices the app has no business with. The device class rides along
        // in the intent, so the check costs nothing.
        //
        // An *unknown* class still starts the service: a headphone that did
        // not announce its class yet must not lose its EQ over our thrift.
        // Which profiles apply is still solely the service's judgement.
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val majorClass = runCatching { device?.bluetoothClass?.majorDeviceClass }.getOrNull()
        if (majorClass != null && majorClass != android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO) {
            Log.i(TAG, "ignoring a non-audio device (class $majorClass)")
            return
        }

        Log.i(TAG, "a Bluetooth audio device connected, making sure the EQ service is up")
        EqForegroundService.start(context)
    }

    private companion object {
        const val TAG = "BtConnectReceiver"
    }
}
