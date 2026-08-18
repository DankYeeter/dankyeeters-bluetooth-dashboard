package dev.dankyeeter.btdashboard.hearing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.sqrt
import org.junit.Test

/** The pure NAL-R prescription, checked against COMPENSATION.md section 2. */
class NalRTest {

    private val eps = 1e-9

    @Test
    fun `C table is reproduced exactly at its own frequencies`() {
        val expected = mapOf(
            250 to -17.0,
            500 to -8.0,
            1000 to 1.0,
            2000 to -1.0,
            3000 to -2.0,
            4000 to -2.0,
            6000 to -2.0,
        )
        expected.forEach { (hz, c) ->
            assertEquals("C($hz)", c, NalR.correctionDb(hz.toDouble()), eps)
        }
    }

    @Test
    fun `C(8000) equals C(6000) per the spec's edge-hold rule`() {
        assertEquals(-2.0, NalR.correctionDb(8000.0), eps)
        assertEquals(NalR.correctionDb(6000.0), NalR.correctionDb(8000.0), eps)
    }

    @Test
    fun `C below the table holds the 250 Hz value`() {
        assertEquals(-17.0, NalR.correctionDb(125.0), eps)
        assertEquals(-17.0, NalR.correctionDb(31.5), eps)
    }

    @Test
    fun `C interpolates on log frequency, not linear frequency`() {
        // Geometric mean of 500 and 1000 must land exactly halfway between
        // -8 and +1. The arithmetic mean (750 Hz) must not.
        val geometric = sqrt(500.0 * 1000.0)
        assertEquals(-3.5, NalR.correctionDb(geometric), 1e-9)

        val atArithmeticMean = NalR.correctionDb(750.0)
        assertTrue("log interpolation must differ from linear", atArithmeticMean > -3.5)
    }

    @Test
    fun `PTA uses 500, 1k and 2k only`() {
        val thresholds = mapOf(250 to 100.0, 500 to 15.0, 1000 to 20.0, 2000 to 30.0, 4000 to 99.0)
        assertEquals(65.0 / 3.0, NalR.pureToneAverage { thresholds.getValue(it) }, eps)
    }

    @Test
    fun `insertion gain follows X plus 0_31 H plus C`() {
        val pta = 65.0 / 3.0                       // 21.6667
        // X = 0.15 * PTA = 3.25
        // 3.25 + 6.2 + 1, minus the zero-loss baseline max(0, C(1k)) = 1
        assertEquals(9.45, NalR.insertionGainDb(1000.0, 20.0, pta), 1e-9)
        assertEquals(11.55, NalR.insertionGainDb(2000.0, 30.0, pta), 1e-9)  // 3.25 + 9.3 - 1
        assertEquals(13.65, NalR.insertionGainDb(3000.0, 40.0, pta), 1e-9)  // 3.25 + 12.4 - 2
        assertEquals(15.20, NalR.insertionGainDb(4000.0, 45.0, pta), 1e-9)  // 3.25 + 13.95 - 2
        assertEquals(16.75, NalR.insertionGainDb(6000.0, 50.0, pta), 1e-9)  // 3.25 + 15.5 - 2
        assertEquals(18.30, NalR.insertionGainDb(8000.0, 55.0, pta), 1e-9)  // 3.25 + 17.05 - 2
    }

    @Test
    fun `insertion gain is clamped to zero, never negative`() {
        val pta = 65.0 / 3.0
        // 250 Hz: 3.25 + 3.1 - 17 = -10.65 -> 0
        assertEquals(0.0, NalR.insertionGainDb(250.0, 10.0, pta), eps)
        // 500 Hz: 3.25 + 4.65 - 8 = -0.1 -> 0
        assertEquals(0.0, NalR.insertionGainDb(500.0, 15.0, pta), eps)
    }

    @Test
    fun `normal hearing prescribes no gain anywhere`() {
        TEST_FREQUENCIES_HZ.forEach { hz ->
            assertEquals(0.0, NalR.insertionGainDb(hz.toDouble(), 0.0, 0.0), eps)
        }
    }

    @Test
    fun `log interpolation holds the edges instead of extrapolating`() {
        val xs = listOf(100.0, 1000.0)
        val ys = listOf(0.0, 10.0)
        assertEquals(0.0, logInterpolate(xs, ys, 10.0), eps)
        assertEquals(10.0, logInterpolate(xs, ys, 20000.0), eps)
        assertEquals(5.0, logInterpolate(xs, ys, sqrt(100.0 * 1000.0)), 1e-9)
    }
}
