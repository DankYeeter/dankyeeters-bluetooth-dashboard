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

    /**
     * AK-T009-24, the regression test against the finding itself: a window with
     * **no** encoder underflows and 21 stack dropouts is loss.
     *
     * The numbers are arm B of `docs/perf/T-008-experimente.md`, section 3 —
     * 990 kbps pinned, 97 s, 525 dropped packets, 21 dropouts, and
     * `Counts (underflow)` unmoved at its previous value. That arm was audibly
     * broken throughout. A reading that took underflow for the leading indicator
     * would have called it a healthy link, and the long ABR run measured the
     * opposite pairing as well — underflow rising at 0.59/min while nothing was
     * dropped and the sound was perfect (`docs/perf/T-011-messung.md`).
     *
     * So the two are independent channels here, and this test pins that a zero
     * in one of them suppresses nothing in the others.
     */
    @Test
    fun `dropouts are loss even when the underflow counter never moved`() = runTest {
        val first = probeOf(baseBt) { 1_000L }.readOnce()
        // 97 s later: the queue overflowed 525 times in 21 episodes, and the
        // encoder was never short of PCM — `Counts (underflow)` still reads 788.
        val armB = setCounter(baseBt, "Counts (flushed/dropped/dropouts)", "1 / 525 / 21")
            .let { setCounter(it, "Counts (enqueue/dequeue/readbuf)", "394047 / 854736 / 1240579") }
        val second = probeOf(armB) { 98_000L }.readOnce()

        val delta = probeOf(baseBt).sampleBetween(first, second).delta

        assertEquals(97_000L, delta?.windowMs)
        assertEquals(0L, delta?.underflows)
        assertEquals(525L, delta?.dropped)
        assertEquals(21L, delta?.dropouts)
        assertTrue("a window with 21 dropouts is loss whatever underflow says", delta!!.hasLoss)
    }

    /**
     * The other half of AK-T009-24: underflow on its own is not loss.
     *
     * The long ABR run measured `Counts (underflow)` climbing from 2 to 25 over
     * 38.93 minutes in which nothing was dropped and the owner heard no fault
     * (`docs/perf/T-011-messung.md`). At the default 2 s cadence each of those
     * 23 increments is its own window, so a verdict built on this counter turned
     * 39 minutes of clean music into 23 red "Audio lost" lines and 23 rows in
     * the event log.
     */
    @Test
    fun `a window whose only moving counter is underflow is not loss`() = runTest {
        val first = probeOf(baseBt) { 1_000L }.readOnce()
        // One increment, two seconds later, with every other counter standing
        // still — one of the 23 windows from that run.
        val quiet = setCounter(baseBt, "Counts (underflow)", "789")
        val second = probeOf(quiet) { 3_000L }.readOnce()

        val delta = probeOf(baseBt).sampleBetween(first, second).delta

        assertEquals(1L, delta?.underflows)
        assertEquals(0L, delta?.dropped)
        assertEquals(0L, delta?.dropouts)
        assertFalse("an underflow-only window is not audible loss", delta!!.hasLoss)
    }

    /**
     * Dropouts alone, because the arm-B case above carries 525 dropped packets
     * with them and would still read as loss if the dropout channel were dropped
     * from the verdict entirely.
     */
    @Test
    fun `stack dropouts alone are loss`() = runTest {
        val first = probeOf(baseBt) { 1_000L }.readOnce()
        val onlyDropouts = setCounter(baseBt, "Counts (flushed/dropped/dropouts)", "1 / 0 / 21")
        val second = probeOf(onlyDropouts) { 98_000L }.readOnce()

        val delta = probeOf(baseBt).sampleBetween(first, second).delta

        assertEquals(0L, delta?.dropped)
        assertEquals(21L, delta?.dropouts)
        assertEquals(0L, delta?.underflows)
        assertTrue("21 stack dropouts are loss on their own", delta!!.hasLoss)
    }

    /** The same for the other guarded channel, so neither can be lost silently. */
    @Test
    fun `dropped packets alone are loss`() = runTest {
        val first = probeOf(baseBt) { 1_000L }.readOnce()
        val onlyDropped = setCounter(baseBt, "Counts (flushed/dropped/dropouts)", "1 / 525 / 0")
        val second = probeOf(onlyDropped) { 98_000L }.readOnce()

        val delta = probeOf(baseBt).sampleBetween(first, second).delta

        assertEquals(525L, delta?.dropped)
        assertEquals(0L, delta?.dropouts)
        assertEquals(0L, delta?.underflows)
        assertTrue("525 dropped packets are loss on their own", delta!!.hasLoss)
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
     * The adaptive rung and the stack's own count of rung changes travel with
     * the reading, out of the same single dump (D-11).
     *
     * The count is what a sampled series cannot supply: at this channel's 500 ms
     * cadence a rung the encoder held for less than one interval is invisible,
     * and only the counter says how many were missed.
     */
    @Test
    fun `the reading carries the adaptive rung and the count of rung changes`() = runTest {
        val reading = probeOf(liveBt).readOnce()

        assertEquals(4, reading.ldacStack?.adaptiveBitrateIndex)
        assertEquals(3L, reading.ldacStack?.adaptiveBitrateAdjustments)
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
