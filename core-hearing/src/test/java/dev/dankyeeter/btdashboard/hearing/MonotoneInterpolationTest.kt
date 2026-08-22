package dev.dankyeeter.btdashboard.hearing

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What monotone interpolation must and must not do to a measured curve.
 *
 * The "must not" is the important half. Every alternative that reconstructs a
 * notch more smoothly does it by overshooting, and an overshoot here is not a
 * cosmetic artefact: it is a hearing threshold the user never produced, fed
 * into a prescription that then boosts a band on the strength of it.
 */
class MonotoneInterpolationTest {

    private val testXs = TEST_FREQUENCIES_HZ.map { it.toDouble() }

    /** ISO third-octave-ish centres of the 20-band layout, where notches land between points. */
    private val bandCentres20 = listOf(
        25.0, 35.0, 50.0, 71.0, 100.0, 141.0, 200.0, 283.0, 400.0, 566.0,
        800.0, 1130.0, 1600.0, 2260.0, 3200.0, 4520.0, 6400.0, 9050.0, 12800.0, 18100.0,
    )

    /** A classic 4 kHz noise notch with partial recovery at 8 kHz. */
    private fun notch(f: Double): Double {
        val base = 5.0 + 4.0 * max(0.0, ln(f / 1000.0) / ln(2.0))
        val octavesFrom4k = ln(f / 4000.0) / ln(2.0)
        return base + 45.0 * exp(-(octavesFrom4k.pow(2)) / (2 * 0.55.pow(2)))
    }

    @Test
    fun `it reproduces the measured points exactly`() {
        val ys = testXs.map(::notch)
        testXs.forEachIndexed { i, x ->
            assertEquals("at $x Hz", ys[i], logInterpolateMonotone(testXs, ys, x), 1e-9)
        }
    }

    @Test
    fun `outside the measured range the edge value is held, never extrapolated`() {
        val ys = testXs.map(::notch)
        assertEquals(ys.first(), logInterpolateMonotone(testXs, ys, 20.0), 1e-9)
        assertEquals(ys.last(), logInterpolateMonotone(testXs, ys, 20_000.0), 1e-9)
    }

    @Test
    fun `it never overshoots the samples that bracket it`() {
        // The whole reason for choosing Fritsch-Carlson over a natural spline.
        // A deeper dip than was measured would be a threshold the user never
        // produced, and the prescription would act on it.
        val ys = testXs.map(::notch)
        for (i in 0 until testXs.size - 1) {
            val lo = min(ys[i], ys[i + 1])
            val hi = max(ys[i], ys[i + 1])
            // 40 samples across each segment, on the log axis the interpolant uses.
            for (step in 0..40) {
                val t = step / 40.0
                val x = exp(ln(testXs[i]) + t * (ln(testXs[i + 1]) - ln(testXs[i])))
                val y = logInterpolateMonotone(testXs, ys, x)
                assertTrue(
                    "overshoot at %.0f Hz: %.3f outside [%.3f, %.3f]".format(x, y, lo, hi),
                    y in (lo - 1e-9)..(hi + 1e-9),
                )
            }
        }
    }

    @Test
    fun `a monotone stretch stays monotone`() {
        // A steadily worsening high-frequency loss must not develop a bump.
        val ys = listOf(10.0, 12.0, 18.0, 30.0, 45.0, 55.0, 60.0, 62.0)
        var previous = logInterpolateMonotone(testXs, ys, testXs.first())
        var x = testXs.first()
        while (x <= testXs.last()) {
            val y = logInterpolateMonotone(testXs, ys, x)
            assertTrue("dip at $x Hz: $y < $previous", y >= previous - 1e-9)
            previous = y
            x *= 1.02
        }
    }

    @Test
    fun `fewer than three points fall back to the straight line`() {
        val xs = listOf(500.0, 2000.0)
        val ys = listOf(10.0, 30.0)
        // 1000 Hz sits at log-midpoint between 500 and 2000.
        assertEquals(20.0, logInterpolateMonotone(xs, ys, 1000.0), 1e-9)
    }

    @Test
    fun `it reconstructs a notch better than a straight line does`() {
        // The claim made in the KDoc and the reason this exists at all. Measured
        // at the audiometric frequencies, judged at the band centres that fall
        // between them.
        val ys = testXs.map(::notch)
        var worstLinear = 0.0
        var worstMonotone = 0.0
        bandCentres20.filter { it in testXs.first()..testXs.last() }.forEach { centre ->
            val truth = notch(centre)
            worstLinear = max(worstLinear, abs(logInterpolate(testXs, ys, centre) - truth))
            worstMonotone = max(worstMonotone, abs(logInterpolateMonotone(testXs, ys, centre) - truth))
        }
        assertTrue(
            "monotone ${"%.2f".format(worstMonotone)} dB should beat " +
                "linear ${"%.2f".format(worstLinear)} dB",
            worstMonotone < worstLinear,
        )
        // Pin the size of the win, so a regression that quietly halves it fails.
        assertTrue(
            "expected at least a 2x improvement, got " +
                "${"%.2f".format(worstLinear)} -> ${"%.2f".format(worstMonotone)}",
            worstMonotone * 2 < worstLinear,
        )
    }

    @Test
    fun `the ten octave bands are unaffected because they are measured points`() {
        // Why this change is safe to make without re-running the hearing test:
        // at the default layout there is nothing to interpolate.
        val ys = testXs.map(::notch)
        listOf(250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0).forEach { centre ->
            assertEquals(
                "at $centre Hz",
                logInterpolate(testXs, ys, centre),
                logInterpolateMonotone(testXs, ys, centre),
                1e-9,
            )
        }
    }
}
