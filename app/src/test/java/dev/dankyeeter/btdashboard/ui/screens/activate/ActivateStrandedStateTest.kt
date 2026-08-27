package dev.dankyeeter.btdashboard.ui.screens.activate

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.dankyeeter.btdashboard.ui.Destination
import dev.dankyeeter.btdashboard.ui.ROUTE_ACTIVATE
import dev.dankyeeter.btdashboard.ui.ROUTE_ONBOARDING
import dev.dankyeeter.btdashboard.ui.ROUTE_WIZARD
import dev.dankyeeter.btdashboard.ui.leavesRestoredRoute
import dev.dankyeeter.btdashboard.ui.showsBottomBar
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The black screen that said "Helper running." and nothing else.
 *
 * Seen twice on the device: the whole app replaced by that one sentence, no
 * bottom bar, nothing tappable, only force-stop recovering it. Two independent
 * routes led there and both are pinned here.
 *
 * 1. **A finished activation outliving its helper.** `Done` is written once and
 *    the view model outlives any screen, while the gate re-opens the moment the
 *    helper connection drops — which happens under a healthy app, because a
 *    privileged call that throws forgets the connection. The gate then rendered
 *    a state whose entire content is that sentence, on a surface that has no
 *    navigation by design.
 * 2. **A restored back stack.** `activate` is a real destination the boot
 *    notification opens, so Navigation brings it back after process death — a
 *    full-screen route, no bottom bar, belonging to a session that is over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActivateStrandedStateTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // ---- the state can no longer outlive the helper --------------------------

    @Test
    fun `a finished activation with no helper becomes the button again`() {
        assertEquals(
            ActivateState.Idle,
            reconciled(ActivateState.Done, helperConnected = false),
        )
    }

    @Test
    fun `a finished activation with a live helper is left alone`() {
        assertEquals(
            ActivateState.Done,
            reconciled(ActivateState.Done, helperConnected = true),
        )
    }

    @Test
    fun `no other state is rewritten by a missing helper`() {
        // Reconciliation is about one stale claim, not a reset: a failure the
        // user is reading, or a pairing code half typed, must survive it.
        val failed = ActivateState.Failed("Wireless debugging is off.")
        val code = ActivateState.NeedsCode(wrongCode = true)

        assertEquals(failed, reconciled(failed, helperConnected = false))
        assertEquals(code, reconciled(code, helperConnected = false))
        assertEquals(ActivateState.Idle, reconciled(ActivateState.Idle, helperConnected = false))
    }

    // ---- the screen is never a dead end --------------------------------------

    @Test
    fun `the helper-running state carries a way on`() {
        var continued = false
        composeRule.setContent {
            BtDashboardTheme {
                ActivateActions(
                    state = ActivateState.Done,
                    onActivate = {},
                    onSubmitCode = {},
                    onOpenSettings = {},
                    onContinue = { continued = true },
                )
            }
        }

        composeRule.onNodeWithText("Helper running.").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").assertHasClickAction()
        composeRule.onNodeWithText("Continue").performClick()

        assertTrue("the only content on the gate must lead somewhere", continued)
    }

    @Test
    fun `inside the setup process it stays a plain step`() {
        // No button there: the process has its own way forward and moves on by
        // itself, and a second one would be a step that does nothing.
        composeRule.setContent {
            BtDashboardTheme {
                ActivateActions(
                    state = ActivateState.Done,
                    onActivate = {},
                    onSubmitCode = {},
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("Helper running.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Continue").assertCountEquals(0)
    }

    // ---- and it is never what the app comes back on --------------------------

    @Test
    fun `a restored activation screen is left for the dashboard`() {
        assertTrue(leavesRestoredRoute(ROUTE_ACTIVATE, requestedRoute = null))
    }

    @Test
    fun `an activation asked for in this session is honoured`() {
        // The boot notification's button. A deliberate request is not a leftover.
        assertFalse(leavesRestoredRoute(ROUTE_ACTIVATE, requestedRoute = ROUTE_ACTIVATE))
    }

    @Test
    fun `every other restored destination is left alone`() {
        assertFalse(leavesRestoredRoute(Destination.MONITORING.route, null))
        assertFalse(leavesRestoredRoute(ROUTE_WIZARD, null))
        assertFalse(leavesRestoredRoute(null, null))
    }

    @Test
    fun `what it lands on has the bottom bar`() {
        // The point of the whole guard: wherever the restore sends the user,
        // the app has to be navigable again.
        assertTrue(showsBottomBar(Destination.BLUETOOTH.route))
        Destination.entries.forEach { destination ->
            assertTrue(destination.label, showsBottomBar(destination.route))
        }
        // And the routes that legitimately hide it are still hiding it.
        assertFalse(showsBottomBar(ROUTE_ACTIVATE))
        assertFalse(showsBottomBar(ROUTE_ONBOARDING))
        assertFalse(showsBottomBar(ROUTE_WIZARD))
    }
}
