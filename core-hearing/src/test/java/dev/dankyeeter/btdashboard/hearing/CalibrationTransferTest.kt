package dev.dankyeeter.btdashboard.hearing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transfer's one load-bearing claim: subtracting the clinical audiogram
 * from the self-test isolates the headphone, and only the headphone. Every
 * test below feeds a synthetic "truth" through both measurements and checks
 * the device comes back out.
 */
class CalibrationTransferTest {

    /** A flat clinic sheet at [hl] over the full test range. */
    private fun clinic(hl: Double) = TEST_FREQUENCIES_HZ.associateWith { hl }

    /**
     * Self-test thresholds produced by hearing [clinicHl] through a device
     * whose response deviation is [deviceDb] (positive = louder), at an
     * arbitrary global offset standing in for the unknown volume/scale gap.
     */
    private fun selfTest(
        clinicHl: Map<Int, Double>,
        deviceDb: Map<Int, Double>,
        globalOffset: Double,
    ) = clinicHl.mapValues { (hz, hl) -> hl - (deviceDb[hz] ?: 0.0) + globalOffset }

    @Test
    fun `recovers the device response and discards the global offset`() {
        // A bass-heavy, treble-shy device, hidden behind a -55 dB scale gap.
        val device = mapOf(
            250 to 3.0, 500 to 1.5, 1000 to 0.0, 2000 to 0.0,
            3000 to -1.0, 4000 to -2.0, 6000 to -1.0, 8000 to -0.5,
        )
        val clinicL = clinic(10.0)
        val clinicR = clinic(10.0)

        val result = CalibrationTransfer.derive(
            clinicLeftHl = clinicL,
            clinicRightHl = clinicR,
            selfLeftDbfs = selfTest(clinicL, device, globalOffset = -55.0),
            selfRightDbfs = selfTest(clinicR, device, globalOffset = -55.0),
        )!!

        // The device curve has mean ~0 already, so it must come back as-is,
        // and the -55 dB offset must be nowhere in sight.
        TEST_FREQUENCIES_HZ.forEachIndexed { i, hz ->
            assertEquals("at $hz Hz", device.getValue(hz), result.responseDeviationDb[i], 0.26)
        }
        assertTrue(result.warnings.isEmpty())
        assertEquals(0.0, result.earSpreadDb, 1e-9)
    }

    /**
     * A sloped audiogram must NOT leak into the device estimate — the clinic
     * subtraction exists precisely so hearing loss is not mistaken for a dull
     * headphone.
     */
    @Test
    fun `hearing loss does not masquerade as device response`() {
        val slopedHearing = mapOf(
            250 to 10.0, 500 to 10.0, 1000 to 15.0, 2000 to 20.0,
            3000 to 30.0, 4000 to 40.0, 6000 to 45.0, 8000 to 50.0,
        )
        val flatDevice = TEST_FREQUENCIES_HZ.associateWith { 0.0 }

        val result = CalibrationTransfer.derive(
            clinicLeftHl = slopedHearing,
            clinicRightHl = slopedHearing,
            selfLeftDbfs = selfTest(slopedHearing, flatDevice, globalOffset = -40.0),
            selfRightDbfs = selfTest(slopedHearing, flatDevice, globalOffset = -40.0),
        )!!

        result.responseDeviationDb.forEach { assertEquals(0.0, it, 0.26) }
    }

    @Test
    fun `the two ears are averaged and their disagreement is reported`() {
        val device = TEST_FREQUENCIES_HZ.associateWith { 0.0 }
        val clinicSide = clinic(10.0)
        // The right run sat 4 dB worse at 8 kHz — a fit slip, not a device.
        val rightDevice = device + (8000 to -4.0)

        val result = CalibrationTransfer.derive(
            clinicLeftHl = clinicSide,
            clinicRightHl = clinicSide,
            selfLeftDbfs = selfTest(clinicSide, device, -50.0),
            selfRightDbfs = selfTest(clinicSide, rightDevice, -50.0),
        )!!

        // Averaged: half of the 4 dB slip survives at 8 kHz (mean-centering
        // spreads a fraction over the rest, hence the loose bound), and the
        // spread names the full disagreement.
        assertTrue(result.earSpreadDb >= 3.0)
    }

    @Test
    fun `too little overlap yields null rather than an invented curve`() {
        val clinicL = mapOf(1000 to 10.0, 2000 to 10.0)

        assertNull(
            CalibrationTransfer.derive(
                clinicLeftHl = clinicL,
                clinicRightHl = emptyMap(),
                selfLeftDbfs = mapOf(1000 to -45.0, 2000 to -44.0),
                selfRightDbfs = emptyMap(),
            ),
        )
    }

    @Test
    fun `a wild band comes back with a warning instead of being hidden`() {
        val device = TEST_FREQUENCIES_HZ.associateWith { 0.0 } + (250 to 18.0)
        val clinicSide = clinic(10.0)

        val result = CalibrationTransfer.derive(
            clinicLeftHl = clinicSide,
            clinicRightHl = clinicSide,
            selfLeftDbfs = selfTest(clinicSide, device, -50.0),
            selfRightDbfs = selfTest(clinicSide, device, -50.0),
        )!!

        assertTrue(result.warnings.any { it.contains("unusually large") })
    }

    /** Gaps in the overlap are filled between neighbours, never past them. */
    @Test
    fun `missing frequencies are interpolated with edge hold`() {
        // Clinic sheet without 3 kHz and without 8 kHz.
        val partialClinic = mapOf(
            250 to 10.0, 500 to 10.0, 1000 to 10.0, 2000 to 10.0,
            4000 to 10.0, 6000 to 10.0,
        )
        val device = mapOf(
            250 to 2.0, 500 to 2.0, 1000 to 0.0, 2000 to -2.0,
            4000 to -2.0, 6000 to 0.0,
        )

        val result = CalibrationTransfer.derive(
            clinicLeftHl = partialClinic,
            clinicRightHl = partialClinic,
            selfLeftDbfs = selfTest(partialClinic, device, -50.0),
            selfRightDbfs = selfTest(partialClinic, device, -50.0),
        )!!

        val at3k = result.responseDeviationDb[TEST_FREQUENCIES_HZ.indexOf(3000)]
        val at8k = result.responseDeviationDb[TEST_FREQUENCIES_HZ.indexOf(8000)]
        // 3 kHz sits between the -2.0 at 2 kHz and -2.0 at 4 kHz.
        assertEquals(-2.0, at3k, 0.26)
        // 8 kHz is past the last known point and holds 6 kHz's value.
        assertEquals(0.0, at8k, 0.26)
    }
}
