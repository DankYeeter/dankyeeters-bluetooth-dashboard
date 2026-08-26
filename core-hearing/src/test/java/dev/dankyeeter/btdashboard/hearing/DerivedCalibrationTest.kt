package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.hearing.fit.DeviceFormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of the transfer's wiring that the math core cannot check for
 * itself: what gets fed into it, and what comes out the other end.
 *
 * [CalibrationTransferTest] proves the derivation. This proves that the numbers
 * handed to it are the right numbers — medians of the runs, and only of points
 * that actually converged — and that the preset built from the result keeps the
 * sign convention and the honesty flags it is supposed to.
 */
class DerivedCalibrationTest {

    private fun point(hz: Int, db: Double, converged: Boolean = true) =
        ThresholdPoint(frequencyHz = hz, thresholdDb = db, converged = converged)

    private fun run(id: String, left: List<ThresholdPoint>, right: List<ThresholdPoint> = left) =
        AudiogramRun(
            id = id,
            timestampMillis = 0L,
            deviceAddressHash = "device",
            calibrationPresetId = CalibrationPresetRepository.GENERIC_ID,
            ancMode = AncMode.UNKNOWN,
            ambientNoiseDbA = null,
            left = left,
            right = right,
        )

    // ---- the self-test side ---------------------------------------------------

    @Test
    fun `each frequency is the median across the runs`() {
        val runs = listOf(
            run("a", listOf(point(1000, -50.0), point(2000, -40.0))),
            run("b", listOf(point(1000, -44.0), point(2000, -41.0))),
            run("c", listOf(point(1000, -47.0), point(2000, -60.0))),
        )

        val medians = SelfTestThresholds.medianPerFrequency(runs, Ear.LEFT)

        assertEquals(-47.0, medians.getValue(1000), 1e-9)
        // The -60 outlier is outvoted rather than averaged in, which is the
        // whole reason three runs are asked for.
        assertEquals(-41.0, medians.getValue(2000), 1e-9)
    }

    /**
     * The one that matters. A point that did not converge sat on the level
     * floor — "quieter than the app can ask" — and letting it in would subtract
     * the test's own limit from a real clinical threshold, so the limit would
     * come out looking like a band the headphone plays quietly.
     */
    @Test
    fun `points that did not converge never reach the transfer`() {
        val runs = listOf(
            run("a", listOf(point(1000, -50.0), point(8000, -12.0, converged = false))),
            run("b", listOf(point(1000, -48.0), point(8000, -12.0, converged = false))),
        )

        val medians = SelfTestThresholds.medianPerFrequency(runs, Ear.LEFT)

        assertEquals(-49.0, medians.getValue(1000), 1e-9)
        // Absent, not zero: a frequency nobody could measure has no value, the
        // same sparse-map convention the clinical audiogram uses.
        assertFalse(medians.containsKey(8000))
    }

    @Test
    fun `the two ears are kept apart`() {
        val runs = listOf(
            run(
                "a",
                left = listOf(point(1000, -50.0)),
                right = listOf(point(1000, -30.0)),
            ),
        )

        assertEquals(-50.0, SelfTestThresholds.medianPerFrequency(runs, Ear.LEFT).getValue(1000), 1e-9)
        assertEquals(-30.0, SelfTestThresholds.medianPerFrequency(runs, Ear.RIGHT).getValue(1000), 1e-9)
    }

    @Test
    fun `no runs is an empty map rather than a crash`() {
        assertTrue(SelfTestThresholds.medianPerFrequency(emptyList(), Ear.LEFT).isEmpty())
    }

    // ---- the preset side ------------------------------------------------------

    private fun calibration(
        deviceKey: String = "abc123",
        deviceName: String? = "Focal Bathys",
        deviation: List<Double> = listOf(3.0, 1.5, 0.0, -1.0, -2.0, -1.0, 0.5, -2.5),
    ) = DerivedCalibration(
        deviceKey = deviceKey,
        deviceName = deviceName,
        responseDeviationDb = deviation,
        earSpreadDb = 2.5,
        warnings = emptyList(),
        createdAtMillis = 1_700_000_000_000L,
        sourceRunIds = listOf("a", "b", "c"),
    )

    /**
     * The sign flip belongs to the factory and must happen exactly once: a
     * derivation stored in response-deviation form and read as an offset would
     * boost every band the headphone is already loud in.
     */
    @Test
    fun `the preset negates the deviation exactly once`() {
        val preset = calibration().toPreset()

        assertEquals(TEST_FREQUENCIES_HZ.size, preset.offsetsDb.size)
        assertEquals(-3.0, preset.offsetsDb.first(), 1e-9)
        assertEquals(2.5, preset.offsetsDb.last(), 1e-9)
    }

    @Test
    fun `the id is stable and namespaced by device`() {
        assertEquals("derived_abc123", calibration().toPreset().id)
        assertEquals(
            calibration().toPreset().id,
            DerivedCalibration.presetIdFor("abc123"),
        )
        assertTrue(DerivedCalibration.isDerivedId("derived_abc123"))
        assertFalse(DerivedCalibration.isDerivedId(CalibrationPresetRepository.GENERIC_ID))
        assertFalse(DerivedCalibration.isDerivedId(null))
    }

    /**
     * `approximate` is the flag the UI prints as "APPROXIMATE", and it documents
     * itself as "eyeballed from published charts". These numbers are neither, so
     * it is false — and the caveat that does apply, that this is a device *plus
     * a person*, has to be in the strings instead.
     */
    @Test
    fun `it is honest about what kind of measurement this is`() {
        val preset = calibration().toPreset()

        assertFalse(preset.approximate)
        assertFalse(preset.provenanceLine().contains("APPROXIMATE"))
        assertTrue(preset.displayName.contains("Focal Bathys"))
        assertTrue(preset.notes.contains("not the model in general"))
        // No coupler was involved, so no coupler class is claimed.
        assertEquals(DeviceFormFactor.UNKNOWN, preset.formFactor)
    }

    /** A device with no recorded name must not produce "Derived for null". */
    @Test
    fun `a nameless device still reads as a sentence`() {
        val nameless = calibration(deviceName = null)

        assertNull(nameless.deviceName)
        assertEquals("Measured — your headphone", nameless.toPreset().displayName)
        assertEquals("this headphone", nameless.displayDeviceName)
        assertEquals("Focal Bathys", calibration().displayDeviceName)
    }
}
