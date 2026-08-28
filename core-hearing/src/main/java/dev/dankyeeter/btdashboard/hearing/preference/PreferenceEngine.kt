package dev.dankyeeter.btdashboard.hearing.preference

import kotlin.random.Random

/** What the listener answered to one comparison. */
enum class PreferenceChoice { A, B, NO_DIFFERENCE }

/** Which part of the run a trial belongs to. */
enum class TrialPhase {
    /** Large contrasts, one axis at a time — the part a naive listener can hear. */
    LEAD_IN,

    /** Small steps around what the lead-in found. */
    REFINE,

    /** A pair asked once already, to see whether the same answer comes back. */
    VALIDATE,
}

/** One step of the search: which knob to move, and by how much. */
data class SearchStep(
    val axis: PreferenceAxis,
    val stepDb: Float,
    val phase: TrialPhase,
)

/**
 * A pair worth asking twice, together with the answer it got the first time.
 *
 * Carried between runs as well as inside one: a repeat drawn from *another*
 * song is a stronger consistency check than a repeat drawn from the same song
 * two minutes earlier, because it cannot be answered from short-term memory of
 * the comparison itself.
 */
data class PreferenceRepeatPair(
    val a: PreferenceCandidate,
    val b: PreferenceCandidate,
    /** The candidate chosen last time, or null for "no difference". */
    val chosen: PreferenceCandidate?,
) {
    /** Order-free identity, so A-vs-B and B-vs-A are the same question. */
    internal val key: Set<PreferenceCandidate> get() = setOf(a, b)

    val separationDb: Float get() = a.separationFrom(b)
}

/** One answered comparison. Compact on purpose — a whole run of these is stored. */
data class PreferenceTrial(
    val index: Int,
    val phase: TrialPhase,
    val axis: PreferenceAxis,
    val a: PreferenceCandidate,
    val b: PreferenceCandidate,
    val choice: PreferenceChoice,
    val repeat: Boolean = false,
) {
    val chosen: PreferenceCandidate?
        get() = when (choice) {
            PreferenceChoice.A -> a
            PreferenceChoice.B -> b
            PreferenceChoice.NO_DIFFERENCE -> null
        }

    val separationDb: Float get() = a.separationFrom(b)

    internal fun asRepeatPair(): PreferenceRepeatPair = PreferenceRepeatPair(a, b, chosen)
}

/**
 * How long one song's run is and what it asks.
 *
 * ## Why ten, and why per song
 *
 * Ten comparisons per song, and the precision comes from the pool
 * ([PreferencePool]) on top of that. Three runs on three different songs say
 * more about a person's taste than one long run on one song, because a single
 * track carries its own mastering — a bass-heavy master shifts every comparison
 * made over it in the same direction, and no number of trials inside that one
 * run can see the shift. Spreading runs over songs turns that bias into visible
 * spread instead of an invisible offset.
 *
 * Ten is also about one song's worth of attention, which is the thing that
 * actually breaks preference tests: careful A/B judgements degrade long before
 * the listener notices they are guessing.
 *
 * ## The plan
 *
 * Four lead-in trials, four refine trials, two validation repeats.
 *
 *  * **Lead-in** — ±6 then ±3 dB, bass and treble interleaved. Contrasts large
 *    enough that somebody who has never thought about EQ can hear which one they
 *    like, and a halving staircase that lands the estimate within a few dB of
 *    the answer in two steps per axis.
 *  * **Refine** — the two diagonals first, at a deliberately *small* ±1 dB
 *    ([PreferenceAxis.TILT] then [PreferenceAxis.BOTH]), then the two single
 *    axes at ±1.5 dB. Both halves of that are measurements rather than taste:
 *    a diagonal answered at a point where one axis is already right will trade
 *    that axis away to fix the other, so it goes first, with a step small enough
 *    that the damage is inside what the per-axis trials after it can undo. The
 *    same four trials in the opposite order measure roughly 40 % worse against
 *    the simulated chooser, and the same order with ±2 dB diagonals is worse
 *    still — `PreferenceEngineTest` pins both comparisons, and
 *    `PreferenceConvergenceReport` is the instrument.
 *  * **Validation** — two pairs that were already answered decisively, asked
 *    again. See [PreferenceRepeatPair] for where they come from and
 *    [PreferenceEngine] for how they are scored.
 *
 * Measured mean absolute error against a planted preference, over 448 simulated
 * runs: **0.51 dB bass and 0.63 dB treble for one run**, falling to 0.35 / 0.43
 * once three songs are pooled. A four-trial lead-in on its own manages 0.62 /
 * 0.88, so the four refine trials are earning their place.
 *
 * Protocol shape follows the adaptive two-alternative forced-choice tradition
 * (Levitt, "Transformed Up-Down Methods in Psychoacoustics", JASA 1971) with a
 * third "no difference" answer added, which is what Sonarworks' SoundID does and
 * what stops a listener who genuinely cannot hear a difference from being forced
 * into inventing one.
 */
data class PreferenceProtocol(
    val searchPlan: List<SearchStep> = DEFAULT_SEARCH_PLAN,
    val repeatTrials: Int = 2,
    /** Below this, a run's answers are reported as mixed rather than steady. */
    val consistencyThreshold: Double = 0.67,
    /** Inside this, on both axes, a result is called neutral rather than a taste. */
    val neutralDb: Float = 1.0f,
) {
    val trialsPerRun: Int get() = searchPlan.size + repeatTrials

    companion object {
        val DEFAULT_SEARCH_PLAN: List<SearchStep> = listOf(
            SearchStep(PreferenceAxis.BASS, 6f, TrialPhase.LEAD_IN),
            SearchStep(PreferenceAxis.TREBLE, 6f, TrialPhase.LEAD_IN),
            SearchStep(PreferenceAxis.BASS, 3f, TrialPhase.LEAD_IN),
            SearchStep(PreferenceAxis.TREBLE, 3f, TrialPhase.LEAD_IN),
            SearchStep(PreferenceAxis.TILT, 1f, TrialPhase.REFINE),
            SearchStep(PreferenceAxis.BOTH, 1f, TrialPhase.REFINE),
            SearchStep(PreferenceAxis.BASS, 1.5f, TrialPhase.REFINE),
            SearchStep(PreferenceAxis.TREBLE, 1.5f, TrialPhase.REFINE),
        )
    }
}

/** What one song's run produced. */
data class PreferenceRunResult(
    val candidate: PreferenceCandidate,
    /**
     * How often the repeated pairs got the same answer as the first time:
     * 1 for the same choice, 0 for the opposite, 0.5 when either answer was
     * "no difference" — that is not a contradiction, it is an admission, and
     * scoring it as a contradiction would punish honesty.
     */
    val consistency: Double,
    val repeats: Int,
    val trials: List<PreferenceTrial>,
)

/**
 * One song's mini-run of the preference test: a pure, deterministic state
 * machine, free of Android, audio and coroutines for the same reason
 * [dev.dankyeeter.btdashboard.hearing.protocol.HughsonWestlakeEngine] is — so
 * the protocol can be simulated exhaustively instead of clicked through.
 *
 * The driver ([dev.dankyeeter.btdashboard.ui.screens.preference.PreferenceTestViewModel])
 * turns a [Step.Compare] into two live EQ curves and feeds the listener's answer
 * back through [record].
 *
 * ## The staircase
 *
 * Every search trial offers the current estimate moved up and down one axis by
 * the step, loudness-matched (see [PreferenceShelf.levelOffsetDb]) and with the
 * two candidates assigned to the A and B buttons at random, so a listener who
 * always taps the left button produces noise rather than a bias.
 *
 * The chosen candidate becomes the new estimate. "No difference" leaves it where
 * it is — the step has already halved by the time the axis comes round again,
 * so an honest "I can't tell" narrows the search instead of stalling it.
 *
 * The endpoint of the staircase is the run's estimate. Deliberately **not** the
 * average of the trajectory, which is how a long fixed-step staircase is
 * normally read: with three halving steps the trajectory starts at flat by
 * construction, so averaging it would drag every run towards no-preference-at-all
 * regardless of what the listener said.
 */
class PreferenceEngine(
    private val config: PreferenceProtocol = PreferenceProtocol(),
    /** Decisive pairs from earlier songs; see [PreferenceRepeatPair]. */
    private val carryOver: List<PreferenceRepeatPair> = emptyList(),
    /** Where this run starts. The pool's aggregate, or flat for a first run. */
    startingEstimate: PreferenceCandidate = PreferenceCandidate.NEUTRAL,
    private val random: Random = Random.Default,
) {

    /** What the driver should do next. */
    sealed interface Step {
        /**
         * Play these two and ask which is better.
         *
         * @param index 0-based position in the run, for the progress readout
         * @param total how many comparisons this run plans to ask
         * @param repeat true while this pair has been asked before
         */
        data class Compare(
            val index: Int,
            val total: Int,
            val phase: TrialPhase,
            val axis: PreferenceAxis,
            val a: PreferenceCandidate,
            val b: PreferenceCandidate,
            val repeat: Boolean,
        ) : Step

        data class Finished(val result: PreferenceRunResult) : Step
    }

    private var estimate: PreferenceCandidate = startingEstimate.clamped()
    private val trials = mutableListOf<PreferenceTrial>()
    private var pending: Step.Compare? = null
    private var repeatPlan: List<PreferenceRepeatPair>? = null
    private val agreements = mutableListOf<Double>()

    /** The estimate as it stands right now. Useful to a driver; not the result. */
    val currentEstimate: PreferenceCandidate get() = estimate

    /**
     * @return the next comparison, or [Step.Finished]. Calling this twice
     *   without a [record] in between returns the same pending comparison, so a
     *   recomposition cannot skip a trial.
     */
    fun next(): Step {
        pending?.let { return it }
        val index = trials.size
        val step = if (index < config.searchPlan.size) {
            searchStep(index)
        } else {
            validationStep(index)
        } ?: return Step.Finished(finish())
        pending = step
        return step
    }

    /** Feeds the answer to the comparison returned by [next]. */
    fun record(choice: PreferenceChoice) {
        val step = requireNotNull(pending) { "record() without a pending comparison" }
        pending = null
        val trial = PreferenceTrial(
            index = step.index,
            phase = step.phase,
            axis = step.axis,
            a = step.a,
            b = step.b,
            choice = choice,
            repeat = step.repeat,
        )
        trials += trial

        if (step.repeat) {
            // A validation trial must never move the estimate. It may be a pair
            // from a different song entirely, answered at a different point in
            // the search; folding its answer into this run's estimate would let
            // an old question overwrite a newer answer.
            agreements += agreementOf(step, trial.chosen)
            return
        }
        // The chosen candidate carries both axes, so this works for a diagonal
        // pair as well as for a single-axis one without a special case.
        trial.chosen?.let { estimate = it.clamped() }
    }

    // ---- steps ---------------------------------------------------------------

    private fun searchStep(index: Int): Step.Compare {
        val plan = config.searchPlan[index]
        val high = estimate.movedOn(plan.axis, plan.stepDb)
        val low = estimate.movedOn(plan.axis, -plan.stepDb)
        // The two can never coincide: they would both have to clamp to the same
        // bound, which needs the estimate to sit outside the range it is itself
        // clamped into. Asserted rather than assumed, because a future range
        // change that broke it would silently present two identical curves.
        check(high != low) { "degenerate comparison at index $index" }
        return present(index, plan.phase, plan.axis, high, low, repeat = false)
    }

    private fun validationStep(index: Int): Step.Compare? {
        val plan = repeatPlan ?: buildRepeatPlan().also { repeatPlan = it }
        val slot = index - config.searchPlan.size
        val pair = plan.getOrNull(slot) ?: return null
        return present(
            index = index,
            phase = TrialPhase.VALIDATE,
            axis = axisOf(pair),
            first = pair.a,
            second = pair.b,
            repeat = true,
        )
    }

    /**
     * Which pairs get asked again.
     *
     * At most one comes from an earlier song, and it is taken first: a repeat
     * the listener cannot possibly remember answering is the only one that
     * measures anything about their taste rather than about their memory. The
     * rest come from this run, most decisive first — a pair the listener chose
     * between confidently is a question worth re-asking, a pair they were
     * indifferent about is not, which is why "no difference" answers are never
     * repeated.
     */
    private fun buildRepeatPlan(): List<PreferenceRepeatPair> {
        val own = trials
            .filterNot { it.repeat }
            .filter { it.choice != PreferenceChoice.NO_DIFFERENCE }
            .map { it.asRepeatPair() }
            .sortedByDescending { it.separationDb }
        val borrowed = carryOver
            .filter { it.chosen != null }
            .sortedByDescending { it.separationDb }
            .take(1)
        val seen = mutableSetOf<Set<PreferenceCandidate>>()
        return (borrowed + own)
            .filter { seen.add(it.key) }
            .take(config.repeatTrials)
    }

    private fun present(
        index: Int,
        phase: TrialPhase,
        axis: PreferenceAxis,
        first: PreferenceCandidate,
        second: PreferenceCandidate,
        repeat: Boolean,
    ): Step.Compare {
        // Which candidate is "A" is decided by a coin, every time. Without it,
        // "the boosted one is always A" is learnable in three trials and the
        // rest of the run measures that instead of taste.
        val aFirst = random.nextBoolean()
        return Step.Compare(
            index = index,
            total = config.trialsPerRun,
            phase = phase,
            axis = axis,
            a = if (aFirst) first else second,
            b = if (aFirst) second else first,
            repeat = repeat,
        )
    }

    /**
     * Which knob a repeated pair moves, worked out from the pair itself.
     *
     * A repeat carries no plan entry — it may have come from another song's run
     * with another plan — so the axis is read back off the two candidates. Both
     * axes differing means a diagonal, and the sign of the two differences says
     * which diagonal.
     */
    private fun axisOf(pair: PreferenceRepeatPair): PreferenceAxis {
        val bass = pair.a.bassDb - pair.b.bassDb
        val treble = pair.a.trebleDb - pair.b.trebleDb
        return when {
            bass != 0f && treble != 0f ->
                if (bass > 0f == treble > 0f) PreferenceAxis.BOTH else PreferenceAxis.TILT
            treble != 0f -> PreferenceAxis.TREBLE
            else -> PreferenceAxis.BASS
        }
    }

    private fun agreementOf(step: Step.Compare, chosen: PreferenceCandidate?): Double {
        val before = repeatPlan
            ?.firstOrNull { it.key == setOf(step.a, step.b) }
            ?.chosen
            ?: return 0.5
        return when {
            chosen == null -> 0.5
            chosen == before -> 1.0
            else -> 0.0
        }
    }

    private fun finish(): PreferenceRunResult = PreferenceRunResult(
        candidate = estimate.quantised(),
        // No repeats means nothing was checked, and "nothing was checked" must
        // not read as "perfectly consistent". Zero, with the count beside it so
        // a caller can tell the two apart.
        consistency = if (agreements.isEmpty()) 0.0 else agreements.average(),
        repeats = agreements.size,
        trials = trials.toList(),
    )
}
