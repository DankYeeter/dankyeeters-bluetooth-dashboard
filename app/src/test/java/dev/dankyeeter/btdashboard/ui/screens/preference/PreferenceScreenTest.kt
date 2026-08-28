package dev.dankyeeter.btdashboard.ui.screens.preference

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.hearing.preference.FinalCheck
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceAxis
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceCandidate
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceEngine
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceLabelSource
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceProfile
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceRun
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceRunResult
import dev.dankyeeter.btdashboard.hearing.preference.TrialPhase
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The preference screens, driven straight from a state object.
 *
 * No ViewModel: a Robolectric run has no Bluetooth device, no DataStore and no
 * audio effect, and the interesting states — a full pool, a hand adjustment, a
 * run in progress — are all cheap to build by hand and expensive to reach
 * through the real flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferenceScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val layout = EqBandLayout.OCTAVE_10
    private val flat = List(layout.bandCount) { 0f }

    private class Recorder : PreferenceTestActions {
        val calls = mutableListOf<String>()
        var lastBass: Float? = null
        var lastTreble: Float? = null
        var lastLabel: String? = null
        var lastRemoved: String? = null
        var lastSlot: AbSlot? = null

        override fun start() { calls += "start" }
        override fun play(slot: AbSlot) { calls += "play"; lastSlot = slot }
        override fun confirm() { calls += "confirm" }
        override fun noDifference() { calls += "noDifference" }
        override fun setRunLabel(text: String) { calls += "setRunLabel"; lastLabel = text }
        override fun addAnotherSong() { calls += "addAnotherSong" }
        override fun finish() { calls += "finish" }
        override fun answerFinalCheck(slot: AbSlot?) { calls += "answerFinalCheck"; lastSlot = slot }
        override fun skipFinalCheck() { calls += "skipFinalCheck" }
        override fun setBassDb(db: Float) { calls += "setBassDb"; lastBass = db }
        override fun setTrebleDb(db: Float) { calls += "setTrebleDb"; lastTreble = db }
        override fun clearAdjustment() { calls += "clearAdjustment" }
        override fun removeRun(id: String) { calls += "removeRun"; lastRemoved = id }
        override fun openResult() { calls += "openResult" }
        override fun save() { calls += "save" }
        override fun discard() { calls += "discard" }
        override fun deleteProfile() { calls += "deleteProfile" }
        override fun requestCancel() { calls += "requestCancel" }
        override fun confirmCancel() { calls += "confirmCancel" }
        override fun dismissDialog() { calls += "dismissDialog" }
        override fun keepAdjustment() { calls += "keepAdjustment" }
        override fun useNewMeasurement() { calls += "useNewMeasurement" }
        override fun dismissMessage() { calls += "dismissMessage" }
    }

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent { BtDashboardTheme { content() } }
        composeRule.waitForIdle()
    }

    private fun run(
        id: String,
        bass: Float,
        treble: Float = 0f,
        label: String = "song $id",
        consistency: Double = 1.0,
        at: Long = 0,
    ) = PreferenceRun(
        id = id,
        label = label,
        labelSource = PreferenceLabelSource.MANUAL,
        createdAtMillis = at,
        candidate = PreferenceCandidate(bass, treble),
        consistency = consistency,
    )

    private fun profile(
        runs: List<PreferenceRun> = listOf(run("a", 4f), run("b", 5f), run("c", 4f)),
        manualBass: Float? = null,
        finalCheck: FinalCheck = FinalCheck.YOURS_WON,
    ) = PreferenceProfile(
        deviceKey = "abc123",
        deviceName = "Focal Bathys",
        runs = runs,
        layout = layout,
        baseLeftDb = flat,
        baseRightDb = flat,
        manualBassDb = manualBass,
        finalCheck = finalCheck,
    )

    private fun state(
        phase: PreferencePhase = PreferencePhase.IDLE,
        stored: PreferenceProfile? = null,
        draft: PreferenceProfile? = null,
        deviceKey: String? = "abc123",
        trial: PreferenceEngine.Step.Compare? = null,
        runResult: PreferenceRunResult? = null,
        otherProfiles: List<PreferenceProfile> = emptyList(),
        pendingRun: PreferenceRun? = null,
        confirmingCancel: Boolean = false,
        message: String? = null,
    ) = PreferenceUiState(
        phase = phase,
        deviceKey = deviceKey,
        deviceName = "Focal Bathys",
        stored = stored,
        draft = draft,
        otherProfiles = otherProfiles,
        trial = trial,
        runResult = runResult,
        pendingRun = pendingRun,
        confirmingCancel = confirmingCancel,
        message = message,
    )

    // ---- the card ------------------------------------------------------------

    @Test
    fun `the card says it measures taste, not hearing`() {
        val actions = Recorder()
        show { PreferenceTestCard(state(), actions) }

        // PanelHeader renders its label as a tracked eyebrow, so it is uppercase.
        composeRule.onNodeWithText("PREFERENCE TEST").assertExists()
        composeRule.onNodeWithText("Start preference test").assertExists()
        // The honest first layer, on the surface rather than behind the ?.
        composeRule.onNodeWithText(
            "Ten quick A/B choices over music you are already playing. Needs something " +
                "playing to work.",
        ).assertExists()
    }

    @Test
    fun `with no headphone connected the test cannot be started`() {
        show { PreferenceTestCard(state(deviceKey = null), Recorder()) }

        composeRule.onNodeWithText("Start preference test").assertIsNotEnabled()
        composeRule.onNodeWithText("Connect your headphones first.").assertExists()
    }

    @Test
    fun `a stored curve is summarised and can be reopened`() {
        val actions = Recorder()
        show { PreferenceTestCard(state(stored = profile()), actions) }

        composeRule.onNodeWithText("Add another song").assertIsEnabled()
        composeRule.onNodeWithText("Your preference curve").performClick()
        assertTrue(actions.calls.contains("openResult"))
    }

    /**
     * The binding rule, on screen: a curve measured on another pair is named and
     * not offered. Hiding it would read as data loss; offering it would apply a
     * judgement made through different drivers.
     */
    @Test
    fun `a curve for another headphone is named and not editable here`() {
        val other = profile().copy(deviceKey = "zzz", deviceName = "Sony XM5")
        show { PreferenceTestCard(state(deviceKey = "abc123", otherProfiles = listOf(other)), Recorder()) }

        composeRule.onNodeWithText(
            "Stored for Sony XM5 — connect that pair to use or edit it.",
        ).assertExists()
        // No result button, because there is no profile for the connected pair.
        composeRule.onNodeWithText("Your preference curve").assertDoesNotExist()
    }

    @Test
    fun `a refusal is shown in words and can be dismissed`() {
        val actions = Recorder()
        show { PreferenceTestCard(state(message = "Connect the headphones you want to tune."), actions) }

        composeRule.onNodeWithText("Connect the headphones you want to tune.").assertExists()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertTrue(actions.calls.contains("dismissMessage"))
    }

    // ---- a comparison --------------------------------------------------------

    private fun compare(index: Int = 0) = PreferenceEngine.Step.Compare(
        index = index,
        total = 10,
        phase = TrialPhase.LEAD_IN,
        axis = PreferenceAxis.BASS,
        a = PreferenceCandidate(6f, 0f),
        b = PreferenceCandidate(-6f, 0f),
        repeat = false,
    )

    @Test
    fun `a comparison offers A, B, a commit and no difference`() {
        val actions = Recorder()
        show {
            PreferenceTestContent(
                state(phase = PreferencePhase.RUNNING, draft = profile(), trial = compare()),
                actions,
            )
        }

        composeRule.onNodeWithText("Comparison 1 of 10").assertExists()
        composeRule.onNodeWithTag("preference-B").performClick()
        assertEquals(AbSlot.B, actions.lastSlot)
        composeRule.onNodeWithText("No difference").performClick()
        assertTrue(actions.calls.contains("noDifference"))
    }

    /**
     * Switching and answering are two taps, not one. A single tap that did both
     * would record a preference for a curve the listener never heard.
     */
    @Test
    fun `the commit button names whichever side is playing`() {
        val actions = Recorder()
        show {
            PreferenceTestContent(
                state(phase = PreferencePhase.RUNNING, draft = profile(), trial = compare())
                    .copy(playing = AbSlot.B),
                actions,
            )
        }

        composeRule.onNodeWithText("Prefer B").performClick()
        assertTrue(actions.calls.contains("confirm"))
    }

    @Test
    fun `stopping mid-run asks before throwing the answers away`() {
        val actions = Recorder()
        show {
            PreferenceTestContent(
                state(
                    phase = PreferencePhase.RUNNING,
                    draft = profile(),
                    trial = compare(index = 4),
                    confirmingCancel = true,
                ),
                actions,
            )
        }

        composeRule.onNodeWithText("Stop this song's test?").assertExists()
        composeRule.onNodeWithText("Keep going").performClick()
        assertTrue(actions.calls.contains("dismissDialog"))
    }

    // ---- one song's result ---------------------------------------------------

    @Test
    fun `a finished song shows its answer and asks for another`() {
        val actions = Recorder()
        show {
            PreferenceTestContent(
                state(
                    phase = PreferencePhase.RUN_RESULT,
                    draft = profile(runs = emptyList()),
                    runResult = PreferenceRunResult(
                        candidate = PreferenceCandidate(4.5f, -2f),
                        consistency = 1.0,
                        repeats = 2,
                        trials = emptyList(),
                    ),
                ),
                actions,
            )
        }

        composeRule.onNodeWithText("+4.5 / -2.0 dB").assertExists()
        composeRule.onNodeWithText("You gave the same answer both times it was checked.").assertExists()
        composeRule.onNodeWithText("Add another song").performScrollTo().performClick()
        assertTrue(actions.calls.contains("addAnotherSong"))
    }

    @Test
    fun `the song can be given a name by hand`() {
        val actions = Recorder()
        show {
            PreferenceTestContent(
                state(
                    phase = PreferencePhase.RUN_RESULT,
                    draft = profile(runs = emptyList()),
                    runResult = PreferenceRunResult(PreferenceCandidate(2f, 0f), 1.0, 2, emptyList()),
                ),
                actions,
            )
        }

        composeRule.onNodeWithTag(RUN_LABEL_TAG).performScrollTo().performTextInput("Blue Monday")
        assertEquals("Blue Monday", actions.lastLabel)
    }

    @Test
    fun `the first song is told why a third one matters`() {
        show {
            PreferenceTestContent(
                state(
                    phase = PreferencePhase.RUN_RESULT,
                    draft = profile(runs = emptyList()),
                    runResult = PreferenceRunResult(PreferenceCandidate(2f, 0f), 1.0, 2, emptyList()),
                ),
                Recorder(),
            )
        }

        composeRule.onNodeWithText("That is song 1", substring = true).assertExists()
    }

    // ---- the blind check -----------------------------------------------------

    @Test
    fun `the blind check does not say which side is which, and can be skipped`() {
        val actions = Recorder()
        show {
            PreferenceTestContent(state(phase = PreferencePhase.FINAL_CHECK, draft = profile()), actions)
        }

        composeRule.onNodeWithText("One last check").assertExists()
        composeRule.onNodeWithText(
            "One of these is the curve your songs asked for. The other is no change at " +
                "all, at the same loudness. You are not told which.",
        ).assertExists()
        composeRule.onNodeWithText("Skip the check").performClick()
        assertTrue(actions.calls.contains("skipFinalCheck"))
    }

    // ---- the pool's result ---------------------------------------------------

    @Test
    fun `the result shows the curve, the verdict, the sliders and the songs`() {
        val actions = Recorder()
        show { PreferenceTestContent(state(phase = PreferencePhase.RESULT, draft = profile()), actions) }

        composeRule.onNodeWithText("Personal preference").assertExists()
        composeRule.onNodeWithText("Consistent across your music.").assertExists()
        composeRule.onNodeWithText("song a").assertExists()
        composeRule.onNodeWithTag(BASS_SLIDER_TAG).assertExists()
        composeRule.onNodeWithTag(TREBLE_SLIDER_TAG).assertExists()
        composeRule.onNodeWithText("Save and apply").performScrollTo().performClick()
        assertTrue(actions.calls.contains("save"))
    }

    @Test
    fun `songs that disagree are reported as varying, and the songs are listed`() {
        val varied = profile(runs = listOf(run("a", -2f), run("b", 4f), run("c", 9f)))
        show { PreferenceTestContent(state(phase = PreferencePhase.RESULT, draft = varied), Recorder()) }

        composeRule.onNodeWithText("Your taste varies across songs", substring = true).assertExists()
        composeRule.onNodeWithText("Your songs disagreed by up to", substring = true).assertExists()
    }

    @Test
    fun `a thin pool says so`() {
        val thin = profile(runs = listOf(run("a", 4f)))
        show { PreferenceTestContent(state(phase = PreferencePhase.RESULT, draft = thin), Recorder()) }

        composeRule.onNodeWithText("One song so far", substring = true).assertExists()
    }

    @Test
    fun `losing the blind check is reported as weak rather than hidden`() {
        val weak = profile(finalCheck = FinalCheck.FLAT_WON)
        show { PreferenceTestContent(state(phase = PreferencePhase.RESULT, draft = weak), Recorder()) }

        composeRule.onNodeWithText("Weak — blind against no change", substring = true).assertExists()
        composeRule.onNodeWithText(
            "Blind against no change at all, you picked no change.",
        ).assertExists()
    }

    @Test
    fun `a hand adjustment is labelled and can be undone`() {
        val actions = Recorder()
        val adjusted = profile(manualBass = 7f)
        show { PreferenceTestContent(state(phase = PreferencePhase.RESULT, draft = adjusted), actions) }

        composeRule.onNodeWithText("adjusted").assertExists()
        composeRule.onNodeWithText("+7.0 / +0.0 dB").assertExists()
        composeRule.onNodeWithText("Back to what the songs said").performScrollTo().performClick()
        assertTrue(actions.calls.contains("clearAdjustment"))
    }

    @Test
    fun `a song can be taken back out of the pool`() {
        val actions = Recorder()
        show { PreferenceTestContent(state(phase = PreferencePhase.RESULT, draft = profile()), actions) }

        composeRule.onAllNodesWithText("Remove").onFirst().performScrollTo().performClick()
        assertTrue(actions.calls.contains("removeRun"))
    }

    /** The dirty rule: a new song never silently replaces a hand adjustment. */
    @Test
    fun `adding a song to a hand-adjusted curve asks first`() {
        val actions = Recorder()
        show {
            PreferenceTestContent(
                state(
                    phase = PreferencePhase.RESULT,
                    draft = profile(manualBass = 7f),
                    pendingRun = run("new", 2f),
                ),
                actions,
            )
        }

        composeRule.onNodeWithText("Replace your adjustment?").assertExists()
        composeRule.onNodeWithText("Keep my adjustment").performClick()
        assertTrue(actions.calls.contains("keepAdjustment"))
    }

    @Test
    fun `a neutral result is reported as a real answer`() {
        val neutral = profile(runs = listOf(run("a", 0f), run("b", 0.5f), run("c", 0f)))
        show { PreferenceTestContent(state(phase = PreferencePhase.RESULT, draft = neutral), Recorder()) }

        composeRule.onNodeWithText("You like it as it is", substring = true).assertExists()
    }
}
