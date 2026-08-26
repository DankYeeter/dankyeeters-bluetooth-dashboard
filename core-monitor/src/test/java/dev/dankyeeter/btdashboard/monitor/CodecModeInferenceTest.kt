package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpLinkDumpParser
import dev.dankyeeter.btdashboard.monitor.link.live.CodecModeInference
import dev.dankyeeter.btdashboard.monitor.link.live.CodecModeSignatureRegistry
import dev.dankyeeter.btdashboard.monitor.link.live.Honesty
import dev.dankyeeter.btdashboard.monitor.link.live.InferenceConfidence
import dev.dankyeeter.btdashboard.monitor.link.live.LdacModeSignatures
import dev.dankyeeter.btdashboard.monitor.link.live.LhdcV5ModeSignatures
import dev.dankyeeter.btdashboard.monitor.link.live.ModeSignatureSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bitrate-mode inference, and the arithmetic it rests on.
 *
 * The load-bearing test in this file is [`the owner's morning session decodes
 * to the LDAC floor`], which runs the real capture through the whole chain. It
 * is here rather than in a comment because the conclusion it reaches — that a
 * two-hour session sat at LDAC's lowest rate and never once recovered — is the
 * kind of claim that must break loudly if any constant under it moves.
 */
class CodecModeInferenceTest {

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("dumps/$name"),
    ) { "fixture $name missing" }.bufferedReader().readText()

    // ---- the constants -------------------------------------------------------

    /**
     * LDAC's frame is 128 samples per channel, and the real capture proves it
     * rather than citing it.
     *
     * The session connected at 15:14:21 and dropped at 17:24:49 — 7,828
     * seconds — and encoded 4,693,895 frames in that window. Only one of the
     * two candidate frame lengths fits inside the time the link existed.
     */
    @Test
    fun `the 128 sample frame is the only one that fits the captured session`() {
        val tx = requireNotNull(A2dpLinkDumpParser.parse(fixture("bt_manager_pixel11_ldac_txqueue.txt")).tx)
        val frames = requireNotNull(tx.framesPerPacketTotal).toDouble()
        val linkLifetimeSeconds = 7_828.0

        val at128 = frames / (96_000.0 / 128)
        val at256 = frames / (96_000.0 / 256)

        assertTrue(
            "128 samples per frame implies ${at128.toInt()} s of encoding, which must fit " +
                "inside the ${linkLifetimeSeconds.toInt()} s the link existed",
            at128 < linkLifetimeSeconds,
        )
        assertTrue(
            "256 samples per frame would need ${at256.toInt()} s of encoding — longer than " +
                "the link was up, so it is not merely unlikely but impossible",
            at256 > linkLifetimeSeconds,
        )
    }

    @Test
    fun `the LDAC frame rate does not move with the bitrate mode`() {
        // This independence is what makes the frame rate a sanity check on the
        // config rather than a signal about the mode.
        assertEquals(750.0, requireNotNull(LdacModeSignatures.framesPerSecond(96_000)), 0.001)
        assertEquals(375.0, requireNotNull(LdacModeSignatures.framesPerSecond(48_000)), 0.001)
        assertEquals(344.53, requireNotNull(LdacModeSignatures.framesPerSecond(44_100)), 0.01)
    }

    /**
     * Frame sizes are half in the 88.2/96 kHz family, because those rates emit
     * twice as many frames per second for the same bitrate. Getting this
     * backwards puts every mode one rung off.
     */
    @Test
    fun `LDAC frame sizes follow the sample rate family`() {
        assertEquals(
            listOf(330, 220, 110),
            LdacModeSignatures.signatures(48_000).map { it.frameBytes },
        )
        assertEquals(
            listOf(330, 220, 110),
            LdacModeSignatures.signatures(44_100).map { it.frameBytes },
        )
        assertEquals(
            listOf(165, 110, 55),
            LdacModeSignatures.signatures(96_000).map { it.frameBytes },
        )
        assertEquals(
            listOf(165, 110, 55),
            LdacModeSignatures.signatures(88_200).map { it.frameBytes },
        )
    }

    // ---- the worked example --------------------------------------------------

    /**
     * The whole point, run end to end on the verbatim capture.
     *
     * From the dump: 4,693,895 frames across 389,197 media packets, LDAC at
     * 96 kHz. That is 12.06 frames per packet — and the dump's own
     * `Frames per packet (max)` is also 12, so within the skew between two
     * counters read microseconds apart, **every packet carried 12 frames for
     * the whole session**. The packing never changed, which means the adaptive
     * encoder never changed rate.
     *
     * At 96 kHz the three LDAC modes cost 165, 110 and 55 bytes per frame, so
     * 12 frames per packet needs a payload of 1980, 1320 or 660 bytes. Only the
     * last is reachable by any plausible A2DP media MTU (672 or 679 minus
     * headers), so the mode is Connection priority — 330 kbps, the floor.
     *
     * Which is the answer to "this morning I had massive problems": the link
     * spent the entire session at LDAC's lowest rate and never recovered.
     */
    @Test
    fun `the owner's morning session decodes to the LDAC floor`() {
        val tx = requireNotNull(A2dpLinkDumpParser.parse(fixture("bt_manager_pixel11_ldac_txqueue.txt")).tx)
        val framesPerPacket =
            requireNotNull(tx.framesPerPacketTotal).toDouble() / requireNotNull(tx.enqueueCount)
        assertEquals(12.06, framesPerPacket, 0.01)
        assertEquals(12, tx.framesPerPacketMax)

        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            framesPerPacket = framesPerPacket,
            framesPerSecond = 750.0,
        )

        assertEquals(InferenceConfidence.ANALYTIC, inference.confidence)
        assertEquals(LdacModeSignatures.connectionPriority.rawValue, inference.mode?.rawValue)
        assertEquals(330, inference.nominalKbps)
        assertEquals(Honesty.DERIVED, inference.honesty)
        // 12 frames of 55 bytes, and fewer than 13 would fit.
        assertEquals(660..714, inference.impliedPayloadBytes)
    }

    // ---- refusals ------------------------------------------------------------

    /**
     * The frame rate is mode-independent, so a measured rate that disagrees
     * with the codec's geometry means the premise is wrong — not that the mode
     * is unusual. Everything downstream would be arithmetic on the wrong stream.
     */
    @Test
    fun `a frame rate that contradicts the config stops the inference`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            framesPerPacket = 12.0,
            framesPerSecond = 375.0,
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertTrue(inference.reason.contains("not the negotiated one"))
    }

    /**
     * Six frames per packet fits Standard at a 672-byte MTU and High quality at
     * a 1008-byte one. The media MTU is not readable, so both survive and the
     * answer is UNKNOWN with both named — never the likelier of the two.
     */
    @Test
    fun `an ambiguous packing names its candidates instead of choosing`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            framesPerPacket = 6.0,
            framesPerSecond = 750.0,
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertNull(inference.mode)
        assertEquals(2, inference.candidates.size)
        assertTrue(inference.reason.contains("calibration"))
    }

    /**
     * A polling window that spans an ABR step averages two packings and lands
     * between two integers. It identifies neither, and rounding to the nearer
     * one would invent a transition time.
     */
    @Test
    fun `a window that straddles a change identifies nothing`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            framesPerPacket = 9.4,
            framesPerSecond = 750.0,
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertTrue(inference.reason.contains("straddles"))
        // Still reported, because it is measured and still inverse-monotone in
        // the bitrate even when no mode can be named.
        assertEquals(9.4, requireNotNull(inference.framesPerPacket), 0.001)
    }

    @Test
    fun `a codec with no adjustable mode says so rather than guessing`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.AAC,
            sampleRateHz = 44_100,
            framesPerPacket = 5.0,
            framesPerSecond = 43.0,
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertTrue(inference.reason.contains("no adjustable bitrate mode"))
    }

    @Test
    fun `aptX Adaptive is refused for a reason more data cannot fix`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.APTX_ADAPTIVE,
            sampleRateHz = 48_000,
            framesPerPacket = 8.0,
            framesPerSecond = 375.0,
            rawCodecName = "aptX-Adaptive",
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertTrue(inference.reason.contains("continuously"))
    }

    /**
     * This phone advertises LHDCv5 as codec **type 7**, which
     * `CodecDecoding.aptxAdaptiveVendorIds` already claims for aptX Adaptive —
     * so an LHDC link decodes to the wrong family. Matching the printed codec
     * name first is what stops an LHDC stream being handed aptX Adaptive's
     * provider and given aptX Adaptive's excuse.
     */
    @Test
    fun `LHDC is recognised by name even though its codec type decodes to aptX Adaptive`() {
        val provider = CodecModeSignatureRegistry.providerFor(
            family = CodecFamily.APTX_ADAPTIVE,
            rawCodecName = "LHDCv5",
        )
        assertEquals(LhdcV5ModeSignatures, provider)

        val inference = CodecModeInference.infer(
            codec = CodecFamily.APTX_ADAPTIVE,
            sampleRateHz = 96_000,
            framesPerPacket = 12.0,
            framesPerSecond = 750.0,
            rawCodecName = "LHDCv5",
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertTrue("must give LHDC's reason, not aptX Adaptive's", inference.reason.contains("LHDC"))
    }

    // ---- calibration ---------------------------------------------------------

    private fun sample(mode: Long, framesPerPacket: Int) = ModeSignatureSample(
        deviceKey = "xx:xx:xx:xx:ab:cd",
        codecName = "LDAC",
        modeRawValue = mode,
        sampleRateHz = 96_000,
        framesPerPacket = (framesPerPacket - 0.5)..(framesPerPacket + 0.5),
        packetsPerSecond = 100.0..140.0,
        capturedAtMs = 1L,
    )

    /**
     * The same six-frame packing the analytic path had to refuse. Once this
     * link has demonstrated its own signature under a pinned mode, no MTU has
     * to be assumed at all — so the calibrated answer is both more confident
     * and resting on less.
     */
    @Test
    fun `a calibrated band answers what the analytic path could not`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            framesPerPacket = 6.0,
            framesPerSecond = 750.0,
            calibration = listOf(
                sample(LdacModeSignatures.highQuality.rawValue, 6),
                sample(LdacModeSignatures.standard.rawValue, 9),
                sample(LdacModeSignatures.connectionPriority.rawValue, 18),
            ),
        )
        assertEquals(InferenceConfidence.CALIBRATED, inference.confidence)
        assertEquals(LdacModeSignatures.highQuality.rawValue, inference.mode?.rawValue)
        assertEquals(990, inference.nominalKbps)
        assertEquals(Honesty.DERIVED, inference.honesty)
    }

    /**
     * If two modes produced the same packing on this link, the calibration
     * itself cannot separate them — and falling back to the analytic path would
     * be answering a question the better evidence just declined.
     */
    @Test
    fun `overlapping calibrated bands refuse rather than fall back`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            framesPerPacket = 12.0,
            framesPerSecond = 750.0,
            calibration = listOf(
                sample(LdacModeSignatures.standard.rawValue, 12),
                sample(LdacModeSignatures.connectionPriority.rawValue, 12),
            ),
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertEquals(2, inference.candidates.size)
        assertTrue(inference.reason.contains("overlap"))
    }

    @Test
    fun `calibration for a different packing does not block the analytic path`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            framesPerPacket = 12.0,
            framesPerSecond = 750.0,
            calibration = listOf(sample(LdacModeSignatures.highQuality.rawValue, 4)),
        )
        assertEquals(InferenceConfidence.ANALYTIC, inference.confidence)
        assertEquals(LdacModeSignatures.connectionPriority.rawValue, inference.mode?.rawValue)
    }

    @Test
    fun `no counters yet is a distinct answer from ambiguous`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            framesPerPacket = null,
            framesPerSecond = null,
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertTrue(inference.reason.contains("two polls"))
    }
}
