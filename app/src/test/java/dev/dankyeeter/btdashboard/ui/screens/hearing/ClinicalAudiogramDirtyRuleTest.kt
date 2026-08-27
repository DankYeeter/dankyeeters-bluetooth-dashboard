package dev.dankyeeter.btdashboard.ui.screens.hearing

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.hearing.CLINICAL_FREQUENCIES_HZ
import dev.dankyeeter.btdashboard.hearing.ClinicalAudiogram
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The dismissal guard as a rule over *every* cell, not over the one cell a
 * regression test happened to pick.
 *
 * [ClinicalAudiogramDialogTest] pins the defect it was written for — a swipe
 * that threw away a transcribed ENT form — using the left 250 Hz box and one
 * type-then-clear. That is the shape of the bug but not the shape of the rule,
 * and the rule is what a future edit will break: the guard compares two maps of
 * twenty-two text fields plus two free-text lines, and a comparison can be wrong
 * for one field while being right for the one somebody tested.
 *
 * Two properties, parameterised over the cells:
 *
 *  - **anything surviving anywhere is dirty** — one character in any one cell
 *    has to stop the dialog closing;
 *  - **typed and cleared everywhere is pristine** — filling several cells and
 *    then emptying all of them is back where it started and must close on the
 *    first press. A guard that fires there is a dialog that cannot be left.
 *
 * The cells swept are both ends of the range, the middle, and the inter-octaves
 * the app's own protocol skips — which is where an index-versus-frequency
 * mix-up would land, and the only place a full twenty-two-cell sweep would find
 * anything the five do not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClinicalAudiogramDirtyRuleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var dismissals = 0

    /**
     * A composition key, and the values the dialog opens with.
     *
     * `setContent` may be called once per test, and each case below needs a
     * dialog in its *opened* state — the guard is a comparison against exactly
     * that. Bumping a `key` around the dialog throws the whole subtree away and
     * builds a fresh one, which is what re-opening it means here; the alternative
     * would be one test per cell and no property at all.
     */
    private val caseKey = mutableStateOf(0)
    private val opensWith = mutableStateOf<ClinicalAudiogram?>(null)

    private fun start() {
        composeRule.setContent {
            BtDashboardTheme {
                key(caseKey.value) {
                    ClinicalAudiogramDialog(
                        existing = opensWith.value,
                        onSave = {},
                        onClear = {},
                        onDismiss = { dismissals++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Re-opens the dialog on a clean composition, as if the user had tapped it open. */
    private fun reopen(existing: ClinicalAudiogram? = null) {
        dismissals = 0
        opensWith.value = existing
        caseKey.value += 1
        composeRule.waitForIdle()
    }

    /** The system Back key, delivered to the dialog window where Compose installs it. */
    private fun pressBack() {
        composeRule.runOnUiThread {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun type(ear: Ear, hz: Int, text: String) {
        composeRule.onNodeWithTag(cellTag(ear, hz)).performTextInput(text)
        composeRule.waitForIdle()
    }

    private fun clear(ear: Ear, hz: Int) {
        composeRule.onNodeWithTag(cellTag(ear, hz)).performTextClearance()
        composeRule.waitForIdle()
    }

    private fun assertAsked(label: String) {
        composeRule.onNodeWithText("Discard entered values?").assertExists()
        assertEquals("$label: the form was thrown away without asking", 0, dismissals)
    }

    /** Both ends of the range, the middle, and the two inter-octaves. */
    private val cells: List<Pair<Ear, Int>> = listOf(
        Ear.LEFT to CLINICAL_FREQUENCIES_HZ.first(),
        Ear.LEFT to 750,
        Ear.LEFT to CLINICAL_FREQUENCIES_HZ.last(),
        Ear.RIGHT to CLINICAL_FREQUENCIES_HZ.first(),
        Ear.RIGHT to 1500,
        Ear.RIGHT to CLINICAL_FREQUENCIES_HZ.last(),
    )

    private val storedForm = ClinicalAudiogram(
        leftDbHl = CLINICAL_FREQUENCIES_HZ.associateWith { 10.0 },
        rightDbHl = CLINICAL_FREQUENCIES_HZ.associateWith { 15.0 },
    )

    // ---- any single surviving character is dirty, whichever cell holds it -------

    @Test
    fun `one character in any cell stops the dialog closing`() {
        start()
        cells.forEach { (ear, hz) ->
            reopen()
            type(ear, hz, "4")

            pressBack()

            assertAsked("$ear $hz Hz")
        }
    }

    /**
     * A lone minus sign is the case the guard is deliberately built around: it
     * does not parse, so a check on the *parsed* audiogram would call the form
     * pristine — while a half-typed cell is a form actively being filled in, and
     * that is precisely when a stray swipe costs the most.
     */
    @Test
    fun `a half-typed value that does not parse yet still counts as work`() {
        start()
        cells.forEach { (ear, hz) ->
            reopen()
            type(ear, hz, "-")

            pressBack()

            assertAsked("$ear $hz Hz, lone minus")
        }
    }

    /**
     * The same on a form that was opened with values in it: editing one cell of
     * a stored audiogram is unsaved work exactly as typing into a blank one is.
     */
    @Test
    fun `editing one cell of a stored audiogram is unsaved work`() {
        start()
        cells.forEach { (ear, hz) ->
            reopen(storedForm)
            type(ear, hz, "5")

            pressBack()

            assertAsked("$ear $hz Hz on a stored form")
        }
    }

    /**
     * The mirror image: emptying a cell that had a value is a change just as
     * filling an empty one is. Deleting a transcribed threshold by accident is
     * the same loss as typing one and losing it.
     */
    @Test
    fun `clearing a stored value is a change, not a return to pristine`() {
        start()
        cells.forEach { (ear, hz) ->
            reopen(storedForm)
            clear(ear, hz)

            pressBack()

            assertAsked("cleared $ear $hz Hz")
        }
    }

    // ---- typed then cleared across several cells is pristine again -------------

    /**
     * The undo case, across many cells rather than one.
     *
     * Clearing a field leaves an empty string in the state map where there was
     * no key at all before, so "back where it started" is a claim about how the
     * comparison treats blanks — and it has to hold for every cell that was
     * touched, not merely for the last one.
     */
    @Test
    fun `typing into several cells and clearing them all closes freely`() {
        start()
        reopen()
        cells.forEachIndexed { i, (ear, hz) -> type(ear, hz, "${10 + i}") }
        cells.forEach { (ear, hz) -> clear(ear, hz) }

        pressBack()

        assertEquals("a form back at its starting state asked anyway", 1, dismissals)
    }

    /**
     * The asymmetry that makes both halves necessary: clear every cell but one
     * and the guard must still fire. This is the case a "did anything change at
     * all" flag gets wrong — either set by the typing and never unset, or unset
     * by the clearing and never set again.
     */
    @Test
    fun `clearing all but one cell is still dirty`() {
        start()
        cells.indices.forEach { survivor ->
            reopen()
            cells.forEachIndexed { i, (ear, hz) -> type(ear, hz, "${10 + i}") }
            cells.forEachIndexed { i, (ear, hz) -> if (i != survivor) clear(ear, hz) }

            pressBack()

            assertAsked("survivor ${cells[survivor]}")
        }
    }

    /** The two free-text lines are part of the form and obey the same rule. */
    @Test
    fun `the date and source lines are guarded like the cells`() {
        start()
        listOf("Date on the form", "Where it came from").forEach { label ->
            reopen()
            composeRule.onNodeWithText(label).performTextInput("x")
            composeRule.waitForIdle()

            pressBack()

            assertAsked(label)
        }
    }
}
