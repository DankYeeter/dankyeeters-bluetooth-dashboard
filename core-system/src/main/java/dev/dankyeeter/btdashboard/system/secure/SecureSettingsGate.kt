package dev.dankyeeter.btdashboard.system.secure

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Whether WRITE_SECURE_SETTINGS is granted, and the ADB command that grants it
 * from a computer.
 *
 * The app does obtain this permission by itself now - its helper grants it the
 * moment it attaches - so the command is the fallback, not the route. It stays
 * because the fallback has to work when the helper cannot start at all, and
 * because a permission the user can see no way to grant is worse than a line
 * to copy.
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

    /** Copy-paste command, for the case where the helper never came up. */
    fun adbGrantCommand(): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
}
