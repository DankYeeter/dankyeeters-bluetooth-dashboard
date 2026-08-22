package dev.dankyeeter.btdashboard.monitor.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * "Something that can change which Bluetooth audio devices are connected just
 * happened." No payload, on purpose: every consumer re-reads the A2DP profile
 * itself, because the broadcast extras are a less complete and less consistent
 * answer than the profile is.
 *
 * This is the seam that lets the dashboard be push-based without polling — the
 * only alternative to a refresh button, and the one that costs no battery: a
 * registered receiver is free while nothing happens.
 *
 * Deliberately *not* [MonitorEventSource]. That one produces timeline entries
 * and drops any action it cannot turn into a sentence; this one has to fire on
 * every relevant action, including the ones with no story to tell.
 */
interface ConnectionTicks {
    fun ticks(): Flow<Unit>
}

/**
 * [ConnectionTicks] from the real Bluetooth broadcasts.
 *
 * Registered at collection time and unregistered when the last collector goes
 * away, so the receiver's lifetime is the screen's, not the process's.
 */
class BroadcastConnectionTicks(
    private val context: Context,
) : ConnectionTicks {

    override fun ticks(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == null) return
                trySend(Unit)
            }
        }

        val filter = IntentFilter().apply { BtActions.connectionRelevant.forEach(::addAction) }
        // Must be EXPORTED — the same trap documented in BluetoothBroadcastSource:
        // NOT_EXPORTED admits only senders sharing our uid, and the Bluetooth
        // stack is its own package at uid 1002. It registers happily and then
        // silently drops every broadcast.
        //
        // Safe to export: all of these are protected broadcasts, so no
        // third-party app can forge a connection that isn't there.
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        }.isSuccess
        // Logged because a silent stream and a dead receiver look identical
        // from the UI, which is precisely the ambiguity that hid the
        // NOT_EXPORTED bug for as long as it did.
        Log.i(TAG, "connection-tick receiver registration: $registered")

        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    private companion object {
        const val TAG = "BtConnectionTicks"
    }
}
