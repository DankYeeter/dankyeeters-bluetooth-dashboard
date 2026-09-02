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
import dev.dankyeeter.btdashboard.monitor.link.live.LdacStackState
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LinkObservability
import dev.dankyeeter.btdashboard.monitor.link.live.LiveCodecSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveDeviceSnapshot
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import dev.dankyeeter.btdashboard.ui.tuning.LdacTuningState
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
 * be broken. These render a populated link instead and assert the claims the
 * panel must never get wrong: an adaptive LDAC link shows the rate it is
 * actually running at *and labels it a measurement*, a pinned one shows the spec
 * figure and the measurement side by side, a build that reports neither still
 * refuses honestly, a lossless window says so quietly, and a lossy one says how
 * much and over how long.
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

    /**
     * A minute of a measured LDAC link with one lossy window in the middle.
     *
     * The plotted series is the bitrate, so the dip is a rate drop rather than a
     * packet-rate dip — which is what the graph is now about.
     */
    private fun overviewWithLoss(): LiveTrace {
        var trace = LiveTrace.overview(2_000L)
        (0 until 20).forEach { i ->
            trace = trace.plus(
                TracePoint(
                    timestampMs = 1_000L + i * 2_000L,
                    bitrateKbps = if (i == 10) 330.0 else 492.0,
                    packetsPerSecond = 50.0,
                    lossCount = if (i == 10) 3 else 0,
                ),
            )
        }
        return trace
    }

    private fun assertShows(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).onFirst().assertExists()

    private fun assertHides(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).assertCountEquals(0)

    /** What the stack's `LDAC quality mode` prints for a given pin. */
    private fun stackToken(codecSpecific1: Long): String = when (codecSpecific1) {
        1000L -> "HIGH"
        1001L -> "MID"
        1002L -> "LOW"
        else -> "ABR"
    }

    /**
     * The state the whole rebuild was for: unpinned, so the *configuration* is
     * adaptive, and the rate is nonetheless a reading rather than a refusal.
     *
     * "(measured)" has to be in the first layer. This is the number people have
     * been shown as a codec headline figure for years, and the one thing that
     * separates this panel from that is saying which kind of number it is
     * without being asked.
     */
    @Test
    fun `adaptive ldac shows the rate it is actually running at`() {
        render(snapshot(codecSpecific1 = 0L, measuredKbps = 396))

        assertShows("Adaptive — 396 kbps right now (measured)")
        assertHides("not observable")
    }

    /**
     * Both figures, because they answer different questions and can disagree: a
     * link pinned to High quality that is only managing 660 is a story, and one
     * number would hide it.
     */
    @Test
    fun `pinned ldac shows the spec figure and the measurement side by side`() {
        render(snapshot(codecSpecific1 = 1000L, measuredKbps = 660))

        assertShows("990 kbps (pinned)")
        assertShows("660 kbps measured")
    }

    @Test
    fun `pinned ldac shows the rate of its sample rate family`() {
        // 44.1 kHz runs the 909/606/303 ladder, not 990/660/330.
        render(snapshot(codecSpecific1 = 1000L, sampleRateHz = 44_100))

        assertShows("909 kbps (pinned)")
    }

    /**
     * The fallback is not dead wording: it is what every build without an
     * `A2DP LDAC State:` section says, and it must stay exactly as honest as it
     * was before the direct read existed.
     */
    @Test
    fun `a build that reports no rate still refuses instead of guessing`() {
        render(snapshot(codecSpecific1 = 0L, measuredKbps = null))

        assertShows("Adaptive — rate not observable")
        // The chips still offer "990 kbps" as a thing to pin, which is fine —
        // what must not appear is a claim that anything was measured.
        assertHides("(measured)")
    }

    /**
     * The row that used to sit under the rate, offering frames-per-packet as a
     * stand-in for it. The counter under that row turned out to be a 20 ms timer
     * tick, so the row was reporting the playing duty cycle in the shape of a
     * quality indicator. Nothing on this panel may imply packing or rate from
     * those counters again.
     */
    @Test
    fun `the falsified frames-per-packet proxy is gone from the panel`() {
        render(snapshot(codecSpecific1 = 0L, measuredKbps = 396))

        assertHides("frames per packet")
        assertHides("(proxy)")
    }

    /**
     * One line, both sides of it.
     *
     * The negotiated format used to be printed twice — in full under the device
     * name ("96 kHz · 32 bit · stereo") and again in short form on the line
     * below ("→ Link: LDAC 96 kHz/32"). Two notations for one fact is not
     * redundancy, it is a question: do they agree? So there is one line now, and
     * this pins that both halves survived the merge.
     */
    @Test
    fun `the input and the negotiated format are one line`() {
        render(snapshot(codecSpecific1 = 1000L))

        // The link side, in the panel's one notation.
        assertShows("96 kHz · 32 bit · stereo")
        // The input side: the app's own rate, before whatever the link does.
        assertShows("44.1 kHz")
        // And not the second notation the line below used to repeat it in.
        assertHides("Link: LDAC")
    }

    /**
     * The internal liveness proxy, and why it is not a row any more.
     *
     * "Encoder queue: 50 handovers/s" was honest and useless: the counter under
     * it ticks with the encoder's 20 ms timer, so it reads 50 on every healthy
     * link and its only message to a user was that a number was moving. The
     * trace graph carries the same liveness as a line that is drawn rather than
     * broken, where it costs no words at all.
     */
    @Test
    fun `the handovers-per-second liveness proxy is gone from the panel`() {
        render(snapshot(codecSpecific1 = 0L, measuredKbps = 396))

        assertHides("handovers")
        assertHides("Encoder queue")
    }

    /**
     * The caveat about renegotiation is still on the screen — it is behind the
     * chips' own question mark, where a thing you need to know once belongs,
     * rather than printed under them forever.
     */
    @Test
    fun `the renegotiation paragraph is not in the first layer`() {
        render(snapshot(codecSpecific1 = 0L))

        assertShows("LDAC quality")
        assertHides("the audio cuts out for a moment")
        // "Live tuning" was a second name for the same control, one line under
        // the first one.
        assertHides("Live tuning")
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

    /**
     * AK-T009-24 where the owner sees it: a window in which only the encoder
     * underflow counter moved stays quiet, and the count is still on the screen.
     *
     * That pairing is the whole point of the fix. The counter rose 23 times over
     * 38.93 minutes of playback with nothing dropped and no fault heard
     * (`docs/perf/T-011-messung.md`), so a red "Audio lost" line for each of
     * them was wrong — and dropping the number instead would have cost a
     * measurement that AK-2 keeps.
     */
    @Test
    fun `an underflow-only window stays quiet and still shows the count`() {
        render(
            snapshot(codecSpecific1 = 0L).let { base ->
                base.copy(txDelta = base.txDelta?.copy(underflows = 3L))
            },
        )

        assertShows("No loss this window.")
        assertHides("Audio lost")
        assertShows("3 encoder underflows in the last 2 s.")
    }

    /**
     * The channel AK-T009-24 names word for word, asserted on the words.
     *
     * Dropouts move here on their own — no dropped packets alongside them — so
     * the line cannot be produced by the other tx counter.
     */
    @Test
    fun `a window of stack dropouts alone names that channel`() {
        render(
            snapshot(codecSpecific1 = 0L).let { base ->
                base.copy(txDelta = base.txDelta?.copy(dropouts = 21L))
            },
        )

        assertShows("Audio lost: 21 stack dropouts in the last 2 s.")
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
        render(snapshot(codecSpecific1 = 0L, measuredKbps = 396), overview = overviewWithLoss())

        // The caption names the measured series and its unit, not the enqueue
        // rate that shares the same graph shape an order of magnitude lower.
        assertShows("492 kbps now")
        assertShows("peak 492")
        assertShows("3 loss marks")
    }

    /**
     * The chip is the control and the message. A permanent line under it saying
     * the close-up is off — to a reader who can see it is off — was the panel
     * narrating itself, and the cost it quoted is in the section's explanation
     * for anyone who wants the reason.
     */
    @Test
    fun `an empty close-up offers the chip without a paragraph under it`() {
        render(snapshot(codecSpecific1 = 0L), overview = overviewWithLoss())

        assertShows("Watch closely")
        assertHides("Off by default")
        assertHides("twice a second")
    }

    @Test
    fun `switching the close-up on changes what the chip says`() {
        render(snapshot(codecSpecific1 = 0L), closeUpEnabled = true)

        assertShows("Watching")
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
        /** Null models a build with no `A2DP LDAC State:` section. */
        measuredKbps: Int? = null,
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
        ldac = LdacState.from(
            codecSpecific1,
            sampleRateHz,
            // The stack's token follows the pin, as it does on the device: pin
            // 1000 and the block reports HIGH rather than ABR. Deriving it here
            // keeps the two halves of the fixture from contradicting each other.
            measuredKbps?.let {
                LdacStackState(qualityMode = stackToken(codecSpecific1), transmissionKbps = it)
            },
        ),
        // framesPerPacketAvg is still parsed and still must never reach a row.
        tx = A2dpTxStats(enqueueCount = 8_000, framesPerPacketAvg = 4),
        txDelta = A2dpTxDelta(windowMs = 2_000, enqueued = 100),
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
