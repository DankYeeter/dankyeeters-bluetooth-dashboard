package dev.dankyeeter.btdashboard.privileged.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Takes the pairing code without ever leaving Android's pairing dialog.
 *
 * ## Why a notification, of all things
 *
 * Because the obvious design cannot work, and that was measured rather than
 * assumed: Android publishes the pairing service **only while its dialog is in
 * the foreground**. Read the code, switch to this app to type it, and by the
 * time the field has focus there is nothing left to pair with - the service is
 * gone, and the app can only report a failure that looks like its own fault.
 *
 * The notification shade is the exception. It overlays the current activity
 * instead of replacing it, and the service stays advertised - verified on the
 * device, same port before and during. So the code is typed into a
 * direct-reply notification with the pairing dialog still alive behind it.
 *
 * The result is better than what it replaces: **no app switch at all.** Pull
 * down, type six digits, done.
 */
object PairingCodeNotification {

    private const val TAG = "PairingCodeNotice"
    private const val CHANNEL_ID = "adb_pairing_code"
    private const val NOTIFICATION_ID = 1003
    const val KEY_CODE = "pairing_code"
    const val ACTION_SUBMIT = "dev.dankyeeter.btdashboard.SUBMIT_PAIRING_CODE"

    /**
     * Asks for the code, right where the user already is.
     *
     * [IMPORTANCE_HIGH] on purpose, and it is the one notification in this app
     * that earns it: it appears only in direct response to the user pressing
     * Activate, and it is useless if it sinks quietly to the bottom of a shade
     * they are about to pull down anyway.
     */
    fun show(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Pairing code",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Shown while the app is waiting for a wireless " +
                    "debugging pairing code. Appears only when you ask it to."
                setShowBadge(false)
            },
        )

        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel("6-digit code")
            .build()

        val reply = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PairingCodeReceiver::class.java).setAction(ACTION_SUBMIT),
            // MUTABLE is required: the system fills the code into this intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Enter the pairing code")
            .setContentText(
                "Open Wireless debugging → \"Pair device with pairing code\", " +
                    "then swipe down and type the six digits here.",
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Open Wireless debugging → \"Pair device with pairing code\". " +
                        "Leave that screen open, swipe down, and type the six " +
                        "digits here — the code stops working the moment you " +
                        "leave Android's pairing screen.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action.Builder(0, "Enter code", reply)
                    .addRemoteInput(remoteInput)
                    .build(),
            )
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "could not post the pairing prompt", it) }
    }

    fun dismiss(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}

/**
 * Receives the typed code and runs the pairing.
 *
 * Deliberately does the work here rather than handing it to the app: the whole
 * point is that the user never left Android's pairing screen, and starting an
 * Activity now would close the very dialog the code belongs to.
 */
class PairingCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // First line on purpose. "Nothing happened" is not a diagnosis, and the
        // difference between a receiver that never ran and one that ran and
        // rejected the input is otherwise invisible from outside.
        Log.i(TAG, "onReceive action=${intent.action}")
        if (intent.action != PairingCodeNotification.ACTION_SUBMIT) return
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingCodeNotification.KEY_CODE)
            ?.toString()
            ?.filter(Char::isDigit)
            .orEmpty()

        if (code.length != CODE_LENGTH) {
            Log.w(TAG, "ignoring a ${code.length}-digit entry")
            return
        }

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val outcome = HelperAutoStart(appContext).pairThenStart(code)
                Log.i(TAG, "pairing outcome: $outcome")
                if (outcome is HelperAutoStart.Outcome.Started) {
                    PairingCodeNotification.dismiss(appContext)
                } else {
                    // Post it again. Android removes a direct-reply
                    // notification once the reply is delivered and waits for
                    // the app to put something back; without this the prompt
                    // simply vanishes on a failed attempt, and the user has to
                    // go back to the app to get another - while Android has
                    // already shown them a fresh code they could have typed.
                    PairingCodeNotification.show(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "PairingCodeRx"
        const val CODE_LENGTH = 6
    }
}
