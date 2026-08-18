package dev.dankyeeter.btdashboard.system.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.attach.AttachmentStatus
import dev.dankyeeter.btdashboard.system.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reapplies the saved EQ after a reboot.
 *
 * Honest limitation, surfaced to the user rather than hidden: Shizuku does not
 * survive a reboot on a non-rooted device — wireless debugging is off after
 * boot, so the Shizuku service must be restarted manually. When that is the
 * case we post a notification saying the EQ is inactive, instead of silently
 * pretending it was restored.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> Unit
            else -> return
        }

        val pending = goAsync()
        SystemGraph.init(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SystemGraph.settingsStore.current()
                if (!settings.enabled) return@launch

                SystemGraph.shizuku.refresh()
                val controller = SystemGraph.eqController
                controller.apply(settings)

                val status = controller.status.value
                val shizukuReady = SystemGraph.shizuku.state.value is ShizukuState.Ready
                if (status !is AttachmentStatus.ActiveGlobal && !shizukuReady) {
                    notifyEqInactive(context)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to restore EQ after boot", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyEqInactive(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "EQ status",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Tells you when the system EQ could not be restored." }
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("EQ inactive — restart Shizuku")
            .setContentText(
                "Shizuku does not survive a reboot. Start it again (wireless debugging) " +
                    "to restore the system-wide EQ."
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Shizuku does not survive a reboot on an unrooted device: wireless " +
                        "debugging is disabled after boot. Re-enable wireless debugging and " +
                        "start Shizuku, then open the app to reactivate the system-wide EQ."
                )
            )
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing more we can do here.
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        const val CHANNEL_ID = "eq_status"
        const val NOTIFICATION_ID = 1001
    }
}
