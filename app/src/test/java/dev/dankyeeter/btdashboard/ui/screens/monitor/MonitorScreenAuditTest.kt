package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.QualityReportAvailability
import dev.dankyeeter.btdashboard.monitor.sampling.MonitorStatus
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingMode
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the Monitoring page no longer says.
 *
 * `ScreenSmokeTest` deliberately asserts no copy — it is there to catch a screen
 * that throws before drawing. This one is the opposite and is deliberately
 * narrow: it names the lines the content audit removed, so that each of them has
 * to be re-added on purpose rather than drift back in as "one more helpful
 * sentence". Every string below was a real line on this page.
 *
 * The panels are rendered directly rather than through `MonitorScreen`. That is
 * not only for isolation: composing the screen starts a ViewModel that reaches
 * for Bluetooth and the privileged helper and fails *asynchronously* when
 * nothing is granted, and those failures surface in whichever compose test runs
 * next — so a screen-level assertion here would fail tests that never touched
 * this code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MonitorScreenAuditTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent { BtDashboardTheme { content() } }
        composeRule.waitForIdle()
    }

    private fun assertHides(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).assertCountEquals(0)

    private fun assertShows(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).onFirst().assertExists()

    /**
     * The data source is a reading, not a paragraph. "Reading the link through
     * the Bluetooth stack's own dump." spent fourteen words naming one noun,
     * beside a pill that already said whether anything was reading at all.
     */
    @Test
    fun `the data source is named rather than described`() {
        render {
            DataSourcePanel(
                mode = SamplingMode.ACTIVE,
                source = LinkDataSource.DUMPSYS,
                bqr = QualityReportAvailability.Unavailable("not privileged"),
                samplingReason = "playing, screen on",
                capturedMs = null,
                keptOffScreen = false,
                onWatchLive = {},
                onStopCapture = {},
            )
        }

        assertHides("Reading the link through")
        assertHides("Nothing is reading the link right now")
        // The source is still named — in two words, as a label.
        assertShows("Bluetooth stack dump")
        // And the state, and the control the panel exists to carry.
        assertShows("Watching")
        assertShows("Watch live")
        // The sampler's own reason string is a first-layer line no longer.
        assertHides("playing, screen on")
    }

    /**
     * The tally under the timeline: "0 samples · 0 events" counts the app's own
     * bookkeeping, which nobody can check against anything they heard.
     */
    @Test
    fun `the timeline carries no sample tally`() {
        render { TimelinePanel(samples = emptyList(), events = emptyList()) }

        assertHides("samples ·")
        assertHides("0 events")
        assertShows("Nothing recorded yet")
    }

    /** AK-T054-1, the deep-capture half: announced while it runs, with its progress. */
    @Test
    fun `a running deep capture says it keeps capturing off the screen`() {
        renderDeepCapture(capturedMs = 60_000L, keptOffScreen = true)

        assertExactly("Keeps capturing for up to 5 minutes even off this screen — stop it here or from the notification.")
        assertExactly("60 s of 5 min captured.")
        assertHides(DEEP_NEEDS_THIS_SCREEN)
    }

    /** AD-039 point 5: Android refused the service, so the screen asks to stay open instead. */
    @Test
    fun `a deep capture without the service asks for the screen instead`() {
        renderDeepCapture(capturedMs = 60_000L, keptOffScreen = false)

        assertExactly(DEEP_NEEDS_THIS_SCREEN)
        assertExactly("60 s of 5 min captured.")
        assertHides(DEEP_KEEPS_CAPTURING)
    }

    @Test
    fun `no capture, no capture lines`() {
        renderDeepCapture(capturedMs = null, keptOffScreen = true)

        assertHides(DEEP_KEEPS_CAPTURING)
        assertHides(DEEP_NEEDS_THIS_SCREEN)
        assertHides("captured.")
    }

    @Test
    fun `captured time runs from the capture's start to its newest sample`() {
        val running = MonitorStatus(mode = SamplingMode.DEEP, deepCaptureUntilMs = 400_000L, deepCaptureSinceMs = 100_000L)
        val samples = listOf(sample(90_000L), sample(160_000L), sample(130_000L))

        assertEquals(60_000L, deepCapturedMs(running, samples))
        assertEquals("nothing since the start", 0L, deepCapturedMs(running, listOf(sample(90_000L))))
        assertEquals(0L, deepCapturedMs(running, emptyList()))
        assertNull("stopped", deepCapturedMs(running.copy(deepCaptureUntilMs = 0L), samples))
        assertNull("not in DEEP", deepCapturedMs(running.copy(mode = SamplingMode.ACTIVE), samples))
    }

    private fun renderDeepCapture(capturedMs: Long?, keptOffScreen: Boolean) = render {
        DataSourcePanel(
            mode = SamplingMode.DEEP,
            source = LinkDataSource.DUMPSYS,
            bqr = QualityReportAvailability.Unavailable("not privileged"),
            samplingReason = "deep capture requested",
            capturedMs = capturedMs,
            keptOffScreen = keptOffScreen,
            onWatchLive = {},
            onStopCapture = {},
        )
    }

    private fun assertExactly(text: String) =
        composeRule.onAllNodesWithText(text).assertCountEquals(1)

    private fun sample(atMs: Long) =
        LinkQualitySample(timestampMs = atMs, deviceAddress = "XX:XX:XX:XX:37:8F", source = LinkDataSource.DUMPSYS)
}
