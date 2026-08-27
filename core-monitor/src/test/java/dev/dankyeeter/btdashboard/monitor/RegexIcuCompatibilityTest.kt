package dev.dankyeeter.btdashboard.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every `Regex(...)` literal in `:core-monitor` and `:core-system`, checked
 * against the constructs Android's ICU engine rejects and the JVM accepts.
 *
 * ## Why a lint test and not more parser tests
 *
 * This module's regexes live in `private val` properties of `object`s, so they
 * are compiled in the class's static initialiser. A pattern the JVM tolerates
 * and ICU does not therefore fails as an `ExceptionInInitializerError` the first
 * time anything touches the parser — the whole live panel went to "No device"
 * once for exactly that reason, from a bare `}` at the end of a pattern. The
 * unit tests were all green, because they run on the JVM's `java.util.regex`,
 * which reads a lone `}` as a literal brace. ICU reads it as an error.
 *
 * No amount of example-based parser testing catches that class of bug: the
 * example never runs on the engine that rejects it. What catches it is reading
 * the patterns as text and refusing the constructs where the two engines
 * disagree, which is what this file does.
 *
 * ## The rules, and why they are only these
 *
 * Six, each one a construct that is either an outright error in ICU or an error
 * in both engines. Nothing here is a style rule, because a style rule that fires
 * on a working pattern would teach the next person to delete the test:
 *
 *  1. `BARE_CLOSE_BRACE` — an unescaped `}` outside a character class that does
 *     not close a bounded quantifier. **The historic crash.**
 *  2. `BARE_OPEN_BRACE` — an unescaped `{` outside a character class that does
 *     not open a valid `{m}` / `{m,}` / `{m,n}`. Same disagreement, other end.
 *  3. `TRAILING_ESCAPE` — a pattern ending in a lone `\`.
 *  4. `UNBALANCED_GROUP` — unescaped `(` and `)` that do not pair up.
 *  5. `UNCLOSED_CLASS` — a `[` with no closing `]`.
 *  6. `DANGLING_QUANTIFIER` — `*`, `+` or `?` with no atom in front of it.
 *
 * A `{` that opens a real bounded quantifier is fine and stays fine; so does
 * every brace inside a character class, and every escaped one. That is why the
 * current tree passes with zero findings rather than with a suppression list.
 *
 * The checker is proved to still bite by running it over the historic bad
 * patterns inline, not by breaking a source file.
 */
class RegexIcuCompatibilityTest {

    // ---- the tests -----------------------------------------------------------

    @Test
    fun `every regex literal in both modules is ICU-safe`() {
        val findings = scanAll().flatMap { literal ->
            IcuRegexRules.check(literal.pattern).map { "${literal.where}: ${it.describe()}" }
        }
        assertEquals(
            "regex literals that Android's ICU engine would reject:\n" +
                findings.joinToString("\n"),
            emptyList<String>(),
            findings,
        )
    }

    /**
     * The scan must never pass by finding nothing.
     *
     * A refactor that renames the source directories, or a `Regex(` built from a
     * variable instead of a literal, would otherwise turn this whole file into a
     * test that asserts the empty list is empty.
     */
    @Test
    fun `the scan actually reaches the sources it claims to check`() {
        val literals = scanAll()
        val byModule = literals.groupBy { it.module }
        assertTrue(
            "no regex literals found in :core-monitor — the scan lost its sources",
            (byModule["core-monitor"]?.size ?: 0) >= 30,
        )
        assertTrue(
            "no regex literals found in :core-system — the scan lost its sources",
            (byModule["core-system"]?.size ?: 0) >= 3,
        )
        // The redacted-MAC pattern is in both modules' parsers and is the one
        // with the most braces in it, so its absence means the extractor, not
        // the tree, changed.
        assertTrue(
            "the extractor no longer recognises the MAC pattern",
            literals.any { it.pattern.contains("(?:[0-9A-Fa-fxX]{2}:){5}") },
        )

        // Every file that mentions `Regex(` must have yielded at least one
        // analysable pattern. This is what surfaces a regex assembled from
        // variables, which the text checker cannot see into and would otherwise
        // skip in silence.
        val mentioning = mainSources()
            .filter { it.readText().contains("Regex(") }
            .map { it.absolutePath }
            .toSet()
        val analysed = literals.map { it.file.absolutePath }.toSet()
        assertEquals(
            "files with a Regex( the extractor could not read a literal out of",
            emptySet<String>(),
            mentioning - analysed,
        )
    }

    /**
     * The self-test that keeps this file honest.
     *
     * These are the shapes that actually shipped or nearly shipped, written
     * inline so the checker can be proved to reject them without a source file
     * ever having to be broken.
     */
    @Test
    fun `the checker rejects the constructs that crashed on the device`() {
        // The historic one, verbatim in shape: a closing brace that was never
        // escaped because the JVM read it as a literal.
        assertRejected("BARE_CLOSE_BRACE", "m?[Cc]odec[Cc]onfig\\s*[:=]?\\s*\\{([^}]*)}")
        assertRejected("BARE_CLOSE_BRACE", "Counts \\(underflow\\): (\\d+)}")
        // The same disagreement from the opening side.
        assertRejected("BARE_OPEN_BRACE", "LDAC transmission bitrate {Kbps}")
        assertRejected("BARE_OPEN_BRACE", "sampleRate=\\d+{")
        // `{,5}` is not a bounded quantifier — ICU rejects it, the JVM reads it
        // as three literal characters.
        assertRejected("BARE_OPEN_BRACE", "a{,5}")

        assertRejected("TRAILING_ESCAPE", "codecName:\\s*(\\w+)\\")
        assertRejected("UNBALANCED_GROUP", "(\\d+)/\\s*(\\d+")
        assertRejected("UNBALANCED_GROUP", "mCodecType:\\s*(\\d+))")
        assertRejected("UNCLOSED_CLASS", "[0-9A-Fa-f")
        assertRejected("DANGLING_QUANTIFIER", "*started")
        assertRejected("DANGLING_QUANTIFIER", "(?:started|+paused)")
    }

    /**
     * The other half of the proof: the constructs the tree legitimately uses
     * must stay clean, or the rule set is a false-positive generator and the
     * first person to hit one will delete it.
     */
    @Test
    fun `the checker accepts the constructs the parsers legitimately use`() {
        listOf(
            "(?:[0-9A-Fa-fxX]{2}:){5}[0-9A-Fa-fxX]{2}",
            "m?[Cc]odec[Cc]onfig\\s*[:=]?\\s*\\{([^}]*)\\}",
            "codecName\\s*[:=]\\s*([^,}\\]]+)",
            "-?\\s*UUID:\\s*([0-9A-Fa-f\\-]{8,})",
            "^\\s*(\\d{2,7})\\s+\\d+\\s+([yn])\\s*$",
            "mSampleRate\\s*[:=]\\s*(?:0x[0-9a-fA-F]+\\((\\d+)\\)|(\\d+))",
            "^(Input thread|Historical Thread Log|Patches:|Power )",
            "^\\s*\\[?\\s*(?:name|Name)\\s*[:=]\\s*(.+?)\\s*$",
            // A brace is a plain character inside a class, in both engines.
            "[{}]+",
            // Possessive and lazy quantifiers stack; the second one is not
            // dangling just because a quantifier precedes it.
            "\\d++\\s*?",
        ).forEach { pattern ->
            assertEquals(
                "false positive on a pattern the tree uses: $pattern",
                emptyList<IcuRegexRules.Violation>(),
                IcuRegexRules.check(pattern),
            )
            // Cross-check against the engine the tests do run on: anything the
            // JVM already rejects would make the case above meaningless.
            Regex(pattern)
        }
    }

    /**
     * The whole chain — extract from Kotlin source, then check — on a file that
     * reintroduces the historic bug.
     *
     * The two tests above prove the rule set and the corpus separately; this is
     * what proves they meet. It runs on an inline source snippet rather than on
     * a deliberately broken file in the tree, so nothing has to be broken and
     * then remembered about.
     *
     * The snippet carries the two shapes that break a naive scanner: a char
     * literal containing a double quote (real code in `DumpsysBluetoothParser`,
     * and a scanner that misreads it swallows the rest of the file), and a
     * commented-out regex, which must not be reported because it compiles
     * nothing.
     */
    @Test
    fun `a bad pattern in Kotlin source form is extracted and rejected`() {
        val bad = rawRegexCall("""Counts \(underflow\): (\d+)}""")
        val good = rawRegexCall("""mCodecType\s*[:=]\s*(\d+)""")
        val commentedOut = rawRegexCall("""this one is dead}""")
        val source = """
            |object Fake {
            |    private val trailing = name.trim('[', ']', '"')
            |    private val BAD = $bad
            |    // private val OLD = $commentedOut
            |    private val GOOD = $good
            |}
        """.trimMargin()

        val patterns = KotlinSource.regexCallPatterns(source).map { it.second }
        assertEquals(
            "the extractor did not read exactly the two live patterns",
            listOf("""Counts \(underflow\): (\d+)}""", """mCodecType\s*[:=]\s*(\d+)"""),
            patterns,
        )
        assertEquals(
            listOf("BARE_CLOSE_BRACE"),
            patterns.flatMap { IcuRegexRules.check(it) }.map { it.rule },
        )
    }

    /** `Regex("""<pattern>""")`, assembled so the quotes survive being nested. */
    private fun rawRegexCall(pattern: String): String {
        val fence = "\"\"\""
        return "Regex($fence$pattern$fence)"
    }

    private fun assertRejected(rule: String, pattern: String) {
        val violations = IcuRegexRules.check(pattern)
        assertTrue(
            "expected $rule for <$pattern>, got ${violations.map { it.rule }}",
            violations.any { it.rule == rule },
        )
    }

    // ---- source scanning -----------------------------------------------------

    private data class RegexLiteral(
        val module: String,
        val file: File,
        val line: Int,
        val pattern: String,
    ) {
        val where: String get() = "${file.name}:$line"
    }

    private fun mainSources(): List<File> =
        RepoTree.mainSourceFiles("core-monitor") + RepoTree.mainSourceFiles("core-system")

    private fun scanAll(): List<RegexLiteral> = mainSources().flatMap { file ->
        val module = if (file.absolutePath.replace('\\', '/').contains("/core-monitor/")) {
            "core-monitor"
        } else {
            "core-system"
        }
        val text = file.readText()
        KotlinSource.regexCallPatterns(text).map { (offset, pattern) ->
            RegexLiteral(
                module = module,
                file = file,
                line = text.take(offset).count { it == '\n' } + 1,
                pattern = pattern,
            )
        }
    }
}

/**
 * Just enough Kotlin lexing to find `Regex(...)` calls and read their string
 * arguments back out as the pattern the engine will actually compile.
 *
 * Full-blown parsing would be the wrong trade here — this only has to be right
 * about which characters are code, which are inside a string, and which are
 * inside a comment, because getting *that* wrong is what makes a scanner report
 * a pattern that does not exist. Char literals matter for the same reason:
 * `trim('[', ']', '"')` is real code in this module, and a scanner that missed
 * it would read the rest of that file as one enormous string.
 */
internal object KotlinSource {

    /** Every `Regex(` call, as (offset of the call, concatenated pattern). */
    fun regexCallPatterns(source: String): List<Pair<Int, String>> {
        val found = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> i = source.indexOfOrEnd("\n", i)
                source.startsWith("/*", i) -> i = source.indexOfOrEnd("*/", i + 2) + 2
                source.startsWith("\"\"\"", i) -> i = skipRawString(source, i)
                source[i] == '"' -> i = skipString(source, i)
                source[i] == '\'' -> i = skipCharLiteral(source, i)
                source.startsWith(CALL, i) && isCallStart(source, i) -> {
                    val start = i
                    val (end, pattern) = readCallArguments(source, i + CALL.length)
                    if (pattern != null) found += start to pattern
                    i = end
                }

                else -> i++
            }
        }
        return found
    }

    private const val CALL = "Regex("

    /** `MyRegex(` and `foo.Regex(` are not the constructor. */
    private fun isCallStart(source: String, i: Int): Boolean {
        if (i == 0) return true
        val prev = source[i - 1]
        return !(prev.isLetterOrDigit() || prev == '_' || prev == '.')
    }

    /**
     * Collects the string literals of one call and concatenates them.
     *
     * Concatenation is not a nicety: the AudioFlinger row patterns are split
     * across two raw strings with a `+`, and checking either half on its own
     * would report an unbalanced group that the whole pattern does not have.
     *
     * Returns null when the call carried no string literal at all — a regex
     * built from variables, which this checker cannot read and must not pretend
     * to have approved.
     */
    private fun readCallArguments(source: String, from: Int): Pair<Int, String?> {
        val parts = StringBuilder()
        var sawLiteral = false
        var depth = 1
        var i = from
        while (i < source.length && depth > 0) {
            when {
                source.startsWith("//", i) -> i = source.indexOfOrEnd("\n", i)
                source.startsWith("/*", i) -> i = source.indexOfOrEnd("*/", i + 2) + 2
                source.startsWith("\"\"\"", i) -> {
                    val end = skipRawString(source, i)
                    parts.append(source, i + 3, end - 3)
                    sawLiteral = true
                    i = end
                }

                source[i] == '"' -> {
                    val end = skipString(source, i)
                    parts.append(unescape(source.substring(i + 1, end - 1)))
                    sawLiteral = true
                    i = end
                }

                source[i] == '\'' -> i = skipCharLiteral(source, i)
                source[i] == '(' -> { depth++; i++ }
                source[i] == ')' -> { depth--; i++ }
                else -> i++
            }
        }
        return i to parts.toString().takeIf { sawLiteral }
    }

    private fun skipRawString(source: String, start: Int): Int {
        val end = source.indexOf("\"\"\"", start + 3)
        return if (end < 0) source.length else end + 3
    }

    private fun skipString(source: String, start: Int): Int {
        var i = start + 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                '\n' -> return i // unterminated; do not run off the file
                else -> i++
            }
        }
        return source.length
    }

    private fun skipCharLiteral(source: String, start: Int): Int {
        var i = start + 1
        if (i < source.length && source[i] == '\\') i += 2 else i += 1
        return if (i < source.length && source[i] == '\'') i + 1 else i
    }

    /** Kotlin escapes that change what the regex engine sees. */
    private fun unescape(literal: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < literal.length) {
            val c = literal[i]
            if (c != '\\' || i == literal.length - 1) {
                out.append(c)
                i++
                continue
            }
            when (val next = literal[i + 1]) {
                '\\' -> out.append('\\')
                '"' -> out.append('"')
                '\'' -> out.append('\'')
                '$' -> out.append('$')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'u' -> {
                    val hex = literal.substring(i + 2, minOf(i + 6, literal.length))
                    hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                    i += 4
                }
                // Not a Kotlin escape, so the backslash reaches the engine.
                else -> out.append('\\').append(next)
            }
            i += 2
        }
        return out.toString()
    }

    private fun String.indexOfOrEnd(needle: String, from: Int): Int =
        indexOf(needle, from).takeIf { it >= 0 } ?: length
}

/**
 * The rule set itself: constructs where `java.util.regex` and Android's ICU
 * engine disagree, plus the few that both reject.
 *
 * Written as a hand-rolled walk rather than as regexes about regexes, because
 * the whole question is "which characters are inside a character class and which
 * are escaped", and that is state, not a pattern.
 */
internal object IcuRegexRules {

    data class Violation(val rule: String, val index: Int, val detail: String) {
        fun describe(): String = "$rule at offset $index — $detail"
    }

    /** `{m}`, `{m,}`, `{m,n}` — the only brace form ICU accepts unescaped. */
    private val BOUNDED_QUANTIFIER = Regex("""\{\d+(?:,\d*)?\}""")

    /**
     * "Nothing has been read yet", as a value the walk can compare against.
     *
     * Written as an escape rather than as a raw character because this
     * repository's build rejects a NUL byte in a Kotlin source outright.
     */
    private const val START_OF_PATTERN = '\u0000'

    fun check(pattern: String): List<Violation> {
        val violations = mutableListOf<Violation>()
        var i = 0
        var parenDepth = 0
        var classStart = -1
        var classBodyStart = -1
        // The previous top-level element, coarsely: 'A' for something a
        // quantifier may attach to, '(' or '|' for something it may not, and
        // NUL for the start of the pattern.
        var previous = START_OF_PATTERN

        while (i < pattern.length) {
            val c = pattern[i]

            if (c == '\\') {
                if (i == pattern.length - 1) {
                    violations += Violation("TRAILING_ESCAPE", i, "pattern ends in a lone backslash")
                    break
                }
                i += 2
                if (classStart < 0) previous = 'A'
                continue
            }

            if (classStart >= 0) {
                // A `]` in the first body position is a literal, in both
                // engines, so it does not close the class.
                if (c == ']' && i > classBodyStart) {
                    classStart = -1
                    previous = 'A'
                }
                i++
                continue
            }

            when (c) {
                '[' -> {
                    classStart = i
                    classBodyStart = if (i + 1 < pattern.length && pattern[i + 1] == '^') i + 2 else i + 1
                    i++
                }

                '(' -> {
                    parenDepth++
                    previous = '('
                    i++
                }

                ')' -> {
                    parenDepth--
                    if (parenDepth < 0) {
                        violations += Violation("UNBALANCED_GROUP", i, "')' with no open group")
                        parenDepth = 0
                    }
                    previous = 'A'
                    i++
                }

                '{' -> {
                    val bounded = BOUNDED_QUANTIFIER.find(pattern, i)?.takeIf { it.range.first == i }
                    if (bounded == null) {
                        violations += Violation(
                            "BARE_OPEN_BRACE",
                            i,
                            "'{' that does not open a bounded quantifier; ICU rejects it, escape as \\{",
                        )
                        i++
                    } else {
                        i += bounded.value.length
                    }
                    previous = 'A'
                }

                '}' -> {
                    violations += Violation(
                        "BARE_CLOSE_BRACE",
                        i,
                        "unescaped '}' outside a character class; ICU rejects it, escape as \\}",
                    )
                    previous = 'A'
                    i++
                }

                '*', '+' -> {
                    if (previous == START_OF_PATTERN || previous == '(' || previous == '|') {
                        violations += Violation(
                            "DANGLING_QUANTIFIER",
                            i,
                            "'$c' with nothing to repeat",
                        )
                    }
                    previous = 'A'
                    i++
                }

                '?' -> {
                    // `(?` opens a non-capturing or flag group, so a `?` right
                    // after `(` is structure rather than a quantifier.
                    if (previous == START_OF_PATTERN || previous == '|') {
                        violations += Violation("DANGLING_QUANTIFIER", i, "'?' with nothing to repeat")
                    }
                    previous = 'A'
                    i++
                }

                '|' -> {
                    previous = '|'
                    i++
                }

                else -> {
                    previous = 'A'
                    i++
                }
            }
        }

        if (classStart >= 0) {
            violations += Violation("UNCLOSED_CLASS", classStart, "'[' is never closed")
        }
        if (parenDepth > 0) {
            violations += Violation("UNBALANCED_GROUP", pattern.length, "$parenDepth group(s) left open")
        }
        return violations
    }
}
