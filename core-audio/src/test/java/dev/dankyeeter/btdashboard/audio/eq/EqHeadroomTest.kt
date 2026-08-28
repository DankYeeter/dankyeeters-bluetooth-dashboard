package dev.dankyeeter.btdashboard.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic headroom, in both directions.
 *
 * The rule used to be one-way: `sanitized()` could only ever make the pre-gain
 * deeper. That is right while a finger is on a slider — the boost is already in
 * the signal and has to be paid for in the same write — and wrong the moment the
 * finger comes off, because the boost the headroom was bought for may be gone.
 * What the owner hit: push a band to +5 dB, the music drops 5 dB, pull the band
 * back to 0 and it stays 5 dB down until "Reset" is pressed.
 *
 * [HeadroomMode] is that distinction made explicit, so these tests are the
 * contract for both halves of it rather than for the recovery alone: a mode that
 * recovered while dragging would trade a stuck level for audible pumping.
 */
class EqHeadroomTest {

    private val layout = EqBandLayout.OCTAVE_10

    private fun settings(
        gains: List<Float> = List(layout.bandCount) { 0f },
        preGainDb: Float = 0f,
        autoHeadroom: Boolean = true,
        loudnessRestoration: Boolean = false,
        tiltOn: Boolean = false,
    ) = EqSettings(
        enabled = true,
        layout = layout,
        leftGainsDb = gains,
        rightGainsDb = gains,
        preGainDb = preGainDb,
        autoHeadroom = autoHeadroom,
        loudnessRestoration = loudnessRestoration,
        volumeAwareTilt = tiltOn,
    )

    private fun band(index: Int, gainDb: Float): List<Float> =
        List(layout.bandCount) { if (it == index) gainDb else 0f }

    // ---- the bug ------------------------------------------------------------

    @Test
    fun `a boost taken back gives its headroom back on a committed edit`() {
        val boosted = settings(gains = band(3, 5f)).sanitized(HeadroomMode.TRACK)
        assertEquals(-5f, boosted.preGainDb)

        // The band goes home. This is the state the old rule could not express.
        val restored = boosted.copy(
            leftGainsDb = band(3, 0f),
            rightGainsDb = band(3, 0f),
        ).sanitized(HeadroomMode.TRACK)

        assertEquals(0f, restored.preGainDb)
    }

    @Test
    fun `the deepen-only rule is what keeps a drag from pumping`() {
        val boosted = settings(gains = band(3, 5f)).sanitized(HeadroomMode.DEEPEN_ONLY)
        assertEquals(-5f, boosted.preGainDb)

        // Same edit, mid-drag: the level is held rather than handed back on
        // every intermediate value the finger passes through.
        val midDrag = boosted.copy(
            leftGainsDb = band(3, 1f),
            rightGainsDb = band(3, 1f),
        ).sanitized(HeadroomMode.DEEPEN_ONLY)

        assertEquals(-5f, midDrag.preGainDb)
    }

    @Test
    fun `no intermediate value of a drag is ever left uncharged`() {
        // The safety half of the contract: whatever the finger is doing, the
        // pre-gain is at least as deep as the peak it has to cover.
        var current = settings()
        val walk = listOf(0f, 2f, 7f, 15f, 9f, 4f, 0f, 11f, 0f)
        walk.forEach { value ->
            current = current.copy(
                leftGainsDb = band(0, value),
                rightGainsDb = band(0, value),
            ).sanitized(HeadroomMode.DEEPEN_ONLY)
            assertTrue(
                "at $value dB the pre-gain was ${current.preGainDb}",
                current.preGainDb <= -value,
            )
        }
    }

    @Test
    fun `tracking still charges for a boost immediately`() {
        // Recovery must not have cost the safety: TRACK is exact, not lax.
        val boosted = settings(gains = band(0, 12f)).sanitized(HeadroomMode.TRACK)
        assertEquals(-12f, boosted.preGainDb)
    }

    @Test
    fun `deepen-only is the default, so an unconsidered caller stays safe`() {
        val deep = settings(gains = band(0, 3f), preGainDb = -9f)
        assertEquals(deep.sanitized(HeadroomMode.DEEPEN_ONLY), deep.sanitized())
    }

    // ---- the interactions the model documents -------------------------------

    @Test
    fun `with the automatic headroom off neither mode touches the pre-gain`() {
        val manual = settings(gains = band(0, 6f), preGainDb = -2f, autoHeadroom = false)
        assertEquals(-2f, manual.sanitized(HeadroomMode.DEEPEN_ONLY).preGainDb)
        assertEquals(-2f, manual.sanitized(HeadroomMode.TRACK).preGainDb)
    }

    @Test
    fun `the tilt's headroom recovers when the tilt goes away`() {
        val quiet = settings(tiltOn = true).withVolumeTilt(0.1f).sanitized(HeadroomMode.TRACK)
        val cost = quiet.tiltGainsDb.max()
        assertTrue("expected a real tilt, got $cost", cost > 0f)
        assertEquals(-cost, quiet.preGainDb)

        // Switched off, the layer is written back as zeros by withVolumeTilt and
        // the headroom it bought has to go with it. This is the case the
        // ViewModel used to patch by zeroing the pre-gain by hand before
        // sanitising; TRACK is what made that patch unnecessary.
        val off = quiet.copy(volumeAwareTilt = false)
            .withVolumeTilt(0.1f)
            .sanitized(HeadroomMode.TRACK)
        assertEquals(0f, off.preGainDb)
    }

    @Test
    fun `a band boost and a tilt recover independently of each other`() {
        val both = settings(gains = band(9, 4f), tiltOn = true)
            .withVolumeTilt(0.1f)
            .sanitized(HeadroomMode.TRACK)
        // The tilt lives at the bottom of the spectrum and the boost at the top,
        // so whichever is larger is what the headroom covers.
        val peak = both.staticGainsFor(Ear.LEFT).max()
        assertEquals(-peak, both.preGainDb)

        // Drop the band; the tilt is still there and still charged for.
        val bandGone = both.copy(
            leftGainsDb = List(layout.bandCount) { 0f },
            rightGainsDb = List(layout.bandCount) { 0f },
        ).sanitized(HeadroomMode.TRACK)
        assertEquals(-bandGone.tiltGainsDb.max(), bandGone.preGainDb)
    }

    @Test
    fun `in loudness-restoration mode the boosts stop costing headroom on commit`() {
        val boosted = settings(gains = band(2, 8f)).sanitized(HeadroomMode.TRACK)
        assertEquals(-8f, boosted.preGainDb)

        // The boosts move to the compressor, where they cannot clip, so the
        // pre-gain has to let go of them — which the deepen-only rule could not
        // do, and which is why switching this on used to leave the music quiet.
        val restoring = boosted.copy(loudnessRestoration = true).sanitized(HeadroomMode.TRACK)
        assertEquals(0f, restoring.preGainDb)

        // And back: the boosts are static again and are charged again.
        val static = restoring.copy(loudnessRestoration = false).sanitized(HeadroomMode.TRACK)
        assertEquals(-8f, static.preGainDb)
    }
}
