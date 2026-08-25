package dev.dankyeeter.btdashboard.audio.eq

/**
 * Which ear a set of band gains applies to. `DynamicsProcessing` exposes the
 * pre-EQ per channel, so left/right can differ — that is what makes asymmetric
 * hearing loss compensable.
 */
enum class Ear(val channelIndex: Int) {
    LEFT(0),
    RIGHT(1),
}

/**
 * Layout-independent EQ constants.
 *
 * The band *positions* moved to [EqBandLayout] once the EQ stopped being fixed
 * at ten; what is left here applies to every layout.
 */
object EqBands {
    /** Gain range the UI and the compensation math must stay inside. */
    const val MIN_GAIN_DB: Float = -15f
    const val MAX_GAIN_DB: Float = 15f

    /** The default layout's centres, kept for callers that predate layouts. */
    val CENTER_FREQUENCIES_HZ: List<Float> get() = EqBandLayout.DEFAULT.centersHz

    val COUNT: Int get() = EqBandLayout.DEFAULT.bandCount

    val EXTRAPOLATED_INDICES: Set<Int> get() = EqBandLayout.DEFAULT.extrapolatedIndices
}

/**
 * Complete state of the system EQ. This is the single object persisted by
 * :core-system and produced by the compensation math in :core-hearing.
 *
 * @param enabled master on/off; when false the effect is detached, not zeroed
 * @param leftGainsDb 10 gains in dB for the left channel, index-aligned with
 *   [EqBands.CENTER_FREQUENCIES_HZ]
 * @param rightGainsDb same for the right channel
 * @param preGainDb input gain applied before the bands. Should be negative
 *   headroom: roughly `-max(0, maxBandGain)` so boosted bands cannot clip.
 * @param limiterEnabled enables the built-in per-channel limiter as the last
 *   stage (tames loudly mastered tracks; not a loudness normaliser)
 * @param autoHeadroom lowers the whole signal by whatever the loudest band was
 *   raised, so nothing can overflow. On by default. Turning it off makes a
 *   boost audible *as* a boost - which is what a person expects when they drag
 *   a band upwards - at the price that a loud passage can now clip. The
 *   limiter is the second net; whoever switches that off as well is on their
 *   own, and should be.
 */
data class EqSettings(
    val enabled: Boolean = false,
    val layout: EqBandLayout = EqBandLayout.DEFAULT,
    val leftGainsDb: List<Float> = List(layout.bandCount) { 0f },
    val rightGainsDb: List<Float> = List(layout.bandCount) { 0f },
    val preGainDb: Float = 0f,
    val limiterEnabled: Boolean = true,
    val autoHeadroom: Boolean = true,
) {
    init {
        require(leftGainsDb.size == layout.bandCount) {
            "leftGainsDb must have ${layout.bandCount} entries for ${layout.id}"
        }
        require(rightGainsDb.size == layout.bandCount) {
            "rightGainsDb must have ${layout.bandCount} entries for ${layout.id}"
        }
    }

    val bandCount: Int get() = layout.bandCount

    val centersHz: List<Float> get() = layout.centersHz

    /**
     * Same curve, different resolution. Gains are resampled rather than reset,
     * so trying a finer layout never costs the user the setting they had.
     */
    fun withLayout(target: EqBandLayout): EqSettings {
        if (target == layout) return this
        return copy(
            layout = target,
            leftGainsDb = EqBandLayout.resample(leftGainsDb, layout, target),
            rightGainsDb = EqBandLayout.resample(rightGainsDb, layout, target),
        ).sanitized()
    }

    fun gainsFor(ear: Ear): List<Float> = when (ear) {
        Ear.LEFT -> leftGainsDb
        Ear.RIGHT -> rightGainsDb
    }

    /**
     * Clamps all gains into the supported range and, if asked, recomputes safe
     * headroom.
     *
     * The headroom rule used to be unconditional, and it made every boost
     * inaudible as a boost: raising a band by 15 dB lowered everything else by
     * 15 dB, so the band ended up back where it started and the only audible
     * change was that the music got quieter. Correct against clipping, useless
     * as a control. It stays the default and is now a choice - see
     * [autoHeadroom].
     */
    fun sanitized(): EqSettings {
        val l = leftGainsDb.map { it.coerceIn(EqBands.MIN_GAIN_DB, EqBands.MAX_GAIN_DB) }
        val r = rightGainsDb.map { it.coerceIn(EqBands.MIN_GAIN_DB, EqBands.MAX_GAIN_DB) }
        val peak = ((l + r).maxOrNull() ?: 0f).coerceAtLeast(0f)
        val gain = preGainDb.coerceIn(-24f, 0f)
        return copy(
            leftGainsDb = l,
            rightGainsDb = r,
            preGainDb = if (autoHeadroom) gain.coerceAtMost(-peak) else gain,
        )
    }

    companion object {
        val FLAT = EqSettings()

        /** Suggested headroom for a given set of band gains. */
        fun headroomFor(vararg gains: List<Float>): Float =
            -(gains.flatMap { it }.maxOrNull() ?: 0f).coerceAtLeast(0f)
    }
}
