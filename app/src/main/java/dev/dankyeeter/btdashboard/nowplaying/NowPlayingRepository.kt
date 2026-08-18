package dev.dankyeeter.btdashboard.nowplaying

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the latest now-playing reading for the UI.
 *
 * A `NotificationListenerService` is created and destroyed by the system, so
 * the state cannot live inside it. The service pushes here; the Dashboard
 * collects from here.
 */
object NowPlayingRepository {

    private val _current = MutableStateFlow<NowPlaying?>(null)
    val current: StateFlow<NowPlaying?> = _current.asStateFlow()

    /** True while the listener service is connected to the notification stream. */
    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    internal fun publish(value: NowPlaying?) {
        _current.value = value
    }

    internal fun setConnected(connected: Boolean) {
        _listenerConnected.value = connected
        if (!connected) _current.value = null
    }
}

/**
 * Onboarding helper for notification access.
 *
 * Notification access is a Settings toggle — it cannot be requested with a
 * runtime permission dialog, so the app has to explain why it is needed and
 * deep-link the user to the right screen. Without it, the now-playing card is
 * the only thing that stops working; everything else is unaffected.
 */
object NotificationAccess {

    /**
     * True when the user has granted this app notification access.
     *
     * Read from the secure setting that lists the enabled listeners, which is
     * the documented way to check without holding a privileged permission.
     */
    fun isGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val target = context.packageName
        return enabled.split(':').any { entry ->
            entry.substringBefore('/') == target
        }
    }

    /**
     * Intent for the system's notification-access screen.
     *
     * Android has no reliable way to deep-link to *our* row inside that list on
     * every OEM build, so we open the list itself and the UI tells the user
     * which entry to enable.
     */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Nudges the system to rebind our listener after the user grants access.
     * Without this the service can stay unbound until the next reboot.
     */
    fun requestRebind(context: Context) {
        runCatching {
            NotificationListenerService.requestRebind(
                android.content.ComponentName(context, TidalNotificationListener::class.java),
            )
        }
    }
}
