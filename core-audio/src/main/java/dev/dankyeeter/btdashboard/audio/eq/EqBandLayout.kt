package dev.dankyeeter.btdashboard.audio.eq

/**
 * A graphic-EQ band layout: how many filters, and where they sit.
 *
 * `DynamicsProcessing` takes the band count at construction time, so changing
 * layout means rebuilding the effect rather than moving a slider — see
 * [SystemEqualizer.applyLayout]. The layouts below are the standard ISO
 * spacings rather than arbitrary counts, because the whole point of a wider
 * layout is finer *resolution*, and evenly spaced octave fractions are what
 * make a correction curve land where it was measured.
 */
enum class EqBandLayout(
    val id: String,
    val label: String,
    val description: String,
    val centersHz: List<Float>,
) {
    /** ISO octave centres. The classic ten-slider layout. */
    OCTAVE_10(
        id = "octave_10",
        label = "10 bands",
        description = "One band per octave. Broad, forgiving, easy to set by ear.",
        centersHz = listOf(31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f),
    ),

    /** Half-octave spacing: twice the resolution, still readable on a phone. */
    HALF_OCTAVE_20(
        id = "half_octave_20",
        label = "20 bands",
        description = "Half-octave steps. Enough resolution to follow a measured curve.",
        centersHz = listOf(
            25f, 35f, 50f, 71f, 100f, 141f, 200f, 283f, 400f, 566f,
            800f, 1130f, 1600f, 2260f, 3200f, 4520f, 6400f, 9050f, 12800f, 18100f,
        ),
    ),

    /** ISO third-octave. What measurement rigs and room EQ actually use. */
    THIRD_OCTAVE_31(
        id = "third_octave_31",
        label = "31 bands",
        description = "Third-octave, the spacing measurements are published in.",
        centersHz = listOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
            200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
            2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f,
            20000f,
        ),
    ),
    ;

    val bandCount: Int get() = centersHz.size

    /**
     * Bands the hearing test cannot reach. Audiometry runs 250-8000 Hz, so
     * anything outside that is extrapolated from the nearest measured point and
     * the UI marks it as such.
     */
    val extrapolatedIndices: Set<Int>
        get() = centersHz.indices.filter { centersHz[it] < 250f || centersHz[it] > 8000f }.toSet()

    companion object {
        val DEFAULT = OCTAVE_10

        fun fromId(id: String?): EqBandLayout =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        /**
         * Resamples a gain curve from one layout onto another so switching
         * layouts keeps the sound instead of resetting it to flat.
         *
         * Linear interpolation in log-frequency, which is how the ear spaces
         * pitch and how the curves were drawn in the first place; outside the
         * source range the nearest edge value is held rather than extrapolated
         * into a runaway slope.
         */
        fun resample(
            gainsDb: List<Float>,
            from: EqBandLayout,
            to: EqBandLayout,
        ): List<Float> {
            if (from == to) return gainsDb
            if (gainsDb.size != from.bandCount) return List(to.bandCount) { 0f }
            return to.centersHz.map { target -> interpolate(target, from.centersHz, gainsDb) }
        }

        private fun interpolate(
            targetHz: Float,
            sourceHz: List<Float>,
            gains: List<Float>,
        ): Float {
            if (sourceHz.isEmpty()) return 0f
            if (targetHz <= sourceHz.first()) return gains.first()
            if (targetHz >= sourceHz.last()) return gains.last()
            val upper = sourceHz.indexOfFirst { it >= targetHz }
            val lower = upper - 1
            val lo = sourceHz[lower]
            val hi = sourceHz[upper]
            if (hi <= lo) return gains[upper]
            // Interpolate on a log axis: 500 Hz sits midway between 250 and
            // 1000 to the ear, not a quarter of the way as it would linearly.
            val t = (ln(targetHz) - ln(lo)) / (ln(hi) - ln(lo))
            return gains[lower] + (gains[upper] - gains[lower]) * t
        }

        private fun ln(v: Float): Float = kotlin.math.ln(v.toDouble()).toFloat()
    }
}
