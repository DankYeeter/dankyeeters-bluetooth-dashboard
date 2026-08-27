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
