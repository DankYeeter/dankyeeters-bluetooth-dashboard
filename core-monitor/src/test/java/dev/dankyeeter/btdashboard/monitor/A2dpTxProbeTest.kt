package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxProbe
import dev.dankyeeter.btdashboard.monitor.link.live.LinkObservability
import dev.dankyeeter.btdashboard.monitor.shell.ShellResult
import dev.dankyeeter.btdashboard.monitor.shell.UnavailableShellRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slim tx probe that feeds the ten-second close-up graph.
 *
 * The dump handed to the fake shell is the real Pixel 11 capture, edited only
 * in ways the device itself would produce — a counter advanced, a codec moved
 * into the offload list. What is being pinned is the arithmetic between two
 * passes, because that is what the graph is drawn from and it is the part that
 * fails quietly: a counter reset drawn as zero throughput looks exactly like a
 * link that stopped.
 */
class A2dpTxProbeTest {

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("dumps/$name"),
    ) { "fixture $name missing" }.bufferedReader().readText()

    private val baseBt by lazy {
        fixture("bt_manager_pixel11_ldac_txqueue.txt")
            .replace("mConnectionState: STATE_DISCONNECTED", "mConnectionState: STATE_CONNECTED")
            .replace("mIsPlaying: false", "mIsPlaying: true")
    }

    /**
     * The newer capture, which carries the `A2DP LDAC State:` block. Kept beside
     * [baseBt] rather than replacing it: the counter arithmetic above must keep
     * working on a build that prints no such block, because most do not.
     */
    private val liveBt by lazy { fixture("bt_manager_pixel11_ldac_state_abr.txt") }

    /** Rewrites one `label : a / b / c` row, ignoring the device's own padding. */
    private fun setCounter(dump: String, label: String, values: String): String =
        dump.lineSequence().joinToString("\n") { line ->
            if (line.trim().startsWith(label)) line.substringBefore(':') + ": " + values else line
        }

    private fun withLdacOffloaded(dump: String): String =
        dump.lineSequence().flatMap { line ->
            if (line.trim().startsWith("codecConfigOffloading")) {
                sequenceOf(line, "    {codecName:LDAC,mCodecType:4,mCodecPriority:0}")
            } else {
                sequenceOf(line)
            }
        }.joinToString("\n")

    private fun probeOf(dump: String, clock: () -> Long = { 1_000L }) = A2dpTxProbe(
        shell = FakeShellRunner(mapOf("dumpsys bluetooth_manager" to ShellResult(0, dump))),
        clock = clock,
    )

    @Test
    fun `it reads one dump and no others`() = runTest {
        val shell = FakeShellRunner(
            mapOf("dumpsys bluetooth_manager" to ShellResult(0, baseBt)),
        )

        A2dpTxProbe(shell) { 1_000L }.readOnce()

        // The entire reason this class exists next to LiveLinkSource: one exec
        // per pass, so 500 ms sampling is possible at all.
        assertEquals(listOf(listOf("dumpsys", "bluetooth_manager")), shell.commands)
    }

    @Test
    fun `the first pass has no rate, because a rate needs two readings`() = runTest {
        val probe = probeOf(baseBt)

        val sample = probe.sampleBetween(null, probe.readOnce())

        assertNull(sample.delta)
        assertEquals(LinkObservability.HOST_ENCODED, sample.observability)
    }

    @Test
    fun `two passes half a second apart give packets per second`() = runTest {
        val first = probeOf(baseBt) { 1_000L }.readOnce()
        // 216 more packets enqueued in the 500 ms between the passes.
        val advanced = setCounter(
            baseBt,
            "Counts (enqueue/dequeue/readbuf)",
            "389413 / 854736 / 1240579",
        )
        val second = probeOf(advanced) { 1_500L }.readOnce()

        val sample = probeOf(baseBt).sampleBetween(first, second)

        assertEquals(500L, sample.delta?.windowMs)
        assertEquals(216L, sample.delta?.enqueued)
        assertEquals(432.0, sample.delta?.packetsPerSecond!!, 0.001)
    }

    @Test
    fun `loss in the window is carried apart from the rate`() = runTest {
        val first = probeOf(baseBt) { 1_000L }.readOnce()
        val lossy = setCounter(baseBt, "Counts (flushed/dropped/dropouts)", "1 / 3 / 1")
            .let { setCounter(it, "Counts (underflow)", "790") }
        val second = probeOf(lossy) { 1_500L }.readOnce()

        val delta = probeOf(baseBt).sampleBetween(first, second).delta

        assertEquals(3L, delta?.dropped)
        assertEquals(1L, delta?.dropouts)
        assertEquals(2L, delta?.underflows)
        assertTrue(delta!!.hasLoss)
    }

    @Test
    fun `a counter that went backwards is not a window of zero`() = runTest {
        val first = probeOf(baseBt) { 1_000L }.readOnce()
        // The Bluetooth stack restarted: every counter starts again from nearly
        // nothing. Reporting that as "0 packets/s" would draw a dropout that
        // never happened.
        val restarted = setCounter(
            baseBt,
            "Counts (enqueue/dequeue/readbuf)",
            "12 / 8 / 20",
        )
        val second = probeOf(restarted) { 1_500L }.readOnce()

        assertNull(probeOf(baseBt).sampleBetween(first, second).delta)
    }

    @Test
    fun `an offloaded codec reports no counters and says why`() = runTest {
        val probe = probeOf(withLdacOffloaded(baseBt))

        val reading = probe.readOnce()

        assertNull("offloaded links must not carry the host's stale counters", reading.stats)
        assertEquals(LinkObservability.OFFLOADED, reading.observability)
        assertTrue(reading.unavailable!!.contains("encoded by the controller"))
        // And nothing downstream can build a rate out of it.
        assertNull(probe.sampleBetween(reading, reading).delta)
    }

    /**
     * The close-up graph's actual subject, from the same single dump.
     *
     * The probe's whole justification is that it runs one exec so 2 Hz sampling
     * is possible; the `A2DP LDAC State:` block is inside that same exec, so the
     * measured bitrate costs nothing extra. The command list is asserted again
     * here because "just read one more dump" is the easy way to lose that.
     */
    @Test
    fun `it carries the measured LDAC bitrate without reading a second dump`() = runTest {
        val shell = FakeShellRunner(
            mapOf("dumpsys bluetooth_manager" to ShellResult(0, liveBt)),
        )

        val reading = A2dpTxProbe(shell) { 1_000L }.readOnce()

        assertEquals(396, reading.ldacStack?.transmissionKbps)
        assertEquals("ABR", reading.ldacStack?.qualityMode)
        assertEquals(listOf(listOf("dumpsys", "bluetooth_manager")), shell.commands)
    }

    /**
     * A rate is a reading, not a difference — so unlike every counter here it is
     * on the very first sample of a run, and the graph draws a point instead of
     * waiting half a second for something to subtract from.
     */
    @Test
    fun `the first sample already carries a rate even though it has no delta`() = runTest {
        val probe = probeOf(liveBt)

        val sample = probe.sampleBetween(null, probe.readOnce())

        assertNull("nothing to difference yet", sample.delta)
        assertEquals(396, sample.bitrateKbps)
        assertEquals("ABR", sample.qualityModeLabel)
    }

    /**
     * A counter reset blanks the delta, and must not blank the rate with it: the
     * stack restarting says nothing about what the encoder then reported.
     */
    @Test
    fun `a counter reset loses the delta and keeps the rate`() = runTest {
        val first = probeOf(liveBt) { 1_000L }.readOnce()
        val restarted = setCounter(liveBt, "Counts (enqueue/dequeue/readbuf)", "12 / 8 / 20")
        val second = probeOf(restarted) { 1_500L }.readOnce()

        val sample = probeOf(liveBt).sampleBetween(first, second)

        assertNull(sample.delta)
        assertEquals(396, sample.bitrateKbps)
    }

    /**
     * An offloaded codec has no host-side encoder state either, so a stale LDAC
     * block found beside it would be a bitrate for a codec that is not running.
     */
    @Test
    fun `an offloaded codec withholds the rate as well as the counters`() = runTest {
        val reading = probeOf(withLdacOffloaded(liveBt)).readOnce()

        assertNull(reading.stats)
        assertNull(reading.ldacStack)
    }

    @Test
    fun `without a shell identity it says so instead of reporting silence`() = runTest {
        val reading = A2dpTxProbe(UnavailableShellRunner) { 1_000L }.readOnce()

        assertNull(reading.stats)
        assertNotNull(reading.unavailable)
        assertFalse(reading.unavailable!!.isBlank())
    }

    @Test
    fun `an empty dump is a stated failure, not an empty link`() = runTest {
        val probe = A2dpTxProbe(
            FakeShellRunner(mapOf("dumpsys bluetooth_manager" to ShellResult(1, "", "timeout"))),
        ) { 1_000L }

        val reading = probe.readOnce()

        assertNull(reading.stats)
        assertTrue(reading.unavailable!!.contains("timeout"))
    }
}
