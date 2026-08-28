package dev.dankyeeter.btdashboard.hearing.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.preferenceDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "preference_profiles")

/**
 * The listening-preference curves, one per headphone.
 *
 * Its own DataStore file rather than another key in [AudiogramStore], and the
 * reason is what the two stores hold. Everything in that one is a *measurement*
 * — thresholds, a clinical form, a device response derived from them. This is a
 * record of taste: it is rewritten every time a song-run finishes, it is
 * expected to change as somebody's ears and music change, and it has no
 * business sharing a write path with data that describes a person's hearing.
 *
 * One JSON string for the whole list, like every other store in this package, so
 * that a partial write can never leave half a pool behind, and any parse failure
 * degrades to "no preference profiles" rather than crashing. Nothing leaves the
 * device — the app has no INTERNET permission.
 */
class PreferenceProfileStore(context: Context) {

    private val appContext = context.applicationContext

    val profiles: Flow<List<PreferenceProfile>> = appContext.preferenceDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> PreferenceProfileJson.parse(prefs[KEY_PROFILES]) }

    suspend fun current(): List<PreferenceProfile> = profiles.first()

    /** The profile for one headphone, or null — including for a null key. */
    fun profileFor(deviceKey: String?): Flow<PreferenceProfile?> =
        profiles.map { all -> all.firstOrNull { it.deviceKey == deviceKey } }

    /**
     * Stores one profile, replacing any earlier one for the same headphone.
     *
     * Replace rather than append, exactly as
     * [AudiogramStore.saveDerivedCalibration] does: two disagreeing answers
     * about one headphone is not a state anybody can act on, and the pool
     * inside the profile is already where several opinions live.
     */
    suspend fun save(profile: PreferenceProfile) {
        appContext.preferenceDataStore.edit { prefs ->
            val kept = PreferenceProfileJson.parse(prefs[KEY_PROFILES])
                .filterNot { it.deviceKey == profile.deviceKey }
            prefs[KEY_PROFILES] = PreferenceProfileJson.encode(kept + profile)
        }
    }

    suspend fun delete(deviceKey: String) {
        appContext.preferenceDataStore.edit { prefs ->
            val kept = PreferenceProfileJson.parse(prefs[KEY_PROFILES])
                .filterNot { it.deviceKey == deviceKey }
            prefs[KEY_PROFILES] = PreferenceProfileJson.encode(kept)
        }
    }

    private companion object {
        val KEY_PROFILES = stringPreferencesKey("preference_profiles_json")
    }
}
