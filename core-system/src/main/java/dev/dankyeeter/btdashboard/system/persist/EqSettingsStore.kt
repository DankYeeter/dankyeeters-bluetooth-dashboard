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
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
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
            prefs[KEY_LAYOUT] = clean.layout.id
            prefs[KEY_LEFT] = clean.leftGainsDb.joinToString(";")
            prefs[KEY_RIGHT] = clean.rightGainsDb.joinToString(";")
            prefs[KEY_PRE_GAIN] = clean.preGainDb
            prefs[KEY_LIMITER] = clean.limiterEnabled
            prefs[KEY_AUTO_HEADROOM] = clean.autoHeadroom
            prefs[KEY_LOUDNESS] = clean.loudnessRestoration
            // Only the switch. `tiltGainsDb` is derived from the media volume
            // at the moment it is applied, so storing it would mean restoring a
            // correction for a volume that is no longer set.
            prefs[KEY_VOLUME_TILT] = clean.volumeAwareTilt
        }
    }

    /** Id of the active compensation profile, or null if the EQ is manual. */
    val activeProfileId: Flow<String?> = context.eqDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_PROFILE_ID] }

    /** One-shot read of [activeProfileId] (used by the backup export). */
    suspend fun activeProfileIdOrNull(): String? = activeProfileId.first()

    suspend fun setActiveProfileId(id: String?) {
        context.eqDataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_PROFILE_ID) else prefs[KEY_PROFILE_ID] = id
        }
    }

    private fun Preferences.toEqSettings(): EqSettings {
        val layout = EqBandLayout.fromId(this[KEY_LAYOUT])
        return EqSettings(
            enabled = this[KEY_ENABLED] ?: false,
            layout = layout,
            leftGainsDb = parseGains(this[KEY_LEFT], layout),
            rightGainsDb = parseGains(this[KEY_RIGHT], layout),
            preGainDb = this[KEY_PRE_GAIN] ?: 0f,
            limiterEnabled = this[KEY_LIMITER] ?: true,
            autoHeadroom = this[KEY_AUTO_HEADROOM] ?: true,
            loudnessRestoration = this[KEY_LOUDNESS] ?: false,
            volumeAwareTilt = this[KEY_VOLUME_TILT] ?: false,
        )
    }

    /**
     * Defensive: a corrupted string degrades to flat. A string whose length
     * belongs to a *different* layout is resampled rather than discarded — the
     * stored curve is still the user's curve, only at another resolution, and
     * throwing it away on a layout change would be the wrong kind of safe.
     */
    private fun parseGains(raw: String?, layout: EqBandLayout): List<Float> {
        val parsed = raw?.split(";")?.mapNotNull { it.toFloatOrNull() } ?: emptyList()
        if (parsed.size == layout.bandCount) return parsed
        val source = EqBandLayout.entries.firstOrNull { it.bandCount == parsed.size }
            ?: return List(layout.bandCount) { 0f }
        return EqBandLayout.resample(parsed, source, layout)
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("eq_enabled")
        // Added later than the fields above; missing keys fall back to the
        // model's defaults, so an old store reads cleanly.
        val KEY_AUTO_HEADROOM = booleanPreferencesKey("eq_auto_headroom")
        val KEY_LOUDNESS = booleanPreferencesKey("eq_loudness_restoration")
        val KEY_VOLUME_TILT = booleanPreferencesKey("eq_volume_aware_tilt")
        val KEY_LEFT = stringPreferencesKey("eq_gains_left")
        val KEY_RIGHT = stringPreferencesKey("eq_gains_right")
        val KEY_PRE_GAIN = floatPreferencesKey("eq_pre_gain_db")
        val KEY_LIMITER = booleanPreferencesKey("eq_limiter_enabled")
        val KEY_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val KEY_LAYOUT = stringPreferencesKey("eq_band_layout")
    }
}
