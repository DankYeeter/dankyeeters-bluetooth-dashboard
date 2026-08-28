package dev.dankyeeter.btdashboard.hearing.store

/**
 * A tiny JSON reader and the two writing primitives that go with it, shared by
 * the records in this package that have to be readable by a **host-JVM unit
 * test**.
 *
 * `org.json` ships inside `android.jar`, so on the host JVM every one of its
 * methods throws "not mocked" and a codec built on it cannot have a round-trip
 * test at all. [DerivedCalibrationJson] explains why that trade is unacceptable
 * for a derived calibration; the same argument applies to
 * [PreferenceProfileJson], whose contents are a dozen listening sessions the
 * user cannot repeat in an afternoon. Rather than a second copy of the parser,
 * both use this one.
 *
 * The grammar understood here is ordinary JSON and nothing exotic. Both callers
 * only ever hand it strings they wrote themselves, and anything unreadable is
 * expected to degrade to "no records" rather than to a half-read one — so
 * [JsonReader] throws freely and the callers catch.
 */
internal object MiniJson {

    /**
     * JSON has no NaN and no infinity. Writing one would produce a file that
     * cannot be read back at all, so they become 0.0 rather than a corrupt
     * record.
     */
    fun number(value: Double): String = if (value.isFinite()) value.toString() else "0.0"

    fun number(value: Float): String = number(value.toDouble())

    fun quote(value: String): String = buildString {
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

    /** Parses [raw] into `Map`/`List`/`String`/`Double`/`Boolean`/null, or null. */
    fun parse(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        return runCatching { JsonReader(raw).readValue() }.getOrNull()
    }

    /**
     * A recursive-descent reader over the JSON grammar. Every number becomes a
     * [Double], including timestamps — JSON itself does not distinguish, and a
     * millisecond epoch is exact in a double.
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
                when (val c = next()) {
                    '"' -> return out.toString()
                    '\\' -> when (val escaped = next()) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        // Form feed. Kotlin has no escape for it, so it is
                        // spelled by code point.
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
