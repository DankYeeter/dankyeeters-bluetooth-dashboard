package dev.dankyeeter.btdashboard.hearing.preference

import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * A listener with a planted true preference, used to measure whether the
 * protocol actually finds it.
 *
 * The model is the standard one for a preference judgement: utility falls off
 * with the **square** of the distance from the preferred setting, so being 1 dB
 * out barely matters and being 6 dB out matters a great deal. A linear model
 * would make the diagonals behave quite differently and would flatter the
 * protocol, because every move that helps one axis by as much as it hurts the
 * other would come out a tie.
 *
 * Three behaviours on top of that, all of which real listeners have:
 *
 *  * an **indifference band** — below a utility difference of [indifference]
 *    the answer is "no difference" rather than a guess;
 *  * **noise** — above it the better candidate is chosen with a logistic
 *    probability rather than always, so a close call can go the wrong way;
 *  * **no memory** — a repeated pair is answered afresh, which is what makes
 *    the consistency score mean anything.
 */
class SimulatedListener(
    private val truth: PreferenceCandidate,
    private val random: Random,
    /** Utility difference below which the two are called the same. */
    private val indifference: Double = 2.0,
    /** Logistic temperature. Larger is a less decisive listener. */
    private val temperature: Double = 6.0,
) {

    fun answer(step: PreferenceEngine.Step.Compare): PreferenceChoice {
        // Positive means A is the better one.
        val delta = penalty(step.b) - penalty(step.a)
        if (abs(delta) < indifference) return PreferenceChoice.NO_DIFFERENCE
        val pickA = random.nextDouble() < 1.0 / (1.0 + exp(-delta / temperature))
        return if (pickA) PreferenceChoice.A else PreferenceChoice.B
    }

    private fun penalty(candidate: PreferenceCandidate): Double {
        val bass = (candidate.bassDb - truth.bassDb).toDouble()
        val treble = (candidate.trebleDb - truth.trebleDb).toDouble()
        return bass * bass + treble * treble
    }
}

/** Drives one engine to completion against [listener]. */
fun runToCompletion(
    engine: PreferenceEngine,
    listener: SimulatedListener,
): PreferenceRunResult {
    var guard = 0
    while (true) {
        when (val step = engine.next()) {
            is PreferenceEngine.Step.Finished -> return step.result
            is PreferenceEngine.Step.Compare -> engine.record(listener.answer(step))
        }
        check(guard++ < 1_000) { "engine did not terminate" }
    }
}
