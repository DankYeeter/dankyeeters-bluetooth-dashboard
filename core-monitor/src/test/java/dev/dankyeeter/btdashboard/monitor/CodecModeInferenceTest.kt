package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpLinkDumpParser
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxDelta
import dev.dankyeeter.btdashboard.monitor.link.live.CodecModeInference
import dev.dankyeeter.btdashboard.monitor.link.live.CodecModeSignatureRegistry
import dev.dankyeeter.btdashboard.monitor.link.live.Honesty
import dev.dankyeeter.btdashboard.monitor.link.live.InferenceConfidence
import dev.dankyeeter.btdashboard.monitor.link.live.LdacModeSignatures
import dev.dankyeeter.btdashboard.monitor.link.live.LdacStackState
import dev.dankyeeter.btdashboard.monitor.link.live.LhdcV5ModeSignatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app is allowed to say the encoder is doing.
 *
 * This file used to hold a worked example deriving LDAC's mode from the tx-queue
 * counters, and a conclusion drawn from it — that a two-hour session had sat at
 * the 330 kbps floor. Both are gone, and the falsification that removed them is
 * the first test below, kept as a test rather than a comment so that anyone
 * tempted to rebuild the inference finds the measurement first.
 */
class CodecModeInferenceTest {

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("dumps/$name"),
    ) { "fixture $name missing" }.bufferedReader().readText()

    // ---- the falsification ---------------------------------------------------

    /**
     * Why the counter-based inference is gone.
     *
     * On the device, with music playing, the enqueue counter ticked at a
     * constant ~50/s in **every** mode — pinned 990, pinned 330, and ABR alike.
     * It counts the media timer's 20 ms period, not radio packets. The two rows
     * below are those two pinned runs, and what they show is that
     * frames-divided-by-enqueues reported the playing duty cycle
     * (`duty x 15`) and nothing about the mode: the *higher* bitrate produced
     * the *higher* ratio, which is backwards for a packing, and both figures are
     * simply 15 times how much of the window had audio in it.
     *
     * A packing would have had to move by a factor of three between these two
     * modes. It moved by a quarter, in the wrong direction.
     */
    @Test
    fun `frames per enqueue reports duty cycle and not the LDAC mode`() {
        // 990 pinned: 90% of the window playing, at 750 frames/s and 50 ticks/s.
        val at990 = A2dpTxDelta(windowMs = 10_000, enqueued = 500, framesEncoded = 6_750)
        // 330 pinned: 70% of the same window playing. Same counters, same rule.
        val at330 = A2dpTxDelta(windowMs = 10_000, enqueued = 500, framesEncoded = 5_250)

        assertEquals(13.5, requireNotNull(at990.framesPerEnqueue), 0.001)
        assertEquals(10.5, requireNotNull(at330.framesPerEnqueue), 0.001)
        // Both are duty x 15, which is the whole content of the number.
        assertEquals(0.90 * 15, requireNotNull(at990.framesPerEnqueue), 0.001)
        assertEquals(0.70 * 15, requireNotNull(at330.framesPerEnqueue), 0.001)
        // And the enqueue rate is the same 50/s in both, which is what makes it
        // a timer rather than a radio counter.
        assertEquals(50.0, requireNotNull(at990.packetsPerSecond), 0.001)
        assertEquals(50.0, requireNotNull(at330.packetsPerSecond), 0.001)
    }

    /**
     * The frame count itself is still real, and still mode-independent — which
     * is exactly why it can never carry a rate.
     *
     * The verbatim capture recorded 4,693,895 encoded frames over a session that
     * connected at 15:14:21 and dropped at 17:24:49, 7,828 seconds. Only one of
     * the two candidate frame lengths fits inside the time the link existed.
     */
    @Test
    fun `the 128 sample frame is the only one that fits the captured session`() {
        val tx = requireNotNull(
            A2dpLinkDumpParser.parse(fixture("bt_manager_pixel11_ldac_txqueue.txt")).tx,
        )
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
        assertEquals(750.0, requireNotNull(LdacModeSignatures.framesPerSecond(96_000)), 0.001)
        assertEquals(375.0, requireNotNull(LdacModeSignatures.framesPerSecond(48_000)), 0.001)
        assertEquals(344.53, requireNotNull(LdacModeSignatures.framesPerSecond(44_100)), 0.01)
    }

    /**
     * The frame geometry itself, which is verified and still correct — it is the
     * *use* of it as a mode signature that was falsified, not the arithmetic.
     * Kept because the calibration seam is built on it and a future codec would
     * inherit the same rule: sizes are half in the 88.2/96 kHz family, because
     * those rates emit twice as many frames per second for the same bitrate.
     */
    @Test
    fun `LDAC frame sizes follow the sample rate family`() {
        assertEquals(
            listOf(330, 220, 110),
            LdacModeSignatures.signatures(48_000).map { it.frameBytes },
        )
        assertEquals(
            listOf(165, 110, 55),
            LdacModeSignatures.signatures(96_000).map { it.frameBytes },
        )
    }

    // ---- the direct read -----------------------------------------------------

    /**
     * The whole feature, on the verbatim capture: an LDAC link running ABR, and
     * the rate is simply read.
     */
    @Test
    fun `the ABR capture reports its rate as a measurement`() {
        val parsed = A2dpLinkDumpParser.parse(fixture("bt_manager_pixel11_ldac_state_abr.txt"))

        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            rawCodecName = "LDAC",
            stack = parsed.ldacStack,
        )

        assertEquals(InferenceConfidence.MEASURED, inference.confidence)
        assertEquals(Honesty.MEASURED, inference.honesty)
        assertEquals(396, inference.measuredKbps)
        assertEquals("ABR", inference.qualityModeLabel)
        assertEquals(883, inference.effectiveMtu)
        // ABR has no named mode, and inventing one from the nearest ladder rung
        // would turn a measurement back into a guess.
        assertNull(inference.mode)
        assertNull(inference.nominalKbps)
        assertTrue(inference.reason.contains("adaptive"))
    }

    /**
     * 396 kbps is not on LDAC's 990/660/330 ladder at all — ABR uses steps in
     * between — so the measurement must survive having no rung to sit on.
     */
    @Test
    fun `an intermediate ABR step is reported as itself and not rounded to a rung`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            stack = LdacStackState(qualityMode = "ABR", transmissionKbps = 492),
        )
        assertEquals(492, inference.measuredKbps)
        assertNotEquals(660, inference.measuredKbps)
        assertNull(inference.mode)
    }

    /** A pinned link gets its name back, because the stack names it. */
    @Test
    fun `a pinned link is named from the stack's own quality token`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            stack = LdacStackState(qualityMode = "HIGH", transmissionKbps = 990),
        )
        assertEquals(InferenceConfidence.MEASURED, inference.confidence)
        assertEquals(LdacModeSignatures.highQuality.rawValue, inference.mode?.rawValue)
        assertEquals(990, inference.nominalKbps)
        assertEquals(990, inference.measuredKbps)
    }

    /**
     * The spec figure and the measurement are kept apart even when they
     * disagree, which is the case worth having them both for: a link pinned to
     * High quality that is only managing 660 has a story, and one number would
     * hide it.
     */
    @Test
    fun `a pinned mode that is not delivering keeps both figures`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            stack = LdacStackState(qualityMode = "HIGH", transmissionKbps = 660),
        )
        assertEquals(990, inference.nominalKbps)
        assertEquals(660, inference.measuredKbps)
    }

    /**
     * The token is the stack's vocabulary, not this app's. An unfamiliar one
     * must reach the screen as itself, with the rate still measured.
     */
    @Test
    fun `an unknown quality token is passed through with the rate intact`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            stack = LdacStackState(qualityMode = "TURBO", transmissionKbps = 512),
        )
        assertEquals(InferenceConfidence.MEASURED, inference.confidence)
        assertEquals("TURBO", inference.qualityModeLabel)
        assertEquals(512, inference.measuredKbps)
        assertNull("an unrecognised token names no mode", inference.mode)
        assertTrue(inference.reason.contains("TURBO"))
    }

    // ---- refusals ------------------------------------------------------------

    /**
     * The fallback the panel's "rate not observable" line still exists for. No
     * section, no rate — and specifically no attempt to reconstruct one from the
     * counters, which is what the first test in this file rules out.
     */
    @Test
    fun `without the stack section LDAC has no rate and says so`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            rawCodecName = "LDAC",
            stack = null,
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertEquals(Honesty.UNAVAILABLE, inference.honesty)
        assertNull(inference.measuredKbps)
        assertTrue(inference.reason.contains("does not print"))
    }

    /** A block that was found but carried no bitrate is the same absence. */
    @Test
    fun `a section without a bitrate is absence and not zero`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.LDAC,
            sampleRateHz = 96_000,
            stack = LdacStackState(qualityMode = "ABR", transmissionKbps = null),
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertNull(inference.measuredKbps)
    }

    @Test
    fun `a codec with no adjustable mode says so rather than guessing`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.AAC,
            sampleRateHz = 44_100,
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertTrue(inference.reason.contains("no adjustable bitrate mode"))
    }

    @Test
    fun `aptX Adaptive is refused for a reason more data cannot fix`() {
        val inference = CodecModeInference.infer(
            codec = CodecFamily.APTX_ADAPTIVE,
            sampleRateHz = 48_000,
            rawCodecName = "aptX-Adaptive",
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertTrue(inference.reason.contains("continuously"))
    }

    /**
     * This phone advertises LHDCv5 as codec **type 7**, an id this app used to
     * hand to aptX Adaptive. `CodecDecoding` now decides by name, so the family
     * that arrives here is the right one — but the registry's own name-first
     * lookup still has to hold, because a source that read the link through the
     * framework API has a number and no name, and every number it cannot place
     * arrives as the one shared [CodecFamily.VENDOR].
     *
     * Either way an LHDC stream must get LHDC's refusal and never aptX
     * Adaptive's, which describes a codec it is not.
     */
    @Test
    fun `LHDC is recognised by name whatever its codec type decoded to`() {
        assertEquals(
            LhdcV5ModeSignatures,
            CodecModeSignatureRegistry.providerFor(
                family = CodecFamily.VENDOR,
                rawCodecName = "LHDCv5",
            ),
        )
        assertEquals(
            LhdcV5ModeSignatures,
            CodecModeSignatureRegistry.providerFor(family = CodecFamily.LHDC_V5),
        )

        val inference = CodecModeInference.infer(
            codec = CodecFamily.VENDOR,
            sampleRateHz = 96_000,
            rawCodecName = "LHDCv5",
        )
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertTrue("must give LHDC's reason, not aptX Adaptive's", inference.reason.contains("LHDC"))
    }

    @Test
    fun `no negotiated codec is a distinct answer from an unreported rate`() {
        val inference = CodecModeInference.infer(codec = null, sampleRateHz = null)
        assertEquals(InferenceConfidence.UNKNOWN, inference.confidence)
        assertTrue(inference.reason.contains("no negotiated codec"))
        assertFalse(inference.reason.contains("does not print"))
    }
}
