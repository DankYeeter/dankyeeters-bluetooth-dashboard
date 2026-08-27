package dev.dankyeeter.btdashboard.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one property the whole mode stands on: the compressor takes exactly the
 * boost back by the time the signal reaches full scale. If this drifts, the
 * mode either clips (took back too little) or ducks loud music (too much).
 */
class LoudnessRestorationMathTest {

    /** Net gain at input level x: postGain minus what the ratio removed above threshold. */
    private fun netGainAt(inputDb: Float, boostDb: Float): Float {
        val t = LoudnessRestorationMath.THRESHOLD_DB
        val r = LoudnessRestorationMath.ratioFor(boostDb)
        if (inputDb <= t) return boostDb
        return boostDb - (inputDb - t) * (1f - 1f / r)
    }

    @Test
    fun `quiet signal gets the full boost`() {
        assertEquals(9f, netGainAt(-60f, 9f), 1e-4f)
    }

    @Test
    fun `full scale gets no boost at all`() {
        for (boost in listOf(1f, 5f, 10f, 15f)) {
            assertEquals("boost $boost", 0f, netGainAt(0f, boost), 1e-4f)
        }
    }

    @Test
    fun `the boost only ever shrinks as the level rises`() {
        var previous = Float.MAX_VALUE
        var level = -80f
        while (level <= 0f) {
            val net = netGainAt(level, 12f)
            assertTrue("net gain rose from $previous to $net at $level dB", net <= previous + 1e-4f)
            previous = net
            level += 1f
        }
    }

    @Test
    fun `zero boost is a unity ratio, not a division by zero`() {
        assertEquals(1f, LoudnessRestorationMath.ratioFor(0f), 1e-6f)
    }

    /**
     * The clamp exists for inputs the sliders cannot produce today; if the
     * gain range ever grows past the threshold depth, the ratio must stay
     * finite rather than inverting the curve.
     */
    @Test
    fun `an absurd boost is clamped, not exploded`() {
        val ratio = LoudnessRestorationMath.ratioFor(60f)
        assertTrue(ratio.isFinite() && ratio > 0f)
    }

    // ---- the property across the whole band range, not three hand-picked boosts ----

    /**
     * Every boost a slider can produce, in the 0.25 dB steps a drag makes.
     *
     * The three-value list above was chosen for readability, and a value chosen
     * for readability is a value that cannot fail: the interesting boosts are the
     * ones nobody would write down — 0.25, 14.75, and everything between.
     */
    private fun bandRangeBoosts(): List<Float> =
        (0..(EqBands.MAX_GAIN_DB * 4).toInt()).map { it / 4f }

    @Test
    fun `net gain is zero at full scale for every boost the sliders can reach`() {
        bandRangeBoosts().forEach { boost ->
            assertEquals("boost $boost", 0f, netGainAt(0f, boost), 1e-3f)
        }
    }

    @Test
    fun `net gain is the full boost below the threshold for every boost`() {
        bandRangeBoosts().forEach { boost ->
            assertEquals("boost $boost", boost, netGainAt(-60f, boost), 1e-4f)
        }
    }

    /**
     * A larger boost has to be taken back harder, so the ratio may never dip as
     * the boost grows. A non-monotone ratio would mean two boosts crossing over
     * somewhere in the middle of the range — the band asked to lift more ending
     * up compressed less — which no listening test would ever localise.
     */
    @Test
    fun `the ratio never decreases as the boost grows`() {
        var previous = 0f
        var boost = 0f
        while (boost <= -LoudnessRestorationMath.THRESHOLD_DB + 5f) {
            val ratio = LoudnessRestorationMath.ratioFor(boost)
            assertTrue("ratio fell from $previous to $ratio at boost $boost", ratio >= previous)
            previous = ratio
            boost += 0.25f
        }
    }

    /**
     * A boost small enough to be a rounding error must still be a compressor,
     * not a bypass: the ratio leaves 1 as soon as the boost does, or the first
     * quarter-decibel of a drag would be static gain that outlives full scale.
     */
    @Test
    fun `a tiny boost is a tiny ratio rather than unity`() {
        val range = -LoudnessRestorationMath.THRESHOLD_DB
        listOf(0.05f, 0.25f, 1f).forEach { boost ->
            val ratio = LoudnessRestorationMath.ratioFor(boost)
            assertTrue("boost $boost gave ratio $ratio", ratio > 1f)
            // range / (range - boost): still within a hair of unity, and exact.
            assertEquals("boost $boost", range / (range - boost), ratio, 1e-4f)
        }
    }

    /**
     * The clamp's own edge, stated as the number it produces.
     *
     * [LoudnessRestorationMath.ratioFor] clamps the boost to one decibel short of
     * the threshold depth, so the largest ratio it can ever return is
     * `range / 1` — 35 today. Anything at or past that edge is the same finite
     * ratio, which is what keeps a future wider gain range from inverting the
     * curve instead of merely saturating it.
     */
    @Test
    fun `at and past the range limit the ratio saturates at a finite value`() {
        val range = -LoudnessRestorationMath.THRESHOLD_DB
        val atLimit = LoudnessRestorationMath.ratioFor(range - 1f)

        assertEquals(range, atLimit, 1e-3f)
        listOf(range, range + 1f, 100f, 1_000f).forEach { boost ->
            val ratio = LoudnessRestorationMath.ratioFor(boost)
            assertEquals("boost $boost", atLimit, ratio, 1e-3f)
            assertTrue("boost $boost gave $ratio", ratio.isFinite() && ratio > 0f)
        }
    }

    /**
     * A negative boost is a cut, and cuts do not live in this mode at all — the
     * static pre-EQ keeps them. Asked about one anyway, the ratio must be unity
     * rather than a value below 1, which the effect would read as *expansion*.
     */
    @Test
    fun `a cut is not turned into an expander`() {
        listOf(-0.25f, -6f, -EqBands.MAX_GAIN_DB).forEach { boost ->
            assertEquals("boost $boost", 1f, LoudnessRestorationMath.ratioFor(boost), 1e-6f)
        }
    }

    @Test
    fun `restoration mode frees the automatic headroom`() {
        val boosted = EqSettings(
            leftGainsDb = List(EqBandLayout.DEFAULT.bandCount) { 12f },
            rightGainsDb = List(EqBandLayout.DEFAULT.bandCount) { 12f },
            preGainDb = 0f,
        )

        // Static: the boost must buy headroom. Dynamic: it must not — the
        // compressor guarantees net zero at full scale, so there is nothing
        // left to protect against.
        assertEquals(-12f, boosted.sanitized().preGainDb, 1e-4f)
        assertEquals(0f, boosted.copy(loudnessRestoration = true).sanitized().preGainDb, 1e-4f)
    }
}
