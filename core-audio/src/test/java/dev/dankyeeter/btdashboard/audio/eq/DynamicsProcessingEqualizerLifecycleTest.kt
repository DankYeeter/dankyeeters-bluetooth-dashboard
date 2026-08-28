package dev.dankyeeter.btdashboard.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instance accounting for the `DynamicsProcessing` wrapper: **every native
 * effect this class creates must end up released, exactly once, on every path
 * that ends the object's life.**
 *
 * ## Why this file exists
 *
 * On 2026-08-28 a long-lived EQ chain starved the Bluetooth encoder at ~49
 * underflows/s while a freshly attached one was clean, which points at a chain
 * that is not one effect but several. :core-system's races are pinned in
 * `EqAttachmentLifecycleTest`; this file pins the two mechanisms that lived
 * inside the effect wrapper itself:
 *
 *  * `close()` began with `if (!alive) return`, while `alive` was set false by
 *    the exception guard and by a refused rebuild — neither of which released
 *    anything. A dying effect therefore stayed registered in AudioFlinger for
 *    the life of the process, unreachable.
 *  * the rebuild released the old effect inside a `runCatching` and created the
 *    replacement regardless, so a throwing release left two instances on one
 *    session with only one of them reachable.
 *
 * The framework is faked through the [DpEffect] seam. Nothing here needs a
 * device: what is under test is bookkeeping, and bookkeeping is exactly what
 * could not be seen on the device until the encoder complained.
 */
class DynamicsProcessingEqualizerLifecycleTest {

    // The counters are process-wide and monotone, so every assertion is a delta.
    private val createdAtStart = DynamicsProcessingEqualizer.createdInstanceCount
    private val releasedAtStart = DynamicsProcessingEqualizer.releasedInstanceCount

    private val enabled = EqSettings.FLAT.copy(enabled = true)

    // ---- instance accounting -------------------------------------------------

    @Test
    fun `a normal close releases everything it created`() {
        val rig = Rig()
        rig.eq.apply(enabled)
        rig.eq.close()

        assertEquals("one effect built", 1, created())
        assertBalanced()
        assertEquals(1, rig.effects.single().releases)
    }

    /**
     * The regression test for the strongest suspicion: a `guard()`-killed effect
     * must be released.
     *
     * A HAL that throws `UnsupportedOperationException` out of a band write is
     * the documented reason this guard exists. It marked the object dead and
     * returned — and the caller, seeing `isAlive == false`, dropped its
     * reference. The native effect went on running.
     */
    @Test
    fun `an effect killed by a throwing framework call is released`() {
        val rig = Rig()
        rig.effects.single().throwOnPreEqWrite = true

        rig.eq.apply(enabled)

        assertFalse("a throwing framework call must kill the object", rig.eq.isAlive)
        assertEquals(
            "the effect that died is still registered in AudioFlinger unless it was released",
            1,
            rig.effects.single().releases,
        )
        assertBalanced()
    }

    /**
     * A dead object's `close()` must still release. This is the call
     * `SessionAttachmentStrategy` makes when it prunes a dead effect, and the
     * `if (!alive) return` at the top of `close()` used to swallow it.
     */
    @Test
    fun `closing an already dead equaliser is harmless and still releases once`() {
        val rig = Rig()
        rig.effects.single().throwOnPreEqWrite = true
        rig.eq.apply(enabled)

        rig.eq.close()
        rig.eq.close()

        assertEquals("release must happen exactly once", 1, rig.effects.single().releases)
        assertBalanced()
    }

    @Test
    fun `closing twice releases once`() {
        val rig = Rig()
        rig.eq.apply(enabled)

        rig.eq.close()
        rig.eq.close()

        assertEquals(1, rig.effects.single().releases)
        assertEquals(1, created())
        assertBalanced()
    }

    /**
     * A rebuild the framework refuses: the old effect is already gone, so the
     * object dies — and it must die *released*, not merely dead.
     */
    @Test
    fun `a refused rebuild leaves nothing registered`() {
        val rig = Rig()
        rig.eq.apply(enabled)
        rig.refuseRebuild = true

        rig.eq.apply(enabled.withLayout(EqBandLayout.THIRD_OCTAVE_31))

        assertFalse(rig.eq.isAlive)
        assertEquals("no replacement was built", 1, rig.effects.size)
        assertEquals(1, rig.effects.single().releases)
        assertEquals(1, created())
        assertBalanced()

        // And the close the caller makes afterwards must not release twice.
        rig.eq.close()
        assertEquals(1, rig.effects.single().releases)
        assertBalanced()
    }

    /**
     * A `release()` that throws must not lose the reference.
     *
     * The old code did `runCatching { effect.release() }` and built the
     * replacement regardless: the old instance was still registered on the
     * session, and the only reference to it had just been overwritten. Now it
     * stays on the outstanding list and is retried at the next opportunity.
     */
    @Test
    fun `a release that throws is retried rather than dropped`() {
        val rig = Rig()
        rig.eq.apply(enabled)
        val first = rig.effects.single()
        first.throwOnRelease = true

        rig.eq.apply(enabled.withLayout(EqBandLayout.HALF_OCTAVE_20))

        assertTrue("the rebuild itself must succeed", rig.eq.isAlive)
        assertEquals(2, rig.effects.size)
        assertEquals("the throwing release was attempted", 1, first.releaseAttempts)
        assertEquals("and it did not take", 0, first.releases)
        assertEquals(
            "the live effect plus the one the framework refused to release",
            2,
            created() - released(),
        )

        // The framework recovers; the next apply is the "next opportunity".
        first.throwOnRelease = false
        rig.eq.apply(enabled.withLayout(EqBandLayout.HALF_OCTAVE_20))

        assertEquals("the retry released it", 1, first.releases)
        assertEquals("only the live effect is left", 1, created() - released())
        rig.eq.close()
        assertEquals(2, created())
        assertBalanced()
    }

    /** The same retry, reached through `close()` instead of a second `apply`. */
    @Test
    fun `close retries a release that threw during a rebuild`() {
        val rig = Rig()
        rig.eq.apply(enabled)
        val first = rig.effects.single()
        first.throwOnRelease = true
        rig.eq.apply(enabled.withLayout(EqBandLayout.HALF_OCTAVE_20))

        first.throwOnRelease = false
        rig.eq.close()

        assertEquals(1, first.releases)
        assertEquals(2, created())
        assertBalanced()
    }

    // ---- diff-aware apply ----------------------------------------------------

    /**
     * The write cost of one `apply` on the widest layout, stated as a number so
     * that a change to it is a decision rather than an accident: two channels x
     * 31 bands x (pre-EQ + compressor).
     */
    @Test
    fun `the first apply writes every band once`() {
        val rig = Rig(EqBandLayout.THIRD_OCTAVE_31)
        rig.eq.apply(enabled.withLayout(EqBandLayout.THIRD_OCTAVE_31))

        val effect = rig.effects.single()
        assertEquals(2 * 31, effect.preEqWrites.size)
        assertEquals(2 * 31, effect.mbcWrites.size)
    }

    /**
     * The volume tilt re-applies the same curve constantly (the service pushes
     * an update per distinct volume-derived curve). Identical settings must
     * cost no binder traffic at all.
     */
    @Test
    fun `re-applying identical settings writes no band at all`() {
        val rig = Rig(EqBandLayout.THIRD_OCTAVE_31)
        val settings = enabled.withLayout(EqBandLayout.THIRD_OCTAVE_31)
        rig.eq.apply(settings)
        val effect = rig.effects.single()
        effect.clearWrites()

        rig.eq.apply(settings)

        assertEquals("no pre-EQ band may be rewritten", 0, effect.preEqWrites.size)
        assertEquals("no compressor band may be rewritten", 0, effect.mbcWrites.size)
        assertEquals("and no input gain either", 0, effect.inputGainWrites)
        assertEquals("nor the limiter", 0, effect.limiterWrites)
    }

    @Test
    fun `changing one band writes exactly that band`() {
        val rig = Rig(EqBandLayout.THIRD_OCTAVE_31)
        val settings = enabled.withLayout(EqBandLayout.THIRD_OCTAVE_31)
        rig.eq.apply(settings)
        val effect = rig.effects.single()
        effect.clearWrites()

        // A cut, so the automatic headroom does not move and the input gain
        // stays out of the count.
        val moved = settings.copy(
            leftGainsDb = settings.leftGainsDb.toMutableList().also { it[7] = -6f },
        )
        rig.eq.apply(moved)

        assertEquals(1, effect.preEqWrites.size)
        val write = effect.preEqWrites.single()
        assertEquals(Ear.LEFT.channelIndex, write.channelIndex)
        assertEquals(7, write.bandIndex)
        assertEquals(-6f, write.band.gainDb, 0.001f)
        assertEquals("the compressor is untouched with restoration off", 0, effect.mbcWrites.size)
    }

    /**
     * A layout change is a new effect, so nothing may be suppressed: the
     * replacement holds none of the values the diff cache remembers, and a band
     * skipped here would be a band that never gets written at all.
     */
    @Test
    fun `a layout change rewrites every band of the new effect`() {
        val rig = Rig()
        rig.eq.apply(enabled)
        rig.effects.single().clearWrites()

        rig.eq.apply(enabled.withLayout(EqBandLayout.THIRD_OCTAVE_31))

        assertEquals(2, rig.effects.size)
        val rebuilt = rig.effects.last()
        assertEquals(2 * 31, rebuilt.preEqWrites.size)
        assertEquals(2 * 31, rebuilt.mbcWrites.size)
        assertEquals("the new effect needs its input gain", 1, rebuilt.inputGainWrites)
        assertEquals("and its limiter, once per channel", 2, rebuilt.limiterWrites)

        rig.eq.close()
        assertBalanced()
    }

    /** A single band gain must reach the effect even when nothing else changed. */
    @Test
    fun `setBandGain writes through and then diffs like everything else`() {
        val rig = Rig()
        rig.eq.apply(enabled)
        val effect = rig.effects.single()
        effect.clearWrites()

        rig.eq.setBandGain(Ear.RIGHT, 2, -4f)
        assertEquals(1, effect.preEqWrites.size)

        rig.eq.setBandGain(Ear.RIGHT, 2, -4f)
        assertEquals("the second write is the same value", 1, effect.preEqWrites.size)
    }

    // ---- enable verification -------------------------------------------------

    /**
     * The verification that catches an attached-but-inert effect must stop at
     * the first poll the framework accepts, not sit out the whole budget: it
     * runs inside `SessionAttachmentStrategy`'s lock, which serialises every
     * attach and every update in the app.
     */
    @Test
    fun `the enable verification stops as soon as the framework accepts`() {
        val rig = Rig()
        val effect = rig.effects.single()
        // As the device behaved on a freshly harvested foreign session: the
        // first setEnabled is silently dropped, the next one takes.
        effect.refusedEnables = 1

        rig.eq.apply(enabled)

        assertTrue("the effect must end up enabled", effect.enabled)
        assertEquals(
            "one refused write plus one accepted write; a fixed sleep would have " +
                "cost the full budget for the same two calls",
            2,
            effect.enableAttempts,
        )
    }

    // ---- helpers -------------------------------------------------------------

    private fun created() = DynamicsProcessingEqualizer.createdInstanceCount - createdAtStart

    private fun released() = DynamicsProcessingEqualizer.releasedInstanceCount - releasedAtStart

    private fun assertBalanced() = assertEquals(
        "every native effect this test created must have been released",
        created(),
        released(),
    )

    /** An equaliser wired to fakes, with a factory that records its rebuilds. */
    private class Rig(layout: EqBandLayout = EqBandLayout.OCTAVE_10) {
        val effects = mutableListOf<FakeDpEffect>()

        /** Stands in for AudioFlinger refusing the replacement configuration. */
        var refuseRebuild = false

        val eq: DynamicsProcessingEqualizer

        init {
            val first = FakeDpEffect().also { effects += it }
            eq = DynamicsProcessingEqualizer(SESSION, first, layout) { _, _ ->
                if (refuseRebuild) null else FakeDpEffect().also { effects += it }
            }
        }
    }

    private data class PreEqWrite(val channelIndex: Int, val bandIndex: Int, val band: PreEqBandSpec)

    private data class MbcWrite(val channelIndex: Int, val bandIndex: Int, val band: MbcBandSpec)

    /**
     * Everything the wrapper can do to the framework, recorded.
     *
     * The real object is a binder proxy into audioserver: its writes cost a
     * round trip each, its `release()` is what unregisters the effect, and its
     * `setEnabled` can be refused without saying so. All three are what the
     * tests above are about, so all three are configurable here.
     */
    private class FakeDpEffect : DpEffect {
        val preEqWrites = mutableListOf<PreEqWrite>()
        val mbcWrites = mutableListOf<MbcWrite>()
        var inputGainWrites = 0
            private set
        var limiterWrites = 0
            private set

        /** Successful releases, and attempts including the ones that threw. */
        var releases = 0
            private set
        var releaseAttempts = 0
            private set

        var throwOnPreEqWrite = false
        var throwOnRelease = false

        /** How many `setEnabled` writes the framework drops before accepting. */
        var refusedEnables = 0
        var enableAttempts = 0
            private set

        private var enabledValue = false

        override var enabled: Boolean
            get() = enabledValue
            set(value) {
                enableAttempts++
                if (refusedEnables > 0) {
                    refusedEnables--
                    return
                }
                enabledValue = value
            }

        override fun hasControl(): Boolean = true

        override fun setPreEqBand(channelIndex: Int, bandIndex: Int, band: PreEqBandSpec) {
            if (throwOnPreEqWrite) throw UnsupportedOperationException("HAL says no")
            preEqWrites += PreEqWrite(channelIndex, bandIndex, band)
        }

        override fun setMbcBand(channelIndex: Int, bandIndex: Int, band: MbcBandSpec) {
            mbcWrites += MbcWrite(channelIndex, bandIndex, band)
        }

        override fun setInputGainAllChannelsTo(db: Float) {
            inputGainWrites++
        }

        override fun setLimiterEnabled(channelIndex: Int, enabled: Boolean) {
            limiterWrites++
        }

        override fun preEqBandGain(channelIndex: Int, bandIndex: Int): Float =
            preEqWrites.lastOrNull { it.channelIndex == channelIndex && it.bandIndex == bandIndex }
                ?.band?.gainDb ?: 0f

        override fun release() {
            releaseAttempts++
            if (throwOnRelease) throw IllegalStateException("release refused")
            releases++
        }

        fun clearWrites() {
            preEqWrites.clear()
            mbcWrites.clear()
            inputGainWrites = 0
            limiterWrites = 0
        }
    }

    private companion object {
        /** A harvested Tidal session id, as the device printed it. */
        const val SESSION = 8009
    }
}
