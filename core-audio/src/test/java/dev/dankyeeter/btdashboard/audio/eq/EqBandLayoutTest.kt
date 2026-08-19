package dev.dankyeeter.btdashboard.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Switching layout must change the *resolution* of a curve, not the curve.
 * Resetting to flat on every switch would quietly throw away a setting the
 * user tuned by ear, or a compensation curve derived from a hearing test.
 */
class EqBandLayoutTest {

    @Test
    fun `layouts are ordered low to high with no duplicates`() {
        EqBandLayout.entries.forEach { layout ->
            val centres = layout.centersHz
            assertEquals("${layout.id} has duplicates", centres.size, centres.distinct().size)
            assertEquals(
                "${layout.id} is not ascending",
                centres.sorted(),
                centres,
            )
        }
    }

    @Test
    fun `band count matches the declared centres`() {
        EqBandLayout.entries.forEach { layout ->
            assertEquals(layout.centersHz.size, layout.bandCount)
        }
    }

    @Test
    fun `resampling to the same layout is a no-op`() {
        val gains = List(EqBandLayout.OCTAVE_10.bandCount) { it.toFloat() }
        assertEquals(
            gains,
            EqBandLayout.resample(gains, EqBandLayout.OCTAVE_10, EqBandLayout.OCTAVE_10),
        )
    }

    @Test
    fun `a flat curve stays flat at any resolution`() {
        val flat = List(EqBandLayout.OCTAVE_10.bandCount) { 0f }
        val wide = EqBandLayout.resample(flat, EqBandLayout.OCTAVE_10, EqBandLayout.THIRD_OCTAVE_31)
        assertEquals(31, wide.size)
        assertTrue(wide.all { abs(it) < 1e-4 })
    }

    @Test
    fun `a constant boost survives the switch`() {
        val boosted = List(EqBandLayout.OCTAVE_10.bandCount) { 6f }
        val wide = EqBandLayout.resample(boosted, EqBandLayout.OCTAVE_10, EqBandLayout.THIRD_OCTAVE_31)
        assertTrue("expected a uniform 6 dB, got $wide", wide.all { abs(it - 6f) < 0.01f })
    }

    @Test
    fun `a band centre shared by both layouts keeps its gain exactly`() {
        // 1 kHz exists in the 10- and the 31-band layout.
        val gains = List(EqBandLayout.OCTAVE_10.bandCount) { 0f }.toMutableList()
        val source = EqBandLayout.OCTAVE_10.centersHz.indexOf(1000f)
        gains[source] = 9f
        val wide = EqBandLayout.resample(gains, EqBandLayout.OCTAVE_10, EqBandLayout.THIRD_OCTAVE_31)
        val target = EqBandLayout.THIRD_OCTAVE_31.centersHz.indexOf(1000f)
        assertEquals(9f, wide[target], 0.01f)
    }

    @Test
    fun `interpolation is logarithmic, not linear`() {
        // Ramp 0 dB at 250 Hz to 12 dB at 500 Hz, then probe 315 Hz — a centre
        // the source layout does not have, so the value has to be interpolated
        // rather than copied. On a log axis 315 Hz lands at 4.00 dB; a linear
        // interpolation would put it at 3.12 dB, so the two are distinguishable.
        val gains = EqBandLayout.OCTAVE_10.centersHz.map { hz ->
            if (hz <= 250f) 0f else 12f
        }
        val resampled = EqBandLayout.resample(gains, EqBandLayout.OCTAVE_10, EqBandLayout.THIRD_OCTAVE_31)
        val at315 = resampled[EqBandLayout.THIRD_OCTAVE_31.centersHz.indexOf(315f)]
        assertEquals(4.00f, at315, 0.1f)
    }

    @Test
    fun `outside the source range the edge value is held, never extrapolated`() {
        val gains = EqBandLayout.OCTAVE_10.centersHz.map { if (it <= 31.5f) -9f else 0f }
        val wide = EqBandLayout.resample(gains, EqBandLayout.OCTAVE_10, EqBandLayout.THIRD_OCTAVE_31)
        // 20 Hz sits below the 10-band layout's lowest centre (31.5 Hz).
        val at20 = wide[EqBandLayout.THIRD_OCTAVE_31.centersHz.indexOf(20f)]
        assertEquals("edge must be held, not run away", -9f, at20, 0.01f)
    }

    @Test
    fun `a mismatched gain list degrades to flat instead of crashing`() {
        val wrong = listOf(1f, 2f, 3f)
        val out = EqBandLayout.resample(wrong, EqBandLayout.OCTAVE_10, EqBandLayout.HALF_OCTAVE_20)
        assertEquals(20, out.size)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `a round trip through a finer layout stays close to the original`() {
        val gains = EqBandLayout.OCTAVE_10.centersHz.mapIndexed { i, _ -> (i % 5).toFloat() }
        val wide = EqBandLayout.resample(gains, EqBandLayout.OCTAVE_10, EqBandLayout.THIRD_OCTAVE_31)
        val back = EqBandLayout.resample(wide, EqBandLayout.THIRD_OCTAVE_31, EqBandLayout.OCTAVE_10)
        gains.indices.forEach { i ->
            assertEquals("band $i drifted", gains[i], back[i], 0.6f)
        }
    }

    @Test
    fun `extrapolated bands are the ones audiometry cannot reach`() {
        val layout = EqBandLayout.THIRD_OCTAVE_31
        layout.extrapolatedIndices.forEach { i ->
            val hz = layout.centersHz[i]
            assertTrue("$hz should be inside 250-8000", hz < 250f || hz > 8000f)
        }
        // 1 kHz is squarely inside the measured range.
        assertTrue(layout.centersHz.indexOf(1000f) !in layout.extrapolatedIndices)
    }
}

/** [EqSettings] must never hand the audio engine a wrongly sized gain list. */
class EqSettingsLayoutTest {

    @Test
    fun `switching layout resizes both channels`() {
        val settings = EqSettings(layout = EqBandLayout.OCTAVE_10)
        val wide = settings.withLayout(EqBandLayout.THIRD_OCTAVE_31)
        assertEquals(31, wide.leftGainsDb.size)
        assertEquals(31, wide.rightGainsDb.size)
        assertEquals(EqBandLayout.THIRD_OCTAVE_31, wide.layout)
    }

    @Test
    fun `headroom is recomputed for the resampled curve`() {
        val boosted = EqSettings(
            layout = EqBandLayout.OCTAVE_10,
            leftGainsDb = List(10) { 10f },
            rightGainsDb = List(10) { 10f },
        )
        val wide = boosted.withLayout(EqBandLayout.THIRD_OCTAVE_31)
        assertTrue("pre-gain must absorb the boost", wide.preGainDb <= -9.9f)
    }

    @Test
    fun `a wrongly sized gain list is rejected at construction`() {
        val failed = runCatching {
            EqSettings(layout = EqBandLayout.THIRD_OCTAVE_31, leftGainsDb = List(10) { 0f })
        }.isFailure
        assertTrue("a 10-entry list must not pass as 31 bands", failed)
    }
}
