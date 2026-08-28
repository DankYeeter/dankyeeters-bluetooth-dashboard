package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventSummary
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The event log's two layers, as rendered.
 *
 * The panel is the answer to one complaint: the log was forty rows of the
 * machinery's own sentences, and reading it meant reading all of them. So the
 * properties pinned here are the ones that complaint turns into —
 *
 *  - a row is **one short line**, and it is not the sentence behind it;
 *  - the sentence is still there, one tap down, with the values it carried;
 *  - events that exist to be read afterwards rather than scanned are **not in
 *    the list at all**, and the log says how many it is holding back rather
 *    than quietly dropping them.
 *
 * The wording itself is `MonitorEventSummaryTest` in `:core-monitor`; this is
 * about what reaches the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MonitorEventLogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(events: List<MonitorEvent>) {
        composeRule.setContent {
            BtDashboardTheme { EventLogPanel(events) }
        }
        composeRule.waitForIdle()
    }

    private fun assertShows(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).onFirst().assertExists()

    private fun assertHides(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).assertCountEquals(0)

    private fun event(
        type: MonitorEventType,
        detail: String,
        deviceName: String? = "Encore",
        codec: CodecFamily? = null,
        bitrateKbps: Int? = null,
        timestampMs: Long = 1_700_000_000_000L,
    ) = MonitorEvent(
        timestampMs = timestampMs,
        deviceAddress = "AC:DE:48:00:37:8F",
        deviceName = deviceName,
        type = type,
        detail = detail,
        codec = codec,
        bitrateKbps = bitrateKbps,
    )

    /** The sentence a dropout used to put straight into the list. */
    private val dropoutDetail =
        "Audio was lost in a 2 s window: 3 app underruns, 1 encoder underflow."

    private fun dropout() = event(MonitorEventType.DROPOUT, dropoutDetail)

    // ---- layer one -----------------------------------------------------------

    @Test
    fun `a row shows the short line and not the sentence behind it`() {
        render(listOf(dropout()))

        assertShows("Audio dropout")
        assertHides("app underruns")
    }

    /**
     * The bound, on the thing that is actually drawn. The wording module
     * guarantees it, and this is the check that the panel renders *that* string
     * rather than helpfully substituting something longer.
     */
    @Test
    fun `every rendered row is within the length bound`() {
        val events = listOf(
            event(MonitorEventType.ACL_CONNECTED, "Encore connected to this phone."),
            dropout(),
            event(
                MonitorEventType.BITRATE_MODE_CHANGED,
                "The measured rate fell from 990 to 660 kbps. Measured out of the stack.",
                codec = CodecFamily.LDAC,
                bitrateKbps = 660,
                timestampMs = 1_700_000_002_000L,
            ),
        )
        render(events)

        MonitorEventSummary.lines(events).forEach { line ->
            assertTrue(
                "${line.summary} is ${line.summary.length} chars",
                line.summary.length <= MonitorEventSummary.MAX_CHARS,
            )
            assertShows(line.summary)
        }
    }

    /**
     * A nameless headphone puts its address in `deviceName`, and the summary is
     * built from that name. The list is a place a real MAC must not appear, for
     * the same reason as everywhere else on this screen — see [redactAddresses].
     */
    @Test
    fun `an unnamed device is identified without printing a real address`() {
        render(
            listOf(
                event(
                    MonitorEventType.ACL_CONNECTED,
                    "connected",
                    deviceName = "AC:DE:48:00:37:8F",
                ),
            ),
        )

        assertShows("XX:XX:XX:XX:37:8F")
        assertHides("AC:DE:48")
    }

    // ---- layer two -----------------------------------------------------------

    @Test
    fun `tapping a row opens the full wording and its values`() {
        render(
            listOf(
                event(
                    MonitorEventType.BITRATE_MODE_CHANGED,
                    "The measured rate fell from 990 to 660 kbps.",
                    codec = CodecFamily.LDAC,
                    bitrateKbps = 660,
                ),
            ),
        )

        assertHides("The measured rate fell")
        composeRule.onNodeWithText("LDAC 660 kbps", substring = true).performClick()
        composeRule.waitForIdle()

        assertShows("The measured rate fell from 990 to 660 kbps.")
        // The payload: the values, without a class name or an enum constant.
        assertShows("Encore · LDAC · 660 kbps")
        assertHides("BITRATE_MODE_CHANGED")
    }

    /** A detail is a look, not a destination: the same tap puts it away. */
    @Test
    fun `tapping the open row closes it again`() {
        render(listOf(dropout()))

        composeRule.onNodeWithText("Audio dropout", substring = true).performClick()
        composeRule.waitForIdle()
        assertShows("app underruns")

        composeRule.onNodeWithText("Audio dropout", substring = true).performClick()
        composeRule.waitForIdle()
        assertHides("app underruns")
    }

    /** Only one at a time — otherwise the log becomes a stack of open cards. */
    @Test
    fun `opening a second row closes the first`() {
        render(
            listOf(
                dropout(),
                event(
                    MonitorEventType.ACL_DISCONNECTED,
                    "The link to Encore dropped.",
                    timestampMs = 1_700_000_005_000L,
                ),
            ),
        )

        composeRule.onNodeWithText("Audio dropout", substring = true).performClick()
        composeRule.waitForIdle()
        assertShows("app underruns")

        composeRule.onNodeWithText("Encore disconnected", substring = true).performClick()
        composeRule.waitForIdle()

        assertShows("The link to Encore dropped.")
        assertHides("app underruns")
    }

    // ---- the filter ----------------------------------------------------------

    @Test
    fun `diagnostic events stay out of the list`() {
        render(
            listOf(
                dropout(),
                event(
                    MonitorEventType.ACTIVE_DEVICE_CHANGED,
                    "Encore is now the active audio device.",
                    timestampMs = 1_700_000_003_000L,
                ),
            ),
        )

        assertShows("Audio dropout")
        assertHides("is now active")
        // Held back, not dropped: the count is the honest half of a filter.
        assertShows("Diagnostics (1)")
    }

    @Test
    fun `the chip brings the diagnostic events back`() {
        render(
            listOf(
                dropout(),
                event(
                    MonitorEventType.QUALITY_REPORT,
                    "Bitrate dropped from 990 to 660 kbps; Signal is weak (-85 dBm)",
                    timestampMs = 1_700_000_003_000L,
                ),
            ),
        )

        composeRule.onNodeWithText("Diagnostics (1)", substring = true).performClick()
        composeRule.waitForIdle()

        assertShows("Link anomaly noticed")
        // And still only as a line — its raw text stays in the detail layer.
        assertHides("Signal is weak")
    }

    /** A filter that can only ever show the same list is a control that lies. */
    @Test
    fun `there is no chip when nothing is being held back`() {
        render(listOf(dropout()))

        assertHides("Diagnostics")
    }

    // ---- empty states --------------------------------------------------------

    @Test
    fun `an empty log says what would appear here`() {
        render(emptyList())

        assertShows("No events yet")
    }

    /**
     * A log that holds only diagnostics is not an empty log, and saying "no
     * events yet" there would be false — the chip beside this sentence is
     * offering exactly the events it claims do not exist.
     */
    @Test
    fun `a log of only diagnostics does not claim to be empty`() {
        render(
            listOf(event(MonitorEventType.ACTIVE_DEVICE_CHANGED, "Encore is now active.")),
        )

        assertShows("Nothing but diagnostics")
        assertHides("No events yet")
    }
}
