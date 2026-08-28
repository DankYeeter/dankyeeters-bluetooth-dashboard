package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ISO 7029 age model, its clamps, and the one conclusion it is allowed to
 * draw.
 *
 * Two kinds of test in here and they are worth telling apart. Most of them pin
 * *structure* — the quadratic, the zero at 18, the clamp at 80, the way the
 * sexes relate, the fact that an unspecified answer is exactly the average.
 * Those hold whatever the coefficients are, and they are what a future
 * correction of the table must not break.
 *
 * A few pin the *numbers*, and those are deliberately written as regression
 * pins rather than as claims of correctness: the coefficients in [Iso7029] were
 * reproduced from knowledge rather than transcribed from the paywalled
 * standard, and the object's KDoc says so at length. Pinning them means that
 * anybody who checks them against the real table and finds them wrong gets a
 * failing test to update, instead of silently changing what the app draws.
 */
class Iso7029Test {

    // ---- the model's shape ---------------------------------------------------

    @Test
    fun `the reference age has no age-related shift at all`() {
        val expected = Iso7029.expectedMedianHl(18, Iso7029Sex.MALE)
        assertTrue(
            "18 is the model's own zero, so every frequency must be exactly 0: $expected",
            expected.values.all { it == 0.0 },
        )
    }

    @Test
    fun `the shift grows with the square of the years since 18`() {
        val at38 = Iso7029.expectedMedianHl(38, Iso7029Sex.MALE).getValue(4000)
        val at58 = Iso7029.expectedMedianHl(58, Iso7029Sex.MALE).getValue(4000)
        // Twice the years, four times the shift. This is the whole model.
        assertEquals(4.0, at58 / at38, 1e-9)
    }

    @Test
    fun `age is clamped into the model's range at both ends`() {
        assertEquals(
            Iso7029.expectedMedianHl(Iso7029.MIN_AGE_YEARS, Iso7029Sex.MALE),
            Iso7029.expectedMedianHl(4, Iso7029Sex.MALE),
        )
        assertEquals(
            Iso7029.expectedMedianHl(Iso7029.MAX_AGE_YEARS, Iso7029Sex.MALE),
            Iso7029.expectedMedianHl(120, Iso7029Sex.MALE),
        )
        assertTrue(Iso7029.isAgeClamped(95))
        assertTrue(Iso7029.isAgeClamped(9))
        assertFalse(Iso7029.isAgeClamped(45))
    }

    @Test
    fun `the curve rises toward the top of the range, never the other way`() {
        val curve = Iso7029.expectedMedianHl(70, Iso7029Sex.MALE, Iso7029.FREQUENCIES_HZ)
        val values = Iso7029.FREQUENCIES_HZ.map { curve.getValue(it) }
        assertEquals(
            "presbyacusis is a high-frequency story; the table must not dip: $values",
            values.sorted(),
            values,
        )
    }

    // ---- the sexes -----------------------------------------------------------

    @Test
    fun `men are modelled as ageing faster at the top of the range`() {
        val male = Iso7029.expectedMedianHl(70, Iso7029Sex.MALE).getValue(4000)
        val female = Iso7029.expectedMedianHl(70, Iso7029Sex.FEMALE).getValue(4000)
        assertTrue("male $male should exceed female $female at 4 kHz", male > female)
    }

    @Test
    fun `unspecified is exactly the average of the two columns, not a third dataset`() {
        listOf(250, 1000, 4000, 8000).forEach { hz ->
            val male = Iso7029.expectedMedianHl(65, Iso7029Sex.MALE).getValue(hz)
            val female = Iso7029.expectedMedianHl(65, Iso7029Sex.FEMALE).getValue(hz)
            val unspecified = Iso7029.expectedMedianHl(65, Iso7029Sex.UNSPECIFIED).getValue(hz)
            assertEquals("at $hz Hz", (male + female) / 2.0, unspecified, 1e-9)
        }
    }

    // ---- off-table frequencies ----------------------------------------------

    @Test
    fun `a frequency the standard does not tabulate is interpolated between its neighbours`() {
        // 750 Hz is the one clinical frequency ISO 7029 skips.
        val at500 = Iso7029.alphaFor(500, Iso7029Sex.MALE)
        val at750 = Iso7029.alphaFor(750, Iso7029Sex.MALE)
        val at1000 = Iso7029.alphaFor(1000, Iso7029Sex.MALE)
        assertTrue("$at750 should sit between $at500 and $at1000", at750 in at500..at1000)
    }

    @Test
    fun `beyond the tabulated range the edge value is held rather than extrapolated`() {
        assertEquals(
            Iso7029.alphaFor(8000, Iso7029Sex.MALE),
            Iso7029.alphaFor(16000, Iso7029Sex.MALE),
            1e-12,
        )
        assertEquals(
            Iso7029.alphaFor(125, Iso7029Sex.MALE),
            Iso7029.alphaFor(60, Iso7029Sex.MALE),
            1e-12,
        )
    }

    // ---- regression pins on the reproduced table -----------------------------

    /**
     * The two numbers most often quoted from ISO 7029, pinned so a correction
     * of the reproduced table is a deliberate act with a failing test attached.
     * See the class KDoc: this asserts what the app currently draws, not what
     * the standard certainly says.
     */
    @Test
    fun `the reproduced table still produces the figures it was checked against`() {
        assertEquals(43.3, Iso7029.expectedMedianHl(70, Iso7029Sex.MALE).getValue(4000), 0.2)
        assertEquals(24.3, Iso7029.expectedMedianHl(70, Iso7029Sex.FEMALE).getValue(4000), 0.2)
        assertEquals(10.8, Iso7029.expectedMedianHl(70, Iso7029Sex.MALE).getValue(1000), 0.2)
    }

    // ---- the chart overlay ---------------------------------------------------

    @Test
    fun `a young listener's reference line is flat`() {
        val curve = Iso7029.deviationCurve(22, Iso7029Sex.UNSPECIFIED)
        assertTrue(
            "the model expects nothing measurable at 22: $curve",
            curve.all { kotlin.math.abs(it.second) < 1.0 },
        )
    }

    @Test
    fun `the overlay is centred on its own median, like every other curve on the chart`() {
        val curve = Iso7029.deviationCurve(70, Iso7029Sex.MALE)
        val median = ClinicalAudiogram.medianOf(curve.map { it.second })
        assertEquals("a deviation curve has zero median by construction", 0.0, median, 1e-9)
        // Positive is "more sensitive", so the low frequencies — where the model
        // predicts almost no loss — must sit above the line and the top below it.
        assertTrue(curve.first { it.first == 250 }.second > 0)
        assertTrue(curve.first { it.first == 8000 }.second < 0)
    }

    // ---- the plausibility check ----------------------------------------------

    private fun points(vararg pairs: Pair<Int, Double>, converged: Boolean = true) =
        pairs.map { (hz, db) -> ThresholdPoint(hz, db, converged = converged) }

    private fun flat(db: Double = -50.0) =
        TEST_FREQUENCIES_HZ.map { ThresholdPoint(it, db) }

    @Test
    fun `a flat curve says nothing about anybody's age`() {
        assertNull(Iso7029.gapAgainstAgeReference(flat(), 30, Iso7029Sex.UNSPECIFIED))
        assertNull(Iso7029.gapAgainstAgeReference(flat(), 70, Iso7029Sex.MALE))
    }

    @Test
    fun `a steep high-frequency roll-off at a young age is reported`() {
        val steep = points(
            250 to -60.0,
            500 to -60.0,
            1000 to -60.0,
            2000 to -58.0,
            3000 to -40.0,
            4000 to -35.0,
            6000 to -30.0,
            8000 to -28.0,
        )
        val gap = Iso7029.gapAgainstAgeReference(steep, 25, Iso7029Sex.UNSPECIFIED)
        assertNotNull("a 30 dB roll-off is not typical at 25", gap)
        assertTrue(gap!!.frequenciesHz.size >= Iso7029.MIN_GAP_FREQUENCIES)
        assertTrue("the top of the range is what carries it", gap.frequenciesHz.contains(8000))
    }

    @Test
    fun `the same roll-off is unremarkable at the age the model expects it`() {
        // Ages the model does account for should absorb part of the shape. The
        // point is not that the notice vanishes entirely at 75 - it is that the
        // gap must be strictly smaller than at 25, because the reference itself
        // has moved down at the top.
        val steep = points(
            250 to -60.0,
            500 to -60.0,
            1000 to -60.0,
            2000 to -58.0,
            3000 to -40.0,
            4000 to -35.0,
            6000 to -30.0,
            8000 to -28.0,
        )
        val young = Iso7029.gapAgainstAgeReference(steep, 25, Iso7029Sex.MALE)!!.largestGapDb
        val old = Iso7029.gapAgainstAgeReference(steep, 75, Iso7029Sex.MALE)?.largestGapDb ?: 0.0
        assertTrue("age must explain part of it: $young vs $old", old < young)
    }

    @Test
    fun `raised low tones alone can never raise an age notice`() {
        // The classic seal-leak signature: 250 and 500 far worse than the rest,
        // everything else flat. [LowToneArtifact] owns this case, and reporting
        // it as an age finding would blame the ears for a loose eartip.
        val leaking = points(
            250 to -25.0,
            500 to -28.0,
            1000 to -60.0,
            2000 to -60.0,
            3000 to -60.0,
            4000 to -60.0,
            6000 to -60.0,
            8000 to -60.0,
        )
        assertNull(Iso7029.gapAgainstAgeReference(leaking, 30, Iso7029Sex.UNSPECIFIED))
    }

    @Test
    fun `hollow points take no part in the comparison`() {
        val hollowTop = flat().dropLast(2) + points(
            6000 to -20.0,
            8000 to -18.0,
            converged = false,
        )
        assertNull(
            "points at the test's own ceiling are a fact about the test",
            Iso7029.gapAgainstAgeReference(hollowTop, 30, Iso7029Sex.UNSPECIFIED),
        )
    }

    @Test
    fun `one bad frequency is a point, not a pattern`() {
        val oneNotch = TEST_FREQUENCIES_HZ.map { hz ->
            ThresholdPoint(hz, if (hz == 4000) -20.0 else -55.0)
        }
        assertNull(Iso7029.gapAgainstAgeReference(oneNotch, 30, Iso7029Sex.UNSPECIFIED))
    }

    @Test
    fun `both ears are judged separately`() {
        val good = flat()
        val bad = points(
            250 to -60.0,
            500 to -60.0,
            1000 to -60.0,
            2000 to -58.0,
            3000 to -40.0,
            4000 to -33.0,
            6000 to -28.0,
            8000 to -26.0,
        )
        val audiogram = Audiogram(runIds = listOf("x"), left = bad, right = good)
        val gaps = Iso7029.gapsAgainstAgeReference(audiogram, 25, Iso7029Sex.UNSPECIFIED)
        assertEquals(1, gaps.size)
        assertEquals(Ear.LEFT, gaps.single().first)
    }

    // ---- the stored record ---------------------------------------------------

    @Test
    fun `a birth year keeps meaning the same thing as the years pass`() {
        val reference = AgeReference(birthYear = 1980, sex = Iso7029Sex.MALE)
        assertEquals(45, reference.ageAt(2025))
        assertEquals(55, reference.ageAt(2035))
    }

    @Test
    fun `an implausible birth year is refused rather than clamped`() {
        assertFalse(AgeReference.isPlausible(2099, currentYear = 2026))
        assertFalse(AgeReference.isPlausible(1800, currentYear = 2026))
        assertTrue(AgeReference.isPlausible(2026, currentYear = 2026))
        assertTrue(AgeReference.isPlausible(1950, currentYear = 2026))
    }

    @Test
    fun `an age past the model's range is reported as clamped`() {
        val ancient = AgeReference(birthYear = 1930)
        assertTrue(ancient.isClampedAt(2026))
        assertEquals(Iso7029.MAX_AGE_YEARS, ancient.ageAt(2026))
        assertFalse(AgeReference(birthYear = 1985).isClampedAt(2026))
    }
}
