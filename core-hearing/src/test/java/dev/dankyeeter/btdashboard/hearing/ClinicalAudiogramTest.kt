package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clinical anchor: the model, the mapping into NAL-R's units, and the
 * low-tone artifact rule.
 *
 * The owner's own audiogram is the fixture throughout — flat 10 dB HL on both
 * sides with 15 dB at 125 and 250 Hz on the right — because it is the case the
 * feature exists for and the case where the *correct* answer is "no correction".
 * A test suite that only exercised hearing loss would never catch a pipeline
 * that quietly invents a curve for someone with normal hearing.
 */
class ClinicalAudiogramTest {

    private val eps = 1e-9

    private fun flat(value: Double): Map<Int, Double> =
        CLINICAL_FREQUENCIES_HZ.associateWith { value }

    /** The owner's ENT result. */
    private val owner = ClinicalAudiogram(
        leftDbHl = flat(10.0),
        rightDbHl = flat(10.0) + mapOf(125 to 15.0, 250 to 15.0),
        measuredOn = "2026-08",
        source = "ENT practice",
    )

    private val calculator = NalRCompensationCalculator()

    // ---- model ---------------------------------------------------------------

    @Test
    fun `an empty audiogram is empty and prescribes nothing`() {
        val empty = ClinicalAudiogram()
        assertTrue(empty.isEmpty)
        assertTrue(empty.prescribesNothing)
        assertNull(empty.medianDbHl())
        assertNull(empty.toAudiogram())
        // "no data" is not "normal hearing" — the clinic never vouched for
        // anything, so the normal-limits claim must not be made.
        assertFalse(empty.withinNormalLimits)
    }

    @Test
    fun `a missing frequency is absent, never zero`() {
        val sparse = ClinicalAudiogram(leftDbHl = mapOf(1000 to 30.0))
        assertNull(sparse.valuesFor(Ear.LEFT)[750])
        assertTrue(sparse.valuesFor(Ear.RIGHT).isEmpty())
    }

    @Test
    fun `the owners audiogram is inside normal limits`() {
        assertTrue(owner.withinNormalLimits)
        assertTrue(owner.prescribesNothing)
    }

    @Test
    fun `the normal-limits line sits at the clinical boundary, not past it`() {
        assertTrue(ClinicalAudiogram(leftDbHl = flat(ClinicalAudiogram.NORMAL_LIMIT_DB)).withinNormalLimits)
        assertFalse(
            ClinicalAudiogram(leftDbHl = flat(ClinicalAudiogram.NORMAL_LIMIT_DB + 5.0)).withinNormalLimits,
        )
    }

    @Test
    fun `one raised frequency takes the whole audiogram out of normal limits`() {
        val notch = ClinicalAudiogram(leftDbHl = flat(10.0) + mapOf(4000 to 45.0))
        assertFalse(notch.withinNormalLimits)
        assertFalse(notch.prescribesNothing)
    }

    // ---- chart overlay -------------------------------------------------------

    @Test
    fun `a flat clinical curve deviates from its own median by zero`() {
        val curve = ClinicalAudiogram(leftDbHl = flat(10.0)).deviationCurve(Ear.LEFT)
        assertEquals(TEST_FREQUENCIES_HZ, curve.map { it.first })
        curve.forEach { (hz, deviation) -> assertEquals("$hz Hz", 0.0, deviation, eps) }
    }

    @Test
    fun `worse in dB HL plots below the line, matching the charts direction`() {
        // The right ear is 5 dB worse at 250 Hz than the pair's median of 10.
        // The chart's positive direction is "more sensitive", so this has to
        // come out negative or the two curves would disagree about which way
        // is better.
        val at250 = owner.deviationCurve(Ear.RIGHT).first { it.first == 250 }.second
        assertEquals(-5.0, at250, eps)
        assertEquals(0.0, owner.deviationCurve(Ear.RIGHT).first { it.first == 1000 }.second, eps)
    }

    @Test
    fun `the overlay uses one median across both ears so asymmetry survives`() {
        // A per-ear median would subtract the right ear's own offset and make
        // the two curves look identical, which is the one thing the overlay
        // exists to disprove.
        val left = owner.deviationCurve(Ear.LEFT).toMap()
        val right = owner.deviationCurve(Ear.RIGHT).toMap()
        assertEquals(0.0, left.getValue(250), eps)
        assertEquals(-5.0, right.getValue(250), eps)
    }

    @Test
    fun `the overlay is resampled onto the charts frequencies and never extrapolated`() {
        // 3000 and 6000 Hz are not on this sparse form; they have to be
        // interpolated rather than dropped, and nothing may appear outside the
        // range of values that were actually recorded.
        val sparse = ClinicalAudiogram(
            leftDbHl = mapOf(500 to 10.0, 1000 to 20.0, 2000 to 30.0, 4000 to 40.0),
        )
        val curve = sparse.deviationCurve(Ear.LEFT).toMap()
        assertEquals(TEST_FREQUENCIES_HZ.toSet(), curve.keys)
        val median = sparse.medianDbHl()!!
        val bounds = listOf(median - 40.0, median - 10.0)
        curve.values.forEach { assertTrue(it in bounds.min()..bounds.max()) }
        // Below the lowest recorded point the edge value is held, not continued.
        assertEquals(curve.getValue(250), median - 10.0, eps)
    }

    @Test
    fun `an ear with no clinical values draws no overlay curve`() {
        assertTrue(ClinicalAudiogram(leftDbHl = flat(10.0)).deviationCurve(Ear.RIGHT).isEmpty())
    }

    // ---- the NAL-R unit mapping ---------------------------------------------

    @Test
    fun `a real loss goes into NAL-R as written on the form, unscaled`() {
        val loss = ClinicalAudiogram(
            leftDbHl = mapOf(
                250 to 10.0, 500 to 15.0, 1000 to 20.0, 2000 to 30.0,
                3000 to 40.0, 4000 to 45.0, 6000 to 50.0, 8000 to 55.0,
            ),
        )
        // Exactly the thresholds of NalRCompensationCalculatorTest's worked
        // example: same numbers in, so the same prescription must come out.
        assertEquals(
            listOf(10.0, 15.0, 20.0, 30.0, 40.0, 45.0, 50.0, 55.0),
            loss.prescriptionThresholdsDbHl(Ear.LEFT),
        )

        val audiogram = loss.toAudiogram()!!
        val result = calculator.computeDetailed(
            audiogram,
            CalibrationPresetRepository.GENERIC_ID,
            0.6f,
            1f,
        )
        assertEquals(65.0 / 3.0, result.left.ptaDb, 1e-4)
        listOf(0.0, 0.0, 9.45, 11.55, 13.65, 15.20, 16.75, 18.30)
            .forEachIndexed { i, expected ->
                assertEquals("IG(${TEST_FREQUENCIES_HZ[i]})", expected, result.left.insertionGainDb[i], 1e-4)
            }
    }

    @Test
    fun `a flat normal audiogram produces exactly no correction`() {
        // The whole point of the feature. NAL-R fed 10 dB HL literally would
        // still return a few dB, all of it the rule's own speech-spectrum
        // tilt; none of it says anything about these ears.
        val audiogram = owner.toAudiogram()!!
        val result = calculator.computeDetailed(
            audiogram,
            CalibrationPresetRepository.GENERIC_ID,
            1f,
            1f,
            AdjustedReference.LAYOUT,
        )
        result.left.insertionGainDb.forEach { assertEquals(0.0, it, eps) }
        result.right.insertionGainDb.forEach { assertEquals(0.0, it, eps) }
        result.eq.leftGainsDb.forEach { assertEquals(0f, it, 1e-6f) }
        result.eq.rightGainsDb.forEach { assertEquals(0f, it, 1e-6f) }
        assertEquals(0f, result.eq.preGainDb, 1e-6f)
        // And the read-out that drives the UI stays silent rather than
        // announcing "+0.0 dB at 1 kHz".
        assertNull(result.peakBand)
    }

    @Test
    fun `the gate is all-or-nothing, so a real loss is never scaled down`() {
        // 25 dB HL is outside normal limits by one audiometric step. The whole
        // 25 has to reach the rule — subtracting the 20 dB line from it would
        // halve the prescription for everyone with a genuine loss.
        val mild = ClinicalAudiogram(leftDbHl = flat(25.0), rightDbHl = flat(25.0))
        assertEquals(
            List(TEST_FREQUENCIES_HZ.size) { 25.0 },
            mild.prescriptionThresholdsDbHl(Ear.LEFT),
        )
    }

    @Test
    fun `one ear out of normal limits lifts the gate for both`() {
        // The gate answers "does this person have a loss", and an audiogram is
        // one document. A left ear zeroed while the right is prescribed for
        // would be an asymmetry the audiogram does not contain.
        val oneSided = ClinicalAudiogram(leftDbHl = flat(10.0), rightDbHl = flat(40.0))
        assertFalse(oneSided.prescribesNothing)
        assertEquals(List(TEST_FREQUENCIES_HZ.size) { 10.0 }, oneSided.prescriptionThresholdsDbHl(Ear.LEFT))
        assertEquals(List(TEST_FREQUENCIES_HZ.size) { 40.0 }, oneSided.prescriptionThresholdsDbHl(Ear.RIGHT))
    }

    @Test
    fun `an ear the clinic did not measure asks for nothing rather than guessing`() {
        val leftOnly = ClinicalAudiogram(leftDbHl = flat(40.0))
        assertEquals(
            List(TEST_FREQUENCIES_HZ.size) { 0.0 },
            leftOnly.prescriptionThresholdsDbHl(Ear.RIGHT),
        )
    }

    @Test
    fun `the clinical audiogram is marked as not coming from a run`() {
        assertEquals(listOf(ClinicalAudiogram.RUN_ID), owner.toAudiogram()!!.runIds)
        assertTrue(owner.toAudiogram()!!.left.all { it.converged })
    }

    // ---- low-tone artifact ---------------------------------------------------

    /** Internal dBFS levels: negative, and a larger number is a worse threshold. */
    private fun run(
        left: List<Double>,
        right: List<Double> = left,
        ambient: Double? = null,
    ) = AudiogramRun(
        id = "r1",
        timestampMillis = 0L,
        deviceAddressHash = null,
        calibrationPresetId = CalibrationPresetRepository.GENERIC_ID,
        ancMode = AncMode.UNKNOWN,
        ambientNoiseDbA = ambient,
        left = TEST_FREQUENCIES_HZ.zip(left) { hz, db -> ThresholdPoint(hz, db) },
        right = TEST_FREQUENCIES_HZ.zip(right) { hz, db -> ThresholdPoint(hz, db) },
    )

    private val evenRun = listOf(-70.0, -70.0, -70.0, -70.0, -70.0, -70.0, -70.0, -70.0)

    /** 250 and 500 Hz sit 12 dB worse than the mids — the leak signature. */
    private val raisedLows = listOf(-58.0, -58.0, -70.0, -70.0, -70.0, -70.0, -70.0, -70.0)

    @Test
    fun `an even run says nothing, whatever the clinic found`() {
        assertNull(LowToneArtifact.evaluate(run(evenRun), owner))
        assertNull(LowToneArtifact.evaluate(run(evenRun, ambient = 60.0), owner))
    }

    @Test
    fun `raised lows alone are not enough - the shape needs a reason to be doubted`() {
        assertNull(LowToneArtifact.evaluate(run(raisedLows), clinical = null))
        assertNull(LowToneArtifact.evaluate(run(raisedLows, ambient = 30.0), clinical = null))
    }

    @Test
    fun `raised lows against a flat-normal clinic is the owners case`() {
        val advice = LowToneArtifact.evaluate(run(raisedLows), owner)
        assertNotNull(advice)
        assertTrue(advice!!.clinicalContradicts)
        assertFalse(advice.roomWasNoisy)
    }

    @Test
    fun `a noisy room is reason enough on its own`() {
        val advice = LowToneArtifact.evaluate(run(raisedLows, ambient = LowToneArtifact.NOISY_ROOM_DB), null)
        assertNotNull(advice)
        assertFalse(advice!!.clinicalContradicts)
        assertTrue(advice.roomWasNoisy)
    }

    @Test
    fun `a clinical low-frequency dip is respected rather than waved away`() {
        // Inside normal limits at 20 dB HL, but 10 dB above the audiogram's own
        // median: a real shape, and the app must not tell this person their
        // measured lows are a bad seal.
        val dip = ClinicalAudiogram(
            leftDbHl = flat(5.0) + mapOf(250 to 20.0, 500 to 20.0),
            rightDbHl = flat(5.0) + mapOf(250 to 20.0, 500 to 20.0),
        )
        assertFalse(dip.isNormalAndFlatAt(LowToneArtifact.LOW_FREQUENCIES_HZ))
        assertNull(LowToneArtifact.evaluate(run(raisedLows), dip))
    }

    @Test
    fun `a frequency the clinic never measured cannot vouch for itself`() {
        val noLows = ClinicalAudiogram(leftDbHl = mapOf(1000 to 10.0, 2000 to 10.0, 4000 to 10.0))
        assertFalse(noLows.isNormalAndFlatAt(LowToneArtifact.LOW_FREQUENCIES_HZ))
        assertNull(LowToneArtifact.evaluate(run(raisedLows), noLows))
    }

    @Test
    fun `the threshold is two audiometric steps, and it is not crossed at one`() {
        val nineDb = listOf(-61.0, -61.0, -70.0, -70.0, -70.0, -70.0, -70.0, -70.0)
        assertFalse(LowToneArtifact.lowTonesAreRaised(run(nineDb).left))
        val tenDb = listOf(-60.0, -60.0, -70.0, -70.0, -70.0, -70.0, -70.0, -70.0)
        assertTrue(LowToneArtifact.lowTonesAreRaised(run(tenDb).left))
    }

    @Test
    fun `one ear is enough - a loose tip is usually one-sided`() {
        assertNotNull(LowToneArtifact.evaluate(run(left = raisedLows, right = evenRun), owner))
    }

    @Test
    fun `an ordinary high-frequency loss does not drag the reference and fire`() {
        // 6 and 8 kHz raised by 20 dB is the commonest real finding there is.
        // If they were in the mid reference the lows would look raised by
        // comparison and the advisory would fire on a true result.
        val slope = listOf(-70.0, -70.0, -70.0, -70.0, -70.0, -70.0, -50.0, -50.0)
        assertFalse(LowToneArtifact.lowTonesAreRaised(run(slope).left))
    }

    @Test
    fun `hollow points are ignored on both sides of the comparison`() {
        // A 250 Hz point pinned at the ceiling says "louder than the app can
        // ask", which is a fact about the test. Counting it would let the
        // test's own limit trigger a warning about the room.
        val points = TEST_FREQUENCIES_HZ.map { hz ->
            ThresholdPoint(hz, if (hz == 250) -6.0 else -70.0, converged = hz != 250)
        }
        assertFalse(LowToneArtifact.lowTonesAreRaised(points))
    }

    @Test
    fun `a run with no usable mids cannot be judged`() {
        val onlyLows = listOf(-58.0, -58.0).let { lows ->
            listOf(250, 500).zip(lows) { hz, db -> ThresholdPoint(hz, db) }
        }
        assertFalse(LowToneArtifact.lowTonesAreRaised(onlyLows))
    }
}
