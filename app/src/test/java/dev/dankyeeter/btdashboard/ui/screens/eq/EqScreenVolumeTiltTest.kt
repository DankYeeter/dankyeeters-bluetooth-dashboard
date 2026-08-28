package dev.dankyeeter.btdashboard.ui.screens.eq

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The quiet-listening tilt row is on the EQ screen, and it is off until asked.
 *
 * Like `BluetoothScreenSettingsTest` this has to name something, and it names
 * the control's label rather than the paragraph behind the question mark —
 * prose moves, a switch's label is the contract. The second assertion is the
 * one with teeth: the readout line only exists while the feature is on, so its
 * absence on a fresh install is what proves the default is off rather than
 * merely that a switch was drawn.
 *
 * The readout is matched on "At this volume" rather than on the feature's name,
 * which it used to repeat. Once the row itself is called "Quiet-listening tilt"
 * the old prefix said the same words twice and, worse, made the presence check
 * unable to tell the label from the readout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EqScreenVolumeTiltTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun showScreen() {
        composeRule.setContent { BtDashboardTheme { EqScreen() } }
        composeRule.waitForIdle()
    }

    private fun nodesWith(text: String) =
        composeRule.onAllNodesWithText(text, substring = true, ignoreCase = true)
            .fetchSemanticsNodes()

    @Test
    fun `the quiet-listening tilt row is on the screen`() {
        showScreen()

        assertTrue(
            "expected the quiet-listening tilt switch on the EQ screen",
            nodesWith("Quiet-listening tilt").isNotEmpty(),
        )
    }

    @Test
    fun `the feature is off until it is switched on`() {
        showScreen()

        assertTrue(
            "the tilt readout must not appear while the feature is off",
            nodesWith("At this volume").isEmpty(),
        )
        assertTrue(
            "the hold-to-compare control belongs to a layer that is running",
            nodesWith("Hold to hear without it").isEmpty(),
        )
    }

    /**
     * Loudness restoration only re-routes boosts that are in the curve, and a
     * fresh install has none. The row stays on screen — hiding it would make
     * the feature undiscoverable for the people who will later have a curve
     * worth restoring — and says so instead of waiting to be switched on and
     * doing nothing.
     */
    @Test
    fun `loudness restoration says why it is unavailable on a flat curve`() {
        showScreen()

        assertTrue(
            "the row must stay visible",
            nodesWith("Loudness restoration").isNotEmpty(),
        )
        assertTrue(
            "expected the flat-curve line under the disabled row",
            nodesWith("this would do nothing").isNotEmpty(),
        )
    }
}
