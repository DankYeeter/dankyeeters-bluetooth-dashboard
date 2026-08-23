package dev.dankyeeter.btdashboard.privileged.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * Turns wireless debugging on, so the user does not have to.
 *
 * ## Why this is worth doing
 *
 * Wireless debugging is the door the helper comes through, and it keeps
 * closing. Measured on this device: Android switches it off whenever there is
 * no Wi-Fi connection, and refuses to keep it on at all while a USB cable with
 * active debugging is plugged in - within 23 ms of being switched on. Asking
 * the user to go and re-enable it before every Activate would make the whole
 * "one tap after a reboot" idea a fiction.
 *
 * ## Why the app is allowed to
 *
 * `WRITE_SECURE_SETTINGS`, which this app already holds for its Bluetooth
 * work. It is not something the app can grant itself - it arrives through the
 * same one-time shell setup as everything else here - so this is not a
 * loophole, just a use of something already established.
 *
 * The switch is only ever turned **on**, and only when the user has just asked
 * for the helper. Nothing here turns it off again: leaving a debugging
 * interface open is the user's decision to make and revisit, not something an
 * app should quietly toggle behind them.
 */
class WirelessDebuggingSwitch(private val context: Context) {

    fun isEnabled(): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, SETTING) == 1
    }.getOrDefault(false)

    fun canEnable(): Boolean =
        context.checkPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS,
            Process.myPid(),
            Process.myUid(),
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * @return true if wireless debugging is on afterwards - whether this call
     *   turned it on or it already was.
     *
     * Writing the setting is a request, not a guarantee: Android's own service
     * decides, and it says no when there is no Wi-Fi. The value is therefore
     * read back rather than assumed, because a caller that believes the switch
     * moved will report a puzzling failure two steps later instead of the plain
     * "this needs Wi-Fi" that the user can act on.
     */
    fun enable(): Boolean {
        if (isEnabled()) return true
        if (!canEnable()) return false

        runCatching { Settings.Global.putInt(context.contentResolver, SETTING, 1) }
            .onFailure { Log.w(TAG, "could not switch wireless debugging on", it) }

        // adbd needs a moment to bind its port and publish the announcement.
        Thread.sleep(SETTLE_MS)
        return isEnabled().also {
            Log.i(TAG, if (it) "wireless debugging is on" else "the system declined to turn it on")
        }
    }

    private companion object {
        const val TAG = "WirelessDebugging"
        const val SETTING = "adb_wifi_enabled"

        /**
         * Long enough for adbd to come up and advertise; short enough to stay
         * inside the spinner the user is already looking at.
         */
        const val SETTLE_MS = 1_200L
    }
}
