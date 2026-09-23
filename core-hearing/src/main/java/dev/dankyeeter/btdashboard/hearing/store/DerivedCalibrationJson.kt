package dev.dankyeeter.btdashboard.hearing.store

import dev.dankyeeter.btdashboard.hearing.DerivedCalibration
import dev.dankyeeter.btdashboard.hearing.TEST_FREQUENCIES_HZ
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialisation for [DerivedCalibration], on `org.json` like every other record
 * in [AudiogramStore].
 *
 * A derivation is the only thing in the module built out of a measurement the
 * user cannot repeat without another appointment at a practice, and a silent
 * encoding bug would lose it. So its round trip is tested under Robolectric,
 * which runs Android's own `org.json` rather than a stand-in (AD-026). A string
 * it cannot read degrades to "no derivations" exactly like every other parser
 * in [AudiogramStore] — never to a half-read record.
 */
internal object DerivedCalibrationJson {

    fun encode(calibrations: List<DerivedCalibration>): String =
        JSONArray(calibrations.map(::toJson)).toString()

    /**
     * Reads back what [encode] wrote. Anything unreadable yields an empty list;
     * a single malformed entry inside a readable array is dropped on its own,
     * the same "one bad row must not cost the record" rule the clinical parser
     * follows.
     */
    fun parse(raw: String?): List<DerivedCalibration> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::toCalibration) }
    }

    private fun toJson(calibration: DerivedCalibration): JSONObject = JSONObject()
        .put("deviceKey", calibration.deviceKey)
        .put("deviceName", calibration.deviceName ?: JSONObject.NULL)
        .put("responseDeviationDb", JSONArray(calibration.responseDeviationDb.map(::finiteOrZero)))
        .put("earSpreadDb", finiteOrZero(calibration.earSpreadDb))
        .put("warnings", JSONArray(calibration.warnings))
        .put("createdAtMillis", calibration.createdAtMillis)
        .put("sourceRunIds", JSONArray(calibration.sourceRunIds))

    private fun toCalibration(obj: JSONObject): DerivedCalibration? {
        val deviceKey = obj.opt("deviceKey") as? String ?: return null
        if (deviceKey.isBlank()) return null
        val deviation = obj.optJSONArray("responseDeviationDb")?.let { array ->
            (0 until array.length()).mapNotNull { (array.opt(it) as? Number)?.toDouble() }
        } ?: return null
        // A shorter or longer list is not this device's response at a different
        // resolution, it is a broken record: CalibrationPreset's own `require`
        // would throw on it later, in a constructor nobody can catch usefully.
        if (deviation.size != TEST_FREQUENCIES_HZ.size) return null
        return DerivedCalibration(
            deviceKey = deviceKey,
            deviceName = obj.opt("deviceName") as? String,
            responseDeviationDb = deviation,
            earSpreadDb = (obj.opt("earSpreadDb") as? Number)?.toDouble() ?: 0.0,
            warnings = obj.optJSONArray("warnings").strings(),
            createdAtMillis = (obj.opt("createdAtMillis") as? Number)?.toLong() ?: 0L,
            sourceRunIds = obj.optJSONArray("sourceRunIds").strings(),
        )
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

    /**
     * JSON has no NaN and no infinity, and `org.json` throws on them. They
     * become 0.0 rather than costing the whole record.
     */
    private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0
}
