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
