package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxDelta
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxStats
import dev.dankyeeter.btdashboard.monitor.link.live.InputStreamSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LinkObservability
import dev.dankyeeter.btdashboard.monitor.link.live.LiveCodecSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveDeviceSnapshot
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the panel is allowed to say.
 *
 * The smoke test only proves the screen composes, and it composes with an empty
 * snapshot — which is exactly the state in which none of the honesty rules can
 * be broken. These render a populated link instead and assert the four claims
 * the panel must never get wrong: an adaptive LDAC link shows no rate, a pinned
 * one shows the rate of the *right* sample-rate family, a lossless window says
 * so quietly, and a lossy one says how much and over how long.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveLinkPanelScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(
        snapshot: LinkLiveSnapshot,
        overview: LiveTrace = LiveTrace.overview(2_000L),
        closeUp: LiveTrace = LiveTrace.closeUp(500L),
        closeUpEnabled: Boolean = false,
    ) {
        composeRule.setContent {
            BtDashboardTheme {
                LiveLinkPanel(
                    snapshot = snapshot,
                    intervalMs = 2_000L,
                    onIntervalChange = {},
                    ldacTuning = LdacTuningState(),
                    onLdacQuality = {},
                    onDismissLdacMessage = {},
                    overviewTrace = overview,
                    closeUpTrace = closeUp,
                    closeUpEnabled = closeUpEnabled,
                    onCloseUpEnabled = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** A minute of steady link with one lossy window in the middle. */
    private fun overviewWithLoss(): LiveTrace {
        var trace = LiveTrace.overview(2_000L)
        (0 until 20).forEach { i ->
            trace = trace.plus(
                TracePoint(
                    timestampMs = 1_000L + i * 2_000L,
                    packetsPerSecond = if (i == 10) 120.0 else 431.0,
                    framesPerPacket = 4.0,
                    lossCount = if (i == 10) 3 else 0,
                ),
            )
        }
        return trace
    }

    private fun assertShows(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).onFirst().assertExists()

    @Test
    fun `adaptive ldac shows no bitrate`() {
        // codecSpecific1 = 0: nobody pinned a quality, so the stack runs ABR and
        // there is no rate to print. A "990" here would be the exact lie the
        // whole live module is built to avoid.
        render(snapshot(codecSpecific1 = 0L))

        assertShows("Adaptive")
        assertShows("not observable")
    }

    @Test
    fun `pinned ldac shows the rate of its sample rate family`() {
        // 44.1 kHz runs the 909/606/303 ladder, not 990/660/330.
        render(snapshot(codecSpecific1 = 1000L, sampleRateHz = 44_100))

        assertShows("909 kbps (pinned)")
    }

    @Test
    fun `negotiated format and the input it carries are both named`() {
        render(snapshot(codecSpecific1 = 1000L))

        assertShows("96 kHz")
        assertShows("32 bit")
        assertShows("stereo")
        // The input side: the app's own rate, before whatever the link does.
        assertShows("In:")
        assertShows("44.1 kHz")
    }

    @Test
    fun `a clean window is quiet`() {
        render(snapshot(codecSpecific1 = 0L))

        assertShows("No loss this window.")
    }

    @Test
    fun `a lossy window names the counters and the window`() {
        render(
            snapshot(codecSpecific1 = 0L).let { base ->
                base.copy(
                    inputs = base.inputs.map { it.copy(underrunDelta = 3L) },
                    txDelta = base.txDelta?.copy(underflows = 1L),
                )
            },
        )

        assertShows("Audio lost:")
        assertShows("3 app underruns")
        assertShows("1 encoder underflow")
        assertShows("in the last 2 s")
    }

    @Test
    fun `an unnamed device is identified without printing a real address`() {
        // A userdebug build does not redact the dump, so the panel does its own
        // masking rather than trusting the source. The two octets that stay are
        // what tells two connected headphones apart.
        render(
            snapshot(codecSpecific1 = 0L).let { base ->
                base.copy(device = base.device?.copy(name = null))
            },
        )

        assertShows("XX:XX:XX:XX:37:8F")
        composeRule.onAllNodesWithText("AC:DE:48", substring = true).assertCountEquals(0)
    }

    @Test
    fun `an offloaded codec says its tx counters do not apply`() {
        render(
            snapshot(codecSpecific1 = 0L).let { base ->
                base.copy(codec = base.codec?.copy(isOffloaded = true))
            },
        )

        assertShows("encoded by the controller")
    }

    @Test
    fun `both graphs are labelled with the window they cover`() {
        render(snapshot(codecSpecific1 = 0L), overview = overviewWithLoss())

        assertShows("Last 60 seconds")
        assertShows("Last 10 seconds")
    }

    @Test
    fun `the overview caption reads the rate and the loss off the window`() {
        render(snapshot(codecSpecific1 = 0L), overview = overviewWithLoss())

        assertShows("431 packets/s now")
        assertShows("peak 431")
        assertShows("3 loss marks")
    }

    @Test
    fun `an empty close-up says it is off rather than drawing a flat line`() {
        render(snapshot(codecSpecific1 = 0L), overview = overviewWithLoss())

        assertShows("Watch closely")
        assertShows("reads the Bluetooth stack twice a second")
    }

    @Test
    fun `switching the close-up on changes what the chip says`() {
        render(snapshot(codecSpecific1 = 0L), closeUpEnabled = true)

        assertShows("Watching")
        // The cost line belongs to the off state; once it is running the user
        // has already been told.
        composeRule.onAllNodesWithText("Off by default", substring = true).assertCountEquals(0)
    }

    @Test
    fun `an offloaded link explains the empty graph instead of plotting zero`() {
        val offloaded = LiveTrace.overview(2_000L).withReason(
            "AAC is encoded by the controller, so the host cannot see the stream — " +
                "there is no throughput to plot.",
            LinkObservability.OFFLOADED,
        )

        render(snapshot(codecSpecific1 = 0L), overview = offloaded)

        assertShows("no throughput to plot")
    }

    private fun snapshot(
        codecSpecific1: Long,
        sampleRateHz: Int = 96_000,
    ) = LinkLiveSnapshot(
        timestampMs = 1_700_000_000_000L,
        device = LiveDeviceSnapshot(
            // The address from the field report, so the masking assertions read
            // against the same string the owner's phone produced.
            address = "AC:DE:48:00:37:8F",
            name = "Bathys",
            isConnected = true,
            isActive = true,
            isPlaying = true,
        ),
        codec = LiveCodecSnapshot(
            family = CodecFamily.LDAC,
            sampleRateHz = sampleRateHz,
            bitsPerSample = 32,
            channelMode = ChannelMode.STEREO,
            codecSpecific1 = codecSpecific1,
        ),
        ldac = LdacState.from(codecSpecific1, sampleRateHz),
        tx = A2dpTxStats(enqueueCount = 8_000, framesPerPacketAvg = 4),
        txDelta = A2dpTxDelta(windowMs = 2_000, enqueued = 862),
        inputs = listOf(
            InputStreamSnapshot(
                uid = 10_123,
                pid = 4_242,
                sessionId = 8_009,
                sampleRateHz = 44_100,
                channelCount = 2,
                usage = "USAGE_MEDIA",
                contentType = "CONTENT_TYPE_MUSIC",
                underrunCount = 0,
                underrunDelta = 0,
            ),
        ),
    )
}
