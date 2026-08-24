package dev.dankyeeter.btdashboard.system.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.attach.AttachmentStatus
import dev.dankyeeter.btdashboard.system.service.EqForegroundService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reapplies the saved EQ after a reboot.
 *
 * Since the EQ became a foreground service this receiver's job is small: start
 * that service, then say something only if the result is worse than what the
 * user left behind.
 *
 * ## The premise that used to be here, and is now wrong
 *
 * This file used to state that the EQ always comes back with less reach after a
 * reboot, because shell access does not survive one. That was measured and
 * refuted on 21 August: the global attach needs no shell identity at all —
 * `DynamicsProcessing` on session 0 attaches with plain
 * `MODIFY_AUDIO_SETTINGS`. What still dies at boot is the privileged *helper*,
 * and that only costs codec control and the dumpsys reads, not the EQ.
 *
 * So the common case after a reboot is now a full recovery with no notification
 * at all, and the notice below is for the genuine failures.
 *
 * ### The tension, stated rather than resolved
 *
 * Two rules pull against each other here.
 *
 *  - *Never interrupt.* So this is [NotificationManager.IMPORTANCE_LOW]: no
 *    sound, no vibration, no heads-up peek, no badge. It sits in the shade
 *    until the user pulls it down.
 *  - *Never claim what cannot be proven.* So it is not simply deleted.
 *    Suppressing it would mean the app silently behaves as if the EQ came back
 *    when it did not, and the user's whole reason for the EQ is that they
 *    cannot hear parts of the signal without it. A silent wrong answer is worse
 *    than a quiet true one.
 *
 * Low importance is the most this can be softened without crossing into the
 * second rule. If it should ever become invisible entirely, the honest version
 * of that is `IMPORTANCE_MIN`, not "stop posting it".
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> Unit
            else -> return
        }

        // Once per process, whichever boot action arrives first.
        //
        // The filter lists BOOT_COMPLETED *and* LOCKED_BOOT_COMPLETED, and a
        // boot delivers both - once before the user unlocks and once after. The
        // restore below then ran twice, which was visible on the device as two
        // privileged helpers starting seconds apart, the second one retiring
        // the first.
        //
        // Both actions are still wanted: which of them arrives depends on
        // whether this receiver is direct-boot aware, and dropping one would
        // trade a duplicate for a silence. Taking the first and ignoring the
        // rest keeps that choice open.
        if (!restoreStarted.compareAndSet(false, true)) {
            Log.i(TAG, "boot restore already running; ignoring ${intent.action}")
            return
        }

        val pending = goAsync()
        SystemGraph.init(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SystemGraph.settingsStore.current()
                // The user switched the EQ off. Nothing was lost, so there is
                // nothing to report.
                if (!settings.enabled) return@launch

                // The service owns the attachment now, so that it survives the
                // app being swiped away. BOOT_COMPLETED is one of the few
                // exemptions that may start a foreground service from the
                // background, which is exactly why the restore lives here.
                EqForegroundService.start(context)

                // Try to bring the helper up without asking anybody.
                //
                // This is the point of the whole activation stack: the pairing
                // key survives a reboot, and once the app holds
                // WRITE_SECURE_SETTINGS it can open wireless debugging, use it
                // and close it again on its own. When that works the user never
                // learns a reboot happened - which is the actual requirement,
                // since settings that only apply when you remember to ask for
                // them are settings you cannot rely on.
                //
                // It is attempted before the status check below, so the
                // notification reports what is true *after* the attempt rather
                // than announcing a problem that has already been solved.
                // Whether a helper is already running is a question only the
                // app side can answer, so it is asked there rather than
                // duplicated here against a type this module cannot see.
                SystemGraph.activateHelper?.let { activate ->
                    val activated = runCatching { activate() }
                        .onFailure { Log.w(TAG, "automatic activation failed", it) }
                        .getOrDefault(false)
                    Log.i(TAG, "automatic activation after boot: $activated")
                }

                val controller = SystemGraph.eqController
                controller.apply(settings)

                // Reporting the *outcome*, not which provider happened to be
                // up. An earlier version stayed quiet whenever the shell
                // provider reported ready, which meant a global attach that failed for
                // any other reason (a build that refuses the effect, say) went
                // unmentioned — a false all-clear in the one place the app
                // exists to be honest about.
                when (val status = controller.status.value) {
                    is AttachmentStatus.ActiveGlobal -> clearStaleNotice(context)
                    else -> notifyReducedReach(context, status)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to restore EQ after boot", t)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The EQ came back with full reach, so any notice left over from an earlier
     * boot is now a lie. Cheap to cancel, and nothing else ever clears it.
     */
    private fun clearStaleNotice(context: Context) = dismissNotice(context)

    private fun notifyReducedReach(context: Context, status: AttachmentStatus) {
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(status.title())
            .setContentText(SHORT_TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status.longText()))
            // Belt and braces with the channel: the channel governs on this
            // minSdk, but `setSilent` also suppresses the one-off alert a
            // re-post would otherwise be allowed to make.
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .apply {
                launchIntent(context)?.let(::setContentIntent)
                // An explicit button, not just a tappable body. This notice
                // appears exactly once per reboot and asks for exactly one
                // thing; a labelled action says what tapping will do, where a
                // bare notification only says that something is wrong.
                //
                // It cannot start the helper itself - that is the sandbox
                // boundary the helper exists to cross, and no app may cross it
                // unaided. What it can do is land on the screen that hands over
                // the command, which turns "remember to do the thing" into one
                // tap plus a paste.
                setupIntent(context)?.let { addAction(0, ACTION_LABEL, it) }
            }
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing more we can do here, and
            // nothing that would justify pestering the user another way.
        }
    }

    /**
     * A tap should land on the screen that can fix this. The launcher intent is
     * looked up by package rather than naming an Activity, because this module
     * is below :app and cannot see one.
     */
    /**
     * Opens the app straight on the setup screen.
     *
     * Deliberately the launcher intent plus an extra rather than naming the
     * Activity: this module sits below `:app` and cannot see one. `:app` reads
     * [OpenRoute.EXTRA] on the way in and navigates there.
     *
     * A distinct request code from [launchIntent] - two PendingIntents on the
     * same code and component would otherwise be treated as the same intent,
     * and the second would silently reuse the first one's extras.
     */
    private fun setupIntent(context: Context): PendingIntent? = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        intent.putExtra(OpenRoute.EXTRA, OpenRoute.ACTIVATE)
        PendingIntent.getActivity(
            context,
            REQUEST_SETUP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    private fun launchIntent(context: Context): PendingIntent? = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    /**
     * Creates the low-importance channel, and drops the old one.
     *
     * A new id rather than re-creating the old channel at a lower importance:
     * Android only *sometimes* lets an app lower an existing channel — it
     * depends on whether the user has touched any field on it, and OEM builds
     * vary. Deleting and re-creating under a new id makes "this never makes a
     * sound" a property of the code instead of a hope about the platform.
     */
    private fun ensureChannel(manager: NotificationManagerCompat) {
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "EQ status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "A quiet note when the system EQ came back with less reach " +
                "than you left it with. Never makes a sound."
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {

        /**
         * Guards against the second boot broadcast. Static, because a receiver
         * is a fresh object for every delivery - an instance field would reset
         * exactly when it was needed.
         */
        private val restoreStarted = AtomicBoolean(false)

        /**
         * Takes the "EQ is off" notice down.
         *
         * Called from the boot check, and - the case that was missing - the
         * moment the privileged helper actually connects. Someone who activates
         * from inside the app never touches the notification, so nothing
         * dismissed it: the shade went on saying the EQ was off while it was
         * running, which is worse than saying nothing at all.
         */
        fun dismissNotice(context: Context) {
            runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        }

        const val TAG = "BootReceiver"

        /** Was IMPORTANCE_DEFAULT, i.e. audible. Deleted on first run of the new code. */
        const val LEGACY_CHANNEL_ID = "eq_status"
        const val CHANNEL_ID = "eq_status_quiet"
        const val NOTIFICATION_ID = 1001

        /** Distinct from the content intent's 0; see [setupIntent]. */
        const val REQUEST_SETUP = 1
        const val ACTION_LABEL = "Activate"

    }
}

// ---- what the notification is allowed to say --------------------------------
//
// Every string below is derived from the status the controller actually
// reported. The old copy named Shizuku as *the* mechanism, which stopped being
// true when the app grew its own helper; naming neither is not evasion but
// accuracy, because both die at the same moment and for the same reason, and
// the app cannot restart either one.

private fun AttachmentStatus.title(): String = when (this) {
    is AttachmentStatus.ActiveSessions -> "EQ is limited"
    else -> "EQ is off"
}

/**
 * The collapsed line, deliberately tiny.
 *
 * This appears once per reboot among a dozen other notices - the watch that was
 * not found, the SIM that lost its number, the debugging warning. Nobody reads a
 * paragraph in that company. The button says what to do; the line only has to
 * say why it is there.
 */
private const val SHORT_TEXT = "Tap Activate to restore it."

private fun AttachmentStatus.longText(): String = buildString {
    append(
        when (this@longText) {
            is AttachmentStatus.ActiveSessions ->
                "The EQ is running in session mode only: it reaches players that announce " +
                    "their audio session and nothing else."

            is AttachmentStatus.Unavailable -> reason
            AttachmentStatus.Inactive -> "The EQ is not attached to anything."
            // Never posted for this case; kept so the `when` cannot go stale.
            is AttachmentStatus.ActiveGlobal -> "The EQ is attached to the output mix."
        },
    )
    append(
        "\n\nSystem-wide reach needs shell-level access, and nothing on an unrooted " +
            "phone can bring that back on its own after a reboot. Settings → System " +
            "access has the one ADB command to run from a computer.",
    )
}
