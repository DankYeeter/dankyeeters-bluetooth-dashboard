package dev.dankyeeter.btdashboard.system.devices

import android.content.Context
import android.provider.Settings
import android.util.Log
import dev.dankyeeter.btdashboard.system.shizuku.SecureSettingsGate
import dev.dankyeeter.btdashboard.system.shizuku.SecureSettingsState

/**
 * [SecureSettingsController] on `Settings.Global`.
 *
 * The same mechanism [AbsoluteVolumeGate] uses, generalised to any key: these
 * are ordinary global settings that Android's own Developer Options screen
 * writes, guarded by WRITE_SECURE_SETTINGS rather than by a private API.
 *
 * The one thing worth reading closely is [write], which confirms by reading
 * back — see there.
 */
class GlobalSettingsController(
    context: Context,
    private val secureSettings: SecureSettingsGate,
) : SecureSettingsController {

    private val resolver = context.applicationContext.contentResolver

    override fun isWritable(): Boolean =
        secureSettings.state() == SecureSettingsState.GRANTED

    /**
     * Null means the key is unset, which is **not** the same as unsupported.
     * `bluetooth_disable_absolute_volume` reads null on a stock Pixel and works
     * perfectly well once written, so nothing may conclude "unsupported" from a
     * null here.
     */
    override fun read(key: String): String? = runCatching {
        Settings.Global.getString(resolver, key)
    }.onFailure { Log.w(TAG, "reading $key failed", it) }.getOrNull()

    /**
     * Writes, then reads back, and only reports success if the value is really
     * there.
     *
     * `Settings.Global.putString` returning true means the write was accepted
     * by the settings provider, not that this build recognises the key. Since
     * no API answers "does this Android version honour this option", the
     * read-back is the only evidence available — and reporting a write as
     * successful without it would turn "your phone ignores this" into a green
     * checkmark in the profile editor.
     *
     * Note what this still does *not* prove: that the Bluetooth stack acts on
     * the value. It reads these at startup, which is why the option carries
     * [DeveloperOption.needsBluetoothRestart] as a stated fact rather than a
     * verified one.
     */
    override fun write(key: String, value: String): Boolean {
        val accepted = runCatching { Settings.Global.putString(resolver, key, value) }
            .onFailure { Log.w(TAG, "writing $key was refused", it) }
            .getOrDefault(false)
        if (!accepted) return false
        val readBack = read(key)
        if (readBack != value) {
            Log.w(TAG, "$key did not stick: wrote '$value', read back '$readBack'")
            return false
        }
        return true
    }

    override fun clear(key: String): Boolean {
        val accepted = runCatching { Settings.Global.putString(resolver, key, null) }
            .onFailure { Log.w(TAG, "clearing $key was refused", it) }
            .getOrDefault(false)
        if (!accepted) return false
        val readBack = read(key)
        if (readBack != null) {
            Log.w(TAG, "$key did not clear: still reads '$readBack'")
            return false
        }
        return true
    }

    private companion object {
        const val TAG = "GlobalSettings"
    }
}
