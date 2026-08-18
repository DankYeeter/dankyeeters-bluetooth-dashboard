package dev.dankyeeter.btdashboard.system.devices

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.deviceProfileDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "device_profiles")

/** Read side of the device-profile storage — the part the applier needs. */
interface DeviceProfileSource {
    suspend fun profileFor(deviceKey: String): DeviceProfile?
}

/**
 * Per-device profiles in DataStore, encoded as one JSON array under a single
 * key. Same reasoning as [dev.dankyeeter.btdashboard.system.persist.EqSettingsStore]:
 * a single key means a partial write can never leave half a profile behind.
 *
 * Every parse step is defensive. A profile list that fails to decode degrades
 * to "no profiles" — losing convenience settings is acceptable, crashing on a
 * headphone connect is not.
 */
class DeviceProfileStore(context: Context) : DeviceProfileSource {

    private val appContext = context.applicationContext

    val profiles: Flow<List<DeviceProfile>> = appContext.deviceProfileDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { parse(it[KEY_PROFILES]) }

    suspend fun current(): List<DeviceProfile> = profiles.first()

    override suspend fun profileFor(deviceKey: String): DeviceProfile? =
        current().firstOrNull { it.deviceKey == deviceKey }

    /** Inserts or replaces by [DeviceProfile.deviceKey]. */
    suspend fun save(profile: DeviceProfile) {
        val clean = profile.sanitized()
        appContext.deviceProfileDataStore.edit { prefs ->
            val kept = parse(prefs[KEY_PROFILES]).filterNot { it.deviceKey == clean.deviceKey }
            prefs[KEY_PROFILES] = encode((kept + clean).takeLast(MAX_PROFILES))
        }
    }

    suspend fun delete(deviceKey: String) {
        appContext.deviceProfileDataStore.edit { prefs ->
            prefs[KEY_PROFILES] = encode(parse(prefs[KEY_PROFILES]).filterNot { it.deviceKey == deviceKey })
        }
    }

    /**
     * Records that a device was seen, creating a stub profile the first time so
     * the editor has something to show. A stub does nothing on connect: every
     * action field is null, so "seen once" never turns into "changed my volume".
     */
    suspend fun noteSeen(deviceKey: String, fallbackName: String, atMillis: Long) {
        appContext.deviceProfileDataStore.edit { prefs ->
            val existing = parse(prefs[KEY_PROFILES])
            val previous = existing.firstOrNull { it.deviceKey == deviceKey }
            val updated = previous?.copy(lastSeenAtMillis = atMillis)
                ?: DeviceProfile(
                    deviceKey = deviceKey,
                    name = fallbackName,
                    lastSeenAtMillis = atMillis,
                ).sanitized()
            prefs[KEY_PROFILES] = encode(
                (existing.filterNot { it.deviceKey == deviceKey } + updated).takeLast(MAX_PROFILES),
            )
        }
    }

    // ---- codec ---------------------------------------------------------------

    private fun encode(profiles: List<DeviceProfile>): String {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(
                JSONObject().apply {
                    put("deviceKey", p.deviceKey)
                    put("name", p.name)
                    putOpt("calibrationPresetId", p.calibrationPresetId)
                    putOpt("compensationProfileId", p.compensationProfileId)
                    putOpt("mediaVolumePercent", p.mediaVolumePercent)
                    putOpt("absoluteVolumeEnabled", p.absoluteVolumeEnabled)
                    put("autoApply", p.autoApply)
                    put("lastSeenAtMillis", p.lastSeenAtMillis)
                },
            )
        }
        return array.toString()
    }

    private fun parse(raw: String?): List<DeviceProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val key = o.optString("deviceKey").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DeviceProfile(
                    deviceKey = key,
                    name = o.optString("name", "Unnamed device"),
                    calibrationPresetId = o.optNullableString("calibrationPresetId"),
                    compensationProfileId = o.optNullableString("compensationProfileId"),
                    mediaVolumePercent = if (o.isNull("mediaVolumePercent")) {
                        null
                    } else {
                        o.optInt("mediaVolumePercent")
                    },
                    absoluteVolumeEnabled = if (o.isNull("absoluteVolumeEnabled")) {
                        null
                    } else {
                        o.optBoolean("absoluteVolumeEnabled")
                    },
                    autoApply = o.optBoolean("autoApply", true),
                    lastSeenAtMillis = o.optLong("lastSeenAtMillis"),
                ).sanitized()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unreadable device profiles", e)
            emptyList()
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "DeviceProfileStore"
        const val MAX_PROFILES = 32
        val KEY_PROFILES = stringPreferencesKey("device_profiles_json")
    }
}
