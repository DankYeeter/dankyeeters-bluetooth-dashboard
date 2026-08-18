package dev.dankyeeter.btdashboard.system.persist

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.eqDataStore: DataStore<Preferences> by preferencesDataStore(name = "eq_settings")

/**
 * Persists the EQ state locally with DataStore. Nothing leaves the device;
 * there is no INTERNET permission.
 *
 * Band gains are stored as a semicolon-separated string rather than 20 separate
 * keys so that a partial write can never leave a half-updated curve behind.
 */
class EqSettingsStore(private val context: Context) {

    val settings: Flow<EqSettings> = context.eqDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toEqSettings() }

    suspend fun current(): EqSettings = settings.first()

    suspend fun save(value: EqSettings) {
        val clean = value.sanitized()
        context.eqDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = clean.enabled
            prefs[KEY_LEFT] = clean.leftGainsDb.joinToString(";")
            prefs[KEY_RIGHT] = clean.rightGainsDb.joinToString(";")
            prefs[KEY_PRE_GAIN] = clean.preGainDb
            prefs[KEY_LIMITER] = clean.limiterEnabled
        }
    }

    /** Id of the active compensation profile, or null if the EQ is manual. */
    val activeProfileId: Flow<String?> = context.eqDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_PROFILE_ID] }

    suspend fun setActiveProfileId(id: String?) {
        context.eqDataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_PROFILE_ID) else prefs[KEY_PROFILE_ID] = id
        }
    }

    private fun Preferences.toEqSettings(): EqSettings {
        val left = parseGains(this[KEY_LEFT])
        val right = parseGains(this[KEY_RIGHT])
        return EqSettings(
            enabled = this[KEY_ENABLED] ?: false,
            leftGainsDb = left,
            rightGainsDb = right,
            preGainDb = this[KEY_PRE_GAIN] ?: 0f,
            limiterEnabled = this[KEY_LIMITER] ?: true,
        )
    }

    /** Defensive: a corrupted or version-shifted string degrades to flat. */
    private fun parseGains(raw: String?): List<Float> {
        val parsed = raw?.split(";")?.mapNotNull { it.toFloatOrNull() } ?: emptyList()
        return if (parsed.size == EqBands.COUNT) parsed else List(EqBands.COUNT) { 0f }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("eq_enabled")
        val KEY_LEFT = stringPreferencesKey("eq_gains_left")
        val KEY_RIGHT = stringPreferencesKey("eq_gains_right")
        val KEY_PRE_GAIN = floatPreferencesKey("eq_pre_gain_db")
        val KEY_LIMITER = booleanPreferencesKey("eq_limiter_enabled")
        val KEY_PROFILE_ID = stringPreferencesKey("active_profile_id")
    }
}
