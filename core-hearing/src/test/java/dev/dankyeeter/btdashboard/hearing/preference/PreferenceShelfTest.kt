package dev.dankyeeter.btdashboard.hearing.preference

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf shape, and the loudness match that keeps the whole test honest.
 */
class PreferenceShelfTest {

    private val octave = EqBandLayout.OCTAVE_10

    // ---- shape ---------------------------------------------------------------

    @Test
    fun `a shelf is exactly half applied at its corner`() {
        assertEquals(0.5, PreferenceShelf.bassWeightAt(PreferenceShelf.BASS_CORNER_HZ), 1e-9)
        assertEquals(0.5, PreferenceShelf.trebleWeightAt(PreferenceShelf.TREBLE_CORNER_HZ), 1e-9)
    }

    @Test
    fun `the bass shelf is nearly whole well below the corner and gone well above`() {
        assertTrue(PreferenceShelf.bassWeightAt(31.5f) > 0.95)
        assertTrue(PreferenceShelf.bassWeightAt(4000f) < 0.01)
    }

    @Test
    fun `the treble shelf is the mirror image`() {
        assertEquals(
            PreferenceShelf.bassWeightAt(PreferenceShelf.BASS_CORNER_HZ / 4f),
            PreferenceShelf.trebleWeightAt(PreferenceShelf.TREBLE_CORNER_HZ * 4f),
            1e-9,
        )
    }

    @Test
    fun `the two shelves barely overlap`() {
        // At the bass corner the treble shelf must be doing essentially nothing,
        // or the two sliders would be fighting over the same bands.
        assertTrue(PreferenceShelf.trebleWeightAt(PreferenceShelf.BASS_CORNER_HZ) < 0.02)
        assertTrue(PreferenceShelf.bassWeightAt(PreferenceShelf.TREBLE_CORNER_HZ) < 0.02)
    }

    @Test
    fun `the slope is gentle rather than a brick wall`() {
        // One octave either side of the corner a 9 dB shelf has moved by
        // something like 6 dB in total, not 9 — roughly 3 dB per octave.
        val candidate = PreferenceCandidate(9f, 0f)
        val below = PreferenceShelf.gainAtHz(candidate, PreferenceShelf.BASS_CORNER_HZ / 2f)
        val above = PreferenceShelf.gainAtHz(candidate, PreferenceShelf.BASS_CORNER_HZ * 2f)
        assertTrue("too steep: $below vs $above", below - above in 5f..8f)
    }

    @Test
    fun `a neutral candidate renders flat`() {
        EqBandLayout.entries.forEach { layout ->
            val gains = PreferenceShelf.gains(PreferenceCandidate.NEUTRAL, layout)
            assertEquals(layout.bandCount, gains.size)
            assertTrue(gains.all { kotlin.math.abs(it) < 1e-6 })
        }
    }

    @Test
    fun `gains are monotone across the bass region`() {
        val gains = PreferenceShelf.gains(PreferenceCandidate(9f, 0f), EqBandLayout.THIRD_OCTAVE_31)
        val centres = EqBandLayout.THIRD_OCTAVE_31.centersHz
        // Below the treble corner nothing but the bass shelf is acting, and it
        // only ever falls with frequency.
        centres.indices.filter { centres[it] <= 1000f }.zipWithNext { a, b ->
            assertTrue("not monotone at ${centres[b]} Hz", gains[b] <= gains[a] + 1e-6f)
        }
    }

    /**
     * The rendering is the closed form evaluated at the layout's own centres —
     * no tabulate-and-resample step in between, so a band at 800 Hz gets the
     * shelf's value at 800 Hz however many bands there are around it.
     */
    @Test
    fun `every layout renders the closed form at its own centres`() {
        val candidate = PreferenceCandidate(6f, -4f)
        EqBandLayout.entries.forEach { layout ->
            PreferenceShelf.gains(candidate, layout).forEachIndexed { index, gain ->
                assertEquals(
                    "${layout.id} at ${layout.centersHz[index]} Hz",
                    PreferenceShelf.gainAtHz(candidate, layout.centersHz[index]).toDouble(),
                    gain.toDouble(),
                    1e-6,
                )
            }
        }
    }

    @Test
    fun `a band that exists in two layouts gets the same gain in both`() {
        val candidate = PreferenceCandidate(6f, -4f)
        val octaveIndex = EqBandLayout.OCTAVE_10.centersHz.indexOf(1000f)
        val thirdIndex = EqBandLayout.THIRD_OCTAVE_31.centersHz.indexOf(1000f)
        assertEquals(
            PreferenceShelf.gains(candidate, EqBandLayout.OCTAVE_10)[octaveIndex].toDouble(),
            PreferenceShelf.gains(candidate, EqBandLayout.THIRD_OCTAVE_31)[thirdIndex].toDouble(),
            1e-6,
        )
    }

    // ---- loudness ------------------------------------------------------------

    /**
     * The number the whole loudness match rests on, pinned by hand.
     *
     * Worked out independently of the implementation: the shelf weight at each
     * of the ten octave centres is `1 / (1 + 2^(2·log2(f/200)))`, the gain there
     * is nine times that, and the pink-weighted mean of `10^(g/10)` over the ten
     * equally wide bands comes to 2.7266, i.e. **4.36 dB**. A +9 dB bass shelf
     * therefore has to audition about four and a third decibels quieter than
     * flat, or it wins every comparison on level alone.
     */
    @Test
    fun `a nine dB bass boost is four and a third decibels louder`() {
        val offset = PreferenceShelf.levelOffsetDb(PreferenceCandidate(9f, 0f), octave)
        assertEquals(4.356, offset.toDouble(), 0.05)
    }

    @Test
    fun `a flat candidate needs no correction`() {
        EqBandLayout.entries.forEach { layout ->
            assertEquals(
                0.0,
                PreferenceShelf.levelOffsetDb(PreferenceCandidate.NEUTRAL, layout).toDouble(),
                1e-6,
            )
        }
    }

    @Test
    fun `a boost is louder and a cut is quieter`() {
        assertTrue(PreferenceShelf.levelOffsetDb(PreferenceCandidate(6f, 0f), octave) > 0f)
        assertTrue(PreferenceShelf.levelOffsetDb(PreferenceCandidate(-6f, 0f), octave) < 0f)
        assertTrue(PreferenceShelf.levelOffsetDb(PreferenceCandidate(0f, 6f), octave) > 0f)
        assertTrue(PreferenceShelf.levelOffsetDb(PreferenceCandidate(0f, -6f), octave) < 0f)
    }

    @Test
    fun `the offset grows with the boost`() {
        val offsets = listOf(0f, 2f, 4f, 6f, 9f)
            .map { PreferenceShelf.levelOffsetDb(PreferenceCandidate(it, 0f), octave) }
        offsets.zipWithNext { a, b -> assertTrue("$a should be below $b", a < b) }
    }

    /**
     * The confound, stated as a test: without the correction a bass-boosted
     * candidate really is louder than a flat one, by a margin far above the
     * roughly 1 dB at which a level difference starts deciding preference
     * judgements on its own.
     */
    @Test
    fun `the level difference the match removes is not small`() {
        val boosted = PreferenceShelf.levelOffsetDb(PreferenceCandidate(9f, 6f), octave)
        assertTrue("only ${boosted}dB — too small to be worth correcting?", boosted > 3f)
    }

    @Test
    fun `the ceiling covers every candidate in the space`() {
        EqBandLayout.entries.forEach { layout ->
            val ceiling = PreferenceShelf.ceilingDb(layout)
            listOf(
                PreferenceCandidate(9f, 6f),
                PreferenceCandidate(9f, -6f),
                PreferenceCandidate(-6f, 6f),
                PreferenceCandidate(-6f, -6f),
                PreferenceCandidate(4f, 3f),
            ).forEach { candidate ->
                val peak = PreferenceShelf.gains(candidate, layout).max()
                assertTrue("$candidate exceeds the ceiling on ${layout.id}", peak <= ceiling + 1e-5f)
            }
            assertTrue(ceiling >= 0f)
        }
    }
}
