package dev.dankyeeter.btdashboard.system.devices

import android.content.Context
import android.provider.Settings
import android.util.Log
import dev.dankyeeter.btdashboard.system.shizuku.SecureSettingsGate
import dev.dankyeeter.btdashboard.system.shizuku.SecureSettingsState

/** What the UI is allowed to claim about absolute volume right now. */
sealed interface AbsoluteVolumeStatus {
    /** Readable and writable — WRITE_SECURE_SETTINGS is granted. */
    data class Available(val enabled: Boolean) : AbsoluteVolumeStatus

    /**
     * The current value is readable (Settings.Global is world-readable) but we
     * may not write it. The UI shows the real state and the ADB command, and
     * disables the switch instead of pretending the toggle works.
     */
    data class PermissionMissing(val enabled: Boolean?, val adbCommand: String) : AbsoluteVolumeStatus

    /** The key does not exist on this build; nothing honest can be shown. */
    data object Unsupported : AbsoluteVolumeStatus
}

/**
 * Reads and writes Android's absolute-volume switch.
 *
 * The underlying setting is inverted and global, and both facts matter:
 *
 * - `bluetooth_disable_absolute_volume = 1` means absolute volume is **off**.
 *   This class exposes the positive sense (`enabled`) and inverts internally,
 *   so nothing else in the app has to remember the double negative.
 * - It is one system-wide setting, not a per-device one. The per-device profile
 *   therefore expresses a *wish* that is applied when that device connects; it
 *   cannot be a state Android keeps per headphone.
 *
 * Writing requires WRITE_SECURE_SETTINGS, which no app can grant itself. We
 * detect its absence via [SecureSettingsGate] and hand the user the same ADB
 * command the setup wizard shows.
 */
class AbsoluteVolumeGate(
    context: Context,
    private val secureSettings: SecureSettingsGate,
) : AbsoluteVolumeController {

    private val resolver = context.applicationContext.contentResolver

    fun status(): AbsoluteVolumeStatus {
        val enabled = isEnabled()
        return when {
            secureSettings.state() != SecureSettingsState.GRANTED ->
                AbsoluteVolumeStatus.PermissionMissing(enabled, secureSettings.adbGrantCommand())

            enabled == null -> AbsoluteVolumeStatus.Unsupported
            else -> AbsoluteVolumeStatus.Available(enabled)
        }
    }

    override fun isWritable(): Boolean = secureSettings.state() == SecureSettingsState.GRANTED

    /**
     * Absent key means the OEM never disabled absolute volume, which is the
     * Android default — reported as enabled rather than as "unknown", because
     * that is what the audio stack actually does.
     */
    override fun isEnabled(): Boolean? = runCatching {
        Settings.Global.getInt(resolver, KEY_DISABLE_ABSOLUTE_VOLUME, 0) == 0
    }.onFailure { Log.w(TAG, "absolute-volume setting unreadable", it) }.getOrNull()

    override fun setEnabled(enabled: Boolean): Boolean = runCatching {
        Settings.Global.putInt(resolver, KEY_DISABLE_ABSOLUTE_VOLUME, if (enabled) 0 else 1)
    }.onFailure { Log.w(TAG, "absolute-volume write refused", it) }.getOrDefault(false)

    private companion object {
        const val TAG = "AbsoluteVolumeGate"

        /**
         * `Settings.Global.BLUETOOTH_DISABLE_ABSOLUTE_VOLUME` is `@hide`, so the
         * string constant is inlined rather than referenced.
         */
        const val KEY_DISABLE_ABSOLUTE_VOLUME = "bluetooth_disable_absolute_volume"
    }
}
