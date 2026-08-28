package dev.dankyeeter.btdashboard.hearing.preference

import kotlin.math.abs
import kotlin.math.max

/** Where a song-run's label came from. Decides how much the label can be trusted. */
enum class PreferenceLabelSource {
    /** Read from the playing track's own metadata. */
    TRACK,

    /** The name of the app that was playing. Not the song. */
    APP,

    /** Typed by the user on the run's result screen. */
    MANUAL,

    /** Nothing was readable; the run is identified by its time only. */
    NONE,
}

/**
 * One song's finished mini-run.
 *
 * @param label what was playing, as well as the phone could tell — see
 *   [PreferenceLabelSource]. Also the replacement key: re-running the same
 *   song replaces its old entry rather than adding a second opinion about it.
 * @param candidate the shelf estimate this song produced
 * @param consistency 0..1, from the repeated pairs in this run
 * @param trials the run's answers, kept because [PreferencePool.carryOverPairs]
 *   draws the next run's cross-song validation pairs out of them
 */
data class PreferenceRun(
    val id: String,
    val label: String,
    val labelSource: PreferenceLabelSource,
    val createdAtMillis: Long,
    val candidate: PreferenceCandidate,
    val consistency: Double,
    val trials: List<PreferenceTrial> = emptyList(),
) {
    val trialCount: Int get() = trials.size

    /**
     * The identity two runs are considered "the same song" by.
     *
     * Case- and space-insensitive, and **blank when the label came from
     * nowhere**: a run labelled only by its timestamp is not the same song as
     * another run labelled only by its timestamp, and treating them as one
     * would silently delete a measurement.
     */
    val matchKey: String
        get() = if (labelSource == PreferenceLabelSource.NONE) {
            ""
        } else {
            label.trim().lowercase()
        }
}

/** The result of the final blind check of the aggregate against flat. */
enum class FinalCheck {
    /** Not asked, or asked and skipped. */
    NOT_RUN,

    /** The listener picked their own curve. */
    YOURS_WON,

    /** The listener picked flat. The result is reported as weak. */
    FLAT_WON,

    /** The listener could not tell the two apart. */
    NO_DIFFERENCE,
}

/** What the result screen says about the pool, in one word. */
enum class PreferenceVerdict {
    /** No runs at all. */
    NONE,

    /** The pool agrees with itself, and the curve beat flat. */
    CONSISTENT,

    /** The repeated pairs disagreed too often to call the result steady. */
    MIXED,

    /** The songs disagreed with each other by more than the spread threshold. */
    VARIED,

    /** Blind against flat, the listener picked flat. */
    WEAK,

    /** The answer is "no preference", which is a real answer. */
    NEUTRAL,
}

/**
 * What the pool as a whole says.
 *
 * @param candidate the curve that gets applied — see [PreferencePool.aggregate]
 * @param bassSpreadDb full spread across the songs, largest minus smallest
 * @param trebleSpreadDb the same for the treble axis
 */
data class PreferenceAggregate(
    val candidate: PreferenceCandidate,
    val bassSpreadDb: Float,
    val trebleSpreadDb: Float,
    val runCount: Int,
    val meanConsistency: Double,
    val finalCheck: FinalCheck = FinalCheck.NOT_RUN,
) {
    val widestSpreadDb: Float get() = max(bassSpreadDb, trebleSpreadDb)

    /** True while the songs disagree enough that the average hides something. */
    val varied: Boolean get() = widestSpreadDb > PreferencePool.SPREAD_WARN_DB

    /** One or two songs: a result, but not yet a stable one. */
    val thin: Boolean get() = runCount in 1 until PreferencePool.RECOMMENDED_RUNS

    /** Inside the neutral band on both axes — "you like it as it is". */
    val neutral: Boolean
        get() = abs(candidate.bassDb) < PreferencePool.NEUTRAL_DB &&
            abs(candidate.trebleDb) < PreferencePool.NEUTRAL_DB

    /**
     * The one-word summary, in the order the checks have to be made.
     *
     * [NEUTRAL] outranks [WEAK] deliberately: when the answer is "no
     * preference", flat winning the blind check is the correct outcome and not
     * a failure of the measurement. Every other order would report the app's
     * best case as its worst.
     */
    val verdict: PreferenceVerdict
        get() = when {
            runCount == 0 -> PreferenceVerdict.NONE
            neutral -> PreferenceVerdict.NEUTRAL
            finalCheck == FinalCheck.FLAT_WON -> PreferenceVerdict.WEAK
            varied -> PreferenceVerdict.VARIED
            meanConsistency < PreferencePool.CONSISTENCY_THRESHOLD -> PreferenceVerdict.MIXED
            else -> PreferenceVerdict.CONSISTENT
        }

    companion object {
        val EMPTY = PreferenceAggregate(
            candidate = PreferenceCandidate.NEUTRAL,
            bassSpreadDb = 0f,
            trebleSpreadDb = 0f,
            runCount = 0,
            meanConsistency = 0.0,
        )
    }
}

/**
 * The pool of song-runs behind one headphone's preference curve, and the rule
 * that turns it into a single answer.
 *
 * Pure Kotlin, no Android: the combination rule is the part of this feature most
 * worth being able to test exhaustively, because it is the part that decides
 * what the listener actually hears.
 */
object PreferencePool {

    /**
     * The most song-runs kept per headphone.
     *
     * Ten is where two things meet: enough that a median has something to be
     * robust about, and few enough that the scatter on the result screen is a
     * picture rather than a wall. Past ten the oldest run drops out, the same
     * rule `AudiogramStore` uses for hearing runs — taste drifts, and a
     * two-year-old judgement about a song that is no longer in rotation should
     * not be outvoting this month's.
     */
    const val MAX_RUNS: Int = 10

    /**
     * Below this many songs the result view says the answer is still thin.
     *
     * Three, because that is the smallest pool in which a median can outvote a
     * single odd song — the same argument
     * [dev.dankyeeter.btdashboard.hearing.AdjustedReference.REQUIRED_RUNS] makes
     * about hearing runs. Two songs that disagree have no tie-breaker.
     */
    const val RECOMMENDED_RUNS: Int = 3

    /**
     * How far the songs may disagree before the result says so, in dB.
     *
     * Four decibels. Below that the disagreement is inside what one run's own
     * ±1.5 dB final step plus a couple of coin-flip answers can produce on its
     * own, so calling it "your taste varies" would be reading noise aloud. Above
     * it the songs really are pulling in different directions, and the honest
     * thing is to apply the median *and say so*, with the per-song points on
     * screen, rather than hand over an average that describes none of them.
     */
    const val SPREAD_WARN_DB: Float = 4f

    /** Inside this on both axes the pool is reporting "no preference". */
    const val NEUTRAL_DB: Float = 1f

    /** Below this mean consistency the pool is reported as mixed. */
    const val CONSISTENCY_THRESHOLD: Double = 0.67

    /**
     * Adds a finished run, replacing any earlier run of the same song and
     * dropping the oldest once the pool is full.
     *
     * Replacement is by [PreferenceRun.matchKey]: running the same track again
     * is a correction, not a second vote, and letting both stand would let
     * somebody weight one song by re-running it. Runs whose label came from
     * nowhere have a blank key and never replace anything, because there is no
     * evidence they are the same song.
     */
    fun add(runs: List<PreferenceRun>, run: PreferenceRun): List<PreferenceRun> {
        val kept = runs.filterNot {
            it.id == run.id || (run.matchKey.isNotEmpty() && it.matchKey == run.matchKey)
        }
        return (kept + run).sortedBy { it.createdAtMillis }.takeLast(MAX_RUNS)
    }

    fun remove(runs: List<PreferenceRun>, id: String): List<PreferenceRun> =
        runs.filterNot { it.id == id }

    /**
     * The curve the pool prescribes: a **consistency-weighted median per axis**.
     *
     * ### Why a median
     *
     * One song can be wrong about a person in a way no amount of care during the
     * run can catch. A track mastered with a heavy low end makes every bass
     * comparison over it read as "already enough", and the run comes back several
     * decibels low. A mean carries that song's error into the answer in full
     * proportion; a median ignores it entirely as long as it is a minority
     * opinion. That is the whole reason for having a pool.
     *
     * ### Why weighted, and by what
     *
     * [PreferenceRun.consistency] is literally "how often you gave the same
     * answer to the same question", measured inside that run and, from the
     * second run on, against a pair carried over from an earlier song. It is
     * therefore exactly the right weight: a run where the listener answered the
     * repeats the same way is a run they were paying attention during, and a run
     * where they contradicted themselves is one that should not be deciding
     * anything. A run at consistency zero gets weight zero and drops out.
     *
     * If *every* run has weight zero the weighting has nothing to say, and the
     * honest fallback is the plain unweighted median rather than no answer at
     * all — the runs still happened.
     *
     * ### Why per axis, not per point
     *
     * Bass and treble are separate questions and a listener can be steady about
     * one and undecided about the other. A two-dimensional median (a geometric
     * median, say) would let indecision about treble drag the bass answer
     * sideways, which is not a thing anybody said.
     *
     * The spread reported alongside is the **full** spread over every run in the
     * pool, weighted or not: the weighting decides where the centre goes, but
     * the picture the listener is shown has to contain every song they sat
     * through.
     */
    fun aggregate(
        runs: List<PreferenceRun>,
        finalCheck: FinalCheck = FinalCheck.NOT_RUN,
    ): PreferenceAggregate {
        if (runs.isEmpty()) return PreferenceAggregate.EMPTY.copy(finalCheck = finalCheck)
        val weights = runs.map { it.consistency.coerceIn(0.0, 1.0) }
        val usable = if (weights.sum() > 0.0) weights else List(runs.size) { 1.0 }
        val bass = weightedMedian(runs.map { it.candidate.bassDb }, usable)
        val treble = weightedMedian(runs.map { it.candidate.trebleDb }, usable)
        return PreferenceAggregate(
            candidate = PreferenceCandidate(bass, treble).clamped().quantised(),
            bassSpreadDb = spreadOf(runs.map { it.candidate.bassDb }),
            trebleSpreadDb = spreadOf(runs.map { it.candidate.trebleDb }),
            runCount = runs.size,
            meanConsistency = runs.map { it.consistency.coerceIn(0.0, 1.0) }.average(),
            finalCheck = finalCheck,
        )
    }

    /**
     * Decisive pairs from the pool, for the next run's cross-song validation.
     *
     * Newest first, because a pair from the song before this one is the one the
     * listener is least likely to remember answering and most likely to still
     * be in the same frame of mind about. Only pairs that got a decisive answer
     * are offered — re-asking a question somebody already shrugged at measures
     * nothing.
     */
    fun carryOverPairs(runs: List<PreferenceRun>): List<PreferenceRepeatPair> =
        runs.sortedByDescending { it.createdAtMillis }
            .flatMap { run ->
                run.trials
                    .filterNot { it.repeat }
                    .filter { it.choice != PreferenceChoice.NO_DIFFERENCE }
                    .map { PreferenceRepeatPair(it.a, it.b, it.chosen) }
            }
            .distinctBy { it.key }

    // ---- maths ---------------------------------------------------------------

    private fun spreadOf(values: List<Float>): Float {
        val min = values.minOrNull() ?: return 0f
        val max = values.maxOrNull() ?: return 0f
        return max - min
    }

    /**
     * The lower weighted median, with the standard tie rule.
     *
     * Values are sorted and weights accumulated; the answer is the first value
     * whose running total passes half the weight. When the running total lands
     * *exactly* on half — which is what happens for an even number of
     * equally-weighted runs — the answer is the midpoint of that value and the
     * next one, so the weighted median degenerates to the ordinary median
     * instead of picking the lower of two equally good candidates.
     */
    internal fun weightedMedian(values: List<Float>, weights: List<Double>): Float {
        require(values.size == weights.size) { "values and weights must line up" }
        val pairs = values.indices
            .map { values[it] to weights[it] }
            .filter { it.second > 0.0 }
            .sortedBy { it.first }
        if (pairs.isEmpty()) return 0f
        val total = pairs.sumOf { it.second }
        var running = 0.0
        pairs.forEachIndexed { index, (value, weight) ->
            running += weight
            if (abs(running - total / 2.0) <= EPSILON) {
                val next = pairs.getOrNull(index + 1)?.first ?: return value
                return (value + next) / 2f
            }
            if (running > total / 2.0) return value
        }
        return pairs.last().first
    }

    private const val EPSILON = 1e-9
}
