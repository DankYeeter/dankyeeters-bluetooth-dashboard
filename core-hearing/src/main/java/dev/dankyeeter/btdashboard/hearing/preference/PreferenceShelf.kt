package dev.dankyeeter.btdashboard.hearing.preference

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One point in the taste space the preference test searches: a bass shelf and a
 * treble shelf, both relative to whatever the listener is already hearing.
 *
 * Two numbers, not thirty-one, and that is the whole design decision. It is not
 * a simplification made for the UI — it is where the published between-listener
 * variance actually lives.
 *
 * ## Why two axes
 *
 * Harman's headphone-preference programme (Olive, Welti and Khonsaripour, a
 * series of AES convention papers between roughly 2013 and 2018, including "A
 * Statistical Model that Predicts Listeners' Preference Ratings of In-Ear
 * Headphones") repeatedly found the same thing: once a headphone is equalised
 * to the reference target, what is left of listener disagreement collapses onto
 * a bass shelf and a treble shelf. Their cluster analyses split listeners into
 * roughly three groups around one target — about two thirds prefer the
 * reference as it is, about a sixth want some 4–6 dB more bass, and about a
 * fifth want less bass and/or a different treble balance — with group
 * membership tracking age and listening experience rather than anything the
 * listener can report about themselves.
 *
 * Those proportions are quoted here to the nearest whole number from the
 * research brief this file was written against. They are population statistics
 * and they are used for exactly one thing: choosing which two knobs to search.
 * Nothing in this app ever assigns a listener to a cluster, and no result is
 * ever nudged towards one — the search below starts flat and goes where the
 * answers take it.
 *
 * Sonarworks' SoundID, the commercial implementation of the same idea, searches
 * the same kind of space with roughly sixteen adaptive A/B comparisons and a
 * "no difference" option. [PreferenceProtocol] follows that shape.
 *
 * ## Ranges
 *
 * Bass runs −6…+9 dB and treble −6…+6 dB. The asymmetry is deliberate: the
 * "more bass" group is the one with a large published offset, and it sits on
 * the positive side. Beyond those numbers a shelf stops being taste and starts
 * being a different sound; the ranges also keep the worst case inside
 * [dev.dankyeeter.btdashboard.audio.eq.EqBands.MAX_GAIN_DB] once a compensation
 * curve is already underneath it.
 */
data class PreferenceCandidate(
    val bassDb: Float,
    val trebleDb: Float,
) {
    /** This candidate with [axis] moved by [deltaDb], clamped back into range. */
    fun movedOn(axis: PreferenceAxis, deltaDb: Float): PreferenceCandidate = when (axis) {
        PreferenceAxis.BASS -> copy(bassDb = bassDb + deltaDb)
        PreferenceAxis.TREBLE -> copy(trebleDb = trebleDb + deltaDb)
        PreferenceAxis.BOTH -> PreferenceCandidate(bassDb + deltaDb, trebleDb + deltaDb)
        PreferenceAxis.TILT -> PreferenceCandidate(bassDb + deltaDb, trebleDb - deltaDb)
    }.clamped()

    fun clamped(): PreferenceCandidate = PreferenceCandidate(
        bassDb = bassDb.coerceIn(PreferenceShelf.MIN_BASS_DB, PreferenceShelf.MAX_BASS_DB),
        trebleDb = trebleDb.coerceIn(PreferenceShelf.MIN_TREBLE_DB, PreferenceShelf.MAX_TREBLE_DB),
    )

    /** Rounded to the step the result is reported and stored in. */
    fun quantised(): PreferenceCandidate = PreferenceCandidate(
        bassDb = quantise(bassDb),
        trebleDb = quantise(trebleDb),
    )

    /** How far apart two candidates are, in dB summed over both axes. */
    fun separationFrom(other: PreferenceCandidate): Float =
        kotlin.math.abs(bassDb - other.bassDb) + kotlin.math.abs(trebleDb - other.trebleDb)

    private fun quantise(db: Float): Float = (db / STEP_DB).roundToInt() * STEP_DB

    companion object {
        /** No preference at all — the curve the test starts from and ends against. */
        val NEUTRAL = PreferenceCandidate(0f, 0f)

        /**
         * Half a decibel, the same grid audiometric levels use elsewhere in this
         * module. Finer would be reporting precision the protocol has not got.
         */
        const val STEP_DB: Float = 0.5f
    }
}

/**
 * Which knob a comparison moves.
 *
 * The two single axes plus the two diagonals of the same square. [BOTH] moves
 * bass and treble the same way — "more of both ends" against "less of both
 * ends", which is roughly a loudness-and-extension judgement. [TILT] moves them
 * opposite ways — "warm" against "bright". They are genuinely different
 * questions and a listener can hold a strong opinion about one and none about
 * the other, so both directions are asked rather than only the first.
 */
enum class PreferenceAxis { BASS, TREBLE, BOTH, TILT }

/**
 * Turns a [PreferenceCandidate] into per-band gains, and says how much louder
 * that makes the music.
 *
 * The second half is not a detail. A/B comparisons of tone curves are decided by
 * level unless the level is taken out first: a boosted curve is louder, louder
 * is preferred on first hearing, and a test that does not correct for it
 * measures nothing but which side had more gain. [levelOffsetDb] is the
 * correction, and [PreferenceAudition] is what folds it in.
 */
object PreferenceShelf {

    /**
     * Where the bass shelf sits.
     *
     * 200 Hz rather than the 105 Hz a Harman-style low shelf uses, because this
     * shelf is a *preference* offset applied on top of whatever the listener
     * already has, not a target curve. Its job is to move the whole low end
     * audibly with one number, and a corner an octave higher does that while
     * still leaving the 400 Hz–1 kHz region — where a shift reads as "boxy"
     * rather than as "more bass" — essentially untouched.
     */
    const val BASS_CORNER_HZ: Float = 200f

    /**
     * Where the treble shelf sits.
     *
     * 2 kHz is the bottom of the region the Harman work found listeners
     * disagreeing about, and it is above the vocal fundamentals, so moving it
     * changes brightness rather than tone colour.
     */
    const val TREBLE_CORNER_HZ: Float = 2000f

    const val MIN_BASS_DB: Float = -6f
    const val MAX_BASS_DB: Float = 9f
    const val MIN_TREBLE_DB: Float = -6f
    const val MAX_TREBLE_DB: Float = 6f

    /**
     * How wide the transition is, in octaves, measured from the corner to the
     * point where the shelf has done four fifths of its work.
     *
     * Half an octave. The shelf is a logistic in log-frequency rather than a
     * biquad response, which is the honest shape for a *graphic* EQ: the gains
     * land on band centres and the filters between them do their own
     * interpolating, so a mathematically exact biquad magnitude would be
     * precision this pipeline cannot deliver. At half an octave the slope
     * through the corner is roughly 3 dB per octave for a 9 dB shelf — gentle,
     * audibly a shelf rather than a bump, and nothing like a brick wall.
     */
    const val HALF_WIDTH_OCTAVES: Float = 0.5f

    /**
     * The fraction of the bass shelf that applies at [hz].
     *
     * A logistic in log-frequency: 1 well below the corner, exactly one half at
     * the corner (the classic definition of a shelf corner), 0 well above.
     */
    fun bassWeightAt(hz: Float): Double = weight(octavesFrom(hz, BASS_CORNER_HZ))

    /** The mirror image of [bassWeightAt] about [TREBLE_CORNER_HZ]. */
    fun trebleWeightAt(hz: Float): Double = weight(-octavesFrom(hz, TREBLE_CORNER_HZ))

    /** The combined shelf gain at one frequency, in dB. */
    fun gainAtHz(candidate: PreferenceCandidate, hz: Float): Float =
        (candidate.bassDb * bassWeightAt(hz) + candidate.trebleDb * trebleWeightAt(hz)).toFloat()

    /**
     * The shelf rendered onto a band layout.
     *
     * Evaluated at the layout's own centres rather than tabulated and resampled
     * through [EqBandLayout.resample]. That helper exists for curves that arrive
     * measured or standardised on somebody else's frequency list — ISO 226 is
     * the other caller, and it has 29 fixed points. A shelf has a closed form at
     * every frequency, so interpolating one would only add error to a number the
     * formula can produce exactly.
     */
    fun gains(candidate: PreferenceCandidate, layout: EqBandLayout): List<Float> =
        layout.centersHz.map { gainAtHz(candidate, it) }

    /**
     * How much louder a set of band gains makes pink noise, in dB.
     *
     * **This is the number that keeps the whole test honest.** A/B preference
     * work has one classic confound and this is it: raise the bass by 9 dB and
     * the music is measurably louder, the louder side wins, and the result is a
     * loudness judgement wearing a tone judgement's clothes.
     *
     * The approximation, stated plainly:
     *
     *  * The reference signal is **pink noise** — equal energy per octave, the
     *    standard stand-in for the long-term average spectrum of music. Each
     *    band therefore contributes in proportion to the octaves it spans, which
     *    for every layout in [EqBandLayout] is the same width for every band, so
     *    the weights come out equal. They are still written out rather than
     *    assumed, so a future layout with uneven spacing stays correct.
     *  * Energies are summed, not decibels averaged: `10·log10(mean(10^(g/10)))`.
     *    Averaging the dB values instead would understate a narrow large boost,
     *    which is exactly the case that matters here.
     *  * The ear's own frequency weighting is **not** applied. A true loudness
     *    match would need a loudness model (ISO 532) and a calibrated level,
     *    and this app has neither. The consequence is that the match is good to
     *    a decibel or so rather than exact — enough to remove the confound, not
     *    enough to call this a loudness meter. It is applied identically to both
     *    sides of every comparison, so what error remains is common to both.
     */
    fun levelOffsetDb(gainsDb: List<Float>, layout: EqBandLayout): Float {
        if (gainsDb.isEmpty()) return 0f
        var weighted = 0.0
        var total = 0.0
        gainsDb.forEachIndexed { index, gain ->
            // Every band of every current layout spans the same number of
            // octaves, so this is a constant — kept per band because the
            // pink-noise rule is "per octave", not "per band".
            val weight = layout.octaveFraction.toDouble()
            weighted += weight * 10.0.pow(gain.toDouble() / 10.0)
            total += weight
            if (index == gainsDb.lastIndex && total <= 0.0) return 0f
        }
        return (10.0 * log10(weighted / total)).toFloat()
    }

    /** [levelOffsetDb] for a candidate's shelf alone. */
    fun levelOffsetDb(candidate: PreferenceCandidate, layout: EqBandLayout): Float =
        levelOffsetDb(gains(candidate, layout), layout)

    /**
     * The largest band gain any candidate in the space can ask for, on this
     * layout — never negative.
     *
     * Used as a fixed headroom during the test. The gain at a frequency is
     * `bass·w_bass(f) + treble·w_treble(f)` with both weights non-negative, so
     * it is linear and increasing in both parameters and the maximum over the
     * whole box sits at the top corner. One number for the whole run, which is
     * the point: a headroom that changed per candidate would itself be a level
     * difference between the two sides.
     */
    fun ceilingDb(layout: EqBandLayout): Float {
        val loudest = PreferenceCandidate(MAX_BASS_DB, MAX_TREBLE_DB)
        return gains(loudest, layout).maxOrNull()?.coerceAtLeast(0f) ?: 0f
    }

    private fun octavesFrom(hz: Float, cornerHz: Float): Double =
        log2(hz.toDouble() / cornerHz.toDouble())

    /**
     * `1 / (1 + 2^(x / halfWidth))` — one half at x = 0, falling as x grows.
     *
     * Written in powers of two rather than of e so the width constant reads in
     * octaves, which is the unit the comment on [HALF_WIDTH_OCTAVES] argues in.
     */
    private fun weight(octaves: Double): Double =
        1.0 / (1.0 + 2.0.pow(octaves / HALF_WIDTH_OCTAVES.toDouble()))
}
