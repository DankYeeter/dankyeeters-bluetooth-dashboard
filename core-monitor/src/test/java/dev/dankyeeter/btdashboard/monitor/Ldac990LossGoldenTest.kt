package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpLinkDumpParser
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxProbe
import dev.dankyeeter.btdashboard.monitor.link.live.Honesty
import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkObservability
import dev.dankyeeter.btdashboard.monitor.shell.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one capture in this repo that shows a real 990 kbps LDAC link losing
 * audio, read end to end by the production parser.
 *
 * ## Why this file exists next to [LiveLinkParserTest]
 *
 * `bt_manager_pixel11_ldac_990_loss.txt` was recorded on 2026-09-02 (T-022) and
 * sat in `dumps/` for a day without a single assertion on its contents: the
 * corpus sweep in `FixtureSweepTest` ran it through every parser, but a sweep
 * only asserts invariants — that nothing throws and nothing is invented — and
 * an invariant holds just as well when every counter reads null. The values
 * themselves were pinned nowhere.
 *
 * What was tested instead were **hand-set** counters. `A2dpTxProbeTest` proves
 * the AK-T009-24 arithmetic by rewriting rows of the zero-loss
 * `..._ldac_txqueue.txt` capture to `1 / 525 / 21`. That is the right test for
 * the arithmetic and no test at all for the reading: if the stack renamed a
 * label or moved a row, the rewrite would follow the parser's own idea of the
 * format and stay green. This file closes that gap by asserting the numbers a
 * real phone printed, in the file it printed them into.
 *
 * ## The ground truth, read out of the fixture
 *
 * Every expectation below was read from the file, not from `dumps/README.md`
 * — the README is a description and the file is the evidence. Line numbers as
 * of the capture:
 *
 * ```
 * 2311  Counts (enqueue/dequeue/readbuf)     : 229391 / 532363 / 740475
 * 2313  Frames per packet (total/max/ave)    : 2763962 / 12 / 12
 * 2314  Counts (flushed/dropped/dropouts)    : 0 / 1851 / 74
 * 2315  Counts (max dropped)                 : 26
 * 2317  Counts (underflow)                   : 623
 * 2318  Bytes (underflow)                    : 637952
 * 2335  LDAC quality mode                    : HIGH
 * 2336  LDAC transmission bitrate (Kbps)     : 990
 * ```
 *
 * and no `LDAC adaptive bit rate` row anywhere in the 3134 lines — the
 * signature of a fixed tier, asserted below rather than assumed.
 *
 * The fixture is never edited to make an assertion pass. If the numbers here
 * ever disagree with it, the file is right.
 */
class Ldac990LossGoldenTest {

    /** JUnit4's assertNotNull returns Unit, so this is the value-carrying form. */
    private fun <T : Any> present(value: T?, what: String): T =
        requireNotNull(value) { "$what missing from the 990 loss fixture" }

    private val dumpText: String by lazy {
        requireNotNull(
            javaClass.classLoader?.getResourceAsStream("dumps/$FIXTURE"),
        ) { "fixture $FIXTURE missing" }.bufferedReader().readText()
    }

    private val parsed by lazy { A2dpLinkDumpParser.parse(dumpText) }

    @Test
    fun `the capture reads as a live host-encoded LDAC link pinned to high quality`() {
        val device = present(parsed.device, "the connected device")
        assertEquals("the capture's active device", "XX:XX:XX:XX:37:8F", device.address)
        assertTrue("mConnectionState is STATE_CONNECTED in this capture", device.isConnected)
        assertTrue("the device is the active A2DP sink in this capture", device.isActive)
        assertTrue("mIsPlaying is true — TIDAL was running", device.isPlaying)

        val codec = present(parsed.codec, "the negotiated codec")
        assertEquals(CodecFamily.LDAC, codec.family)
        assertEquals("negotiated sample rate", 96_000, codec.sampleRateHz)
        assertEquals("negotiated bit depth", 32, codec.bitsPerSample)
        assertEquals(ChannelMode.STEREO, codec.channelMode)
        // 1000 is the Developer-options pin for High quality. It is what makes
        // this a 990 capture rather than an adaptive run that happened to be
        // high at the instant of the dump.
        assertEquals("mCodecSpecific1 — the pinned LDAC quality", 1000L, codec.codecSpecific1)

        // LDAC is absent from codecConfigOffloading in this dump, which is the
        // only thing that makes the tx counters below real numbers instead of
        // a leftover from some earlier host-encoded session.
        assertFalse("LDAC is host-encoded on this controller", codec.isOffloaded)

        assertEquals(
            "a complete capture leaves the parser nothing to complain about",
            emptyList<String>(),
            parsed.warnings,
        )
    }

    @Test
    fun `the tx counters are read verbatim from the A2DP State block`() {
        val tx = present(parsed.tx, "the 'A2DP State:' counters")

        assertEquals("Counts (flushed/dropped/dropouts), field 1", 0L, tx.flushedCount)
        assertEquals("Counts (flushed/dropped/dropouts), field 2", 1851L, tx.droppedCount)
        assertEquals("Counts (flushed/dropped/dropouts), field 3", 74L, tx.dropoutCount)
        assertEquals("Counts (max dropped)", 26L, tx.maxDroppedCount)

        // Three subsystems print `Counts (underflow)` in this one dump — A2DP at
        // line 2317, the Hearing Aid HAL at 2663 and the LE Audio HAL client at
        // 2706 — and the other two read 0. Anything but 623 here means the
        // section boundary slipped and the panel is showing another radio's
        // counter.
        assertEquals("Counts (underflow) inside 'A2DP State:'", 623L, tx.underflowCount)
        assertEquals("Bytes (underflow)", 637_952L, tx.underflowBytes)

        assertEquals("Counts (enqueue/dequeue/readbuf), field 1", 229_391L, tx.enqueueCount)
        assertEquals("Counts (enqueue/dequeue/readbuf), field 2", 532_363L, tx.dequeueCount)
        assertEquals("Counts (enqueue/dequeue/readbuf), field 3", 740_475L, tx.readBufCount)
        assertEquals("Frames per packet (total/max/ave), field 1", 2_763_962L, tx.framesPerPacketTotal)
        assertEquals("Frames per packet (total/max/ave), field 2", 12, tx.framesPerPacketMax)
        assertEquals("Frames per packet (total/max/ave), field 3", 12, tx.framesPerPacketAvg)
    }

    @Test
    fun `the LDAC stack block reports HIGH at 990 kbps`() {
        val stack = present(parsed.ldacStack, "the 'A2DP LDAC State:' block")

        assertEquals("LDAC quality mode, verbatim", "HIGH", stack.qualityMode)
        assertEquals("LDAC transmission bitrate (Kbps)", 990, stack.transmissionKbps)
        // Five of the seven per-codec blocks print an `Effective MTU:` line
        // (LHDCv5 and Opus print none), and four of those five are 0, so this
        // value is also the proof that the LDAC block is the one that was read.
        assertEquals("Effective MTU", 883, stack.effectiveMtu)
        assertEquals("LDAC saved transmit queue length", 11, stack.savedTxQueueLength)
        assertEquals("HIGH is a fixed tier, not adaptive", false, stack.isAdaptive)
    }

    /**
     * The absence that is a fact about this phone, not a gap in the capture.
     *
     * The two `LDAC adaptive bit rate` rows only print while the quality mode is
     * `ABR`; a pinned tier prints neither. T-022 could not find one dump that
     * carried both those rows and non-zero loss counters, and reported that as a
     * requirement conflict rather than adding a line to the file. So the
     * absence is pinned here from both ends: no such row exists in the text, and
     * the parser answers null rather than a plausible index of 0.
     */
    @Test
    fun `a pinned tier prints no adaptive bitrate rows and none are invented`() {
        val abrRows = dumpText.lineSequence()
            .filter { it.trim().startsWith("LDAC adaptive bit rate") }
            .toList()
        assertEquals(
            "a fixed-tier capture carries no ABR rows; found ${abrRows.size}",
            emptyList<String>(),
            abrRows,
        )

        val stack = present(parsed.ldacStack, "the 'A2DP LDAC State:' block")
        assertNull(
            "no ABR index row in the dump, so the index must be absent and not 0",
            stack.adaptiveBitrateIndex,
        )
        assertNull(
            "no ABR adjustments row in the dump, so the count must be absent and not 0",
            stack.adaptiveBitrateAdjustments,
        )
    }

    /**
     * One step further along the path the display actually takes: the probe that
     * feeds the close-up graph, fed the real capture through the shell it would
     * use on the phone.
     *
     * The probe is where the counters can still be thrown away after a correct
     * parse — it drops both `stats` and `ldacStack` unless the link is
     * host-encoded, because on an offloaded codec they would be stale. This
     * capture is the case where they must survive.
     */
    @Test
    fun `the probe passes the capture's numbers on as host-encoded measurements`() = runTest {
        val probe = A2dpTxProbe(
            shell = FakeShellRunner(mapOf("dumpsys bluetooth_manager" to ShellResult(0, dumpText))),
            clock = { 1_000L },
        )

        val reading = probe.readOnce()

        assertEquals(LinkObservability.HOST_ENCODED, reading.observability)
        assertNull("nothing about this capture is unreadable", reading.unavailable)
        assertEquals(
            "dropped packets reach the graph unchanged",
            1851L,
            present(reading.stats, "the probe's tx stats").droppedCount,
        )
        assertEquals(
            "stack dropouts reach the graph unchanged",
            74L,
            present(reading.stats, "the probe's tx stats").dropoutCount,
        )
        assertEquals(
            "the live bitrate reaches the graph unchanged",
            990,
            present(reading.ldacStack, "the probe's LDAC state").transmissionKbps,
        )
    }

    /**
     * The last step to the panel: what the user is told the rate is.
     *
     * `LdacState` puts the spec figure for the pinned mode beside the stack's
     * own reading, so the two can be compared rather than trusted. On this
     * capture they agree at 990, and the rate is labelled MEASURED — which is
     * only honest because the stack printed it in the same dump.
     */
    @Test
    fun `the panel's LDAC state shows a pinned 990 that the stack confirms`() {
        val codec = present(parsed.codec, "the negotiated codec")
        val state = LdacState.from(codec.codecSpecific1, codec.sampleRateHz, parsed.ldacStack)

        assertEquals(LdacQualityMode.HIGH_QUALITY, state.mode)
        assertEquals("spec rate for High quality at 96 kHz", 990, state.nominalKbps)
        assertEquals("what the stack says it is sending", 990, state.measuredKbps)
        assertEquals(Honesty.MEASURED, state.liveBitrateHonesty)
        assertFalse("a pinned tier is not adaptive", state.isAdaptive)
    }

    private companion object {
        /**
         * Verbatim, 325 KB, 3134 lines. Named once so a rename shows up as one
         * missing-resource failure instead of six.
         */
        const val FIXTURE = "bt_manager_pixel11_ldac_990_loss.txt"
    }
}
