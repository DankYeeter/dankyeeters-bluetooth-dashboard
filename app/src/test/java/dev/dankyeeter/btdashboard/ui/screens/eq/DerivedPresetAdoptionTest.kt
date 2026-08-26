package dev.dankyeeter.btdashboard.ui.screens.eq

import dev.dankyeeter.btdashboard.hearing.CalibrationPresetRepository
import dev.dankyeeter.btdashboard.hearing.ClinicalAudiogram
import dev.dankyeeter.btdashboard.hearing.CompensationSource
import dev.dankyeeter.btdashboard.hearing.DerivedCalibration
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a calibration derived for the connected headphone is allowed to become
 * the one in force.
 *
 * The rule is deliberately the same one hardware detection follows — adopt only
 * over the generic preset — and it is pinned here because both halves of it can
 * do real damage. Not adopting leaves a measured correction sitting unused
 * while the EQ computes with no calibration at all; adopting too eagerly
 * reinterprets a run through a different correction than the one it was
 * measured with, which is the failure this whole area exists to prevent.
 */
class DerivedPresetAdoptionTest {

    private val derived = DerivedCalibration(
        deviceKey = "abc123",
        deviceName = "Focal Bathys",
        responseDeviationDb = listOf(1.0, 0.5, 0.0, -1.0, -2.5, -1.0, 2.0, -3.0),
        earSpreadDb = 1.0,
        warnings = emptyList(),
        createdAtMillis = 0L,
        sourceRunIds = emptyList(),
    )

    private val derivedId = DerivedCalibration.presetIdFor("abc123")

    private fun state(
        presetId: String = CalibrationPresetRepository.GENERIC_ID,
        forDevice: DerivedCalibration? = derived,
        clinical: ClinicalAudiogram? = null,
        source: CompensationSource = CompensationSource.MEASURED,
    ) = CompensationUiState(
        presetId = presetId,
        derivedForDevice = forDevice,
        clinical = clinical,
        source = source,
    )

    @Test
    fun `it is adopted over the generic preset`() {
        assertEquals(derivedId, state().activePresetId)
    }

    @Test
    fun `nothing derived leaves the generic preset alone`() {
        assertEquals(
            CalibrationPresetRepository.GENERIC_ID,
            state(forDevice = null).activePresetId,
        )
    }

    /**
     * The one that must not regress. A run stamped `focal_bathys` was measured
     * through that correction; swapping in a derived one afterwards would
     * reinterpret the thresholds through a curve that was not in the path when
     * they were taken.
     */
    @Test
    fun `a preset the user or a run already chose always wins`() {
        assertEquals("focal_bathys", state(presetId = "focal_bathys").activePresetId)
        assertEquals("noble_encore", state(presetId = "noble_encore").activePresetId)
    }

    /**
     * The clinical path applies no device correction at all — an audiogram from
     * a practice never went through a headphone, so subtracting one would
     * corrupt a calibrated measurement. That holds for a derived preset exactly
     * as it holds for a bundled one.
     */
    @Test
    fun `the clinical source still computes with no calibration`() {
        val clinical = ClinicalAudiogram(leftDbHl = mapOf(1000 to 30.0))

        assertEquals(
            CalibrationPresetRepository.GENERIC_ID,
            state(clinical = clinical, source = CompensationSource.CLINICAL).activePresetId,
        )
    }

    /**
     * A stored choice of CLINICAL with no audiogram behind it falls back to the
     * measured curve, and the adoption comes back with it — otherwise a deleted
     * audiogram would silently strip the device correction from the EQ.
     */
    @Test
    fun `a clinical choice with no audiogram falls back to measured, adoption included`() {
        assertEquals(
            derivedId,
            state(clinical = null, source = CompensationSource.CLINICAL).activePresetId,
        )
    }

    /** The readout on screen names whatever is actually in force. */
    @Test
    fun `the named preset follows the active id`() {
        val preset = derived.toPreset()
        val named = state(forDevice = derived).copy(presets = listOf(preset))

        assertEquals(preset, named.preset)
        assertEquals("Measured — your Focal Bathys", named.preset?.displayName)
    }
}
