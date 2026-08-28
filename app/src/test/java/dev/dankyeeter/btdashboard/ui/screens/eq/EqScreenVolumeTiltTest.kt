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
 * The volume-aware tuning row is on the EQ screen, and it is off until asked.
 *
 * Like `BluetoothScreenSettingsTest` this has to name something, and it names
 * the control's label rather than the paragraph behind the question mark —
 * prose moves, a switch's label is the contract. The second assertion is the
 * one with teeth: the readout line only exists while the feature is on, so its
 * absence on a fresh install is what proves the default is off rather than
 * merely that a switch was drawn.
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
    fun `the volume-aware tuning row is on the screen`() {
        showScreen()

        assertTrue(
            "expected the volume-aware tuning switch on the EQ screen",
            nodesWith("Volume-aware tuning").isNotEmpty(),
        )
    }

    @Test
    fun `the feature is off until it is switched on`() {
        showScreen()

        assertTrue(
            "the tilt readout must not appear while the feature is off",
            nodesWith("Quiet-listening tilt").isEmpty(),
        )
    }
}
