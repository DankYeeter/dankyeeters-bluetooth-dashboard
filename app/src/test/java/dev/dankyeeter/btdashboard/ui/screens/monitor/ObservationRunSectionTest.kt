package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.LdacStackState
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveCodecSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveDeviceSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.ObservationRun
import dev.dankyeeter.btdashboard.monitor.link.live.RunEnd
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import dev.dankyeeter.btdashboard.ui.tuning.LdacTuningState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the observation-run section may put on screen (`UI_SPEC.md` T-039). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ObservationRunSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun snapshot(timestampMs: Long = 0L, kbps: Int? = 660) = LinkLiveSnapshot(
        timestampMs = timestampMs,
        device = LiveDeviceSnapshot(address = "AC:DE:48:00:37:8F", isConnected = true, isPlaying = kbps != null),
        codec = LiveCodecSnapshot(family = CodecFamily.LDAC, sampleRateHz = 96_000, codecSpecific1 = 0L),
        ldac = LdacState.from(0L, 96_000, kbps?.let { LdacStackState(qualityMode = "ABR", transmissionKbps = it) }),
    )

    /** A run fed [rates] at 1 s; a null is a paused reading. */
    private fun runOf(rates: List<Int?>) = rates.foldIndexed(ObservationRun.startedAfter(null)) { i, run, kbps ->
        run.plus(snapshot(1_000L + i * 1_000L, kbps), 1_000L)
    }

    /** 2 min 4 s covered: 660, a step to 990, a 6 s pause, then 492. */
    private val counting = runOf(List(60) { 660 } + List(61) { 990 } + List(5) { null } + List(5) { 492 })

    /** The link after a switch to AAC at 44.1 kHz: no LDAC block, so no readable rate. */
    private val aac = LinkLiveSnapshot(
        timestampMs = 132_000L,
        device = LiveDeviceSnapshot(address = "AC:DE:48:00:37:8F", isConnected = true, isPlaying = true),
        codec = LiveCodecSnapshot(family = CodecFamily.AAC, sampleRateHz = 44_100, codecSpecific1 = 0L),
    )

    /** [counting], measured at 96 kHz, ended by the switch to [aac]. */
    private val endedByCodecChange = counting.plus(aac, 1_000L).also { check(it.end == RunEnd.CODEC_CHANGED) }

    /** The five states of the table, in order. */
    private val states: List<Pair<String, Pair<LinkLiveSnapshot, ObservationRun?>>> = listOf(
        "RUN_UNAVAILABLE" to (snapshot(kbps = null) to null),
        "RUN_IDLE" to (snapshot() to null),
        "RUN_COLLECTING" to (snapshot() to runOf(List(21) { 660 })),
        "RUN_ACTIVE" to (snapshot() to counting),
        "RUN_ENDED" to (snapshot() to counting.stopped()),
    )

    /** What the host shows; changed between checks, because a test may set content only once. */
    private var shown by mutableStateOf(snapshot() to ObservationRunUi())

    private fun host(onStart: () -> Unit = {}) {
        composeRule.setContent {
            BtDashboardTheme {
                Column {
                    ObservationRunSection(
                        shown.first,
                        shown.second,
                        onStart = onStart,
                        onStop = {},
                        onThreshold = { shown = shown.first to shown.second.copy(thresholdQuality = it) },
                        onNoticeContinue = {},
                        onNoticeDismiss = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun show(snapshot: LinkLiveSnapshot, ui: ObservationRunUi) {
        shown = snapshot to ui
        composeRule.waitForIdle()
    }

    private fun render(snapshot: LinkLiveSnapshot, run: ObservationRun?, onStart: () -> Unit = {}) {
        shown = snapshot to ObservationRunUi(run = run)
        host(onStart)
    }

    private fun texts(): List<String> = composeRule
        .onAllNodes(
            SemanticsMatcher("carries text") { it.config.getOrNull(SemanticsProperties.Text) != null },
            useUnmergedTree = true,
        )
        .fetchSemanticsNodes()
        .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun openExplanation() {
        composeRule.onNodeWithContentDescription("What is Observation run?").performClick()
        composeRule.waitForIdle()
    }

    /** AK-T039-1: the idle section offers Start, and only the chip reaches the start. */
    @Test
    fun `nothing but the start chip starts a run`() {
        var taps = 0
        render(snapshot(), run = null, onStart = { taps++ })
        assertEquals(0, taps)
        assertFalse(texts().any { "observed" in it })

        composeRule.onNode(hasText("Start") and hasClickAction()).performClick()
        assertEquals(1, taps)
    }

    /** AK-T039-2: in no state is there a figure without its window, nor step rows without their head. */
    @Test
    fun `every figure names its window in every state`() {
        host()
        states.forEach { (name, state) ->
            show(state.first, ObservationRunUi(run = state.second))
            val shown = texts()
            shown.filter { "Lowest reading" in it || "of the time" in it || "Time at each step" in it }
                .forEach { assertTrue("$name: '$it' has no window", it.contains(", ") && "observed" in it) }
            val stepRows = shown.filter { STEP_ROW.containsMatchIn(it) }
            if (stepRows.isNotEmpty()) {
                assertTrue("$name: step rows without their head", shown.any { it.startsWith("Time at each step, ") })
            }
        }
    }

    /** AK-T039-3: ten minutes of pause are named as a break, and not observed. */
    @Test
    fun `a ten minute pause shows as a break and not as observed time`() {
        render(snapshot(), runOf(List(61) { 660 } + List(600) { null } + List(61) { 660 }))

        composeRule.onNodeWithText("Lowest reading 660 kbps, 2 min observed.").assertExists()
        composeRule.onNodeWithText("Not observed for 10 min in 1 break; that time is in no figure here.").assertExists()
    }

    /** AK-T039-4: before both minimums only the collecting line exists. */
    @Test
    fun `a twenty second run shows only the collecting line`() {
        render(snapshot(), runOf(List(21) { 660 }))

        val shown = texts()
        assertTrue(shown.contains("Collecting — 21 readings, 20 s observed. Figures appear at 2 min observed."))
        assertFalse(shown.any { "Lowest" in it || "At or above" in it || "Time at each step" in it || "kbps —" in it })
    }

    /** AK-T039-6 (rendered half) and R-G: no forbidden wording in any state, either layer, or the notice. */
    @Test
    fun `no state says more than it counted`() {
        val forbidden = listOf(
            "always", "never below", "never dropped", "consistently", "stable", "steady enough",
            "safe to pin", "you can pin", "guaranteed", "reliable",
            // R-E / AK-T002-12, and R-G.
            "audio lost", "no loss", "nothing lost", "audible", "you heard", "all good", "healthy",
            "everything fine", "packet",
        )
        host()
        openExplanation()
        val rendered = states.flatMap { (_, state) ->
            show(state.first, ObservationRunUi(run = state.second))
            texts()
        } + run {
            show(snapshot(), ObservationRunUi(startNoticeShown = true))
            texts()
        }

        assertTrue(rendered.contains("This screen must stay open"))
        val offending = rendered.filter { text -> forbidden.any { text.contains(it, ignoreCase = true) } }
        assertTrue("AK-T039-6:\n" + offending.joinToString("\n"), offending.isEmpty())
    }

    /** AK-T039-7: the chips are the pinnable steps, the middle one first; a new one moves the share only. */
    @Test
    fun `a threshold chip recomputes the share over the same run`() {
        render(snapshot(), counting)
        val before = texts()
        assertTrue(before.containsAll(listOf("990 kbps", "660 kbps", "330 kbps")))
        assertFalse(before.contains("ABR"))
        assertTrue(before.contains("At or above 660 kbps 96 % of the time, 2 min observed."))

        composeRule.onNode(hasText("990 kbps") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        val after = texts()

        assertTrue(after.contains("At or above 990 kbps 48 % of the time, 2 min observed."))
        assertEquals(before.filterNot { "At or above" in it }, after.filterNot { "At or above" in it })
    }

    /** AK-T039-9, T-043a finding A: an ended run keeps the 96 kHz ladder it was measured on after a switch to 44.1 kHz. */
    @Test
    fun `an ended run keeps its own ladder when the link changes`() {
        render(aac, endedByCodecChange)

        val shown = texts()
        assertTrue(shown.containsAll(listOf("990 kbps", "660 kbps", "330 kbps")))
        assertTrue(shown.contains("At or above 660 kbps 96 % of the time, 2 min observed."))
        assertFalse(shown.any { "909" in it || "606" in it || "303" in it })
    }

    /** T-043a finding B: no Start while the rate is unreadable, ended run or not; it returns with the rate. */
    @Test
    fun `an ended run offers Start only while the rate is readable`() {
        render(aac, endedByCodecChange)
        composeRule.onNode(hasText("Start") and hasClickAction()).assertDoesNotExist()

        show(snapshot(), ObservationRunUi(run = endedByCodecChange))
        composeRule.onNode(hasText("Start") and hasClickAction()).assertExists()
    }

    /** AK-T039-8 (second-layer half): the share says it is a lower bound. */
    @Test
    fun `the second layer calls the share a lower bound`() {
        render(snapshot(), counting)
        openExplanation()
        assertTrue(texts().any { "the share is a lower bound" in it })
    }

    /** AK-T039-9 (wording half): six ends, six different first lines, figures kept below. */
    @Test
    fun `each end has its own line and keeps the figures`() {
        val lines = RunEnd.entries.map { endLine(it, 124_000L) }
        assertEquals(6, lines.toSet().size)
        assertEquals("Run ended after 2 min without a reading. 2 min observed.", endLine(RunEnd.READING_GAP, 124_000L))

        render(snapshot(), counting.stopped())
        composeRule.onNodeWithText("Run stopped. 2 min observed.").assertExists()
        composeRule.onNodeWithText("Lowest reading 492 kbps, 2 min observed.").assertExists()
        composeRule.onNode(hasText("Start") and hasClickAction()).assertExists()
    }

    /** AK-T039-11: switching the close-up during a run moves no figure of it. */
    @Test
    fun `watch closely changes no figure of the run`() {
        var closeUp by mutableStateOf(false)
        composeRule.setContent {
            BtDashboardTheme {
                LiveLinkPanel(
                    snapshot = snapshot(),
                    intervalMs = 1_000L,
                    onIntervalChange = {},
                    ldacTuning = LdacTuningState(),
                    onLdacQuality = {},
                    onDismissLdacMessage = {},
                    closeUpTrace = if (closeUp) {
                        LiveTrace.closeUp(500L).plus(TracePoint(timestampMs = 1_000L, bitrateKbps = 303.0))
                    } else {
                        LiveTrace.closeUp(500L)
                    },
                    closeUpEnabled = closeUp,
                    observationRun = { snapshot ->
                        ObservationRunSection(
                            snapshot,
                            ObservationRunUi(run = counting),
                            onStart = {},
                            onStop = {},
                            onThreshold = {},
                            onNoticeContinue = {},
                            onNoticeDismiss = {},
                        )
                    },
                )
            }
        }
        composeRule.waitForIdle()
        val runLines = { texts().filter { "observed" in it || STEP_ROW.containsMatchIn(it) } }
        val before = runLines()

        closeUp = true
        composeRule.waitForIdle()

        assertTrue(before.isNotEmpty())
        assertEquals(before, runLines())
    }

    /** AK-T039-12: at rest the section is one line and a chip (plus the question mark). */
    @Test
    fun `the resting section is one line and a chip`() {
        render(snapshot(), run = null)

        assertEquals(listOf("Observation run — off.", "Start"), texts())
        composeRule.onNodeWithContentDescription("What is Observation run?").assertExists()
    }

    /** AK-T039-13: seconds below 90 s, whole minutes from there, hours from an hour. */
    @Test
    fun `spans are stated no finer than the cadence allows`() {
        assertEquals("45 s", formatSpan(45_000L))
        assertEquals("89 s", formatSpan(89_000L))
        assertEquals("1 min", formatSpan(91_000L))
        assertEquals("24 min", formatSpan(24 * 60_000L))
        assertEquals("1 h 12 min", formatSpan(72 * 60_000L))
    }

    /** Decision 5: the notice and the first sentence of the second layer are one string. */
    @Test
    fun `the start notice says what the second layer says first`() {
        render(snapshot(), run = null)
        openExplanation()
        val sentence = "A run counts only while this screen is open; leaving it discards the run, and nothing is recorded."
        assertTrue(texts().any { it.startsWith(sentence) })
    }

    private companion object {
        /** A step row of the dwell list: "660 kbps — 2 min", "moving between steps — 4 s". */
        val STEP_ROW = Regex("""^(\d+ kbps|moving between steps|\d+ shorter steps?) — """)
    }
}
