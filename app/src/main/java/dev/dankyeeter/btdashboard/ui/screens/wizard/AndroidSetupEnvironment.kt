package dev.dankyeeter.btdashboard.ui.screens.wizard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import dev.dankyeeter.btdashboard.system.setup.SetupEnvironment
import dev.dankyeeter.btdashboard.system.setup.SetupStep
import dev.dankyeeter.btdashboard.system.shizuku.SecureSettingsState

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
        SetupStep.NOTIFICATIONS -> granted(Manifest.permission.POST_NOTIFICATIONS)
        SetupStep.SHELL_ACCESS -> PrivilegedConnection.isConnected
        SetupStep.SECURE_SETTINGS -> SystemGraph.secureSettings.state() == SecureSettingsState.GRANTED
    }

    override fun isReachable(step: SetupStep): Boolean = true

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
