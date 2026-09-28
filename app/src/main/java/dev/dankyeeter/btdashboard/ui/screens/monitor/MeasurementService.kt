package dev.dankyeeter.btdashboard.ui.screens.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.dankyeeter.btdashboard.MainActivity
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.monitor.optimize.ARM_TARGET_MS
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * Keeps a measurement the user started running off the screen (AD-039, the AK-4
 * exception), for exactly as long as it runs.
 *
 * Foreground only while a comparison arm counts — its pin included — or a deep
 * capture runs. Not between the arms: `INSTRUCT` has no end of its own, so the
 * service ends with arm A and "Start Arm B" starts it again (T-055s B1). No
 * wake lock (AD-039 point 6), no timer: the notification is posted again only
 * when what it says changes, and the system draws the elapsed time.
 *
 * Stop ends the comparison without a result and the deep capture, and writes
 * no setting — which is why it may run from the lock screen (T-055s V16).
 */
open class MeasurementService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    /** What the notification says now; only a change is posted again. */
    private var posted: RunningMeasurement? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Extras are never read: the only action is this app's own Stop (V15).
        if (intent?.action == ACTION_STOP) {
            stopMeasurement()
            return START_NOT_STICKY
        }
        val running = runningMeasurement(
            ActiveComparison.held.value,
            MonitorGraph.engine.status.value.deepCaptureUntilMs,
            System.currentTimeMillis(),
        )
        if (running == null || !promoteSafely(running)) {
            finish()
            return START_NOT_STICKY
        }
        if (watch == null) watch = scope.launch { follow() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        _foreground.value = false
        super.onDestroy()
    }

    /** Hands the service to Android as a foreground service; open only so a test can make Android refuse. */
    protected open fun promote(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    /**
     * A refusal is an answer, not a crash (V18): the type refused on a newer
     * Android, `BLUETOOTH_CONNECT` not granted, a start from the background.
     * The screen then says the measurement needs it open (AD-039 point 5).
     */
    private fun promoteSafely(running: RunningMeasurement): Boolean {
        ensureChannel()
        val started = try {
            promote(notification(running))
            true
        } catch (e: SecurityException) {
            refused(e)
        } catch (e: IllegalStateException) {
            refused(e)
        }
        _foreground.value = started
        if (started) posted = running
        return started
    }

    /** The class name only: a platform message could carry a device. */
    private fun refused(e: RuntimeException): Boolean {
        Log.w(TAG, "foreground-service TYPE refused (${e.javaClass.simpleName})")
        return false
    }

    private suspend fun follow() {
        measurements(
            held = ActiveComparison.held,
            deepUntilMs = MonitorGraph.engine.status.map { it.deepCaptureUntilMs },
            wallClock = System::currentTimeMillis,
            elapsedClock = SystemClock::elapsedRealtime,
            stopDeep = MonitorGraph.engine::stopDeepCapture,
        ).collect { running ->
            when {
                running == null -> finish()
                running != posted -> post(running)
            }
        }
    }

    private fun post(running: RunningMeasurement) {
        posted = running
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(running))
        } catch (e: SecurityException) {
            // Notifications switched off: the service runs on, the screen's own line carries AK-4.
            Log.w(TAG, "measurement notification not posted (${e.javaClass.simpleName})")
        }
    }

    /** Stop in the notification: exactly these four steps, and no write among them (V16). */
    private fun stopMeasurement() {
        ActiveComparison.end(ComparisonEnd.StoppedOutside)
        MonitorGraph.engine.stopDeepCapture()
        finish()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(running: RunningMeasurement): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(running.title())
            .setContentText(running.text())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Announced at once, not after Android's ten-second grace: the
            // notification is the announcement AK-4 asks for.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setWhen(running.clockMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(running.arm == null)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent.makeMainActivity(ComponentName(this, MainActivity::class.java))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                STOP_LABEL,
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, MeasurementService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    /**
     * Its own channel, deliberately unlike the EQ's: a measurement the user
     * started is news for its duration, so it shows in the status bar and on
     * the lock screen, and stays silent (`IMPORTANCE_LOW`, T-055s B2).
     */
    private fun ensureChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    companion object {
        private const val TAG = "MeasurementService"

        const val ACTION_STOP = "dev.dankyeeter.btdashboard.action.STOP_MEASUREMENT"
        internal const val CHANNEL_ID = "measurement_running"
        internal const val NOTIFICATION_ID = 1004

        private val _foreground = MutableStateFlow(false)

        /** True only after a successful `startForeground`, false again once the service is gone. */
        val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

        /** Only from a tap: "Compare", "Start Arm B", and both "Watch live" (T-055s V17). */
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, MeasurementService::class.java)) }
                .onFailure { Log.w(TAG, "measurement service not started (${it.javaClass.simpleName})") }
        }
    }
}

/** What the notification says: which arm, whether a capture runs, and a time mark — nothing about the device (V19). */
internal data class RunningMeasurement(
    /** "A" or "B" while a comparison holds the service; null when only a deep capture does. */
    val arm: String?,
    val deep: Boolean,
    /** Wall clock for the system's chronometer: the arm's phase start, or the capture's end it counts down to. */
    val clockMs: Long,
) {
    fun title(): String = if (arm != null) "BT Dashboard — comparing" else "BT Dashboard — watching live"

    /** The elapsed time is the chronometer beside the title; the text names the whole length. */
    fun text(): String = when {
        arm == null -> "Up to ${formatSpan(SamplingPolicy.DEEP_CAPTURE_WINDOW_MS)}. Ends on its own, or tap Stop."
        deep -> "Arm $arm: counting ${formatSpan(ARM_TARGET_MS)}, and watching live. " +
            "Ends on its own, or tap Stop to end both."
        else -> "Arm $arm: counting ${formatSpan(ARM_TARGET_MS)}. Ends on its own, or tap Stop."
    }
}

/** What runs now, or null when nothing needs the service. `INSTRUCT` holds nothing (T-055s B1). */
internal fun runningMeasurement(held: ActiveComparison.Held?, deepUntilMs: Long, nowMs: Long): RunningMeasurement? {
    val arm = when (held?.phase) {
        ComparisonPhase.PIN_AND_CHECK, ComparisonPhase.ARM_A -> "A"
        ComparisonPhase.ARM_B -> "B"
        else -> null
    }
    val deep = deepUntilMs > nowMs
    return when {
        held != null && arm != null -> RunningMeasurement(arm, deep, held.phaseSinceMs)
        deep -> RunningMeasurement(null, true, deepUntilMs)
        else -> null
    }
}

/**
 * The notification's content as the measurement moves, null once nothing runs.
 *
 * A deep capture is ended here at the latest one window after this first saw
 * it, measured on [elapsedClock]: the engine counts on the wall clock, and a
 * wall clock set back an hour must not stretch a five-minute capture (V21).
 * One `delay` to a known end, no tick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun measurements(
    held: Flow<ActiveComparison.Held?>,
    deepUntilMs: Flow<Long>,
    wallClock: () -> Long,
    elapsedClock: () -> Long,
    stopDeep: () -> Unit,
): Flow<RunningMeasurement?> = flow {
    // The capture's end, and the elapsed time at which it was first seen.
    var deepSeen: Pair<Long, Long>? = null
    emitAll(
        combine(held, deepUntilMs.distinctUntilChanged(), ::Pair).transformLatest { (current, deepUntil) ->
            val now = wallClock()
            val running = runningMeasurement(current, deepUntil, now)
            emit(running)
            if (running?.deep != true) return@transformLatest
            val seenAt = deepSeen?.takeIf { it.first == deepUntil }?.second
                ?: elapsedClock().also { deepSeen = deepUntil to it }
            val leftOfWindow = SamplingPolicy.DEEP_CAPTURE_WINDOW_MS - (elapsedClock() - seenAt)
            delay(minOf(deepUntil - now, leftOfWindow))
            stopDeep()
        },
    )
}

private const val STOP_LABEL = "Stop"
private const val CHANNEL_NAME = "Measurement running"
private const val CHANNEL_DESCRIPTION =
    "Shown while a comparison arm or a live capture you started keeps measuring " +
        "off the screen. It ends with the measurement."
