package dev.dankyeeter.btdashboard.hearing.store

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.CompensationProfile
import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.profileDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "compensation_profiles")

/**
 * Named compensation profiles, stored locally as JSON in DataStore. Nothing
 * leaves the device — the app has no INTERNET permission.
 *
 * A profile is a *complete* snapshot (audiogram + preset + slider + resulting
 * EQ) so that loading one never depends on the current hearing-test data still
 * being around, and so a later re-test cannot silently change an old profile.
 */
class CompensationProfileStore(context: Context) {

    private val appContext = context.applicationContext

    val profiles: Flow<List<CompensationProfile>> = appContext.profileDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { parse(it[KEY_PROFILES]) }

    suspend fun current(): List<CompensationProfile> = profiles.first()

    /** Inserts or replaces by id. */
    suspend fun save(profile: CompensationProfile) {
        appContext.profileDataStore.edit { prefs ->
            val kept = parse(prefs[KEY_PROFILES]).filterNot { it.id == profile.id }
            val merged = (kept + profile).sortedBy { it.createdAtMillis }.takeLast(MAX_PROFILES)
            prefs[KEY_PROFILES] = encode(merged)
        }
    }

    suspend fun delete(id: String) {
        appContext.profileDataStore.edit { prefs ->
            prefs[KEY_PROFILES] = encode(parse(prefs[KEY_PROFILES]).filterNot { it.id == id })
        }
    }

    // ---- codec ---------------------------------------------------------------

    private fun encode(profiles: List<CompensationProfile>): String {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("createdAtMillis", p.createdAtMillis)
                    put("calibrationPresetId", p.calibrationPresetId)
                    put("ancMode", p.ancMode.name)
                    put("intensity", p.intensity.toDouble())
                    put("partialFactor", p.partialFactor.toDouble())
                    p.audiogram?.let { audiogram ->
                        put("runIds", JSONArray(audiogram.runIds))
                        put("left", encodePoints(audiogram.left))
                        put("right", encodePoints(audiogram.right))
                    }
                    put("eq", encodeEq(p.eq))
                },
            )
        }
        return array.toString()
    }

    private fun encodePoints(points: List<ThresholdPoint>): JSONArray {
        val array = JSONArray()
        points.forEach {
            array.put(
                JSONObject().apply {
                    put("f", it.frequencyHz)
                    put("t", it.thresholdDb)
                    put("converged", it.converged)
                },
            )
        }
        return array
    }

    private fun encodeEq(eq: EqSettings): JSONObject = JSONObject().apply {
        put("enabled", eq.enabled)
        put("left", JSONArray(eq.leftGainsDb.map { it.toDouble() }))
        put("right", JSONArray(eq.rightGainsDb.map { it.toDouble() }))
        put("preGainDb", eq.preGainDb.toDouble())
        put("limiterEnabled", eq.limiterEnabled)
    }

    /** Defensive throughout: a corrupt entry is dropped, never fatal. */
    private fun parse(raw: String?): List<CompensationProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CompensationProfile(
                    id = id,
                    name = o.optString("name", id),
                    createdAtMillis = o.optLong("createdAtMillis"),
                    // No "left"/"right" keys means a hand-tuned profile, not a
                    // corrupt one: it is stored with its EQ curve and nothing else.
                    audiogram = if (!o.has("left") && !o.has("right")) {
                        null
                    } else {
                        Audiogram(
                            runIds = o.optJSONArray("runIds").toStringList(),
                            left = parsePoints(o.optJSONArray("left")),
                            right = parsePoints(o.optJSONArray("right")),
                        )
                    },
                    calibrationPresetId = o.optString("calibrationPresetId"),
                    ancMode = runCatching { AncMode.valueOf(o.optString("ancMode")) }
                        .getOrDefault(AncMode.UNKNOWN),
                    intensity = o.optDouble("intensity", 0.6).toFloat(),
                    partialFactor = o.optDouble("partialFactor", 1.0).toFloat(),
                    eq = parseEq(o.optJSONObject("eq")),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unreadable compensation profiles", e)
            emptyList()
        }
    }

    private fun parsePoints(array: JSONArray?): List<ThresholdPoint> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            ThresholdPoint(
                frequencyHz = o.optInt("f"),
                thresholdDb = o.optDouble("t"),
                converged = o.optBoolean("converged", true),
            )
        }
    }

    private fun parseEq(o: JSONObject?): EqSettings {
        if (o == null) return EqSettings.FLAT
        return EqSettings(
            enabled = o.optBoolean("enabled", false),
            leftGainsDb = o.optJSONArray("left").toGains(),
            rightGainsDb = o.optJSONArray("right").toGains(),
            preGainDb = o.optDouble("preGainDb", 0.0).toFloat(),
            limiterEnabled = o.optBoolean("limiterEnabled", true),
        ).sanitized()
    }

    private fun JSONArray?.toGains(): List<Float> {
        val parsed = (0 until (this?.length() ?: 0)).map { this!!.optDouble(it, 0.0).toFloat() }
        return if (parsed.size == EqBands.COUNT) parsed else List(EqBands.COUNT) { 0f }
    }

    private fun JSONArray?.toStringList(): List<String> =
        (0 until (this?.length() ?: 0)).map { this!!.optString(it) }

    private companion object {
        const val TAG = "CompensationProfileStore"
        const val MAX_PROFILES = 20
        val KEY_PROFILES = stringPreferencesKey("compensation_profiles_json")
    }
}
