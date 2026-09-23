package dev.dankyeeter.btdashboard.ui.tuning

import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.system.devices.AbsoluteVolumeGate.Companion.KEY_DISABLE_ABSOLUTE_VOLUME
import dev.dankyeeter.btdashboard.system.devices.BluetoothDeveloperOptions
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import dev.dankyeeter.btdashboard.system.devices.DeviceKey
import dev.dankyeeter.btdashboard.system.devices.DeviceProfile
import dev.dankyeeter.btdashboard.system.devices.DeviceProfileSource
import dev.dankyeeter.btdashboard.system.devices.HdAudioController
import dev.dankyeeter.btdashboard.system.devices.HdAudioOutcome
import dev.dankyeeter.btdashboard.system.devices.LedgerEntry
import dev.dankyeeter.btdashboard.system.devices.SecureSettingsController
import dev.dankyeeter.btdashboard.system.devices.SettingRead
import dev.dankyeeter.btdashboard.system.devices.SettingsLedger
import dev.dankyeeter.btdashboard.ui.screens.monitor.redactAddresses
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import java.io.IOException

/**
 * What one "back to before" did (AD-038 S3-5).
 *
 * Reasons live here and on screen only — never persisted (T-047b, "darf nicht").
 */
data class RestoreReport(
    val restored: List<LedgerEntry>,
    val pending: List<Pair<LedgerEntry, String>>,
    val autoApplyPausedFor: List<String>,
    /** Devices whose live LDAC level was asked back and read back. */
    val liveRestored: List<String> = emptyList(),
    /** Connected, but the level before was not readable: it stays until the next connect (M12). */
    val liveKeptUntilReconnect: List<String> = emptyList(),
)

/**
 * The way back: every setting the ledger holds goes back to its value before
 * this app first wrote it, with one action (AK-11, AD-033, AD-034).
 *
 * Only ever started by the user's confirmed tap — never on start, restart or
 * after a process death, and not reachable from outside the app.
 */
class SettingsRestore(
    private val ledger: SettingsLedger,
    private val globals: SecureSettingsController,
    private val hdAudio: HdAudioController,
    private val profiles: DeviceProfileSource,
    /** Read and write the profile store apart from [profiles], so a save failure (M10) can be tested without a real DataStore. */
    private val currentProfiles: suspend () -> List<DeviceProfile>,
    private val saveProfile: suspend (DeviceProfile) -> Unit,
    private val connected: suspend () -> List<BtAudioDevice>,
    private val requestLdac: suspend (address: String, quality: Long) -> CodecApplyOutcome,
) {

    suspend fun restoreAll(): RestoreReport {
        val entries = ledger.entries().getOrElse { return RestoreReport(emptyList(), emptyList(), emptyList()) }
        if (entries.isEmpty()) return RestoreReport(emptyList(), emptyList(), emptyList())

        // First, and awaited: a connect during the way back must not re-apply a
        // profile wish. If it cannot be paused, nothing is written (M10).
        val paused = try {
            pauseAutoApply()
        } catch (e: IOException) {
            return RestoreReport(emptyList(), entries.map { it to AUTO_APPLY_NOT_PAUSED }, emptyList())
        }

        val devices = runCatching { connected() }.getOrDefault(emptyList())
        val restored = mutableListOf<LedgerEntry>()
        val pending = mutableListOf<Pair<LedgerEntry, String>>()
        entries.sortedBy(::restoreOrder).forEach { entry ->
            // Same lock as the writers (M4, M11): write, read back and forget
            // one entry before anything else may record or write.
            val failure = ledger.lock.withLock {
                val reason = when (entry) {
                    is LedgerEntry.Global -> putBack(entry)
                    is LedgerEntry.HdAudio -> putBack(entry, devices)
                    is LedgerEntry.Ldac -> putBack(entry, devices)
                }
                reason ?: forget(entry)
            }
            if (failure == null) restored += entry else pending += entry to failure
        }

        val connectedLdac = restored.filterIsInstance<LedgerEntry.Ldac>()
            .filter { devices.addressOf(it.deviceKey) != null }
        return RestoreReport(
            restored = restored,
            pending = pending,
            autoApplyPausedFor = paused,
            liveRestored = connectedLdac.filter { it.priorLive != null }.map { it.deviceKey },
            liveKeptUntilReconnect = connectedLdac.filter { it.priorLive == null }.map { it.deviceKey },
        )
    }

    /** AD-034: only profiles whose wishes touch the audio path. */
    private suspend fun pauseAutoApply(): List<String> =
        currentProfiles()
            .filter { it.autoApply && it.touchesAudioPath() }
            .onEach { saveProfile(it.copy(autoApply = false)) }
            .map { it.name }

    /** Null: put back and confirmed by read-back. Otherwise the reason it was not. */
    private fun putBack(entry: LedgerEntry.Global): String? {
        val key = entry.key
        val prior = entry.prior
        return when {
            // M8: the ledger is data, and data is not trusted with arbitrary keys.
            BluetoothDeveloperOptions.byKey(key) == null && key != KEY_DISABLE_ABSOLUTE_VOLUME -> NOT_OURS
            !globals.isWritable() -> "WRITE_SECURE_SETTINGS is not granted"
            prior != null ->
                if (globals.write(key, prior)) null else "the value did not stick — this Android build may not support it"
            // M9: only "not set" confirms a delete; an unreadable key does not.
            globals.clear(key) && globals.readState(key) == SettingRead.Unset -> null
            else -> "the key could not be cleared — it still holds a value"
        }
    }

    private suspend fun putBack(entry: LedgerEntry.HdAudio, devices: List<BtAudioDevice>): String? {
        val address = devices.addressOf(entry.deviceKey) ?: return NOT_CONNECTED
        return when (val outcome = hdAudio.apply(address, entry.prior)) {
            is HdAudioOutcome.Applied -> null
            is HdAudioOutcome.NotObserved -> outcome.detail
            is HdAudioOutcome.Unavailable -> outcome.reason
        }
    }

    private suspend fun putBack(entry: LedgerEntry.Ldac, devices: List<BtAudioDevice>): String? {
        // A profile deleted since has no wish left that could re-apply.
        val profile = profiles.profileFor(entry.deviceKey)
        if (profile != null && profile.codecPreference != entry.priorWish) {
            try {
                saveProfile(profile.copy(codecPreference = entry.priorWish))
            } catch (e: IOException) {
                return PROFILE_NOT_SAVED
            }
            // A failed re-read must not pass as confirmation: DeviceProfileStore
            // degrades an unreadable store to an empty list, which would make a
            // profile look deleted — and for priorWish == null that reads the
            // same as "put back" (point 2, T-047j).
            val confirmed = profiles.profileFor(entry.deviceKey)
            if (confirmed == null || confirmed.codecPreference != entry.priorWish) return PROFILE_NOT_SAVED
        }

        // Not connected: the live level died with the connection.
        val address = devices.addressOf(entry.deviceKey) ?: return null
        // M12: nothing known to ask for; the report says it stays until the next connect.
        val level = entry.priorLive ?: return null
        val asked = if (level == LdacQuality.NONE) LdacQuality.ADAPTIVE else level
        return when (val outcome = requestLdac(address, asked)) {
            is CodecApplyOutcome.Applied -> null
            is CodecApplyOutcome.NotObserved -> "the link still reads ${outcome.observed}: ${outcome.detail}"
            is CodecApplyOutcome.Unavailable -> outcome.reason
        }
    }

    /** Only after a confirmed read-back (AD-007). */
    private suspend fun forget(entry: LedgerEntry): String? = try {
        ledger.remove(entry)
        null
    } catch (e: IOException) {
        RECORD_NOT_CLEARED
    } catch (e: JSONException) {
        RECORD_NOT_CLEARED
    }

    private companion object {
        const val AUTO_APPLY_NOT_PAUSED =
            "Autoapply could not be paused, so nothing was put back"
        const val NOT_OURS = "this app does not change that setting, so it does not write it back"
        const val NOT_CONNECTED = "the headphone is not connected — connect it and try again"
        const val PROFILE_NOT_SAVED = "the device profile could not be saved"
        const val RECORD_NOT_CLEARED =
            "it was put back, but the record of it could not be cleared"
    }
}

/** Globals, then HD audio, then LDAC (AD-038 S3-5). */
private fun restoreOrder(entry: LedgerEntry): Int = when (entry) {
    is LedgerEntry.Global -> 0
    is LedgerEntry.HdAudio -> 1
    is LedgerEntry.Ldac -> 2
}

private fun List<BtAudioDevice>.addressOf(deviceKey: String): String? =
    firstOrNull { DeviceKey.fromAddress(it.address) == deviceKey }?.address

/** AD-034, fields from `DeviceProfile.kt`. */
private fun DeviceProfile.touchesAudioPath(): Boolean =
    codecPreference != null || developerOptions.isNotEmpty() || absoluteVolumeEnabled != null ||
        absoluteVolumeSystemDefault || hdAudio != null

/** HD-audio wording shared with the apply sentences (`HdAudioSet`). */
internal fun hdAudioStateText(enabled: Boolean?): String = when (enabled) {
    true -> "on"
    false -> "off — this device is now SBC only"
    // The stack's "nobody has chosen". Named rather than rounded to "on",
    // because the two are undone differently.
    null -> "back to Android's own choice"
}

/**
 * The report as sentences, in the order of UI_SPEC S3-5: put back, not put
 * back with reason, Autoapply paused, codec family. [deviceName] never sees or
 * returns an address; every line still goes through [redactAddresses] (M13).
 */
fun RestoreReport.lines(deviceName: (String) -> String): List<String> {
    val putBack = restored.flatMap { entry ->
        when (entry) {
            is LedgerEntry.Global -> listOf(
                entry.prior
                    ?.let { "${globalName(entry.key)} put back to ${globalValue(entry.key, it)}." }
                    ?: "${globalName(entry.key)} put back — cleared, it was not set before this app touched it.",
            )

            is LedgerEntry.HdAudio -> listOf(
                "HD audio for ${deviceName(entry.deviceKey)} put back to ${hdAudioStateText(entry.prior.asEnabled())}.",
            )

            is LedgerEntry.Ldac -> {
                val device = deviceName(entry.deviceKey)
                listOfNotNull(
                    entry.priorWish
                        ?.let { "Codec preference for $device put back to ${codecText(it)}." }
                        ?: "Codec preference for $device put back — cleared, no preference was set before.",
                    entry.priorLive
                        ?.takeIf { entry.deviceKey in liveRestored }
                        ?.let { "LDAC quality for $device put back to ${LdacState.modeOf(it).label}." },
                )
            }
        }
    }
    val kept = liveKeptUntilReconnect.map {
        "LDAC quality for ${deviceName(it)} stays as it is until it connects again — " +
            "the level before this app touched it could not be read."
    }
    val notPutBack = pending.map { (entry, reason) -> "${entryName(entry, deviceName)} could not be put back yet: $reason." }
    val paused = autoApplyPausedFor.map {
        "Autoapply paused for $it. It will not re-apply its codec, developer options, absolute volume " +
            "or HD audio settings — and not its volume or EQ choices either — until you turn Autoapply back on."
    }
    val codecFamily = (restored + pending.map { it.first })
        .filterIsInstance<LedgerEntry.Ldac>()
        .map { it.deviceKey }
        .distinct()
        .map {
            "The codec family for ${deviceName(it)} goes back to the stack's own choice the next time it " +
                "connects — that did not happen as part of this action."
        }
    return (putBack + kept + notPutBack + paused + codecFamily).map(::redactAddresses)
}

private fun entryName(entry: LedgerEntry, deviceName: (String) -> String): String = when (entry) {
    is LedgerEntry.Global -> globalName(entry.key)
    is LedgerEntry.HdAudio -> "HD audio for ${deviceName(entry.deviceKey)}"
    is LedgerEntry.Ldac -> "Codec preference for ${deviceName(entry.deviceKey)}"
}

private fun globalName(key: String): String =
    if (key == KEY_DISABLE_ABSOLUTE_VOLUME) "Absolute volume" else BluetoothDeveloperOptions.byKey(key)?.label ?: key

/** `bluetooth_disable_absolute_volume` is inverted: "1" means absolute volume is off. */
private fun globalValue(key: String, value: String): String = when {
    key == KEY_DISABLE_ABSOLUTE_VOLUME -> if (value == "1") "off" else "on"
    else -> BluetoothDeveloperOptions.byKey(key)?.labelFor(value) ?: value
}

private fun codecText(preference: CodecPreference): String =
    listOfNotNull(
        preference.codec,
        preference.ldacQuality.takeIf { it != LdacQuality.NONE }?.let { LdacState.modeOf(it).label },
    ).joinToString(", ")

/** The D2 banner: shown while anything is held, whatever screen came first. */
sealed interface RestoreBanner {
    data object Hidden : RestoreBanner

    /** [tryAgain]: the last way back left entries behind. */
    data class Open(val count: Int, val tryAgain: Boolean) : RestoreBanner

    /** The ledger could not be read — not the same as "nothing held" (M3). */
    data object Unreadable : RestoreBanner
}

fun restoreBannerFor(entries: Result<List<LedgerEntry>>, lastRestore: RestoreReport?): RestoreBanner =
    entries.fold(
        onSuccess = { held ->
            if (held.isEmpty()) {
                RestoreBanner.Hidden
            } else {
                RestoreBanner.Open(held.size, tryAgain = lastRestore?.pending?.isNotEmpty() == true)
            }
        },
        onFailure = { RestoreBanner.Unreadable },
    )
