package dev.dankyeeter.btdashboard.ui.screens.monitor

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The acceptance criteria `UI_SPEC.md` itself declares checkable by grep, built
 * as greps.
 *
 * ## Why this file exists
 *
 * Of the thirty-odd criteria in `UI_SPEC.md`, one used to be named anywhere in
 * the code. "AK-x is covered" was therefore a sentence in a report rather than a
 * state anybody could check, and on 2026-09-02 two criteria turned out to be
 * violated — found by accident, one of them unchanged since the day it was
 * written (`qa/findings.md`, QA-008). A criterion whose only enforcement is a
 * reviewer's memory is not enforced.
 *
 * ## What a rule in here is allowed to be
 *
 * The condition `UI_SPEC.md` states, not one that seemed sensible while writing
 * the test. Where the spec names a pattern — AK-T017-2 does — that pattern is
 * used verbatim. Where its wording leaves the grep undecided, the rule is **not**
 * written and the gap is handed back, because a rule that checks something
 * adjacent claims cover it does not have and ends the search for the real check.
 *
 * Every test below names its criterion on a line beginning `AK-`, so that
 * `grep -rn "AK-" --include=*.kt` answers "which criterion is anchored, and by
 * what".
 *
 * ## Whole files, comments included
 *
 * These rules read the file text, not only its string literals — the director's
 * decision of 2026-09-02, and the reason four KDoc comments were reworded rather
 * than exempted. A grep that tries to tell prose from display text is a second
 * program with its own faults and would have to be tested itself; a grep over
 * the text has none.
 *
 * The price is that a criterion whose forbidden wording legitimately appears in
 * prose cannot be checked this way at all. Two do — AK-T002-12 and AK-T009-29
 * forbid lines that the code's own comments name in order to record that they
 * were removed — and they are absent here on purpose rather than softened into a
 * rule that passes. Both are anchored on the rendered text instead, in
 * [LiveLinkPanelScreenTest].
 */
class AcceptanceCriteriaGrepTest {

    /**
     * AK-T009-43 — the word family "audible/audibly/audibility" does not occur on
     * the Bluetooth-link and loss surfaces, nor do the figures of the two
     * calibration points.
     *
     * The rule is the word family and not the string "audible": the original
     * wording let "audibly" through, and that is how the sentence "on a link that
     * was audibly breaking up" reached a KDoc in this very tree
     * (`UI_SPEC.md`, AK-T009-43, precised 2026-09-02).
     *
     * What is being kept out is a claim the measurements do not carry. The two
     * points are confounded — 0 dropouts occurred only at 492/660 kbps and
     * 12.94-12.99/min only at 990 kbps pinned — so what is evidenced is "990
     * pinned sounds broken on this route", not "this rate is audible"
     * (`UI_SPEC.md`, T-009 decision 4; rule R-E).
     *
     * The second half reads "die Zahlen der beiden Kalibrierpunkte" as the two
     * figures the spec itself writes down for them, 12.94 and 12.99 per minute.
     * The other point's figure is 0 and cannot be forbidden. That reading is an
     * assumption and is reported as one.
     *
     * The criterion also covers "die dort verwendeten Stringressourcen". This
     * surface uses none — every word on it is a literal — so the third assertion
     * is the one that keeps that true: the day a row moves into `strings.xml`,
     * this rule stops covering it, and it should fail rather than go quiet.
     */
    @Test
    fun `nothing on the monitor surface says a counted rate was heard`() {
        assertNone(
            criterion = "AK-T009-43",
            files = SourceTree.monitorScreen,
            pattern = Regex("""audib(le|ly|ility)""", RegexOption.IGNORE_CASE),
            why = "the two calibration points are confounded, so no wording here may " +
                "say that a counted rate was heard (R-E)",
        )
        assertNone(
            criterion = "AK-T009-43",
            files = SourceTree.monitorScreen,
            pattern = Regex("""12[.,]9[49]"""),
            why = "the calibration points do not appear in the app (T-009, decision 4)",
        )
        assertNone(
            criterion = "AK-T009-43",
            files = SourceTree.monitorScreen,
            pattern = Regex("""R\.string\."""),
            why = "this rule reads the source text, so a word moved into a string " +
                "resource would pass it unread — extend the rule to the resource first",
        )
    }

    /**
     * AK-T009-32 — `EncoderStarvationTripwire` is shown or named nowhere in the
     * interface, as a loss indicator or otherwise.
     *
     * The tripwire watches a *rate* of encoder underflows to catch one specific
     * incident three orders of magnitude away from resting. The counter beneath
     * it stayed at zero through the arm where stack dropouts ran throughout, so
     * anything on screen that carried its name would be offering the user the one
     * signal the device runs disqualified.
     *
     * The single word covers the class name as well, which is the whole of what
     * "named" can mean in source text.
     */
    @Test
    fun `the starvation tripwire is named on no screen`() {
        assertNone(
            criterion = "AK-T009-32",
            files = SourceTree.appUi,
            pattern = Regex("""starvation""", RegexOption.IGNORE_CASE),
            why = "it is not a loss or fault indicator and may not be shown as one",
        )
    }

    /**
     * AK-T009-31 — no parameter carries the blanket marker `TODO(T-001)`; an open
     * one names the single measurement it waits for, as `TODO(M-x)`.
     *
     * `T-001` stopped identifying which measurement was missing once the spec had
     * been written forward twice (`UI_SPEC.md`, T-009 addendum to the T-002
     * threshold rule). The rule is therefore not "no TODOs" but "no TODO that
     * fails to name its measurement" — a marker is how an unset threshold stays
     * visible, and deleting the marker would hide it rather than set it.
     *
     * This is the grep half only. The criterion's other half — that every value
     * that *is* set names the measurement it rests on, file and arm — the spec
     * itself assigns to a reading of the code in review, and nothing here checks
     * it.
     */
    @Test
    fun `every open parameter names the measurement it waits for`() {
        val markers = hits(SourceTree.appAndLinkSources, Regex("""TODO\("""))
        val unnamed = markers.filterNot { Regex("""TODO\(M-\d+\)""").containsMatchIn(it.text) }

        assertTrue(
            "AK-T009-31: a TODO marker here has to name one measurement, as TODO(M-x); " +
                "the blanket TODO(T-001) is withdrawn.\n" + unnamed.joinToString("\n"),
            unnamed.isEmpty(),
        )
    }

    /**
     * AK-T002-19 — no code path switches the close-up on. Only the user's tap on
     * the chip does.
     *
     * The close-up polls twice a second and costs a measured 233 ms per reading,
     * so a state change that turned it on would spend that on the audio path
     * exactly when the link is already struggling — measuring by disturbing,
     * which `GOAL.md` AK-1 exists to prevent.
     *
     * Two checks, because the criterion has two halves: nobody passes a literal
     * `true`, and no call site sits anywhere but in a click handler. The second
     * would go quiet if the callback were renamed, so the scan asserts it found
     * call sites at all.
     */
    @Test
    fun `nothing but the chip switches the close-up on`() {
        assertNone(
            criterion = "AK-T002-19",
            files = SourceTree.appSources,
            pattern = Regex("""(on|set)CloseUpEnabled\(\s*true\s*\)"""),
            why = "the close-up is switched on by the user's tap and by nothing else " +
                "(UI_SPEC.md, T-002, decision 4)",
        )

        val calls = hits(SourceTree.appSources, Regex("""(on|set)CloseUpEnabled\("""))
            .filterNot { Regex("""fun\s+(on|set)CloseUpEnabled\(""").containsMatchIn(it.text) }
        assertTrue(
            "AK-T002-19: no call site found at all — has the callback been renamed?",
            calls.isNotEmpty(),
        )

        val awayFromTheChip = calls.filterNot { it.text.contains("onClick") }
        assertTrue(
            "AK-T002-19: the close-up may only be switched from a click handler.\n" +
                awayFromTheChip.joinToString("\n"),
            awayFromTheChip.isEmpty(),
        )
    }

    /**
     * AK-T002-23 — no figure divides loss by the bitrate or normalises it
     * against the bitrate.
     *
     * A loss-per-kbps number would read as a quality score, and the two points
     * that could calibrate it vary in rate and in ladder step at once. Dividing
     * by the second variable makes the confounder look like arithmetic
     * (`UI_SPEC.md`, T-002 addendum 6).
     */
    @Test
    fun `no figure divides loss by the bitrate`() {
        assertNone(
            criterion = "AK-T002-23",
            files = SourceTree.appAndLinkSources,
            pattern = Regex("""(/|\.div\()\s*[\w.?]*(measuredKbps|bitrateKbps)"""),
            why = "no quantity may be normalised against the bitrate it was measured with",
        )
    }

    /**
     * AK-T017-2 — the live panel carries no "label: {n} (…)" format.
     *
     * The pattern is `UI_SPEC.md`'s own, character for character. The panel keeps
     * every layer in prose — full sentences at the first level, the explanation
     * behind the question mark — and a single row in a colon-and-bracket style
     * would be a fifth register in a panel that already holds three.
     */
    @Test
    fun `the live panel carries no colon-bracket count format`() {
        assertNone(
            criterion = "AK-T017-2",
            files = listOf(SourceTree.livePanel),
            pattern = Regex(""":\s*\d+\s*\("""),
            why = "every line of this panel is a sentence (UI_SPEC.md, T-017)",
        )
    }

    // ---- the scan ---------------------------------------------------------------

    /** One matching line, printed the way a grep would print it. */
    private data class Hit(val file: File, val line: Int, val text: String) {
        override fun toString(): String = "${SourceTree.relative(file)}:$line: ${text.trim()}"
    }

    private fun hits(files: List<File>, pattern: Regex): List<Hit> = files.flatMap { file ->
        file.readLines().withIndex()
            .filter { (_, line) -> pattern.containsMatchIn(line) }
            .map { (index, line) -> Hit(file, index + 1, line) }
    }

    /**
     * [pattern] matches nowhere in [files], with the reason in the failure.
     *
     * The empty-input guard is the part that matters over time: a rule whose path
     * has moved scans nothing, passes, and goes on reporting a criterion as
     * anchored — which is the failure mode this whole file was written against.
     */
    private fun assertNone(criterion: String, files: List<File>, pattern: Regex, why: String) {
        assertTrue(
            "$criterion scanned no files — has the source path moved?",
            files.isNotEmpty(),
        )
        val found = hits(files, pattern)
        assertTrue(
            "$criterion: $why\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }
}

/**
 * Where the checkout is, and which parts of it each criterion is about.
 *
 * Found at runtime rather than assumed: Gradle's working directory for an
 * Android unit test is the module directory, but that is a default and not a
 * promise, so the root is located by climbing until the build's own
 * `settings.gradle.kts` appears — and failing that, from wherever these test
 * classes were compiled to.
 *
 * `:core-monitor`'s test sources have their own copy of this walk
 * (`SyntheticSweepSupport.kt`). It is not shared because sharing it across
 * module boundaries needs a test-fixtures configuration in the build, which is a
 * build change and not this task's to make.
 */
private object SourceTree {

    val root: File by lazy {
        val found = climb(File(System.getProperty("user.dir") ?: ".").absoluteFile)
            ?: climb(compiledClassesDir())
        requireNotNull(found) {
            "repository root not found from ${System.getProperty("user.dir")}"
        }
    }

    /** Everything the monitor's own screen is drawn from — the AK-T009-43 scope. */
    val monitorScreen: List<File> by lazy {
        kotlinUnder("app/src/main/java/dev/dankyeeter/btdashboard/ui/screens/monitor")
    }

    /** The whole `ui` tree — the AK-T009-32 scope. */
    val appUi: List<File> by lazy { kotlinUnder("app/src/main/java/dev/dankyeeter/btdashboard/ui") }

    /** Everything the app module ships. */
    val appSources: List<File> by lazy { kotlinUnder("app/src/main") }

    /** The app and the module that measures the link — where a parameter can live. */
    val appAndLinkSources: List<File> by lazy { appSources + kotlinUnder("core-monitor/src/main") }

    val livePanel: File by lazy {
        file("app/src/main/java/dev/dankyeeter/btdashboard/ui/screens/monitor/LiveLinkPanel.kt")
    }

    fun relative(file: File): String = file.relativeTo(root).path.replace('\\', '/')

    private fun kotlinUnder(path: String): List<File> {
        val dir = File(root, path)
        require(dir.isDirectory) { "source directory missing: $dir" }
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
    }

    private fun file(path: String): File =
        File(root, path).also { require(it.isFile) { "source file missing: $it" } }

    private fun compiledClassesDir(): File = runCatching {
        File(SourceTree::class.java.protectionDomain!!.codeSource.location.toURI())
    }.getOrElse { File(".").absoluteFile }

    private fun climb(start: File): File? = generateSequence(start) { it.parentFile }
        .take(12)
        .firstOrNull {
            File(it, "settings.gradle.kts").isFile && File(it, "core-monitor").isDirectory
        }
}
