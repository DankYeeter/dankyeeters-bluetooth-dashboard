package dev.dankyeeter.btdashboard.ui.screens.hearing

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.dankyeeter.btdashboard.audio.eq.Ear
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
 * The dismissal guard on the clinical audiogram editor.
 *
 * This exists because of a defect found on the phone, twice in one evening:
 * sixteen thresholds transcribed from an ENT form, then a swipe that ended
 * outside the dialog — or a Back press with the keyboard closed — and every
 * value was gone without a word. Both gestures arrive at the same place, the
 * dialog's `onDismissRequest`, and it went straight to "close and forget".
 *
 * What the tests below pin is the asymmetry that makes the fix right: a form
 * with something in it asks before throwing it away, an untouched one does not
 * ask at all, and "Keep editing" gives back every character.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClinicalAudiogramDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var dismissals = 0
    private var saved: ClinicalAudiogram? = null

    private fun show(existing: ClinicalAudiogram? = null) {
        composeRule.setContent {
            BtDashboardTheme {
                ClinicalAudiogramDialog(
                    existing = existing,
                    onSave = { saved = it },
                    onClear = {},
                    onDismiss = { dismissals++ },
                )
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * The system Back key, delivered to the dialog window rather than to the
     * activity — which is where a Compose `Dialog` installs its own handler, and
     * therefore the only place the press the user makes actually lands.
     *
     * A tap outside the dialog is deliberately not simulated separately: both
     * gestures are the same single callback, `onDismissRequest`, and there is no
     * branch between them to get wrong.
     */
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

    // ---- the defect -----------------------------------------------------------

    @Test
    fun `back on a form with typed values asks instead of discarding them`() {
        show()
        type(Ear.LEFT, 250, "35")

        pressBack()

        composeRule.onNodeWithText("Discard entered values?").assertExists()
        // The whole point: nothing has been thrown away yet.
        assertEquals(0, dismissals)
    }

    @Test
    fun `keep editing gives every character back`() {
        show()
        type(Ear.LEFT, 250, "35")
        type(Ear.RIGHT, 4000, "45")
        pressBack()

        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Clinical audiogram").assertExists()
        composeRule.onNodeWithTag(cellTag(Ear.LEFT, 250)).assertTextContains("35")
        composeRule.onNodeWithTag(cellTag(Ear.RIGHT, 4000)).assertTextContains("45")
        assertEquals(0, dismissals)
    }

    @Test
    fun `discard is still available, once it has been asked for`() {
        show()
        type(Ear.LEFT, 250, "35")
        pressBack()

        composeRule.onNodeWithText("Discard").performClick()
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
    }

    // ---- the case that must not become annoying -------------------------------

    @Test
    fun `an untouched form closes on the first back press`() {
        show()

        pressBack()

        assertEquals(1, dismissals)
    }

    /**
     * Opening a stored audiogram and changing nothing is just as untouched as an
     * empty form: the guard compares against what was on screen when the dialog
     * opened, not against "is there anything in here".
     */
    @Test
    fun `reopening a stored audiogram and changing nothing closes freely`() {
        show(ClinicalAudiogram(leftDbHl = mapOf(250 to 10.0), rightDbHl = mapOf(250 to 15.0)))

        pressBack()

        assertEquals(1, dismissals)
    }

    /** Typing into a cell and clearing it again is back where it started. */
    @Test
    fun `a change that was undone does not count as unsaved work`() {
        show()
        type(Ear.LEFT, 500, "20")
        composeRule.onNodeWithTag(cellTag(Ear.LEFT, 500)).performTextClearance()
        composeRule.waitForIdle()

        pressBack()

        assertEquals(1, dismissals)
    }

    /** The buttons are untouched by the guard: Save still saves in one tap. */
    @Test
    fun `save still commits without a confirmation step`() {
        show()
        type(Ear.LEFT, 250, "35")

        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()

        assertEquals(mapOf(250 to 35.0), saved?.leftDbHl)
    }
}
