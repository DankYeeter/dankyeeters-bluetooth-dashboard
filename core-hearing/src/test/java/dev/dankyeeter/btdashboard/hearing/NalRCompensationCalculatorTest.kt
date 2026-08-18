package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.hearing.fit.DeviceFormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full pipeline of COMPENSATION.md section 3.
 *
 * The reference audiogram used throughout is a typical gently sloping
 * high-frequency loss:
 *
 * ```
 * f    250  500  1k   2k   3k   4k   6k   8k
 * H_T   10   15   20   30   40   45   50   55
 * PTA = (15 + 20 + 30) / 3 = 21.6667      X = 3.25
 * IG     0    0  10.45 11.55 13.65 15.20 16.75 18.30
 * ```
 */
class NalRCompensationCalculatorTest {

    private val calculator = NalRCompensationCalculator()
    private val eps = 1e-4

    private val slopingThresholds = listOf(10.0, 15.0, 20.0, 30.0, 40.0, 45.0, 50.0, 55.0)
    private val expectedIg = listOf(0.0, 0.0, 10.45, 11.55, 13.65, 15.20, 16.75, 18.30)

    private fun points(values: List<Double>): List<ThresholdPoint> =
        TEST_FREQUENCIES_HZ.zip(values) { hz, v -> ThresholdPoint(hz, v) }

    private fun audiogram(
        left: List<Double> = slopingThresholds,
        right: List<Double> = slopingThresholds,
    ) = Audiogram(runIds = listOf("r1"), left = points(left), right = points(right))

    private fun flatAudiogram(value: Double) = audiogram(
        List(TEST_FREQUENCIES_HZ.size) { value },
        List(TEST_FREQUENCIES_HZ.size) { value },
    )

    // ---- worked NAL-R numbers -------------------------------------------------

    @Test
    fun `insertion gains match the worked example`() {
        val r = calculator.computeDetailed(audiogram(), CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        assertEquals(65.0 / 3.0, r.left.ptaDb, eps)
        expectedIg.forEachIndexed { i, expected ->
            assertEquals("IG(${TEST_FREQUENCIES_HZ[i]})", expected, r.left.insertionGainDb[i], eps)
        }
    }

    @Test
    fun `full band curve matches the hand-computed golden vector`() {
        // scaled (s = 0.6):  0, 0, 6.27, 6.93, 8.19, 9.12, 10.05, 10.98
        // mapped onto 31.5..16k with edge x0.5 outside 250..8000:
        //   0, 0, 0, 0, 0, 6.27, 6.93, 9.12, 10.98, 5.49
        // 3-point moving average, then the 6 dB/oct limiter (inert here):
        val expected = listOf(0.0, 0.0, 0.0, 0.0, 2.09, 4.40, 7.44, 9.01, 8.53, 8.235)

        val r = calculator.computeDetailed(audiogram(), CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        expected.forEachIndexed { i, e ->
            assertEquals("band ${EqBands.CENTER_FREQUENCIES_HZ[i]}", e, r.left.bandGainsDb[i], eps)
        }
        assertEquals(-9.01f, r.eq.preGainDb, 1e-3f)
    }

    // ---- intensity slider ----------------------------------------------------

    @Test
    fun `intensity scales the prescription linearly`() {
        val half = calculator.computeDetailed(audiogram(), CalibrationPresetRepository.GENERIC_ID, 0.3f, 1f)
        val full = calculator.computeDetailed(audiogram(), CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        half.left.bandGainsDb.forEachIndexed { i, v ->
            assertEquals(full.left.bandGainsDb[i] / 2.0, v, eps)
        }
    }

    @Test
    fun `intensity zero yields a flat curve and zero pre-gain`() {
        val r = calculator.compute(audiogram(), CalibrationPresetRepository.GENERIC_ID, 0f, 1f)
        assertTrue(r.leftGainsDb.all { it == 0f })
        assertTrue(r.rightGainsDb.all { it == 0f })
        assertEquals(0f, r.preGainDb, 1e-6f)
    }

    @Test
    fun `intensity and partial factor multiply`() {
        val a = calculator.computeDetailed(audiogram(), CalibrationPresetRepository.GENERIC_ID, 1f, 0.6f)
        val b = calculator.computeDetailed(audiogram(), CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        assertEquals(0.6, a.effectiveStrength, eps)
        assertEquals(b.left.bandGainsDb, a.left.bandGainsDb)
    }

    @Test
    fun `strength is clamped into 0 to 1`() {
        val over = calculator.computeDetailed(audiogram(), CalibrationPresetRepository.GENERIC_ID, 3f, 2f)
        val one = calculator.computeDetailed(audiogram(), CalibrationPresetRepository.GENERIC_ID, 1f, 1f)
        assertEquals(1.0, over.effectiveStrength, eps)
        assertEquals(one.left.bandGainsDb, over.left.bandGainsDb)

        val negative = calculator.computeDetailed(audiogram(), CalibrationPresetRepository.GENERIC_ID, -1f, 1f)
        assertEquals(0.0, negative.effectiveStrength, eps)
    }

    @Test
    fun `default intensity is the spec's 0_6`() {
        assertEquals(0.6f, DEFAULT_INTENSITY, 0f)
        assertEquals(1.0f, DEFAULT_PARTIAL_FACTOR, 0f)
    }

    // ---- safety clamps -------------------------------------------------------

    @Test
    fun `no band ever exceeds the 12 dB cap`() {
        // A severe flat loss at full strength would prescribe far more than 12.
        val r = calculator.computeDetailed(flatAudiogram(90.0), CalibrationPresetRepository.GENERIC_ID, 1f, 1f)
        assertTrue(r.left.insertionGainDb.any { it > NalRCompensationCalculator.MAX_BAND_GAIN_DB })
        listOf(r.left, r.right).forEach { ear ->
            ear.bandGainsDb.forEach {
                assertTrue("band $it above cap", it <= NalRCompensationCalculator.MAX_BAND_GAIN_DB + 1e-9)
            }
        }
        r.eq.leftGainsDb.forEach { assertTrue(it <= 12f) }
    }

    @Test
    fun `slope never exceeds 6 dB per octave`() {
        val brutal = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 80.0, 80.0, 80.0)
        val r = calculator.computeDetailed(audiogram(brutal, brutal), CalibrationPresetRepository.GENERIC_ID, 1f, 1f)
        val slope = NalRCompensationCalculator.maxSlopeDbPerOctave(r.left.bandGainsDb)
        assertTrue("slope $slope", slope <= NalRCompensationCalculator.MAX_SLOPE_DB_PER_OCTAVE + 1e-9)
    }

    @Test
    fun `slope limiter only lowers bands`() {
        val input = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 12.0, 12.0)
        val limited = NalRCompensationCalculator.limitSlope(input)
        limited.forEachIndexed { i, v -> assertTrue(v <= input[i] + 1e-9) }
        assertEquals(6.0, limited[8], eps)
        assertTrue(NalRCompensationCalculator.maxSlopeDbPerOctave(limited) <= 6.0 + 1e-9)
    }

    @Test
    fun `moving average is a true 3-point average with shortened ends`() {
        val out = NalRCompensationCalculator.movingAverage3(listOf(0.0, 3.0, 6.0, 9.0))
        assertEquals(listOf(1.5, 3.0, 6.0, 7.5), out)
    }

    @Test
    fun `moving average smooths an isolated spike`() {
        val spike = List(10) { if (it == 5) 12.0 else 0.0 }
        val out = NalRCompensationCalculator.movingAverage3(spike)
        assertEquals(4.0, out[5], eps)
        assertEquals(4.0, out[4], eps)
        assertEquals(4.0, out[6], eps)
    }

    // ---- band mapping --------------------------------------------------------

    @Test
    fun `bands outside 250 to 8000 Hz hold the edge value halved`() {
        val r = calculator.computeDetailed(flatAudiogram(50.0), CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        // Reconstruct the pre-smoothing mapping from the prescription itself.
        val scaled = r.left.insertionGainDb.map {
            (0.6 * it).coerceAtMost(NalRCompensationCalculator.MAX_BAND_GAIN_DB)
        }
        // On a flat loss every band is equal inside the range, so the halved
        // edges are directly visible even after smoothing.
        assertTrue(r.left.bandGainsDb[9] < r.left.bandGainsDb[8])
        assertTrue(r.left.bandGainsDb[0] < scaled.first() + 1e-9)
        assertTrue("extrapolated bands must stay below the measured ones",
            EqBands.EXTRAPOLATED_INDICES.all { i -> r.left.bandGainsDb[i] <= scaled.max() + 1e-9 })
    }

    @Test
    fun `1k 2k 4k and 8k bands coincide with the measured frequencies`() {
        // Those EQ centres are exactly test frequencies, so before smoothing the
        // mapping must be an identity, not an interpolation.
        val onlyAt2k = List(TEST_FREQUENCIES_HZ.size) { i -> if (TEST_FREQUENCIES_HZ[i] == 2000) 60.0 else 0.0 }
        val r = calculator.computeDetailed(audiogram(onlyAt2k, onlyAt2k), CalibrationPresetRepository.GENERIC_ID, 1f, 1f)
        // The 2 kHz band (index 6) must carry the most gain of all bands.
        val loudestBand = r.left.bandGainsDb.indices.maxByOrNull { r.left.bandGainsDb[it] } ?: -1
        assertEquals(6, loudestBand)
    }

    // ---- device correction ---------------------------------------------------

    @Test
    fun `preset correction shifts the thresholds by the negated response deviation`() {
        val preset = CalibrationPreset.fromResponseDeviation(
            id = "t",
            displayName = "t",
            dataSource = "test",
            measurementRig = "test",
            targetCurve = "test",
            formFactor = DeviceFormFactor.IN_EAR,
            responseDeviationDb = listOf(3.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        )
        val repo = object : CalibrationPresetRepository {
            override fun all() = listOf(preset)
            override fun byId(id: String) = preset.takeIf { it.id == id }
        }
        val r = NalRCompensationCalculator(repo)
            .computeDetailed(flatAudiogram(30.0), "t", 1f, 1f)
        // A headphone 3 dB loud at 250 Hz makes the measured threshold 3 dB too
        // low, so the corrected threshold must come out 3 dB higher.
        assertEquals(33.0, r.left.correctedThresholdsDb[0], eps)
        assertEquals(30.0, r.left.correctedThresholdsDb[1], eps)
    }

    @Test
    fun `generic preset applies no correction at all`() {
        val r = calculator.computeDetailed(audiogram(), CalibrationPresetRepository.GENERIC_ID, 1f, 1f)
        assertEquals(slopingThresholds, r.left.correctedThresholdsDb)
    }

    @Test
    fun `an unknown preset id falls back to generic instead of throwing`() {
        val r = calculator.computeDetailed(audiogram(), "does_not_exist", 0.6f, 1f)
        assertEquals(CalibrationPresetRepository.GENERIC_ID, r.preset.id)
        assertEquals(slopingThresholds, r.left.correctedThresholdsDb)
    }

    // ---- ears ----------------------------------------------------------------

    @Test
    fun `ears are computed fully independently`() {
        val left = slopingThresholds
        val right = List(TEST_FREQUENCIES_HZ.size) { 5.0 }
        val r = calculator.computeDetailed(audiogram(left, right), CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        assertNotEquals(r.left.bandGainsDb, r.right.bandGainsDb)
        val leftOnly = calculator.computeDetailed(audiogram(left, left), CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        assertEquals(leftOnly.left.bandGainsDb, r.left.bandGainsDb)
    }

    @Test
    fun `band difference reports left minus right`() {
        val right = List(TEST_FREQUENCIES_HZ.size) { 0.0 }
        val r = calculator.computeDetailed(audiogram(slopingThresholds, right), CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        r.bandDifferenceDb.forEachIndexed { i, d ->
            assertEquals(r.left.bandGainsDb[i] - r.right.bandGainsDb[i], d, eps)
        }
        assertTrue(r.bandDifferenceDb.any { it > 0.0 })
    }

    // ---- headroom ------------------------------------------------------------

    @Test
    fun `pre-gain is the negated maximum across both ears`() {
        val quiet = List(TEST_FREQUENCIES_HZ.size) { 0.0 }
        val loud = slopingThresholds
        val r = calculator.computeDetailed(audiogram(quiet, loud), CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        val peak = (r.left.bandGainsDb + r.right.bandGainsDb).max()
        assertEquals((-peak).toFloat(), r.eq.preGainDb, 1e-3f)
        assertTrue("right ear must drive the headroom", r.right.bandGainsDb.max() > r.left.bandGainsDb.max())
    }

    @Test
    fun `pre-gain is never positive`() {
        listOf(0f, 0.3f, 0.6f, 1f).forEach { s ->
            val eq = calculator.compute(flatAudiogram(0.0), CalibrationPresetRepository.GENERIC_ID, s, 1f)
            assertTrue(eq.preGainDb <= 0f)
        }
    }

    // ---- robustness ----------------------------------------------------------

    @Test
    fun `missing threshold points are interpolated instead of crashing`() {
        val partial = listOf(
            ThresholdPoint(250, 10.0),
            ThresholdPoint(1000, 20.0),
            ThresholdPoint(8000, 55.0),
        )
        val a = Audiogram(listOf("r1"), partial, partial)
        val r = calculator.computeDetailed(a, CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        assertEquals(10.0, r.left.correctedThresholdsDb[0], eps)
        assertEquals(20.0, r.left.correctedThresholdsDb[2], eps)
        assertTrue(r.left.correctedThresholdsDb[1] in 10.0..20.0)  // 500 Hz interpolated
    }

    @Test
    fun `an empty audiogram yields a flat curve`() {
        val a = Audiogram(emptyList(), emptyList(), emptyList())
        val eq = calculator.compute(a, CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        assertTrue(eq.leftGainsDb.all { it == 0f })
        assertTrue(eq.rightGainsDb.all { it == 0f })
    }

    @Test
    fun `result always has the expected shape and stays inside the EQ range`() {
        val r = calculator.compute(flatAudiogram(70.0), "noble_encore", 1f, 1f)
        assertEquals(EqBands.COUNT, r.leftGainsDb.size)
        assertEquals(EqBands.COUNT, r.rightGainsDb.size)
        (r.leftGainsDb + r.rightGainsDb).forEach {
            assertTrue(it in EqBands.MIN_GAIN_DB..EqBands.MAX_GAIN_DB)
        }
        assertEquals(r.leftGainsDb, r.gainsFor(Ear.LEFT))
    }

    @Test
    fun `severe loss raises the referral warning`() {
        assertTrue(
            calculator.computeDetailed(flatAudiogram(70.0), CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
                .severeLossWarning
        )
        assertTrue(
            !calculator.computeDetailed(audiogram(), CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
                .severeLossWarning
        )
    }

    @Test
    fun `compute leaves the master switch to the caller`() {
        val eq = calculator.compute(audiogram(), CalibrationPresetRepository.GENERIC_ID, 0.6f, 1f)
        assertTrue(!eq.enabled)
        assertTrue(eq.limiterEnabled)
    }
}
