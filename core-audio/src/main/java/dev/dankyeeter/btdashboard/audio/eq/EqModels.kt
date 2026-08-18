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

/** Fixed 10-band layout (ISO octave centres). Index order is used everywhere. */
object EqBands {
    val CENTER_FREQUENCIES_HZ: List<Float> = listOf(
        31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f,
    )

    const val COUNT: Int = 10

    /** Gain range the UI and the compensation math must stay inside. */
    const val MIN_GAIN_DB: Float = -15f
    const val MAX_GAIN_DB: Float = 15f

    /**
     * Bands outside the 250–8000 Hz audiometry range. The hearing test cannot
     * measure these, so any gain here is extrapolated and must be labelled as
     * such in the UI (Stage B/C requirement).
     */
    val EXTRAPOLATED_INDICES: Set<Int> = setOf(0, 1, 2, 9)
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
 */
data class EqSettings(
    val enabled: Boolean = false,
    val leftGainsDb: List<Float> = List(EqBands.COUNT) { 0f },
    val rightGainsDb: List<Float> = List(EqBands.COUNT) { 0f },
    val preGainDb: Float = 0f,
    val limiterEnabled: Boolean = true,
) {
    init {
        require(leftGainsDb.size == EqBands.COUNT) { "leftGainsDb must have ${EqBands.COUNT} entries" }
        require(rightGainsDb.size == EqBands.COUNT) { "rightGainsDb must have ${EqBands.COUNT} entries" }
    }

    fun gainsFor(ear: Ear): List<Float> = when (ear) {
        Ear.LEFT -> leftGainsDb
        Ear.RIGHT -> rightGainsDb
    }

    /** Clamps all gains into the supported range and recomputes safe headroom. */
    fun sanitized(): EqSettings {
        val l = leftGainsDb.map { it.coerceIn(EqBands.MIN_GAIN_DB, EqBands.MAX_GAIN_DB) }
        val r = rightGainsDb.map { it.coerceIn(EqBands.MIN_GAIN_DB, EqBands.MAX_GAIN_DB) }
        val peak = ((l + r).maxOrNull() ?: 0f).coerceAtLeast(0f)
        return copy(
            leftGainsDb = l,
            rightGainsDb = r,
            preGainDb = preGainDb.coerceIn(-24f, 0f).coerceAtMost(-peak),
        )
    }

    companion object {
        val FLAT = EqSettings()

        /** Suggested headroom for a given set of band gains. */
        fun headroomFor(vararg gains: List<Float>): Float =
            -(gains.flatMap { it }.maxOrNull() ?: 0f).coerceAtLeast(0f)
    }
}
