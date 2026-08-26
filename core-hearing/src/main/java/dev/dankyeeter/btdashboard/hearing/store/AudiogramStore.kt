package dev.dankyeeter.btdashboard.hearing.store

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.ClinicalAudiogram
import dev.dankyeeter.btdashboard.hearing.CompensationSource
import dev.dankyeeter.btdashboard.hearing.DerivedCalibration
import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
import dev.dankyeeter.btdashboard.hearing.fit.FitBaseline
import dev.dankyeeter.btdashboard.hearing.level.VolumeGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

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

    /**
     * Which runs the personal curve is built from. Empty means "not chosen".
     */
    val selectedRunIds: Flow<Set<String>> = appContext.hearingDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[KEY_SELECTED] ?: emptySet() }

    /**
     * The runs that actually feed the compensation: at most three.
     *
     * Three because a single run carries one lapse in attention and the median
     * of three is the smallest aggregate that can outvote it. More runs are
     * kept - they are worth having, and worth comparing - but averaging a
     * dozen sessions from different weeks would blur exactly the change one
     * would want to see.
     */
    val selectedRuns: Flow<List<AudiogramRun>> = combine(runs, selectedRunIds) { all, chosen ->
        selectionOf(all, chosen)
    }

    /**
     * The clinical audiogram, or null while none has been entered.
     *
     * One per person, not one per device — every other record in this store is
     * keyed to the headphone it was measured through, because a threshold
     * measured through a driver describes ear *and* driver together. A clinical
     * audiogram is the exception: it was measured on calibrated equipment at a
     * practice, it is a property of the ears alone, and it stays true whichever
     * headphones are connected. Keying it per device would produce copies that
     * could disagree about one pair of ears.
     */
    val clinicalAudiogram: Flow<ClinicalAudiogram?> = appContext.hearingDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> parseClinical(prefs[KEY_CLINICAL]) }

    /**
     * Which thresholds the compensation is built from. [CompensationSource.MEASURED]
     * until the user picks otherwise, and again whenever the stored name is
     * one this build does not know.
     */
    val compensationSource: Flow<CompensationSource> = appContext.hearingDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            runCatching { CompensationSource.valueOf(prefs[KEY_SOURCE].orEmpty()) }
                .getOrDefault(CompensationSource.MEASURED)
        }

    /**
     * The calibrations derived from the clinical audiogram, one per headphone.
     *
     * The opposite of [clinicalAudiogram] in exactly the way that makes both of
     * them right: an audiogram belongs to a pair of ears and there is one, a
     * derivation belongs to a pair of ears *and one headphone* and there is one
     * per headphone. Two headphones on one head produce two genuinely different
     * device responses, and neither of them is wrong.
     */
    val derivedCalibrations: Flow<List<DerivedCalibration>> = appContext.hearingDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> DerivedCalibrationJson.parse(prefs[KEY_DERIVED]) }

    /** The derivation for one headphone, or null — including for a null key. */
    fun derivedCalibrationFor(deviceKey: String?): Flow<DerivedCalibration?> =
        derivedCalibrations.map { all -> all.firstOrNull { it.deviceKey == deviceKey } }

    suspend fun currentDerivedCalibrations(): List<DerivedCalibration> = derivedCalibrations.first()

    /**
     * Stores one derivation, replacing any earlier one for the same headphone.
     *
     * Replace rather than append: a second derivation for one device is a
     * re-measurement, and keeping both would leave the preset repository with
     * two entries claiming the same id.
     */
    suspend fun saveDerivedCalibration(calibration: DerivedCalibration) {
        appContext.hearingDataStore.edit { prefs ->
            val existing = DerivedCalibrationJson.parse(prefs[KEY_DERIVED])
                .filterNot { it.deviceKey == calibration.deviceKey }
            prefs[KEY_DERIVED] = DerivedCalibrationJson.encode(existing + calibration)
        }
    }

    suspend fun clearDerivedCalibration(deviceKey: String) {
        appContext.hearingDataStore.edit { prefs ->
            val kept = DerivedCalibrationJson.parse(prefs[KEY_DERIVED])
                .filterNot { it.deviceKey == deviceKey }
            prefs[KEY_DERIVED] = DerivedCalibrationJson.encode(kept)
        }
    }

    suspend fun currentClinicalAudiogram(): ClinicalAudiogram? = clinicalAudiogram.first()

    /**
     * Stores the clinical audiogram, or clears it when nothing is filled in.
     *
     * An empty audiogram is not a record of anything, and keeping one would
     * leave the chart with a "clinic" legend entry that draws no curve. Saving
     * a cleared editor is therefore the same act as clearing.
     */
    suspend fun saveClinicalAudiogram(audiogram: ClinicalAudiogram) {
        if (audiogram.isEmpty) return clearClinicalAudiogram()
        appContext.hearingDataStore.edit { prefs ->
            prefs[KEY_CLINICAL] = encodeClinical(audiogram)
        }
    }

    suspend fun clearClinicalAudiogram() {
        appContext.hearingDataStore.edit { prefs ->
            prefs.remove(KEY_CLINICAL)
            // The source goes with it: a compensation built from an audiogram
            // that no longer exists would silently fall back to the measured
            // curve while the screen still said "Clinical audiogram".
            prefs.remove(KEY_SOURCE)
            // KEY_DERIVED deliberately stays. A derivation is a finished result,
            // not a view onto the audiogram: the clinical values were an input
            // that has already been consumed, and the device response it
            // produced is no less true once the form is deleted. Runs behave the
            // same way, and for the same reason.
        }
    }

    suspend fun setCompensationSource(source: CompensationSource) {
        appContext.hearingDataStore.edit { prefs -> prefs[KEY_SOURCE] = source.name }
    }

    suspend fun currentRuns(): List<AudiogramRun> = runs.first()

    suspend fun currentSelectedRuns(): List<AudiogramRun> = selectedRuns.first()

    suspend fun currentSelectedRunIds(): Set<String> = selectedRunIds.first()

    /**
     * Adds or removes one run from the selection. The toggle always takes.
     *
     * Deliberately uncapped. This used to refuse a fourth id by counting the
     * raw stored set - but that set is device-blind and volume-blind, while
     * what actually counts is decided per context by [selectionOf]. An id for a
     * run measured through another headphone, or at another test volume, still
     * occupied one of the three slots even though no screen ever showed it in a
     * curve, so the switch stopped responding with one or two runs visibly in
     * use and no way to tell why. A global cap over a per-context rule can only
     * ever disagree with what is on screen.
     *
     * The cap lives where the rule lives: [selectionOf] takes the last
     * [MAX_SELECTED] of the runs eligible right now, so a stored set of any
     * size still yields three, and the history screen disables the switch once
     * three *eligible* runs are in use. Both of those see the same context this
     * function does not.
     */
    suspend fun setRunSelected(id: String, selected: Boolean) {
        appContext.hearingDataStore.edit { prefs ->
            val current = prefs[KEY_SELECTED] ?: emptySet()
            prefs[KEY_SELECTED] = if (selected) current + id else current - id
        }
    }

    suspend fun currentFitBaseline(): FitBaseline = fitBaseline.first()

    suspend fun addRun(run: AudiogramRun) {
        appContext.hearingDataStore.edit { prefs ->
            val existing = parseRuns(prefs[KEY_RUNS]).filterNot { it.id == run.id }
            val merged = (existing + run).sortedBy { it.timestampMillis }.takeLast(MAX_RUNS)
            prefs[KEY_RUNS] = encodeRuns(merged)
            // The MAX_RUNS trim drops the oldest runs; their selection ids go
            // with them, same as on delete. selectionOf would ignore the dead
            // ids anyway, but a set that accumulates ghosts is a set nobody
            // can reason about.
            val kept = merged.map { it.id }.toSet()
            prefs[KEY_SELECTED]?.let { chosen -> prefs[KEY_SELECTED] = chosen intersect kept }
        }
    }

    suspend fun deleteRun(id: String) {
        appContext.hearingDataStore.edit { prefs ->
            prefs[KEY_RUNS] = encodeRuns(parseRuns(prefs[KEY_RUNS]).filterNot { it.id == id })
            // The selection is pruned with the run, not left to be filtered out
            // later. [selectionOf] already ignores ids with no run behind them,
            // but a set that keeps growing dead entries is a set nobody can
            // reason about - and it is exactly what let deleted runs hold slots
            // when the cap still lived in setRunSelected.
            prefs[KEY_SELECTED]?.let { chosen -> prefs[KEY_SELECTED] = chosen - id }
        }
    }

    suspend fun deleteAllRuns() {
        appContext.hearingDataStore.edit { prefs ->
            prefs[KEY_RUNS] = "[]"
            // No runs left for any id to name.
            prefs[KEY_SELECTED] = emptySet()
        }
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
                    put("deviceName", run.deviceName ?: JSONObject.NULL)
                    put("volume", run.volumeFraction)
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
                    deviceName = obj.optString("deviceName").takeIf { it.isNotBlank() && it != "null" },
                    // A run stored before the volume field existed was taken at
                    // the standard test level by definition - there was no
                    // other one to take it at.
                    volumeFraction = if (obj.has("volume")) {
                        obj.optDouble("volume")
                    } else {
                        VolumeGuard.TEST_VOLUME_FRACTION
                    },
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

    private fun encodeClinical(audiogram: ClinicalAudiogram): String = JSONObject().apply {
        put("left", JSONObject(audiogram.leftDbHl.mapKeys { it.key.toString() }))
        put("right", JSONObject(audiogram.rightDbHl.mapKeys { it.key.toString() }))
        put("measuredOn", audiogram.measuredOn)
        put("source", audiogram.source)
        put("savedAt", audiogram.savedAtMillis)
    }.toString()

    /**
     * Same defensive shape as every other parser here: anything unreadable
     * degrades to "no clinical audiogram" rather than throwing. A frequency key
     * that is not a number is dropped on its own, so one bad entry cannot cost
     * the whole record.
     */
    private fun parseClinical(raw: String?): ClinicalAudiogram? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            ClinicalAudiogram(
                leftDbHl = obj.readMap("left"),
                rightDbHl = obj.readMap("right"),
                measuredOn = obj.optString("measuredOn"),
                source = obj.optString("source"),
                savedAtMillis = obj.optLong("savedAt"),
            ).takeUnless { it.isEmpty }
        } catch (e: Exception) {
            Log.w(TAG, "stored clinical audiogram could not be parsed", e)
            null
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

    companion object {
        /** The most runs the compensation is ever built from. */
        const val MAX_SELECTED = 3

        /**
         * Resolves a stored selection against the runs that still exist.
         *
         * Nothing chosen means the three most recent, so the app works before
         * anyone has thought about this and after a deleted run leaves the
         * selection empty. Ids that no longer exist are ignored rather than
         * remembered.
         *
         * This is the only place [MAX_SELECTED] is enforced, and it has to be:
         * a stored set can hold any number of ids - from an older build, or
         * because ids for runs on other devices and other test levels are kept
         * rather than thrown away when the context changes. Whatever is in the
         * set, at most three eligible runs come out.
         */
        fun selectionOf(all: List<AudiogramRun>, chosen: Set<String>): List<AudiogramRun> =
            selectionOf(all, chosen, deviceKey = null)

        /**
         * Device-aware variant: only runs measured through [deviceKey] count.
         *
         * A hearing curve is a property of ear plus headphone together — the
         * same person measures differently through different drivers, and a
         * correction derived from one device applied to another corrects for
         * hardware that is not there. See [onDevice] for how runs with no
         * recorded device are treated.
         */
        fun selectionOf(
            all: List<AudiogramRun>,
            chosen: Set<String>,
            deviceKey: String?,
        ): List<AudiogramRun> {
            val sameDevice = onDevice(all, deviceKey)
            // Runs only mix at one test volume: thresholds in dBFS mean
            // nothing across volumes, so the newest run decides which window
            // is current and older runs at other volumes wait on the bench.
            val currentVolume = currentVolumeOf(sameDevice)
            val eligible = if (currentVolume == null) {
                sameDevice
            } else {
                sameDevice.filter { isSameVolume(it.volumeFraction, currentVolume) }
            }
            val explicit = eligible.filter { it.id in chosen }
            return if (explicit.isEmpty()) eligible.takeLast(MAX_SELECTED) else explicit.takeLast(MAX_SELECTED)
        }

        /**
         * The test volume that currently counts for [deviceKey], or null when
         * no run belongs to that device at all.
         *
         * The newest run on the device decides; every older run at a different
         * level is benched until one is taken at that level again. Public
         * because the history screen has to state the same rule [selectionOf]
         * applies - a row that looks selectable but is silently ignored by the
         * curve is a broken control, and the only way to keep the screen and
         * the rule from drifting apart is for both to ask the same function.
         */
        fun currentVolumeFor(all: List<AudiogramRun>, deviceKey: String?): Double? =
            currentVolumeOf(onDevice(all, deviceKey))

        /** The one line of rule both callers above share: newest run wins. */
        private fun currentVolumeOf(onDevice: List<AudiogramRun>): Double? =
            onDevice.lastOrNull()?.volumeFraction

        /**
         * Whether two runs were taken at the same test level.
         *
         * Compared with a tolerance, never with `==`. The values come from one
         * shared constant today, so exact equality happens to hold - but a
         * fraction that is computed, round-tripped through JSON at a different
         * precision, or derived from an index into the system volume steps
         * would stop matching itself, and the failure is silent: every run
         * looks benched and the curve quietly empties out. One refactor away
         * from never matching is not a comparison worth keeping.
         */
        fun isSameVolume(a: Double, b: Double): Boolean = abs(a - b) < VOLUME_TOLERANCE

        /**
         * Runs measured through [deviceKey], plus the device-less ones.
         *
         * Runs with no recorded device (older builds) stay usable everywhere:
         * locking them out would strand data nobody can re-attribute.
         */
        private fun onDevice(all: List<AudiogramRun>, deviceKey: String?): List<AudiogramRun> =
            if (deviceKey == null) {
                all
            } else {
                all.filter { it.deviceAddressHash == null || it.deviceAddressHash == deviceKey }
            }

        /** Far below any difference one media-volume step can make. */
        private const val VOLUME_TOLERANCE = 0.001

        private const val TAG = "AudiogramStore"
        private const val MAX_RUNS = 20
        private val KEY_RUNS = stringPreferencesKey("audiogram_runs_json")
        private val KEY_FIT_BASELINE = stringPreferencesKey("fit_baseline_json")
        private val KEY_SELECTED = stringSetPreferencesKey("audiogram_selected_ids")
        private val KEY_CLINICAL = stringPreferencesKey("clinical_audiogram_json")
        private val KEY_DERIVED = stringPreferencesKey("derived_calibrations_json")
        private val KEY_SOURCE = stringPreferencesKey("compensation_source")
    }
}
