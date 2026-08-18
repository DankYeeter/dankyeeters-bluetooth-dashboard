package dev.dankyeeter.btdashboard.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import dev.dankyeeter.btdashboard.ui.screens.dashboard.DashboardScreen
import dev.dankyeeter.btdashboard.ui.screens.eq.EqScreen
import dev.dankyeeter.btdashboard.ui.screens.hearing.HearingTestScreen
import dev.dankyeeter.btdashboard.ui.screens.monitor.MonitorScreen
import dev.dankyeeter.btdashboard.ui.screens.wizard.SetupWizardScreen
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One smoke test per top-level screen: does it compose at all?
 *
 * These are deliberately shallow. Every screen here reaches into Bluetooth,
 * Shizuku, DataStore or the audio effect framework on first composition, and
 * the failure mode that actually bites in this project is a screen that throws
 * before drawing anything — a missing permission path, a null service, a graph
 * that was never initialised. Asserting on rendered text would tie the tests to
 * copy that is still moving; asserting that the tree exists catches the crash.
 *
 * The permissions are all *denied* here, which is the interesting case: it is
 * the state a fresh install is in, and every screen is supposed to degrade
 * rather than fail in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun assertComposes(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent { BtDashboardTheme { content() } }
        composeRule.waitForIdle()
        // onRoot() throws if nothing was composed; this is the assertion.
        composeRule.onRoot().assertExists()
    }

    @Test
    fun `dashboard composes`() = assertComposes {
        DashboardScreen(onOpenOnboarding = {})
    }

    @Test
    fun `eq screen composes`() = assertComposes { EqScreen() }

    @Test
    fun `hearing test intro composes`() = assertComposes { HearingTestScreen() }

    @Test
    fun `monitor screen composes`() = assertComposes { MonitorScreen() }

    @Test
    fun `setup wizard composes`() = assertComposes { SetupWizardScreen(onDone = {}) }
}
