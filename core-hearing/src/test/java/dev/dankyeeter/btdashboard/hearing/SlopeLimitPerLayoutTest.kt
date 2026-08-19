package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/**
 * The 6 dB ceiling is per *octave*. Neighbours on a third-octave layout are a
 * third of an octave apart, so the step allowed between them must be a third as
 * large — otherwise the same audiogram produces a curve that climbs three times
 * as steeply just because the user picked more bands.
 */
class SlopeLimitPerLayoutTest {

    private fun octavesBetween(layout: EqBandLayout, i: Int): Double =
        ln(layout.centersHz[i] / layout.centersHz[i - 1].toDouble()) / ln(2.0)

    private fun maxStepIn(values: List<Double>, layout: EqBandLayout): Double =
        (1 until values.size).maxOf { i ->
            abs(values[i] - values[i - 1]) / octavesBetween(layout, i)
        }

    @Test
    fun `a step from silence to full boost is clamped on the octave layout`() {
        val layout = EqBandLayout.OCTAVE_10
        val input = List(layout.bandCount) { if (it >= 8) 12.0 else 0.0 }

        val limited = NalRCompensationCalculator.limitSlope(input, layout)

        // 4000 -> 8000 Hz is exactly one octave, so exactly 6 dB is allowed.
        assertEquals(6.0, limited[8], 1e-6)
    }

    @Test
    fun `the same jump is clamped harder on a third-octave layout`() {
        val layout = EqBandLayout.THIRD_OCTAVE_31
        val boostFrom = layout.centersHz.indexOf(8000f)
        val input = List(layout.bandCount) { if (it >= boostFrom) 12.0 else 0.0 }

        val limited = NalRCompensationCalculator.limitSlope(input, layout)

        // A third of an octave allows a third of the step, so ~2 dB.
        val step = limited[boostFrom] - limited[boostFrom - 1]
        assertTrue("step was $step dB, expected about 2", step in 1.5..2.5)
    }

    @Test
    fun `no layout exceeds 6 dB per octave`() {
        EqBandLayout.entries.forEach { layout ->
            val spiky = List(layout.bandCount) { if (it % 2 == 0) 12.0 else -12.0 }
            val limited = NalRCompensationCalculator.limitSlope(spiky, layout)
            val worst = maxStepIn(limited, layout)
            assertTrue("${layout.id} reached $worst dB/octave", worst <= 6.0 + 1e-6)
        }
    }

    @Test
    fun `limiting never raises a band on any layout`() {
        EqBandLayout.entries.forEach { layout ->
            val input = List(layout.bandCount) { if (it == layout.bandCount / 2) 12.0 else 0.0 }
            val limited = NalRCompensationCalculator.limitSlope(input, layout)
            limited.forEachIndexed { i, v ->
                assertTrue("${layout.id} band $i was raised", v <= input[i] + 1e-9)
            }
        }
    }

    @Test
    fun `a gain list that does not match the layout still degrades safely`() {
        // Defensive path: fall back to the flat per-octave ceiling rather than
        // indexing past the end of the centre list.
        val limited = NalRCompensationCalculator.limitSlope(
            listOf(0.0, 12.0, 0.0),
            EqBandLayout.THIRD_OCTAVE_31,
        )
        assertEquals(3, limited.size)
        assertTrue(limited[1] <= 6.0 + 1e-9)
    }
}
