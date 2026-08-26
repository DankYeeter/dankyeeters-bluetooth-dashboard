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
 * The reader below understands ordinary JSON and nothing exotic. It is only
 * ever handed strings this same file wrote, and a string it cannot read
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
        val array = runCatching { JsonReader(raw).readValue() as? List<*> }.getOrNull() ?: return emptyList()
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
        append(numberOf(calibration.earSpreadDb))
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
        append(quote(name))
        append(':')
        append(if (value == null) "null" else quote(value))
    }

    private fun StringBuilder.appendStrings(values: List<String>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(quote(value))
        }
        append(']')
    }

    private fun StringBuilder.appendNumbers(values: List<Double>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(numberOf(value))
        }
        append(']')
    }

    /**
     * JSON has no NaN and no infinity. Neither can reach here from the transfer
     * — it rounds to half decibels off finite arithmetic — but writing one
     * would produce a file that cannot be read back at all, so they become 0.0
     * rather than a corrupt record.
     */
    private fun numberOf(value: Double): String =
        if (value.isFinite()) value.toString() else "0.0"

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    // --- reading ------------------------------------------------------------

    /**
     * A recursive-descent reader over the JSON grammar, producing
     * `Map`/`List`/`String`/`Double`/`Boolean`/null. Every number becomes a
     * [Double], including the timestamp — JSON itself does not distinguish, and
     * a long of this magnitude is exact in a double.
     *
     * Throws on anything malformed; [parse] is the only caller and it catches.
     */
    private class JsonReader(private val source: String) {

        private var at = 0

        fun readValue(): Any? {
            val value = value()
            skipWhitespace()
            require(at == source.length) { "trailing content at $at" }
            return value
        }

        private fun value(): Any? {
            skipWhitespace()
            require(at < source.length) { "unexpected end of input" }
            return when (source[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { at++; return result }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                result[key] = value()
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    '}' -> return result
                    else -> throw IllegalArgumentException("expected , or } at $at")
                }
            }
        }

        private fun array(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { at++; return result }
            while (true) {
                result += value()
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    ']' -> return result
                    else -> throw IllegalArgumentException("expected , or ] at $at")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> when (val escaped = next()) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        // Form feed. Kotlin has no escape for it, so it is spelled by code point.
                        'f' -> out.append(Char(12))
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(at + 4 <= source.length) { "truncated \\u escape" }
                            out.append(source.substring(at, at + 4).toInt(16).toChar())
                            at += 4
                        }
                        else -> throw IllegalArgumentException("bad escape \\$escaped")
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = at
            while (at < source.length && source[at] in NUMBER_CHARS) at++
            return source.substring(start, at).toDouble()
        }

        private fun <T> literal(text: String, value: T): T {
            require(source.startsWith(text, at)) { "bad literal at $at" }
            at += text.length
            return value
        }

        private fun peek(): Char? = source.getOrNull(at)

        private fun next(): Char {
            require(at < source.length) { "unexpected end of input" }
            return source[at++]
        }

        private fun expect(c: Char) {
            require(next() == c) { "expected $c at ${at - 1}" }
        }

        private fun skipWhitespace() {
            while (at < source.length && source[at].isWhitespace()) at++
        }

        private companion object {
            const val NUMBER_CHARS = "-+.eE0123456789"
        }
    }
}
