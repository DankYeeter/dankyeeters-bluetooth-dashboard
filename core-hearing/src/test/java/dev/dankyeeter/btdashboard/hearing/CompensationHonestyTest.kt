package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.hearing.fit.DeviceFormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two read-outs that exist to keep the UI from over-claiming: the
 * possible-dead-region flag, and the peak-gain figure under the strength
 * slider.
 *
 * Both are worth pinning because both are quoted verbatim on screen. A flag
 * that fires one dB early turns "cannot check" into a false alarm, and a peak
 * figure that disagrees with the band list underneath it would make the whole
 * panel untrustworthy at a glance.
 */
class CompensationHonestyTest {

    private val calculator = NalRCompensationCalculator()

    private fun points(values: List<Double>) =
        TEST_FREQUENCIES_HZ.zip(values) { hz, v -> ThresholdPoint(hz, v) }

    private fun audiogram(left: List<Double>, right: List<Double> = left) =
        Audiogram(runIds = listOf("r1"), left = points(left), right = points(right))

    private fun flat(value: Double) = audiogram(List(TEST_FREQUENCIES_HZ.size) { value })

    private fun compute(
        audiogram: Audiogram,
        intensity: Float = 0.6f,
        presetId: String = CalibrationPresetRepository.GENERIC_ID,
        layout: EqBandLayout = AdjustedReference.LAYOUT,
        engine: NalRCompensationCalculator = calculator,
    ) = engine.computeDetailed(audiogram, presetId, intensity, 1f, layout)

    // ---- possible dead regions ----------------------------------------------

    @Test
    fun `an ordinary mild loss flags nothing`() {
        val sloping = listOf(10.0, 15.0, 20.0, 30.0, 40.0, 45.0, 50.0, 55.0)
        assertEquals(emptyList<Int>(), compute(audiogram(sloping)).possibleDeadRegionFrequenciesHz)
    }

    @Test
    fun `the flag is strictly above the published figure, not at it`() {
        // Vinay & Moore's number is "thresholds exceeding 70 dB HL". A
        // threshold of exactly 70 is not above 70, and rounding the boundary
        // the friendly way would be inventing a finding.
        assertEquals(
            emptyList<Int>(),
            compute(flat(NalRCompensationCalculator.DEAD_REGION_FLAG_DB)).possibleDeadRegionFrequenciesHz,
        )
        assertEquals(
            TEST_FREQUENCIES_HZ,
            compute(flat(NalRCompensationCalculator.DEAD_REGION_FLAG_DB + 0.5))
                .possibleDeadRegionFrequenciesHz,
        )
    }

    @Test
    fun `one bad frequency in one ear is reported, and only that frequency`() {
        val quiet = List(TEST_FREQUENCIES_HZ.size) { 20.0 }
        val loudAt6k = quiet.toMutableList().also { it[TEST_FREQUENCIES_HZ.indexOf(6000)] = 85.0 }

        val result = compute(audiogram(left = quiet, right = loudAt6k))
        assertEquals(emptyList<Int>(), result.left.possibleDeadRegionFrequenciesHz)
        assertEquals(listOf(6000), result.right.possibleDeadRegionFrequenciesHz)
        // The merged list drives the warning: which ear it came from does not
        // change what the app is unable to determine.
        assertEquals(listOf(6000), result.possibleDeadRegionFrequenciesHz)
    }

    @Test
    fun `both ears merge without duplicates and stay in frequency order`() {
        val left = List(TEST_FREQUENCIES_HZ.size) { i -> if (TEST_FREQUENCIES_HZ[i] >= 4000) 80.0 else 20.0 }
        val right = List(TEST_FREQUENCIES_HZ.size) { i -> if (TEST_FREQUENCIES_HZ[i] >= 3000) 80.0 else 20.0 }
        assertEquals(
            listOf(3000, 4000, 6000, 8000),
            compute(audiogram(left, right)).possibleDeadRegionFrequenciesHz,
        )
    }

    @Test
    fun `the flag reads the device-corrected threshold, not the raw one`() {
        // A headphone 4 dB quiet at 6 kHz makes the measured threshold 4 dB too
        // high; correcting it back down takes this ear under the line. Flagging
        // on the raw number would blame the ear for the headphone.
        val deviation = TEST_FREQUENCIES_HZ.map { if (it == 6000) -4.0 else 0.0 }
        val preset = CalibrationPreset.fromResponseDeviation(
            id = "quiet_at_6k",
            displayName = "test",
            dataSource = "test",
            measurementRig = "test",
            targetCurve = "test",
            formFactor = DeviceFormFactor.OVER_EAR,
            responseDeviationDb = deviation,
        )
        val repo = object : CalibrationPresetRepository {
            override fun all() = listOf(preset)
            override fun byId(id: String) = preset.takeIf { it.id == id }
        }
        val corrected = NalRCompensationCalculator(repo)

        val measured = List(TEST_FREQUENCIES_HZ.size) { i ->
            if (TEST_FREQUENCIES_HZ[i] == 6000) 72.0 else 20.0
        }
        assertEquals(
            listOf(6000),
            compute(audiogram(measured)).possibleDeadRegionFrequenciesHz,
        )
        assertEquals(
            emptyList<Int>(),
            compute(audiogram(measured), presetId = "quiet_at_6k", engine = corrected)
                .possibleDeadRegionFrequenciesHz,
        )
    }

    // ---- the peak read-out ---------------------------------------------------

    @Test
    fun `the peak read-out names the loudest band of the curve it is shown with`() {
        val sloping = listOf(10.0, 15.0, 20.0, 30.0, 40.0, 45.0, 50.0, 55.0)
        val result = compute(audiogram(sloping))
        val peak = requireNotNull(result.peakBand)

        val loudest = (result.left.bandGainsDb + result.right.bandGainsDb).max()
        assertEquals(loudest, peak.gainDb, 1e-9)
        assertEquals(
            AdjustedReference.LAYOUT.centersHz[result.left.bandGainsDb.indexOf(loudest)],
            peak.centerHz,
            0f,
        )
    }

    @Test
    fun `the peak follows the layout the curve was computed on`() {
        val sloping = listOf(10.0, 15.0, 20.0, 30.0, 40.0, 45.0, 50.0, 55.0)
        val coarse = requireNotNull(compute(audiogram(sloping), layout = EqBandLayout.OCTAVE_10).peakBand)
        val fine = requireNotNull(compute(audiogram(sloping), layout = AdjustedReference.LAYOUT).peakBand)
        assertEquals(4000f, coarse.centerHz, 0f)
        assertEquals(4520f, fine.centerHz, 0f)
    }

    @Test
    fun `the peak tracks the strength slider`() {
        val sloping = listOf(10.0, 15.0, 20.0, 30.0, 40.0, 45.0, 50.0, 55.0)
        val low = requireNotNull(compute(audiogram(sloping), intensity = 0.3f).peakBand)
        val high = requireNotNull(compute(audiogram(sloping), intensity = 0.6f).peakBand)
        assertEquals(high.gainDb / 2.0, low.gainDb, 1e-9)
    }

    @Test
    fun `nothing is claimed at strength zero`() {
        val sloping = listOf(10.0, 15.0, 20.0, 30.0, 40.0, 45.0, 50.0, 55.0)
        assertNull(compute(audiogram(sloping), intensity = 0f).peakBand)
    }

    @Test
    fun `nothing is claimed for an ear the prescription asks nothing of`() {
        assertNull(compute(flat(0.0), intensity = 1f).peakBand)
    }

    @Test
    fun `the louder ear sets the figure`() {
        val quiet = List(TEST_FREQUENCIES_HZ.size) { 0.0 }
        val sloping = listOf(10.0, 15.0, 20.0, 30.0, 40.0, 45.0, 50.0, 55.0)
        val result = compute(audiogram(left = quiet, right = sloping), intensity = 1f)
        val peak = requireNotNull(result.peakBand)
        assertTrue(result.right.bandGainsDb.max() > result.left.bandGainsDb.max())
        assertEquals(result.right.bandGainsDb.max(), peak.gainDb, 1e-9)
        // And it is the same number the headroom is derived from, so the two
        // lines on screen can never contradict each other.
        assertEquals(-peak.gainDb.toFloat(), result.eq.preGainDb, 1e-3f)
    }
}
