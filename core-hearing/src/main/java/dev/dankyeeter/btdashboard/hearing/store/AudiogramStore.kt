package dev.dankyeeter.btdashboard.hearing.store

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
import dev.dankyeeter.btdashboard.hearing.fit.FitBaseline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.hearingDataStore: DataStore<Preferences> by preferencesDataStore(name = "hearing_runs")

/**
 * Local persistence for audiogram runs and the fit-check baseline.
 *
 * DataStore rather than Room on purpose: this is a handful of small records
 * with no queries beyond "give me all runs". Everything is serialised into a
 * single JSON string so a partial write can never leave half a run behind, and
 * any parse failure degrades to "no runs" instead of crashing. Nothing leaves
 * the device — there is no INTERNET permission.
 */
class AudiogramStore(context: Context) {

    private val appContext = context.applicationContext

    val runs: Flow<List<AudiogramRun>> = appContext.hearingDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> parseRuns(prefs[KEY_RUNS]) }

    val fitBaseline: Flow<FitBaseline> = appContext.hearingDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> parseBaseline(prefs[KEY_FIT_BASELINE]) }

    suspend fun currentRuns(): List<AudiogramRun> = runs.first()

    suspend fun currentFitBaseline(): FitBaseline = fitBaseline.first()

    suspend fun addRun(run: AudiogramRun) {
        appContext.hearingDataStore.edit { prefs ->
            val existing = parseRuns(prefs[KEY_RUNS]).filterNot { it.id == run.id }
            val merged = (existing + run).sortedBy { it.timestampMillis }.takeLast(MAX_RUNS)
            prefs[KEY_RUNS] = encodeRuns(merged)
        }
    }

    suspend fun deleteRun(id: String) {
        appContext.hearingDataStore.edit { prefs ->
            prefs[KEY_RUNS] = encodeRuns(parseRuns(prefs[KEY_RUNS]).filterNot { it.id == id })
        }
    }

    suspend fun deleteAllRuns() {
        appContext.hearingDataStore.edit { prefs -> prefs[KEY_RUNS] = "[]" }
    }

    suspend fun saveFitBaseline(baseline: FitBaseline) {
        appContext.hearingDataStore.edit { prefs ->
            prefs[KEY_FIT_BASELINE] = encodeBaseline(baseline)
        }
    }

    // --- serialisation -----------------------------------------------------

    private fun encodeRuns(runs: List<AudiogramRun>): String {
        val array = JSONArray()
        runs.forEach { run ->
            array.put(
                JSONObject().apply {
                    put("id", run.id)
                    put("timestamp", run.timestampMillis)
                    put("device", run.deviceAddressHash ?: JSONObject.NULL)
                    put("preset", run.calibrationPresetId)
                    put("anc", run.ancMode.name)
                    put("ambient", run.ambientNoiseDbA ?: JSONObject.NULL)
                    put("left", encodePoints(run.left))
                    put("right", encodePoints(run.right))
                },
            )
        }
        return array.toString()
    }

    private fun encodePoints(points: List<ThresholdPoint>): JSONArray {
        val array = JSONArray()
        points.forEach { point ->
            array.put(
                JSONObject().apply {
                    put("hz", point.frequencyHz)
                    put("db", point.thresholdDb)
                    put("responses", point.responseCount)
                    put("presentations", point.presentationCount)
                    put("converged", point.converged)
                },
            )
        }
        return array
    }

    private fun parseRuns(raw: String?): List<AudiogramRun> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                AudiogramRun(
                    id = obj.optString("id").ifBlank { return@mapNotNull null },
                    timestampMillis = obj.optLong("timestamp"),
                    deviceAddressHash = obj.optString("device").takeIf { it.isNotBlank() && it != "null" },
                    calibrationPresetId = obj.optString("preset"),
                    ancMode = runCatching { AncMode.valueOf(obj.optString("anc")) }.getOrDefault(AncMode.UNKNOWN),
                    ambientNoiseDbA = if (obj.isNull("ambient")) null else obj.optDouble("ambient"),
                    left = parsePoints(obj.optJSONArray("left")),
                    right = parsePoints(obj.optJSONArray("right")),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "stored runs could not be parsed, starting empty", e)
            emptyList()
        }
    }

    private fun parsePoints(array: JSONArray?): List<ThresholdPoint> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            ThresholdPoint(
                frequencyHz = obj.optInt("hz"),
                thresholdDb = obj.optDouble("db"),
                responseCount = obj.optInt("responses"),
                presentationCount = obj.optInt("presentations"),
                converged = obj.optBoolean("converged", true),
            )
        }
    }

    private fun encodeBaseline(baseline: FitBaseline): String = JSONObject().apply {
        put("left", JSONObject(baseline.left.mapKeys { it.key.toString() }))
        put("right", JSONObject(baseline.right.mapKeys { it.key.toString() }))
    }.toString()

    private fun parseBaseline(raw: String?): FitBaseline {
        if (raw.isNullOrBlank()) return FitBaseline()
        return try {
            val obj = JSONObject(raw)
            FitBaseline(left = obj.readMap("left"), right = obj.readMap("right"))
        } catch (e: Exception) {
            Log.w(TAG, "stored fit baseline could not be parsed", e)
            FitBaseline()
        }
    }

    private fun JSONObject.readMap(name: String): Map<Int, Double> {
        val nested = optJSONObject(name) ?: return emptyMap()
        return nested.keys().asSequence().mapNotNull { key ->
            key.toIntOrNull()?.let { it to nested.optDouble(key) }
        }.toMap()
    }

    private companion object {
        const val TAG = "AudiogramStore"
        const val MAX_RUNS = 20
        val KEY_RUNS = stringPreferencesKey("audiogram_runs_json")
        val KEY_FIT_BASELINE = stringPreferencesKey("fit_baseline_json")
    }
}
