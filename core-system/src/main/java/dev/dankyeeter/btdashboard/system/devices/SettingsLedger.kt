package dev.dankyeeter.btdashboard.system.devices

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The value a setting had before this app first wrote it (AD-033).
 *
 * Only the first value counts: a later write must not replace the baseline,
 * or "back to before" would mean "back to the app's own previous write".
 * Devices are identified by their [DeviceKey], never by address or name.
 */
sealed interface LedgerEntry {
    /**
     * `Settings.Global`: the developer options and `bluetooth_disable_absolute_volume`.
     * [prior] null means the key was not set, so the way back is a delete.
     */
    data class Global(val key: String, val prior: String?) : LedgerEntry

    /** Persisted by the stack per device (`a2dpOptionalCodecsEnabled`). */
    data class HdAudio(val deviceKey: String, val prior: HdAudioPreference) : LedgerEntry

    /**
     * Codec wish of the device's profile and live LDAC level. Despite the name
     * (kept, like the JSON type `ldac`, to spare a format migration), [priorWish]
     * may be any codec family or System Default: the profile applier records
     * every codec wish it writes (AD-041). [priorLive] null: not readable or not
     * connected before.
     */
    data class Ldac(val deviceKey: String, val priorWish: CodecPreference?, val priorLive: Long?) : LedgerEntry
}

/** Same setting = same type and same key/deviceKey. */
private val LedgerEntry.settingId: String
    get() = when (this) {
        is LedgerEntry.Global -> "global:$key"
        is LedgerEntry.HdAudio -> "hdAudio:$deviceKey"
        is LedgerEntry.Ldac -> "ldac:$deviceKey"
    }

/** Adds [entry] unless the same setting already has one; the existing baseline wins. */
fun List<LedgerEntry>.withBaseline(entry: LedgerEntry): List<LedgerEntry> =
    if (any { it.settingId == entry.settingId }) this else this + entry

interface SettingsLedger {
    /**
     * Held across "record, then write" for one setting, so the way back can
     * never run between the two and leave a write without a baseline.
     */
    val lock: Mutex

    /**
     * Persists [entry] unless the same setting already has a baseline.
     *
     * True: a baseline is held (new or earlier), the caller may write.
     * False: nothing could be persisted, the caller must **not** write.
     */
    suspend fun recordIfAbsent(entry: LedgerEntry): Boolean

    /** A failure means the ledger could not be read — never an empty list in disguise. */
    suspend fun entries(): Result<List<LedgerEntry>>

    /** [entries] as it changes. The default answers once, for ledgers that cannot be watched. */
    val changes: Flow<Result<List<LedgerEntry>>> get() = flow { emit(entries()) }

    /** Only after the way back is confirmed by read-back. Throws if the ledger cannot be read. */
    suspend fun remove(entry: LedgerEntry)

    /**
     * Only after the user confirmed it (AD-042). Replaces the content with an
     * empty list, but only if it cannot be decoded in the same transaction.
     * True: replaced. False: readable, or not writable — nothing changed.
     */
    suspend fun discardUnreadable(): Boolean
}

/**
 * Keeps nothing and lets every write through that the ledger rules allow.
 * The default for callers and tests that predate the ledger, in the same way
 * [UnavailableHdAudioController] is.
 */
object NoSettingsLedger : SettingsLedger {
    override val lock = Mutex()
    override suspend fun recordIfAbsent(entry: LedgerEntry): Boolean = true
    override suspend fun entries(): Result<List<LedgerEntry>> = Result.success(emptyList())
    override suspend fun remove(entry: LedgerEntry) = Unit
    override suspend fun discardUnreadable(): Boolean = false
}

/** Reads the value before and records it. False: the caller skips the write. */
suspend fun SettingsLedger.recordGlobal(settings: SecureSettingsController, key: String): Boolean =
    when (val read = settings.readState(key)) {
        is SettingRead.Value -> recordIfAbsent(LedgerEntry.Global(key, read.value))
        SettingRead.Unset -> recordIfAbsent(LedgerEntry.Global(key, prior = null))
        SettingRead.Unreadable -> false
    }

/** [HdAudioState.Unreadable] -> false, unless the device already has a baseline. */
suspend fun SettingsLedger.recordHdAudio(deviceKey: String, before: HdAudioState): Boolean =
    when (before) {
        is HdAudioState.Known -> recordIfAbsent(LedgerEntry.HdAudio(deviceKey, before.asPreference()))
        is HdAudioState.Unreadable ->
            entries().getOrNull().orEmpty().any { it is LedgerEntry.HdAudio && it.deviceKey == deviceKey }
    }

private fun HdAudioState.Known.asPreference(): HdAudioPreference = when (enabled) {
    true -> HdAudioPreference.ENABLE
    false -> HdAudioPreference.DISABLE
    null -> HdAudioPreference.SYSTEM_DEFAULT
}

/**
 * The ledger in DataStore, one JSON array under one key — the same shape as
 * [DeviceProfileStore], so a partial write never leaves half an entry.
 *
 * Unlike the profile store, an undecodable ledger is **not** degraded to empty:
 * that would read as "nothing to restore" and let the next write record the
 * app's own value as the baseline. Reads report the failure, writes refuse.
 *
 * Excluded from backup and device transfer (`backup_rules.xml`,
 * `data_extraction_rules.xml`): restored on another phone, it would put back
 * values of a different device.
 */
class SettingsLedgerStore(private val dataStore: DataStore<Preferences>) : SettingsLedger {

    override val lock = Mutex()

    override suspend fun recordIfAbsent(entry: LedgerEntry): Boolean = try {
        // Check, add and write in one edit, so two callers cannot both see "absent".
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_ENTRIES])
            val updated = current.withBaseline(entry)
            if (updated !== current) prefs[KEY_ENTRIES] = encode(updated)
        }
        true
    } catch (e: IOException) {
        // Class name only: a JSONException message carries the whole ledger text (SR-033).
        Log.w(TAG, "ledger not writable, refusing the write (${e.javaClass.simpleName})")
        false
    } catch (e: JSONException) {
        Log.w(TAG, "ledger not decodable, refusing the write (${e.javaClass.simpleName})")
        false
    }

    override val changes: Flow<Result<List<LedgerEntry>>> = dataStore.data
        .map { prefs ->
            try {
                Result.success(decode(prefs[KEY_ENTRIES]))
            } catch (e: JSONException) {
                Result.failure(e)
            }
        }
        .catch { e -> if (e is IOException) emit(Result.failure(e)) else throw e }

    override suspend fun entries(): Result<List<LedgerEntry>> = changes.first()

    override suspend fun remove(entry: LedgerEntry) {
        dataStore.edit { prefs ->
            prefs[KEY_ENTRIES] = encode(decode(prefs[KEY_ENTRIES]).filterNot { it.settingId == entry.settingId })
        }
    }

    override suspend fun discardUnreadable(): Boolean = lock.withLock {
        try {
            var replaced = false
            dataStore.edit { prefs ->
                try {
                    decode(prefs[KEY_ENTRIES])
                } catch (e: JSONException) {
                    prefs[KEY_ENTRIES] = encode(emptyList())
                    replaced = true
                }
            }
            replaced
        } catch (e: IOException) {
            Log.w(TAG, "ledger not writable, nothing discarded (${e.javaClass.simpleName})")
            false
        }
    }

    private fun encode(entries: List<LedgerEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                when (entry) {
                    is LedgerEntry.Global -> JSONObject()
                        .put("type", TYPE_GLOBAL)
                        .put("key", entry.key)
                        .putOpt("prior", entry.prior)

                    is LedgerEntry.HdAudio -> JSONObject()
                        .put("type", TYPE_HD_AUDIO)
                        .put("deviceKey", entry.deviceKey)
                        .put("prior", entry.prior.name)

                    is LedgerEntry.Ldac -> JSONObject()
                        .put("type", TYPE_LDAC)
                        .put("deviceKey", entry.deviceKey)
                        .putOpt("priorWish", entry.priorWish?.let(::encodeCodec))
                        .putOpt("priorLive", entry.priorLive)
                },
            )
        }
        return array.toString()
    }

    /** Throws [JSONException] on anything it cannot read in full — never drops an entry. */
    private fun decode(raw: String?): List<LedgerEntry> {
        if (raw == null) return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            when (val type = o.getString("type")) {
                TYPE_GLOBAL -> LedgerEntry.Global(
                    key = o.getString("key"),
                    prior = if (o.isNull("prior")) null else o.getString("prior"),
                )

                TYPE_HD_AUDIO -> LedgerEntry.HdAudio(
                    deviceKey = o.getString("deviceKey"),
                    prior = o.getString("prior").let { name ->
                        HdAudioPreference.entries.firstOrNull { it.name == name }
                            ?: throw JSONException("unknown HD audio value $name")
                    },
                )

                TYPE_LDAC -> LedgerEntry.Ldac(
                    deviceKey = o.getString("deviceKey"),
                    priorWish = if (o.isNull("priorWish")) null else parseCodec(o.getJSONObject("priorWish")),
                    priorLive = if (o.isNull("priorLive")) null else o.getLong("priorLive"),
                )

                else -> throw JSONException("unknown ledger entry type $type")
            }
        }
    }

    companion object {
        /** DataStore stores this as `files/datastore/settings_ledger.preferences_pb`. */
        const val DATASTORE_NAME = "settings_ledger"

        /**
         * What a corrupt file is replaced with (AD-042): not a JSON array, so the
         * ledger still reads as unreadable (M3), but can be edited — and so
         * discarded by the user — again.
         */
        const val CORRUPT_MARKER = "corrupt ledger file replaced"

        /** The only way the ledger's DataStore is built: once in `SystemGraph` (M6), and by the tests. */
        fun createDataStore(produceFile: () -> File): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { e ->
                    Log.w(TAG, "ledger file corrupt, marked unreadable (${e.javaClass.simpleName})")
                    preferencesOf(KEY_ENTRIES to CORRUPT_MARKER)
                },
                produceFile = produceFile,
            )

        private const val TAG = "SettingsLedger"
        private const val TYPE_GLOBAL = "global"
        private const val TYPE_HD_AUDIO = "hdAudio"
        private const val TYPE_LDAC = "ldac"
        private val KEY_ENTRIES = stringPreferencesKey("entries_json")
    }
}
