package dev.dankyeeter.btdashboard.hearing.store

import dev.dankyeeter.btdashboard.hearing.DerivedCalibration
import dev.dankyeeter.btdashboard.hearing.TEST_FREQUENCIES_HZ

/**
 * Serialisation for [DerivedCalibration], written without `org.json`.
 *
 * Every other record in [AudiogramStore] is encoded with `org.json`, and this
 * one deliberately is not. `org.json` ships inside `android.jar`, so on the
 * host JVM where the unit tests run every one of its methods throws "not
 * mocked" — which means an `org.json` codec cannot have a round-trip test at
 * all. For most of the store that is a fair trade; for this record it is not.
 * A derivation is the only thing in the module built out of a measurement the
 * user cannot repeat without another appointment at a practice, and a silent
 * encoding bug would lose it. So this one pays about eighty lines to be
 * testable.
 *
 * The reader lives in [MiniJson] because a second record in this package needs
 * exactly the same one. It understands ordinary JSON and nothing exotic; it is
 * only ever handed strings this same file wrote, and a string it cannot read
 * degrades to "no derivations" exactly like every other parser in
 * [AudiogramStore] — never to a half-read record.
 */
internal object DerivedCalibrationJson {

    fun encode(calibrations: List<DerivedCalibration>): String = buildString {
        append('[')
        calibrations.forEachIndexed { index, calibration ->
            if (index > 0) append(',')
            appendObject(calibration)
        }
        append(']')
    }

    /**
     * Reads back what [encode] wrote. Anything unreadable yields an empty list;
     * a single malformed entry inside a readable array is dropped on its own,
     * the same "one bad row must not cost the record" rule the clinical parser
     * follows.
     */
    fun parse(raw: String?): List<DerivedCalibration> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = MiniJson.parse(raw) as? List<*> ?: return emptyList()
        return array.mapNotNull { entry -> (entry as? Map<*, *>)?.let(::toCalibration) }
    }

    private fun StringBuilder.appendObject(calibration: DerivedCalibration) {
        append('{')
        appendField("deviceKey", calibration.deviceKey)
        append(',')
        appendField("deviceName", calibration.deviceName)
        append(",\"responseDeviationDb\":")
        appendNumbers(calibration.responseDeviationDb)
        append(",\"earSpreadDb\":")
        append(MiniJson.number(calibration.earSpreadDb))
        append(",\"warnings\":")
        appendStrings(calibration.warnings)
        append(",\"createdAtMillis\":")
        append(calibration.createdAtMillis.toString())
        append(",\"sourceRunIds\":")
        appendStrings(calibration.sourceRunIds)
        append('}')
    }

    private fun toCalibration(obj: Map<*, *>): DerivedCalibration? {
        val deviceKey = obj["deviceKey"] as? String ?: return null
        if (deviceKey.isBlank()) return null
        val deviation = (obj["responseDeviationDb"] as? List<*>)
            ?.mapNotNull { (it as? Double) }
            ?: return null
        // A shorter or longer list is not this device's response at a different
        // resolution, it is a broken record: CalibrationPreset's own `require`
        // would throw on it later, in a constructor nobody can catch usefully.
        if (deviation.size != TEST_FREQUENCIES_HZ.size) return null
        return DerivedCalibration(
            deviceKey = deviceKey,
            deviceName = obj["deviceName"] as? String,
            responseDeviationDb = deviation,
            earSpreadDb = obj["earSpreadDb"] as? Double ?: 0.0,
            warnings = (obj["warnings"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            createdAtMillis = (obj["createdAtMillis"] as? Double)?.toLong() ?: 0L,
            sourceRunIds = (obj["sourceRunIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        )
    }

    // --- writing ------------------------------------------------------------

    private fun StringBuilder.appendField(name: String, value: String?) {
        append(MiniJson.quote(name))
        append(':')
        append(if (value == null) "null" else MiniJson.quote(value))
    }

    private fun StringBuilder.appendStrings(values: List<String>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(MiniJson.quote(value))
        }
        append(']')
    }

    private fun StringBuilder.appendNumbers(values: List<Double>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(MiniJson.number(value))
        }
        append(']')
    }
}
