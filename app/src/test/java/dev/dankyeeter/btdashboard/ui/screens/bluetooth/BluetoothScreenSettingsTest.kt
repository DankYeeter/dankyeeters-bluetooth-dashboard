package dev.dankyeeter.btdashboard.ui.screens.bluetooth

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Bluetooth tab keeps its settings on screen when nothing is connected.
 *
 * This is a regression test with a specific past: the tab used to replace the
 * whole settings card with one sentence whenever no headphone was in use, so
 * the screen looked empty exactly when someone wanted to check what would
 * happen once they put their headphones on. The fix draws the same card in
 * every state and only turns the controls off.
 *
 * Unlike `ScreenSmokeTest`, which deliberately refuses to assert on copy, this
 * one has to name something — otherwise it cannot tell "all settings, disabled"
 * apart from "one line of text". It names *section labels* rather than prose:
 * "Absolute volume" and "Bluetooth codec" are the domain's own words and move
 * far less than the explanatory sentences around them. No Bluetooth device
 * exists under Robolectric, which is precisely the state under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BluetoothScreenSettingsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun showScreen() {
        composeRule.setContent { BtDashboardTheme { BluetoothScreen() } }
        composeRule.waitForIdle()
    }

    @Test
    fun `the settings are on screen with nothing connected`() {
        showScreen()

        listOf(
            "Name",
            "EQ preset",
            "Absolute volume",
            "Bluetooth codec",
            "Apply automatically on connect",
            "Save",
        ).forEach { label ->
            val found = composeRule.onAllNodesWithText(label, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            assertTrue("expected \"$label\" on the Bluetooth tab with nothing connected", found)
        }
    }

    @Test
    fun `the settings cannot be edited with nothing connected`() {
        showScreen()

        // Visible but inert is the whole contract. A card that looked editable
        // and silently saved a profile against an empty device key would be
        // worse than the sentence it replaced.
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }
}
