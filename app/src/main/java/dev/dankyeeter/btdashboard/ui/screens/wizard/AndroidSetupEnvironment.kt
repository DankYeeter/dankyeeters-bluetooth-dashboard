package dev.dankyeeter.btdashboard.ui.screens.wizard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import dev.dankyeeter.btdashboard.system.setup.SetupEnvironment
import dev.dankyeeter.btdashboard.system.setup.SetupStep
import dev.dankyeeter.btdashboard.system.secure.SecureSettingsState

/**
 * The real, live answer to "is this step done?".
 *
 * Every call reads the OS again rather than caching, because all of these can
 * change while the app is backgrounded — a Settings toggle, an ADB command, a
 * helper that died. The wizard re-evaluates on every resume, so a green tick
 * always reflects the current state and never a past one.
 */
class AndroidSetupEnvironment(context: Context) : SetupEnvironment {

    private val appContext = context.applicationContext

    override fun isSatisfied(step: SetupStep): Boolean = when (step) {
        SetupStep.BLUETOOTH ->
            granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_SCAN)

        SetupStep.MICROPHONE -> granted(Manifest.permission.RECORD_AUDIO)
        // Asked as "can this app post a notification?", not as "is
        // POST_NOTIFICATIONS granted?".
        //
        // The permission only exists from Android 13. Below that the platform
        // answers "denied" to a string it does not know - and since this step
        // became required, that answer would have locked Android 12 into a
        // setup process with no way out, on a build whose minSdk is 31. The
        // older phones cannot say so themselves and there is none left here to
        // notice.
        //
        // areNotificationsEnabled() is the same answer on 13 and up, and a
        // better one: it also turns false when the user switches notifications
        // off in Settings, which is exactly the drift this whole live model
        // exists for.
        SetupStep.NOTIFICATIONS -> NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        // A connected helper, and not "helper connected *and* the permission
        // granted".
        //
        // The helper grants WRITE_SECURE_SETTINGS to the app itself the moment
        // it attaches, so the two are one event with a few milliseconds between
        // them - and asking for both here would make the step flicker back to
        // "not done" in that gap, which is when the gate would throw the user
        // out of an app that is in fact working.
        //
        // The permission is not swept under the carpet: [secureSettingsGranted]
        // reports it separately, and the step shows it as its own line. If the
        // grant failed, the user sees that it failed rather than being sent
        // through pairing again for a helper that is already running.
        SetupStep.HELPER -> PrivilegedConnection.isConnected
    }

    /**
     * Whether the helper has managed to grant the app WRITE_SECURE_SETTINGS.
     *
     * Only reported, never gated on - see above. Without it the app cannot
     * close wireless debugging again by itself.
     */
    fun secureSettingsGranted(): Boolean =
        SystemGraph.secureSettings.state() == SecureSettingsState.GRANTED

    override fun isReachable(step: SetupStep): Boolean = true

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
