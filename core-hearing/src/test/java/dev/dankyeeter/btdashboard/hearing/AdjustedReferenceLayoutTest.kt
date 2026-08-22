package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why the generated profile is built on [AdjustedReference.LAYOUT] and not on
 * the app's ten-band default.
 *
 * The first two tests are the proof, not an illustration: on the octave grid the
 * 3 kHz and 6 kHz thresholds change *nothing at all* about the output, so two of
 * the eight tones the user sat through are measured and then discarded. The
 * remaining tests are the guard — they fail if a notch at either frequency ever
 * stops reaching the EQ again, whatever the cause.
 *
 * Everything is asserted on `eq.leftGainsDb`, the gains that actually reach the
 * effect, rather than on an intermediate: the interesting failure is a value
 * that is computed correctly and then dropped on the way out.
 */
class AdjustedReferenceLayoutTest {

    private val calculator = NalRCompensationCalculator()

    /** Flat hearing except for one measured dip, which is the case in question. */
    private fun notchAt(frequencyHz: Int, depthDb: Double = 50.0): Audiogram {
        val points = TEST_FREQUENCIES_HZ.map { hz ->
            ThresholdPoint(hz, if (hz == frequencyHz) depthDb else 0.0)
        }
        return Audiogram(runIds = listOf("r1"), left = points, right = points)
    }

    private fun audiogram(values: List<Double>): Audiogram {
        val points = TEST_FREQUENCIES_HZ.zip(values) { hz, v -> ThresholdPoint(hz, v) }
        return Audiogram(runIds = listOf("r1"), left = points, right = points)
    }

    private fun gains(audiogram: Audiogram, layout: EqBandLayout): List<Float> = calculator
        .computeDetailed(audiogram, CalibrationPresetRepository.GENERIC_ID, 1f, 1f, layout)
        .eq.leftGainsDb

    // ---- the defect ----------------------------------------------------------

    @Test
    fun `the ten-band layout ignores the measured 3 kHz and 6 kHz thresholds entirely`() {
        val sloping = listOf(10.0, 15.0, 20.0, 30.0, 40.0, 45.0, 50.0, 55.0)
        // Same ear, except that the two frequencies in question are moved by a
        // full 40 dB — far more than any measurement error could account for.
        val moved = listOf(10.0, 15.0, 20.0, 30.0, 0.0, 45.0, 10.0, 55.0)

        assertEquals(
            "3 kHz and 6 kHz reach no band on the octave grid",
            gains(audiogram(sloping), EqBandLayout.OCTAVE_10),
            gains(audiogram(moved), EqBandLayout.OCTAVE_10),
        )
    }

    @Test
    fun `the generated layout carries those two thresholds into the output`() {
        val sloping = listOf(10.0, 15.0, 20.0, 30.0, 40.0, 45.0, 50.0, 55.0)
        val moved = listOf(10.0, 15.0, 20.0, 30.0, 0.0, 45.0, 10.0, 55.0)

        assertNotEquals(
            gains(audiogram(sloping), AdjustedReference.LAYOUT),
            gains(audiogram(moved), AdjustedReference.LAYOUT),
        )
    }

    // ---- the consequence, and the guard --------------------------------------

    @Test
    fun `a 3 kHz notch produces no compensation whatsoever on ten bands`() {
        val flat = gains(notchAt(3000), EqBandLayout.OCTAVE_10)
        assertTrue("expected a flat curve, got $flat", flat.all { it == 0f })
    }

    @Test
    fun `a 6 kHz notch produces no compensation whatsoever on ten bands`() {
        val flat = gains(notchAt(6000), EqBandLayout.OCTAVE_10)
        assertTrue("expected a flat curve, got $flat", flat.all { it == 0f })
    }

    @Test
    fun `the generated layout compensates a 3 kHz notch, centred on the notch`() {
        val curve = gains(notchAt(3000), AdjustedReference.LAYOUT)
        val loudest = curve.indices.maxByOrNull { curve[it] } ?: -1
        assertTrue("nothing was lifted: $curve", curve[loudest] > 1f)
        assertEquals(3200f, AdjustedReference.LAYOUT.centersHz[loudest], 0f)
    }

    @Test
    fun `the generated layout compensates a 6 kHz notch, centred on the notch`() {
        val curve = gains(notchAt(6000), AdjustedReference.LAYOUT)
        val loudest = curve.indices.maxByOrNull { curve[it] } ?: -1
        assertTrue("nothing was lifted: $curve", curve[loudest] > 1f)
        assertEquals(6400f, AdjustedReference.LAYOUT.centersHz[loudest], 0f)
    }

    @Test
    fun `a 4 kHz notch reaches both layouts, but the coarse one spreads it two octaves`() {
        // Pinned because the shorthand "a 4 kHz notch produces zero correction"
        // is *not* true and should not be repeated: 4000 Hz is itself an octave
        // centre, so it lands. What the coarse grid does instead is smear it —
        // the three-point average puts the identical lift on 2 kHz and 8 kHz,
        // where nothing was measured as wrong.
        val coarse = gains(notchAt(4000), EqBandLayout.OCTAVE_10)
        assertTrue("the notch should reach the output here: $coarse", coarse[7] > 1f)
        assertEquals("2 kHz gets the same lift as 4 kHz", coarse[7], coarse[6], 1e-4f)
        assertEquals("8 kHz too", coarse[7], coarse[8], 1e-4f)

        // On the generated grid the same notch stays inside 3.2-6.4 kHz.
        val fine = gains(notchAt(4000), AdjustedReference.LAYOUT)
        assertEquals("1.6 kHz stays untouched", 0f, fine[12], 1e-4f)
        assertTrue("the notch region carries it: $fine", fine[14] > 1f && fine[15] > 1f)
    }

    // ---- the setting itself --------------------------------------------------

    @Test
    fun `the generated profile is pinned to a layout that can represent 3k and 6k`() {
        // Guards the constant against a well-meant revert to the default. The
        // requirement is not "twenty bands" but "a centre near each of the two
        // frequencies the octave grid throws away".
        assertNotEquals(EqBandLayout.OCTAVE_10, AdjustedReference.LAYOUT)
        listOf(3000f, 6000f).forEach { measured ->
            val nearest = AdjustedReference.LAYOUT.centersHz.minByOrNull {
                kotlin.math.abs(it - measured)
            } ?: 0f
            val error = kotlin.math.abs(nearest - measured) / measured
            assertTrue("nearest centre to $measured Hz is $nearest Hz", error < 0.1f)
        }
    }

    @Test
    fun `the default layout is unchanged for everything else`() {
        // Manual profiles and the EQ screen keep the ten-band default; this
        // change is scoped to the generated curve alone.
        assertEquals(EqBandLayout.OCTAVE_10, EqBandLayout.DEFAULT)
    }
}
