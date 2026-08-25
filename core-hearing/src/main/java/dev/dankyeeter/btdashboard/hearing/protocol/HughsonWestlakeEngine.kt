package dev.dankyeeter.btdashboard.hearing.protocol

import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Pure, deterministic state machine for the modified Hughson-Westlake protocol.
 *
 * Deliberately free of Android, audio and coroutine dependencies so it can be
 * unit-tested exhaustively: the driver ([dev.dankyeeter.btdashboard.hearing.HughsonWestlakeTestController])
 * only translates [Step.Present] into an actual tone pulse and feeds the answer
 * back through [record].
 *
 * Protocol:
 *  - ascend in [ProtocolConfig.stepUpDb] steps until the listener responds
 *  - on a response drop by [ProtocolConfig.stepDownDb] and ascend again
 *  - threshold = the lowest level with >= [ProtocolConfig.requiredHits]
 *    responses out of at most [ProtocolConfig.maxTrialsPerLevel] ascending
 *    presentations (the classic "2 of 3")
 *  - silent catch trials are interleaved to measure false positives
 *
 * Levels are digital attenuation in dBFS (<= 0, louder = closer to 0). They are
 * only meaningful relative to each other — never dB HL.
 */
class HughsonWestlakeEngine(
    private val frequenciesHz: List<Int>,
    private val config: ProtocolConfig = ProtocolConfig(),
    private val random: Random = Random.Default,
) {

    init {
        require(frequenciesHz.isNotEmpty()) { "at least one test frequency is required" }
        require(config.stepUpDb > 0 && config.stepDownDb > 0) { "steps must be positive" }
        require(config.minLevelDb < config.maxLevelDb) { "level range is inverted" }
    }

    /** What the driver should do next. */
    sealed interface Step {
        /**
         * Present a stimulus. When [catchTrial] is true the driver must stay
         * silent for a normal presentation duration — any response is a false
         * positive.
         */
        data class Present(
            val frequencyIndex: Int,
            val frequencyHz: Int,
            val levelDb: Double,
            val catchTrial: Boolean,
        ) : Step

        /** The whole frequency list is done. */
        data class Finished(val result: EngineResult) : Step
    }

    private val points = mutableListOf<ThresholdPoint>()
    private var frequencyIndex = 0
    private var frequencyState = FrequencyState(config.startLevelDb)
    private var pending: Step.Present? = null
    private var catchTrials = 0
    private var falsePositives = 0
    private var lastWasCatchTrial = false

    /** Presentations issued so far across the whole run (catch trials included). */
    var totalPresentations: Int = 0
        private set

    /**
     * @return the next step. Calling [next] twice without a [record] in between
     *   returns the same pending presentation (idempotent for retry paths).
     */
    fun next(): Step {
        pending?.let { return it }
        if (frequencyIndex >= frequenciesHz.size) {
            return Step.Finished(
                EngineResult(
                    points = points.toList(),
                    catchTrials = catchTrials,
                    falsePositives = falsePositives,
                ),
            )
        }

        val useCatchTrial = !lastWasCatchTrial &&
            catchTrials < config.maxCatchTrials &&
            random.nextDouble() < config.catchTrialProbability

        val step = Step.Present(
            frequencyIndex = frequencyIndex,
            frequencyHz = frequenciesHz[frequencyIndex],
            levelDb = frequencyState.level,
            catchTrial = useCatchTrial,
        )
        pending = step
        return step
    }

    /** Feeds the listener's answer for the presentation returned by [next]. */
    fun record(responded: Boolean) {
        val step = requireNotNull(pending) { "record() without a pending presentation" }
        pending = null
        totalPresentations++

        if (step.catchTrial) {
            catchTrials++
            lastWasCatchTrial = true
            if (responded) falsePositives++
            return
        }
        lastWasCatchTrial = false

        val state = frequencyState
        state.presentations++
        state.trialsAt(step.levelDb).let { trials ->
            trials.presentations++
            if (responded) {
                trials.responses++
                state.responses++
            }
        }

        val threshold = state.thresholdOrNull(config)
        if (threshold != null) {
            // A threshold sitting on the output floor is only an upper bound:
            // we never got to prove that the next 5 dB step down is inaudible.
            completeFrequency(threshold, converged = threshold > config.minLevelDb + EPSILON)
            return
        }

        if (state.presentations >= config.maxPresentationsPerFrequency) {
            // Ran out of patience: fall back to the lowest level that ever
            // produced a response, otherwise the current level, and flag it.
            completeFrequency(state.lowestRespondedLevel() ?: state.level, converged = false)
            return
        }

        if (responded) {
            val next = state.level - config.stepDownDb
            if (next < config.minLevelDb) {
                // Audible even at the quietest level we can produce: the true
                // threshold is at or below the floor, so it is not converged.
                if (state.trialsAt(config.minLevelDb).presentations >= config.maxTrialsPerLevel) {
                    completeFrequency(config.minLevelDb, converged = false)
                } else {
                    state.level = config.minLevelDb
                }
            } else {
                state.level = next
            }
        } else {
            val next = state.level + config.stepUpDb
            if (next > config.maxLevelDb) {
                // Inaudible at the loudest level we allow ourselves to produce.
                if (state.trialsAt(config.maxLevelDb).presentations >= config.maxTrialsPerLevel) {
                    completeFrequency(config.maxLevelDb, converged = false)
                } else {
                    state.level = config.maxLevelDb
                }
            } else {
                state.level = next
            }
        }
    }

    private fun completeFrequency(thresholdDb: Double, converged: Boolean) {
        val state = frequencyState
        points += ThresholdPoint(
            frequencyHz = frequenciesHz[frequencyIndex],
            thresholdDb = thresholdDb,
            responseCount = state.responses,
            presentationCount = state.presentations,
            converged = converged,
        )
        frequencyIndex++
        frequencyState = FrequencyState(config.startLevelDb)
    }

    private class LevelTrials {
        var presentations = 0
        var responses = 0
    }

    private class FrequencyState(var level: Double) {
        private val trials = LinkedHashMap<Int, LevelTrials>()
        var presentations = 0
        var responses = 0

        fun trialsAt(level: Double): LevelTrials = trials.getOrPut(key(level)) { LevelTrials() }

        /**
         * The lowest level that reached the 2-of-3 criterion, or null while the
         * search is still running. Levels that already used up their trial
         * budget without enough responses can never become the threshold.
         */
        fun thresholdOrNull(config: ProtocolConfig): Double? =
            trials.entries
                .filter { it.value.responses >= config.requiredHits }
                .filter { it.value.presentations <= config.maxTrialsPerLevel }
                .minByOrNull { it.key }
                ?.let { it.key / SCALE }

        fun lowestRespondedLevel(): Double? =
            trials.entries.filter { it.value.responses > 0 }.minByOrNull { it.key }
                ?.let { it.key / SCALE }

        private fun key(level: Double): Int = (level * SCALE).roundToInt()

        private companion object {
            /** Levels are multiples of 0.5 dB at worst; key them as tenths. */
            const val SCALE = 10.0
        }
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}

/** Tuning knobs of the protocol. Defaults follow the plan (5 up / 10 down, 2 of 3). */
data class ProtocolConfig(
    /** First presentation level in dBFS — comfortably audible for normal hearing. */
    val startLevelDb: Double = -45.0,
    /** Loudest level the app will ever produce. Hearing-safety ceiling. */
    val maxLevelDb: Double = -6.0,
    /**
     * Quietest level the app will present.
     *
     * Was -85 dBFS, justified as "16-bit output" - but the tone is generated
     * in float, and what is actually 16-bit is the Bluetooth link. On a
     * 16-bit link one LSB sits at about -90 dBFS, and that is the honest
     * floor: below it a sine is smaller than the smallest representable
     * sample and survives only as dither noise, so a "threshold" measured
     * there would be a measurement of the codec, not of an ear.
     *
     * The old floor was not academic. Someone who hears well runs straight
     * into it, every point comes back unconverged, and the correction is 0 dB
     * everywhere - the app then reports even hearing when what it really
     * means is "quieter than I can ask". Five decibels of honest range were
     * being left on the table.
     */
    val minLevelDb: Double = -90.0,
    val stepUpDb: Double = 5.0,
    val stepDownDb: Double = 10.0,
    val requiredHits: Int = 2,
    val maxTrialsPerLevel: Int = 3,
    val maxPresentationsPerFrequency: Int = 40,
    val catchTrialProbability: Double = 0.15,
    val maxCatchTrials: Int = 6,
    /** Above this many false positives the run is flagged as unreliable. */
    val falsePositiveWarningCount: Int = 3,
)

/** Outcome of a completed per-ear engine run. */
data class EngineResult(
    val points: List<ThresholdPoint>,
    val catchTrials: Int,
    val falsePositives: Int,
) {
    fun isUnreliable(config: ProtocolConfig = ProtocolConfig()): Boolean =
        falsePositives >= config.falsePositiveWarningCount
}
