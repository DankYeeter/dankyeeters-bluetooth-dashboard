package dev.dankyeeter.btdashboard.system.devices

import android.content.Context
import android.provider.Settings
import android.util.Log
import dev.dankyeeter.btdashboard.system.secure.SecureSettingsGate

/**
 * A `Settings.Global` read that keeps "not set" and "could not be read" apart.
 *
 * [SecureSettingsController.read] folds both into null, which is fine for a
 * display but not for the settings ledger: a failed read recorded as "not set"
 * would make the way back a delete of a value that was really there.
 */
sealed interface SettingRead {
    data class Value(val value: String) : SettingRead

    data object Unset : SettingRead

    data object Unreadable : SettingRead
}

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
        secureSettings.isGranted()

    /**
     * Null means the key is unset, which is **not** the same as unsupported.
     * `bluetooth_disable_absolute_volume` reads null on a stock Pixel and works
     * perfectly well once written, so nothing may conclude "unsupported" from a
     * null here.
     */
    override fun read(key: String): String? = (readState(key) as? SettingRead.Value)?.value

    override fun readState(key: String): SettingRead = runCatching {
        Settings.Global.getString(resolver, key)
    }.fold(
        onSuccess = { value -> value?.let(SettingRead::Value) ?: SettingRead.Unset },
        onFailure = {
            Log.w(TAG, "reading $key failed", it)
            SettingRead.Unreadable
        },
    )

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
        val readBack = readState(key)
        if (!confirms(value, readBack)) {
            Log.w(TAG, "$key did not stick: wrote '$value', read back $readBack")
            return false
        }
        return true
    }

    override fun clear(key: String): Boolean {
        val accepted = runCatching { Settings.Global.putString(resolver, key, null) }
            .onFailure { Log.w(TAG, "clearing $key was refused", it) }
            .getOrDefault(false)
        if (!accepted) return false
        val readBack = readState(key)
        if (!confirms(null, readBack)) {
            Log.w(TAG, "$key did not clear: still reads $readBack")
            return false
        }
        return true
    }

    private companion object {
        const val TAG = "GlobalSettings"
    }
}

/**
 * Whether [actual] confirms that a key now holds [expected] (null means
 * cleared).
 *
 * SR-025: confirming through [SecureSettingsController.read] folds a failed
 * read ([SettingRead.Unreadable]) into null, the same value a real clear
 * produces — so a provider that could not be read would pass as "cleared".
 * [readState] keeps the two apart; this is the one line that tells them apart.
 */
internal fun confirms(expected: String?, actual: SettingRead): Boolean =
    actual == (expected?.let(SettingRead::Value) ?: SettingRead.Unset)
