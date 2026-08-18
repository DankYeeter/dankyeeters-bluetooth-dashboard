package dev.dankyeeter.btdashboard.hearing.fit

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitCheckTest {

    private fun probe(vararg pairs: Pair<Int, Double>) =
        pairs.map { ThresholdPoint(it.first, it.second) }

    @Test
    fun `the first probe becomes the baseline`() {
        val result = FitCheck.evaluate(probe(125 to -50.0, 250 to -55.0), baseline = null, ear = Ear.LEFT)
        val stored = result as FitCheckResult.BaselineStored
        assertEquals(mapOf(125 to -50.0, 250 to -55.0), stored.baseline.left)
        assertTrue(stored.baseline.right.isEmpty())
    }

    @Test
    fun `the other ear keeps its own baseline`() {
        val left = (FitCheck.evaluate(probe(125 to -50.0), null, Ear.LEFT) as FitCheckResult.BaselineStored).baseline
        val both = (FitCheck.evaluate(probe(125 to -46.0), left, Ear.RIGHT) as FitCheckResult.BaselineStored).baseline
        assertEquals(mapOf(125 to -50.0), both.left)
        assertEquals(mapOf(125 to -46.0), both.right)
    }

    @Test
    fun `a small deviation passes`() {
        val baseline = FitBaseline(left = mapOf(125 to -50.0, 250 to -55.0))
        val result = FitCheck.evaluate(probe(125 to -48.0, 250 to -56.0), baseline, Ear.LEFT)
        assertTrue(result is FitCheckResult.Good)
        assertEquals(0.5, (result as FitCheckResult.Good).deviationDb, 1e-9)
    }

    @Test
    fun `losing bass sensitivity warns about the seal`() {
        val baseline = FitBaseline(left = mapOf(125 to -50.0, 250 to -55.0))
        val result = FitCheck.evaluate(probe(125 to -38.0, 250 to -43.0), baseline, Ear.LEFT)
        val warning = result as FitCheckResult.Warning
        assertEquals(12.0, warning.deviationDb, 1e-9)
        assertTrue(warning.message.contains("seal"))
    }

    @Test
    fun `a much better than baseline result also warns`() {
        val baseline = FitBaseline(left = mapOf(125 to -50.0))
        val result = FitCheck.evaluate(probe(125 to -65.0), baseline, Ear.LEFT)
        assertTrue(result is FitCheckResult.Warning)
        assertEquals(-15.0, (result as FitCheckResult.Warning).deviationDb, 1e-9)
    }

    @Test
    fun `an empty probe is inconclusive`() {
        assertTrue(
            FitCheck.evaluate(emptyList(), FitBaseline(left = mapOf(125 to -50.0)), Ear.LEFT)
                is FitCheckResult.Inconclusive,
        )
    }

    @Test
    fun `a probe with no overlapping frequencies re-baselines instead of guessing`() {
        val baseline = FitBaseline(left = mapOf(125 to -50.0))
        val result = FitCheck.evaluate(probe(250 to -55.0), baseline, Ear.LEFT)
        assertTrue(result is FitCheckResult.BaselineStored)
    }

    @Test
    fun `the warning threshold is exactly inclusive`() {
        val baseline = FitBaseline(left = mapOf(125 to -50.0))
        val onEdge = FitCheck.evaluate(probe(125 to -50.0 + FitCheck.WARN_DEVIATION_DB), baseline, Ear.LEFT)
        assertTrue(onEdge is FitCheckResult.Warning)
    }

    @Test
    fun `the fit probe protocol stays below the audiogram range and skips catch trials`() {
        assertEquals(listOf(125, 250), FitCheck.FREQUENCIES_HZ)
        assertEquals(0.0, FitCheck.PROTOCOL.catchTrialProbability, 1e-9)
    }

    @Test
    fun `in-ear devices must run the fit check, over-ears may skip it`() {
        assertTrue(DeviceFormFactor.IN_EAR.fitCheckMandatory)
        assertTrue(!DeviceFormFactor.OVER_EAR.fitCheckMandatory)
    }
}
