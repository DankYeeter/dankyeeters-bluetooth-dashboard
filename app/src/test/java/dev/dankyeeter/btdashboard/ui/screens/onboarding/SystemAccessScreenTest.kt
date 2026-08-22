package dev.dankyeeter.btdashboard.ui.screens.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.privileged.PrivilegedBootstrap
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The System access screen, which `ScreenSmokeTest` does not cover.
 *
 * It lives here rather than in that file only because two other workers are
 * editing the shared test class right now; fold it in once they land.
 *
 * Two assertions, and the second is the interesting one. The screen exists to
 * hand the user one command, and that command is *generated* — it carries a
 * token minted on this device and the app's own package path. A screen that
 * composed fine but showed the wrong command, or none, would look completely
 * healthy. So the test asks the bootstrap for the command and then goes
 * looking for exactly that string on screen.
 *
 * Deliberately not asserting on prose: the copy is still moving, and pinning
 * sentences would make every wording change a test failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemAccessScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `system access screen composes with no shell running`() {
        composeRule.setContent { BtDashboardTheme { SystemAccessScreen(onDone = {}) } }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test
    fun `the helper's ADB command is on screen`() {
        val expected = PrivilegedBootstrap(ApplicationProvider.getApplicationContext())
            .adbCommand()

        composeRule.setContent { BtDashboardTheme { SystemAccessScreen(onDone = {}) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(expected, substring = true).assertExists()
    }
}
