package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxDelta
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxStats
import dev.dankyeeter.btdashboard.monitor.link.live.InputStreamSnapshot
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
 */
class MonitorTraceModelTest {

    private fun point(atMs: Long, rate: Double? = 400.0, loss: Long = 0) = TracePoint(
        timestampMs = atMs,
        packetsPerSecond = rate,
        framesPerPacket = 4.0,
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
        assertEquals(400.0, trace.peakPacketsPerSecond!!, 0.001)
        assertEquals(400.0, trace.latestPacketsPerSecond!!, 0.001)
    }

    @Test
    fun `an empty trace has nothing to draw and says nothing about the link`() {
        val trace = closeUp()

        assertFalse(trace.hasRate)
        assertNull(trace.peakPacketsPerSecond)
        assertNull(trace.newestMs)
        assertEquals(0L, trace.lossTotal)
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
            observability = LinkObservability.HOST_ENCODED,
        )

        val point = sample.toTracePoint()

        assertEquals(432.0, point.packetsPerSecond!!, 0.001)
        assertEquals(4.0, point.framesPerPacket!!, 0.001)
        assertEquals(4L, point.lossCount)
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
        // Three app underruns plus one dropped packet plus one underflow: the
        // full pass sees all three places the path can lose audio.
        assertEquals(5L, point.lossCount)
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
