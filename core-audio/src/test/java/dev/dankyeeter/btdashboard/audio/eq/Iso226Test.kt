package dev.dankyeeter.btdashboard.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcription of ISO 226:2003 and the shape of the curve derived from it.
 *
 * Two of these tests are checks against the *outside*, which is what a table of
 * 87 hand-copied numbers needs: the phon identity at 1 kHz is the standard's own
 * definition, and the 40-phon contour is published. A typo in any row shows up
 * in one or the other. The rest are properties of the tilt — the things the
 * feature promises out loud, pinned so they cannot quietly stop being true.
 */
class Iso226Test {

    private fun index(hz: Float): Int = Iso226.FREQUENCIES_HZ.indexOf(hz)

    // ---- the transcription ---------------------------------------------------

    @Test
    fun `the tables have one entry per standard frequency`() {
        assertEquals(29, Iso226.FREQUENCIES_HZ.size)
        // Every level produces a full contour; a short coefficient array would
        // throw here rather than at some later call site.
        assertEquals(29, Iso226.contourDb(40f).size)
        assertEquals(20f, Iso226.FREQUENCIES_HZ.first())
        assertEquals(12500f, Iso226.FREQUENCIES_HZ.last())
    }

    /**
     * At 1 kHz a loudness level of N phon *is* N dB SPL — that is the definition
     * the phon scale rests on, so the formula plus the 1 kHz row must reproduce
     * it. The residual is the standard's own rounding of `af`, `Lu` and `Tf` to
     * three and one decimals; it is about 0.01 dB.
     */
    @Test
    fun `the formula returns the phon it was given at one kilohertz`() {
        val anchor = index(Iso226.ANCHOR_HZ)
        var phon = Iso226.MIN_PHON
        while (phon <= Iso226.MAX_PHON) {
            assertEquals(
                "SPL at 1 kHz for $phon phon",
                phon.toDouble(),
                Iso226.soundPressureLevelDb(anchor, phon).toDouble(),
                0.05,
            )
            phon += 1f
        }
    }

    /**
     * The published 40-phon contour of ISO 226:2003, all 29 points.
     *
     * This is the check that the coefficient tables are the standard's and not
     * something plausible: the contour is a *result* of all three tables at
     * once, so a single wrong digit anywhere moves at least one of these values
     * by more than the tolerance.
     */
    @Test
    fun `the forty-phon contour matches the standard's published values`() {
        val published = listOf(
            99.85f, 93.94f, 88.17f, 82.63f, 77.78f, 73.08f, 68.48f, 64.37f, 60.59f, 56.70f,
            53.41f, 50.40f, 47.58f, 44.98f, 43.05f, 41.34f, 40.06f, 40.01f, 41.82f, 42.51f,
            39.23f, 36.51f, 35.61f, 36.65f, 40.01f, 45.83f, 51.80f, 54.28f, 51.49f,
        )
        val computed = Iso226.contourDb(40f)
        published.forEachIndexed { i, expected ->
            assertEquals(
                "${Iso226.FREQUENCIES_HZ[i]} Hz",
                expected.toDouble(),
                computed[i].toDouble(),
                0.1,
            )
        }
    }

    @Test
    fun `levels outside the standard's range are clamped rather than extrapolated`() {
        val anchor = index(Iso226.ANCHOR_HZ)
        assertEquals(
            Iso226.soundPressureLevelDb(anchor, Iso226.MIN_PHON),
            Iso226.soundPressureLevelDb(anchor, 0f),
        )
        assertEquals(
            Iso226.soundPressureLevelDb(anchor, Iso226.MAX_PHON),
            Iso226.soundPressureLevelDb(anchor, 130f),
        )
    }

    // ---- the tilt ------------------------------------------------------------

    @Test
    fun `the tilt is exactly zero when the current level is the reference`() {
        listOf(40f, 55f, 78f, 90f).forEach { level ->
            Iso226.tiltDb(currentPhon = level, referencePhon = level).forEachIndexed { i, v ->
                assertEquals("${Iso226.FREQUENCIES_HZ[i]} Hz at $level phon", 0.0, v.toDouble(), 1e-4)
            }
        }
    }

    @Test
    fun `the tilt is anchored at one kilohertz for every level`() {
        val anchor = index(Iso226.ANCHOR_HZ)
        listOf(40f, 50f, 60f, 70f, 78f, 85f, 90f).forEach { level ->
            assertEquals(
                "anchor at $level phon",
                0.0,
                Iso226.tiltDb(level, 78f)[anchor].toDouble(),
                1e-4,
            )
        }
    }

    /**
     * The whole point of the feature: the quieter the listening, the more the
     * bass has lost. Checked at 125 Hz, which is below the mids and above the
     * region where the product cap would flatten the comparison.
     */
    @Test
    fun `bass restoration grows monotonically as the level drops`() {
        val bass = index(125f)
        val levels = listOf(78f, 70f, 60f, 50f, 40f)
        val values = levels.map { Iso226.tiltDb(it, 78f)[bass] }
        values.zipWithNext().forEach { (louder, quieter) ->
            assertTrue("125 Hz tilt must grow as the level drops: $values", quieter > louder)
        }
        assertEquals(0.0, values.first().toDouble(), 1e-4)
    }

    /** The same at the top end: quiet listening loses treble too, not only bass. */
    @Test
    fun `treble restoration grows monotonically as the level drops`() {
        val treble = index(12500f)
        val values = listOf(78f, 70f, 60f, 50f, 40f).map { Iso226.tiltDb(it, 78f)[treble] }
        values.zipWithNext().forEach { (louder, quieter) ->
            assertTrue("12.5 kHz tilt must grow as the level drops: $values", quieter > louder)
        }
    }

    /**
     * Above the reference the standard's difference turns negative everywhere —
     * symmetry would say to cut. The raw math is expected to say so; refusing to
     * act on it is [VolumeAwareTilt]'s decision, tested there.
     */
    @Test
    fun `above the reference the raw tilt is negative at the edges of the spectrum`() {
        val loud = Iso226.tiltDb(currentPhon = 90f, referencePhon = 78f)
        assertTrue("20 Hz: ${loud[index(20f)]}", loud[index(20f)] < 0f)
        assertTrue("63 Hz: ${loud[index(63f)]}", loud[index(63f)] < 0f)
        assertTrue("12.5 kHz: ${loud[index(12500f)]}", loud[index(12500f)] < 0f)
    }

    // ---- resampling ----------------------------------------------------------

    @Test
    fun `a curve resamples onto every layout at that layout's length`() {
        val curve = Iso226.tiltDb(50f, 78f)
        EqBandLayout.entries.forEach { layout ->
            assertEquals(layout.id, layout.bandCount, Iso226.resampleTo(curve, layout).size)
        }
    }

    /**
     * ISO 226:2003 stops at 12.5 kHz and every layout has bands above it. The
     * value is held rather than extrapolated: the curve is steep up there, and a
     * linear continuation would invent a double-digit correction for a region
     * the standard says nothing about.
     */
    @Test
    fun `bands above the standard's range hold its last value`() {
        val curve = Iso226.tiltDb(40f, 78f)
        val last = curve.last()
        EqBandLayout.entries.forEach { layout ->
            val resampled = Iso226.resampleTo(curve, layout)
            layout.centersHz.forEachIndexed { i, hz ->
                if (hz > Iso226.FREQUENCIES_HZ.last()) {
                    assertEquals("${layout.id} at $hz Hz", last, resampled[i])
                }
            }
        }
    }

    /** A point that is a standard frequency comes back as its own value. */
    @Test
    fun `resampling is exact where a band centre is a standard frequency`() {
        val curve = Iso226.tiltDb(45f, 78f)
        val resampled = Iso226.resampleTo(curve, EqBandLayout.THIRD_OCTAVE_31)
        // The third-octave layout is the standard's own spacing from 20 Hz up.
        Iso226.FREQUENCIES_HZ.forEachIndexed { i, hz ->
            val band = EqBandLayout.THIRD_OCTAVE_31.centersHz.indexOf(hz)
            if (band >= 0) {
                assertEquals("$hz Hz", curve[i].toDouble(), resampled[band].toDouble(), 1e-4)
            }
        }
    }
}
