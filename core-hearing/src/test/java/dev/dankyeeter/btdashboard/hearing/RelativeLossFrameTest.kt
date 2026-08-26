package dev.dankyeeter.btdashboard.hearing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the dBFS-to-loss rebase that stands between the measured audiogram
 * and NAL-R. The bug it pins down: raw negative dBFS thresholds made every
 * term of the formula negative, the >= 0 clamp flattened it, and the generated
 * curve prescribed nothing for every realistic measurement.
 */
class RelativeLossFrameTest {

    private fun point(hz: Int, db: Double, converged: Boolean = true) =
        ThresholdPoint(frequencyHz = hz, thresholdDb = db, converged = converged)

    @Test
    fun `a realistic dBFS audiogram now yields a real prescription`() {
        // A gentle high-frequency slope, the way the engine actually stores it.
        val measured = Audiogram(
            runIds = listOf("r"),
            left = TEST_FREQUENCIES_HZ.mapIndexed { i, hz -> point(hz, -70.0 + i * 2) },
            right = TEST_FREQUENCIES_HZ.mapIndexed { i, hz -> point(hz, -70.0 + i * 2) },
        )

        val result = NalRCompensationCalculator().computeDetailed(
            audiogram = measured.asRelativeLossHl(),
            calibrationPresetId = CalibrationPresetRepository.GENERIC_ID,
            intensity = 1f,
            partialFactor = DEFAULT_PARTIAL_FACTOR,
        )

        // Before the rebase this was all zeros with peakBand null.
        assertTrue(result.eq.leftGainsDb.any { it > 0f })
    }

    @Test
    fun `the loss frame is positive and anchored at the best converged point`() {
        val rebased = Audiogram(
            runIds = listOf("r"),
            left = listOf(point(1000, -70.0), point(2000, -60.0)),
            right = listOf(point(1000, -66.0), point(2000, -58.0)),
        ).asRelativeLossHl()

        assertEquals(0.0, rebased.left.first { it.frequencyHz == 1000 }.thresholdDb, 1e-9)
        assertEquals(10.0, rebased.left.first { it.frequencyHz == 2000 }.thresholdDb, 1e-9)
        // The worse right ear keeps its asymmetry against the global best.
        assertEquals(4.0, rebased.right.first { it.frequencyHz == 1000 }.thresholdDb, 1e-9)
    }

    @Test
    fun `flat measured hearing prescribes nothing, like a flat clinic sheet`() {
        val flat = Audiogram(
            runIds = listOf("r"),
            left = TEST_FREQUENCIES_HZ.map { point(it, -72.0) },
            right = TEST_FREQUENCIES_HZ.map { point(it, -72.0) },
        )

        val result = NalRCompensationCalculator().computeDetailed(
            audiogram = flat.asRelativeLossHl(),
            calibrationPresetId = CalibrationPresetRepository.GENERIC_ID,
            intensity = 1f,
            partialFactor = DEFAULT_PARTIAL_FACTOR,
        )

        assertTrue(result.eq.leftGainsDb.all { it == 0f })
    }

    @Test
    fun `an unconverged floor point cannot invent loss`() {
        val rebased = Audiogram(
            runIds = listOf("r"),
            left = listOf(point(1000, -72.0), point(250, -72.0, converged = false)),
            right = emptyList(),
        ).asRelativeLossHl()

        assertEquals(0.0, rebased.left.first { it.frequencyHz == 250 }.thresholdDb, 1e-9)
    }
}
