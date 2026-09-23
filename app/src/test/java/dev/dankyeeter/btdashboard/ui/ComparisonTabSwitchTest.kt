package dev.dankyeeter.btdashboard.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.LdacStackState
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveCodecSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveDeviceSnapshot
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import dev.dankyeeter.btdashboard.ui.screens.monitor.ComparisonController
import dev.dankyeeter.btdashboard.ui.screens.monitor.ComparisonPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stands in for the Monitor screen's ViewModel: the same controller, none of the Bluetooth graph. */
class ComparisonHolder : ViewModel() {
    internal val controller = ComparisonController(
        scope = viewModelScope,
        pin = { CodecApplyOutcome.Applied("LDAC 990") },
        readBook = { _, _ -> emptyMap() },
    )
}

/**
 * AD-038 risk, mandatory test: a tab switch does not discard arm A. Runs the
 * bottom bar's own [navigateToTab] over a NavHost with the real routes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComparisonTabSwitchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun reading(timestampMs: Long) = LinkLiveSnapshot(
        timestampMs = timestampMs,
        device = LiveDeviceSnapshot(address = "XX:XX:XX:XX:37:8F", isConnected = true, isPlaying = true),
        codec = LiveCodecSnapshot(family = CodecFamily.LDAC, sampleRateHz = 96_000, codecSpecific1 = 1000L),
        ldac = LdacState.from(1000L, 96_000, LdacStackState(qualityMode = "HIGH", transmissionKbps = 990)),
    )

    @Test
    fun `arm A survives a switch to another tab and back`() {
        lateinit var nav: NavHostController
        var holder: ComparisonHolder? = null
        composeRule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Destination.BLUETOOTH.route) {
                composable(Destination.BLUETOOTH.route) { Text("Bluetooth") }
                composable(Destination.MONITORING.route) { holder = viewModel() }
            }
        }
        composeRule.runOnUiThread { nav.navigateToTab(Destination.MONITORING.route) }
        composeRule.waitForIdle()

        val first = requireNotNull(holder)
        val control = first.controller
        composeRule.runOnUiThread {
            var t = 1_000L
            control.onReading(reading(t), STEP_MS)
            control.onMeasure(Measure.NO_DISCOVERY)
            control.onCompare()
            while (control.ui.value.phase != ComparisonPhase.INSTRUCT && t < LIMIT_MS) {
                t += STEP_MS
                control.onReading(reading(t), STEP_MS)
            }
        }
        val armA = control.ui.value.armA
        assertNotNull(armA)

        composeRule.runOnUiThread { nav.navigateToTab(Destination.BLUETOOTH.route) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { nav.navigateToTab(Destination.MONITORING.route) }
        composeRule.waitForIdle()

        assertSame("the Monitor ViewModel came back, not a new one", first, holder)
        assertEquals(ComparisonPhase.INSTRUCT, first.controller.ui.value.phase)
        assertSame(armA, first.controller.ui.value.armA)
    }

    private companion object {
        const val STEP_MS = 1_000L
        const val LIMIT_MS = 2_000_000L
    }
}
