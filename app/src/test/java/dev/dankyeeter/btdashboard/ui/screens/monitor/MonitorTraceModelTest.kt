package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxDelta
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxStats
import dev.dankyeeter.btdashboard.monitor.link.live.InputStreamSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LdacStackState
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LinkObservability
import dev.dankyeeter.btdashboard.monitor.link.live.LiveCodecSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.TxProbeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring buffer behind the two live graphs.
 *
 * Everything here is about what the drawing is allowed to imply. A trace that
 * silently kept old points would draw a minute-long line over a window nobody
 * measured; one that joined across a missed reading would draw the smoothest
 * possible link over the exact moment the phone was too busy to look.
 *
 * The plotted series is now the **measured** LDAC bitrate, with the enqueue rate
 * as a liveness fallback for a link that does not report one. Every rule below
 * is stated against `plotValue` rather than against either series by name,
 * because the gap and window rules must hold whichever of the two is being
 * drawn — and the last tests in the file pin which one that is.
 */
class MonitorTraceModelTest {

    /** A point on the fallback series: a link that reports no bitrate. */
    private fun point(atMs: Long, rate: Double? = 400.0, loss: Long = 0) = TracePoint(
        timestampMs = atMs,
        packetsPerSecond = rate,
        lossCount = loss,
    )

    /** A point on the primary series: a link whose rate the stack prints. */
    private fun kbpsPoint(atMs: Long, kbps: Double? = 396.0, loss: Long = 0) = TracePoint(
        timestampMs = atMs,
        bitrateKbps = kbps,
        packetsPerSecond = 50.0,
        lossCount = loss,
    )

    private fun closeUp() = LiveTrace.closeUp(500L)

    @Test
    fun `the window really is the window it claims`() {
        var trace = closeUp()
        // 30 seconds of readings into a 10-second window.
        (0 until 60).forEach { i -> trace = trace.plus(point(i * 500L)) }

        val span = trace.points.last().timestampMs - trace.points.first().timestampMs
        assertTrue("kept $span ms in a 10 s window", span <= LiveTrace.CLOSE_UP_WINDOW_MS)
        assertTrue(trace.points.size <= trace.maxPoints)
    }

    @Test
    fun `a clock that stands still cannot grow the buffer`() {
        var trace = closeUp()
        // Same timestamp over and over: the time rule alone would never trim,
        // so the count ceiling is what has to hold — and the monotonic guard
        // means these are dropped outright.
        repeat(500) { trace = trace.plus(point(1_000L)) }

        assertEquals(1, trace.points.size)
    }

    @Test
    fun `a replayed reading is not counted twice`() {
        // The shared poll flow replays its last update to a new collector after
        // a rotation. Counting it again would put a second spike on the graph
        // for one dropout the user heard once.
        val trace = closeUp()
            .plus(point(1_000L, loss = 1))
            .plus(point(1_500L, loss = 1))
            .plus(point(1_500L, loss = 1))
            .plus(point(1_200L, loss = 1))

        assertEquals(2, trace.points.size)
        assertEquals(2L, trace.lossTotal)
    }

    @Test
    fun `a missed reading breaks the line instead of being drawn through`() {
        val trace = closeUp()
            .plus(point(1_000L))
            .plus(point(1_500L))
            // Two and a half seconds later: five intervals, i.e. the poller
            // missed four passes.
            .plus(point(4_000L))

        assertFalse("consecutive readings must join", trace.breakBefore(1))
        assertTrue("a gap must break the line", trace.breakBefore(2))
    }

    @Test
    fun `a window with no measurable rate breaks the line too`() {
        val trace = closeUp()
            .plus(point(1_000L))
            .plus(point(1_500L, rate = null))
            .plus(point(2_000L))

        assertTrue(trace.breakBefore(1))
        assertTrue(trace.breakBefore(2))
        assertEquals(400.0, trace.peakValue!!, 0.001)
        assertEquals(400.0, trace.latestValue!!, 0.001)
    }

    @Test
    fun `an empty trace has nothing to draw and says nothing about the link`() {
        val trace = closeUp()

        assertFalse(trace.hasRate)
        assertNull(trace.peakValue)
        assertNull(trace.newestMs)
        assertEquals(0L, trace.lossTotal)
    }

    /**
     * The measured bitrate is the line, and the enqueue rate beside it is not.
     *
     * They differ by an order of magnitude on a real link — about 50 handovers a
     * second against a few hundred kbps — so plotting the wrong one is not a
     * subtle error, and the caption's unit has to follow the same choice.
     */
    @Test
    fun `the measured bitrate is what gets plotted when it is there`() {
        var trace = closeUp()
        listOf(330.0, 396.0, 660.0).forEachIndexed { i, kbps ->
            trace = trace.plus(kbpsPoint(1_000L + i * 500L, kbps))
        }

        assertTrue(trace.isMeasuredBitrate)
        assertEquals("kbps", trace.unitLabel)
        assertEquals(660.0, trace.peakValue!!, 0.001)
        assertEquals(660.0, trace.latestValue!!, 0.001)
    }

    /**
     * And on a link that reports no rate the fallback is drawn and *named* as
     * the fallback. A caption saying "kbps" over the enqueue rate would turn a
     * stand-in into a claim about throughput.
     */
    @Test
    fun `a link with no reported rate falls back and says which series it is`() {
        val trace = closeUp().plus(point(1_000L, rate = 50.0)).plus(point(1_500L, rate = 50.0))

        assertTrue(trace.hasRate)
        assertFalse(trace.isMeasuredBitrate)
        assertEquals("packets/s", trace.unitLabel)
        assertEquals(50.0, trace.latestValue!!, 0.001)
    }

    @Test
    fun `the close-up counts the stack's loss only`() {
        val sample = TxProbeSample(
            timestampMs = 1_000L,
            delta = A2dpTxDelta(
                windowMs = 500,
                enqueued = 216,
                dropped = 1,
                dropouts = 1,
                underflows = 2,
                framesEncoded = 864,
            ),
            bitrateKbps = 492,
            qualityModeLabel = "ABR",
            observability = LinkObservability.HOST_ENCODED,
        )

        val point = sample.toTracePoint()

        assertEquals(492.0, point.bitrateKbps!!, 0.001)
        assertEquals("the measured rate wins the line", 492.0, point.plotValue!!, 0.001)
        assertEquals(432.0, point.packetsPerSecond!!, 0.001)
        // One dropped packet and one dropout. The two underflows in the same
        // window are not marks: a mark claims something was lost at that
        // instant, and that counter cannot make the claim (AK-T009-24).
        assertEquals(2L, point.lossCount)
    }

    /**
     * The rate is a reading and the loss is a difference, so the very first
     * close-up sample already draws a point instead of half a second of nothing.
     */
    @Test
    fun `the close-up's first sample plots a rate with no delta to difference`() {
        val point = TxProbeSample(
            timestampMs = 1_000L,
            delta = null,
            bitrateKbps = 396,
            observability = LinkObservability.HOST_ENCODED,
        ).toTracePoint()

        assertEquals(396.0, point.plotValue!!, 0.001)
        assertNull(point.packetsPerSecond)
        assertEquals(0L, point.lossCount)
    }

    @Test
    fun `the overview counts app, mixer and stack loss together`() {
        val snapshot = LinkLiveSnapshot(
            timestampMs = 2_000L,
            codec = LiveCodecSnapshot(family = CodecFamily.LDAC),
            tx = A2dpTxStats(enqueueCount = 100),
            txDelta = A2dpTxDelta(windowMs = 2_000, enqueued = 862, dropped = 1, underflows = 1),
            inputs = listOf(
                InputStreamSnapshot(
                    uid = 10_123,
                    pid = 42,
                    sessionId = 1,
                    sampleRateHz = 44_100,
                    channelCount = 2,
                    underrunDelta = 3,
                ),
            ),
        )

        val point = snapshot.toTracePoint()

        assertEquals(431.0, point.packetsPerSecond!!, 0.001)
        // Three app underruns plus one dropped packet: the full pass sees all
        // three places the path can lose audio, and counts the two that can
        // say so. The underflow in the same window is not one of them
        // (AK-T009-24).
        assertEquals(4L, point.lossCount)
    }

    /**
     * The graph's half of AK-T009-24.
     *
     * A window in which only the encoder underflow counter moved draws no mark
     * on either channel. In the 39-minute ABR run that counter moved 23 times
     * over playback with nothing dropped and no fault heard
     * (`docs/perf/T-011-messung.md`), which would have put 23 marks and a
     * "23 loss marks" caption under a graph of a clean link.
     */
    @Test
    fun `an underflow-only window puts no mark on either channel`() {
        val delta = A2dpTxDelta(windowMs = 2_000, enqueued = 862, underflows = 3)

        val closeUp = TxProbeSample(
            timestampMs = 1_000L,
            delta = delta,
            bitrateKbps = 492,
            observability = LinkObservability.HOST_ENCODED,
        ).toTracePoint()
        val overview = LinkLiveSnapshot(
            timestampMs = 2_000L,
            codec = LiveCodecSnapshot(family = CodecFamily.LDAC),
            tx = A2dpTxStats(enqueueCount = 100),
            txDelta = delta,
        ).toTracePoint()

        assertEquals(0L, closeUp.lossCount)
        assertFalse("the close-up marked a window nothing was lost in", closeUp.hasLoss)
        assertEquals(0L, overview.lossCount)
        assertFalse("the overview marked a window nothing was lost in", overview.hasLoss)
    }

    /** The overview plots the same measured series the close-up does. */
    @Test
    fun `the overview plots the snapshot's measured bitrate`() {
        val snapshot = LinkLiveSnapshot(
            timestampMs = 2_000L,
            codec = LiveCodecSnapshot(family = CodecFamily.LDAC, sampleRateHz = 96_000),
            ldac = LdacState.from(
                codecSpecific1 = 0L,
                sampleRateHz = 96_000,
                stack = LdacStackState(qualityMode = "ABR", transmissionKbps = 396),
            ),
            observability = LinkObservability.HOST_ENCODED,
        )

        val trace = LiveTrace.overview(2_000L).append(snapshot, 2_000L)

        assertTrue(trace.hasRate)
        assertEquals("kbps", trace.unitLabel)
        assertEquals(396.0, trace.latestValue!!, 0.001)
        assertNull(
            "a measured rate needs no second reading, so there is nothing to explain",
            trace.unavailable,
        )
    }

    @Test
    fun `an offloaded link is empty with a reason rather than a zero line`() {
        val snapshot = LinkLiveSnapshot(
            timestampMs = 1_000L,
            codec = LiveCodecSnapshot(family = CodecFamily.AAC, isOffloaded = true),
            observability = LinkObservability.OFFLOADED,
        )

        val trace = LiveTrace.overview(2_000L).append(snapshot, 2_000L)

        assertFalse(trace.hasRate)
        assertEquals(LinkObservability.OFFLOADED, trace.observability)
        assertTrue(trace.unavailable!!.contains("encoded by the controller"))
    }

    @Test
    fun `a first reading explains itself rather than looking broken`() {
        val snapshot = LinkLiveSnapshot(
            timestampMs = 1_000L,
            codec = LiveCodecSnapshot(family = CodecFamily.LDAC),
            observability = LinkObservability.HOST_ENCODED,
            tx = A2dpTxStats(enqueueCount = 100),
        )

        val trace = LiveTrace.overview(2_000L).append(snapshot, 2_000L)

        assertFalse(trace.hasRate)
        assertTrue(trace.unavailable!!.contains("two readings"))
    }

    @Test
    fun `changing the poll rate changes what counts as a gap`() {
        // The 5 s chip means readings are 5 s apart; at the 1 s chip the same
        // spacing is four missed passes. The rule has to follow the rate.
        val slow = LiveTrace.overview(5_000L)
            .plus(point(0L), expectedIntervalMs = 5_000L)
            .plus(point(5_000L), expectedIntervalMs = 5_000L)
        val fast = LiveTrace.overview(1_000L)
            .plus(point(0L), expectedIntervalMs = 1_000L)
            .plus(point(5_000L), expectedIntervalMs = 1_000L)

        assertFalse(slow.breakBefore(1))
        assertTrue(fast.breakBefore(1))
    }
}
