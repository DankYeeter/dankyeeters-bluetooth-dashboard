package dev.dankyeeter.btdashboard.system.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.attach.AttachmentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the EQ attached while the app is not on screen.
 *
 * ## Why this exists at all
 *
 * A `DynamicsProcessing` effect belongs to the process that created it. The
 * whole audio chain — the global attach, the per-device profile applier, the
 * connect listener — used to hang off `Application`, which means it lived
 * exactly as long as Android felt like keeping the process. Swipe the app away
 * out of habit, or simply leave it in the background until memory gets tight,
 * and the EQ stopped. For someone using it to compensate hearing loss that is
 * not a degraded feature, it is the feature disappearing without a word.
 *
 * A foreground service is the only construct on Android that survives a task
 * swipe and can be restarted at boot. Everything else — WorkManager, alarms,
 * bound services — is either killable at will or cannot hold a live audio
 * effect.
 *
 * ## What it costs
 *
 * Close to nothing, and that is on purpose. The service holds an attachment and
 * listens to broadcasts; it does not poll, does not sample on a timer, and does
 * no work between Bluetooth events. Daniel's standing rule — battery drain
 * matters — is what shapes that: the sampler still idles itself to zero, and
 * this service adds no loop of its own.
 *
 * The price is a permanent notification. Android requires one for a foreground
 * service and there is no way around it; the honest response is to make it say
 * something true and useful (what the EQ actually reaches right now) rather
 * than a placeholder.
 *
 * ## What still kills it
 *
 * "Force stop" in Android's app settings, and uninstalling. That is the
 * intended contract: closing the app leaves it running, killing the app stops
 * it. A reboot stops it too, which is what [dev.dankyeeter.btdashboard.system.boot.BootReceiver]
 * is for.
 */
class EqForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SystemGraph.init(applicationContext)
        // The notification is the one place EQ state is visible without
        // opening anything, so it follows the live status instead of freezing
        // on whatever was true at start. Event-driven: this collector only
        // runs when the status actually changes, which is rare.
        scope.launch {
            SystemGraph.eqController.status.collect { status ->
                if (foregrounded) startForegroundHonestly(status)
            }
        }
    }

    /** Set once the first startForeground succeeded; guards the collector above. */
    @Volatile
    private var foregrounded = false

    /** Whether the one-time restore already ran in this service instance. */
    @Volatile
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promoted before any slow work: Android gives a service a few seconds
        // to call startForeground and kills the process if it misses that, and
        // reading DataStore is exactly the kind of work that could miss it.
        startForegroundHonestly(SystemGraph.eqController.status.value)
        foregrounded = true

        // start() is called on every Bluetooth connect, so restarts are the
        // common case, not the exception. Everything below is idempotent and
        // cheap when already done — apply() on a live attachment just rewrites
        // the band gains — but skipping the DataStore read entirely on a
        // no-op restart is cheaper still.
        if (started) return START_STICKY
        started = true

        scope.launch {
            val settings = SystemGraph.settingsStore.current()
            if (!settings.enabled) {
                // The user switched the EQ off. Holding a notification for a
                // thing that is deliberately not running would be noise.
                stopSelf()
                return@launch
            }

            SystemGraph.eqController.apply(settings)
            SystemGraph.startDeviceProfileAutoApply()
        }

        // Restarted with the last intent if the system reclaims us under
        // pressure: the point of this service is to come back.
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The notification says what the EQ *actually* reaches, not that a service
     * is running.
     *
     * "BT Dashboard is running" would be the placeholder every app posts and
     * nobody reads. This is the one place the user sees EQ state without
     * opening anything, so it carries the real status — including when that
     * status is "session only", which is the case worth noticing.
     */
    private fun startForegroundHonestly(status: AttachmentStatus) {
        ensureChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(status.serviceTitle())
            .setContentText(status.serviceText())
            .setStyle(NotificationCompat.BigTextStyle().bigText(status.serviceText()))
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Nothing on the lock screen. The EQ running is not news, and a
            // permanent line on a screen the user sees dozens of times a day is
            // the kind of thing that reads as an alert whether or not it is one.
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            // No timestamp: "since 07:14" invites the question "what happened
            // at 07:14", and the answer is "nothing".
            .setShowWhen(false)
            .apply { launchIntent()?.let(::setContentIntent) }
            .build()

        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
        }.onFailure { t ->
            // Distinguish a foreground-service-type refusal from a generic
            // failure by name. Android 15+ keeps tightening which types may
            // start when — and 17 may require a *connected* device for
            // connectedDevice, not just the permission. When that day comes,
            // this log line is what says "it was the type", instead of a
            // nondescript "could not go foreground". The class name is matched
            // as a string so this compiles on every SDK level.
            val kind = t.javaClass.simpleName
            if (kind.contains("ForegroundServiceStartNotAllowed") ||
                kind.contains("MissingForegroundServiceType") ||
                kind.contains("ForegroundServiceType")
            ) {
                Log.w(TAG, "foreground-service TYPE refused ($kind) — the connectedDevice " +
                    "type may need review on this Android version", t)
            } else {
                Log.w(TAG, "could not go foreground", t)
            }
        }
    }

    private fun launchIntent(): PendingIntent? = runCatching {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    /**
     * Its own channel, separate from the boot notice: different thing,
     * different switch — and as quiet as Android permits.
     *
     * `IMPORTANCE_MIN` is the point: it takes the icon out of the status bar
     * and collapses the entry to the bottom of the shade. Daniel reads any
     * persistent symbol as something demanding attention, and this one never
     * is — it exists because Android requires a foreground service to have a
     * notification, not because there is news.
     *
     * A **new channel id** rather than lowering the old one, for the same
     * reason the boot channel did it: Android only sometimes honours lowering
     * an existing channel, depending on whether the user has touched it. A new
     * id makes "no status bar icon" a property of the code rather than a hope.
     */
    private fun ensureChannel() {
        val manager = NotificationManagerCompat.from(this)
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "EQ running",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Shown while the equalizer is active in the background. " +
                "Android requires it; it never makes a sound and stays out of the " +
                "status bar and the lock screen."
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "EqService"

        /** Was IMPORTANCE_LOW, i.e. it put an icon in the status bar. */
        const val LEGACY_CHANNEL_ID = "eq_running"
        const val CHANNEL_ID = "eq_running_quiet"
        const val NOTIFICATION_ID = 1002

        /**
         * Starts the service, or does nothing if the platform refuses.
         *
         * A refusal is real and not exceptional: starting a foreground service
         * from the background is forbidden outside a handful of exemptions
         * (BOOT_COMPLETED being one). Callers that are not exempt should be
         * calling this while the app is visible.
         */
        fun start(context: Context) {
            val intent = Intent(context, EqForegroundService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "could not start the EQ service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, EqForegroundService::class.java)) }
        }
    }
}

/**
 * Daniel's wording. One line that covers both halves of what the service is
 * doing — the correction curve *and* the per-device Bluetooth settings — so the
 * shade does not imply the EQ is the only thing being kept alive.
 */
private const val SERVICE_TITLE = "EQ and Bluetooth adjusted"

private fun AttachmentStatus.serviceTitle(): String = when (this) {
    is AttachmentStatus.ActiveGlobal, is AttachmentStatus.ActiveSessions -> SERVICE_TITLE
    is AttachmentStatus.Unavailable -> "EQ not attached"
    AttachmentStatus.Inactive -> "Starting…"
}

private fun AttachmentStatus.serviceText(): String = when (this) {
    is AttachmentStatus.ActiveGlobal ->
        "Attached to the output mix, so it reaches every app."
    is AttachmentStatus.ActiveSessions ->
        "Attached to ${sessionIds.size} audio session(s). Players that do not " +
            "announce their session are not corrected."
    is AttachmentStatus.Unavailable -> reason
    AttachmentStatus.Inactive -> "Restoring your saved settings."
}
