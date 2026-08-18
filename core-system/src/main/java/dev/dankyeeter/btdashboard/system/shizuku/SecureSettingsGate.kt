package dev.dankyeeter.btdashboard.system.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Detects whether WRITE_SECURE_SETTINGS is granted and produces the exact ADB
 * command for the onboarding screen. We never try to grant it ourselves.
 */
class SecureSettingsGate(private val context: Context) {

    fun state(): SecureSettingsState =
        if (context.checkPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                Process.myPid(),
                Process.myUid(),
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            SecureSettingsState.GRANTED
        } else {
            SecureSettingsState.NOT_GRANTED
        }

    /** Copy-paste command shown in onboarding. */
    fun adbGrantCommand(): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
}
