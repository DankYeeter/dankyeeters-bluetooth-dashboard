package dev.dankyeeter.btdashboard.audio.eq

import android.media.audiofx.DynamicsProcessing
import android.util.Log

/**
 * [SystemEqualizer] backed by Android's `DynamicsProcessing` effect.
 *
 * Layout: 2 channels x N pre-EQ bands (N from [EqBandLayout]), no multiband
 * compressor, no post-EQ, plus the built-in per-channel limiter as the final
 * stage.
 *
 * `DynamicsProcessing` fixes its band count at construction, so a layout change
 * is not a parameter update — the effect is released and rebuilt on the same
 * session. That is why [apply] tracks which layout the live effect was built
 * for and recreates it when the incoming settings disagree.
 *
 * Every call into the framework is wrapped defensively: OEM audio HALs are
 * inconsistent about `DynamicsProcessing` support and throw
 * `UnsupportedOperationException`/`IllegalStateException` rather than returning
 * errors. A dead effect flips [isAlive] to false; callers re-attach.
 */
class DynamicsProcessingEqualizer private constructor(
    override val sessionId: Int,
    private var effect: DynamicsProcessing,
    private var layout: EqBandLayout,
) : SystemEqualizer {

    private var alive = true
    override val isAlive: Boolean get() = alive

    override fun apply(settings: EqSettings) = guard {
        val clean = settings.sanitized()
        if (clean.layout != layout) rebuildFor(clean.layout)
        Ear.entries.forEach { ear ->
            val gains = clean.gainsFor(ear)
            for (band in 0 until layout.bandCount) {
                writeBand(ear, band, gains[band])
            }
        }
        effect.setInputGainAllChannelsTo(clean.preGainDb)
        Ear.entries.forEach { ear ->
            val limiter = effect.getLimiterByChannelIndex(ear.channelIndex)
            limiter.isEnabled = clean.limiterEnabled
            effect.setLimiterByChannelIndex(ear.channelIndex, limiter)
        }
        setEnabledVerified(clean.enabled)
    }

    /**
     * Switches the effect on and checks that it actually went on.
     *
     * `effect.enabled = x` looks like a property assignment, but underneath it
     * is `AudioEffect.setEnabled`, which **returns a status code instead of
     * throwing**. Kotlin discards that value, so a refusal is completely
     * silent: the effect stays attached, the app reports success, and nothing
     * is equalised.
     *
     * That is not hypothetical. Attaching to a freshly harvested foreign
     * session on the device produced exactly this - `Registered=y, Enabled=n` in
     * AudioFlinger - and the EQ only came alive when a later playback event
     * happened to re-attach it. The framework needs a moment after an effect is
     * created on someone else's session before it will accept the enable.
     *
     * So: assign, read back, and try once more if the framework disagreed. The
     * read-back is the point - it is the only way to tell "on" from "asked to be
     * on".
     */
    private fun setEnabledVerified(target: Boolean) {
        effect.enabled = target
        // hasControl matters more than the return value here: another effect
        // holding control makes every setEnabled a no-op that still reports
        // success locally, which is exactly the "attached but Enabled=n" state
        // AudioFlinger showed on a harvested session.
        Log.i(TAG, "session $sessionId enable=$target control=${effect.hasControl()} readback=${effect.enabled}")
        if (effect.enabled == target) return

        Thread.sleep(ENABLE_RETRY_DELAY_MS)
        effect.enabled = target
        if (effect.enabled != target) {
            Log.w(TAG, "session $sessionId refused enabled=$target; the EQ is attached but inert")
        }
    }

    override val activeLayout: EqBandLayout get() = layout

    override fun setBandGain(ear: Ear, bandIndex: Int, gainDb: Float) = guard {
        require(bandIndex in 0 until layout.bandCount) { "band index out of range: $bandIndex" }
        writeBand(ear, bandIndex, gainDb.coerceIn(EqBands.MIN_GAIN_DB, EqBands.MAX_GAIN_DB))
    }

    override fun setPreGain(db: Float) = guard {
        effect.setInputGainAllChannelsTo(db.coerceIn(-24f, 0f))
    }

    override fun setEnabled(enabled: Boolean) = guard {
        effect.enabled = enabled
    }

    /**
     * Swaps in an effect with the new band count. If the framework refuses the
     * new configuration the old effect is already gone, so the equaliser marks
     * itself dead and the caller re-attaches — the same path a HAL failure takes.
     */
    private fun rebuildFor(target: EqBandLayout) {
        runCatching { effect.enabled = false }
        runCatching { effect.release() }
        val rebuilt = createEffect(sessionId, target)
        if (rebuilt == null) {
            alive = false
            Log.w(TAG, "Could not rebuild DynamicsProcessing for ${target.id}")
            return
        }
        effect = rebuilt
        layout = target
    }

    override fun close() {
        if (!alive) return
        alive = false
        runCatching { effect.enabled = false }
        runCatching { effect.release() }
    }

    private fun writeBand(ear: Ear, bandIndex: Int, gainDb: Float) {
        val band = effect.getPreEqBandByChannelIndex(ear.channelIndex, bandIndex)
        band.isEnabled = true
        // The band's upper *edge*, not its centre: that is what
        // cutoffFrequency means, and the difference was measurable in the air.
        // Writing centres here shifted every band half an octave down, so the
        // slider labelled 1000 Hz cut 600-1000 Hz and left 1300 Hz alone.
        //
        // Still the *active* layout's list, not the default one's: reading the
        // default indexed out of bounds from band 10 upward on the 20- and
        // 31-band layouts; guard() swallowed the exception and marked the
        // effect dead, so those layouts moved sliders and changed nothing.
        band.cutoffFrequency = layout.upperEdgesHz[bandIndex]
        band.gain = gainDb
        effect.setPreEqBandByChannelIndex(ear.channelIndex, bandIndex, band)
    }

    /** Reads a gain back out of the framework — proof the value reached the effect. */
    fun readBandGain(ear: Ear, bandIndex: Int): Float? = runCatching {
        effect.getPreEqBandByChannelIndex(ear.channelIndex, bandIndex).gain
    }.getOrNull()

    private inline fun guard(block: () -> Unit) {
        if (!alive) return
        try {
            block()
        } catch (t: RuntimeException) {
            Log.w(TAG, "DynamicsProcessing call failed on session $sessionId; marking dead", t)
            alive = false
        }
    }

    companion object {
        private const val TAG = "DpEqualizer"
        private const val EFFECT_PRIORITY = 0

        /**
         * Long enough for the framework to finish wiring a new effect onto a
         * foreign session, short enough to stay on the caller's thread. The
         * attach path already runs off the main thread.
         */
        private const val ENABLE_RETRY_DELAY_MS = 120L

        /** Attack/release/ratio/threshold/post-gain for the output limiter. */
        private const val LIMITER_ATTACK_MS = 1f
        private const val LIMITER_RELEASE_MS = 60f
        private const val LIMITER_RATIO = 10f
        private const val LIMITER_THRESHOLD_DB = -1f
        private const val LIMITER_POST_GAIN_DB = 0f

        /**
         * Attaches a fresh effect to [sessionId]. Returns null on any failure —
         * missing permission for a foreign/global session, unsupported device,
         * or a session that disappeared between discovery and attach.
         */
        fun create(
            sessionId: Int,
            layout: EqBandLayout = EqBandLayout.DEFAULT,
            priority: Int = EFFECT_PRIORITY,
        ): DynamicsProcessingEqualizer? = try {
            val effect = DynamicsProcessing(priority, sessionId, buildConfig(layout))
            DynamicsProcessingEqualizer(sessionId, effect, layout)
        } catch (t: RuntimeException) {
            Log.w(TAG, "Could not attach DynamicsProcessing to session $sessionId", t)
            null
        }

        /** Raw effect for [rebuildFor], which needs the framework object itself. */
        private fun createEffect(sessionId: Int, layout: EqBandLayout): DynamicsProcessing? = try {
            DynamicsProcessing(EFFECT_PRIORITY, sessionId, buildConfig(layout))
        } catch (t: RuntimeException) {
            Log.w(TAG, "Could not rebuild DynamicsProcessing on session $sessionId", t)
            null
        }

        private fun buildConfig(layout: EqBandLayout): DynamicsProcessing.Config {
            val edges = layout.upperEdgesHz
            val eq = DynamicsProcessing.Eq(true, true, layout.bandCount).apply {
                for (i in 0 until layout.bandCount) {
                    setBand(
                        i,
                        DynamicsProcessing.EqBand(
                            /* enabled = */ true,
                            // Upper edge, not centre - see writeBand.
                            /* cutoffFrequency = */ edges[i],
                            /* gain = */ 0f,
                        ),
                    )
                }
            }
            val limiter = DynamicsProcessing.Limiter(
                /* inUse = */ true,
                /* enabled = */ true,
                /* linkGroup = */ 0,
                LIMITER_ATTACK_MS,
                LIMITER_RELEASE_MS,
                LIMITER_RATIO,
                LIMITER_THRESHOLD_DB,
                LIMITER_POST_GAIN_DB,
            )
            return DynamicsProcessing.Config.Builder(
                /* variant = */ DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                /* channelCount = */ 2,
                /* preEqInUse = */ true,
                /* preEqBandCount = */ layout.bandCount,
                /* mbcInUse = */ false,
                /* mbcBandCount = */ 0,
                /* postEqInUse = */ false,
                /* postEqBandCount = */ 0,
                /* limiterInUse = */ true,
            )
                .setPreferredFrameDuration(10f)
                .setPreEqAllChannelsTo(eq)
                .setLimiterAllChannelsTo(limiter)
                .build()
        }
    }
}

/** Default factory; :core-system decides which session id to pass in. */
class DynamicsProcessingEqualizerFactory : SystemEqualizerFactory {
    override fun create(sessionId: Int): SystemEqualizer? =
        DynamicsProcessingEqualizer.create(sessionId)
}
