package dev.dankyeeter.btdashboard.ui.screens.wizard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.dankyeeter.btdashboard.nowplaying.NotificationAccess
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.setup.SetupEnvironment
import dev.dankyeeter.btdashboard.system.setup.SetupStep
import dev.dankyeeter.btdashboard.system.shizuku.SecureSettingsState
import dev.dankyeeter.btdashboard.system.shizuku.ShizukuState

/**
 * The real, live answer to "is this step done?".
 *
 * Every call reads the OS again rather than caching, because all of these can
 * change while the app is backgrounded — a Settings toggle, an ADB command, a
 * Shizuku service that stopped. The wizard re-evaluates on every resume, so a
 * green tick always reflects the current state and never a past one.
 */
class AndroidSetupEnvironment(context: Context) : SetupEnvironment {

    private val appContext = context.applicationContext

    override fun isSatisfied(step: SetupStep): Boolean = when (step) {
        SetupStep.BLUETOOTH ->
            granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_SCAN)

        SetupStep.MICROPHONE -> granted(Manifest.permission.RECORD_AUDIO)
        SetupStep.NOTIFICATIONS -> granted(Manifest.permission.POST_NOTIFICATIONS)
        SetupStep.NOTIFICATION_ACCESS -> runCatching {
            NotificationAccess.isGranted(appContext)
        }.getOrDefault(false)

        SetupStep.SHIZUKU -> SystemGraph.shizuku.state.value.isReady
        SetupStep.SECURE_SETTINGS -> SystemGraph.secureSettings.state() == SecureSettingsState.GRANTED
    }

    /**
     * Only Shizuku can be genuinely unreachable: when its binder reports an
     * error there is no button we could offer that would help, so the wizard
     * shows the reason instead of a dead "Grant" button.
     */
    override fun isReachable(step: SetupStep): Boolean = when (step) {
        SetupStep.SHIZUKU -> SystemGraph.shizuku.state.value !is ShizukuState.Error
        else -> true
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
