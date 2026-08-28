package dev.dankyeeter.btdashboard.hearing.preference

import kotlin.math.abs
import kotlin.random.Random

/**
 * The measuring stick for the protocol: how far a finished run, or a finished
 * pool, lands from a planted true preference.
 *
 * Kept out of the test class so several tests can quote the same numbers, and
 * so the plan comparison in `PreferenceEngineTest` is an apples-to-apples
 * measurement rather than two hand-written loops.
 */
data class ConvergenceStats(
    val samples: Int,
    val meanBassErrorDb: Double,
    val meanTrebleErrorDb: Double,
    val p90BassErrorDb: Double,
    val p90TrebleErrorDb: Double,
    val meanConsistency: Double,
) {
    val meanErrorDb: Double get() = (meanBassErrorDb + meanTrebleErrorDb) / 2.0
    val p90ErrorDb: Double get() = maxOf(p90BassErrorDb, p90TrebleErrorDb)

    override fun toString(): String =
        "n=%d  mean |bass| %.2f dB, |treble| %.2f dB  ·  p90 %.2f / %.2f dB  ·  consistency %.2f"
            .format(samples, meanBassErrorDb, meanTrebleErrorDb, p90BassErrorDb, p90TrebleErrorDb, meanConsistency)
}

object PreferenceConvergenceReport {

    /**
     * Truths spread over the whole parameter space, on a coarse grid rather
     * than at random: a random sample would mostly land in the middle, which is
     * the easy part, and the corners are where a staircase with a clamp can go
     * wrong.
     */
    val TRUTHS: List<PreferenceCandidate> = buildList {
        for (bass in listOf(-6f, -4f, -2f, 0f, 2f, 4f, 6f, 9f)) {
            for (treble in listOf(-6f, -4f, -2f, 0f, 2f, 4f, 6f)) {
                add(PreferenceCandidate(bass, treble))
            }
        }
    }

    /** One run per truth per seed, starting from flat. */
    fun singleRun(
        protocol: PreferenceProtocol,
        seeds: Int = 8,
        indifference: Double = 2.0,
        temperature: Double = 6.0,
    ): ConvergenceStats = measure(seeds) { truth, random ->
        runToCompletion(
            PreferenceEngine(config = protocol, random = random),
            SimulatedListener(truth, random, indifference, temperature),
        )
    }

    /**
     * [runsPerPool] runs per truth per seed, combined the way the app combines
     * them, with each run starting where the pool's aggregate then stood — which
     * is exactly what the driver does.
     */
    fun pooled(
        protocol: PreferenceProtocol,
        runsPerPool: Int = 3,
        seeds: Int = 8,
        indifference: Double = 2.0,
        temperature: Double = 6.0,
    ): ConvergenceStats = measure(seeds) { truth, random ->
        var pool = emptyList<PreferenceRun>()
        var last: PreferenceRunResult? = null
        repeat(runsPerPool) { index ->
            val result = runToCompletion(
                PreferenceEngine(
                    config = protocol,
                    carryOver = PreferencePool.carryOverPairs(pool),
                    startingEstimate = PreferencePool.aggregate(pool).candidate,
                    random = random,
                ),
                SimulatedListener(truth, random, indifference, temperature),
            )
            pool = PreferencePool.add(
                pool,
                PreferenceRun(
                    id = "run-$index",
                    label = "song-$index",
                    labelSource = PreferenceLabelSource.MANUAL,
                    createdAtMillis = index.toLong(),
                    candidate = result.candidate,
                    consistency = result.consistency,
                    trials = result.trials,
                ),
            )
            last = result
        }
        val aggregate = PreferencePool.aggregate(pool)
        PreferenceRunResult(
            candidate = aggregate.candidate,
            consistency = aggregate.meanConsistency,
            repeats = last?.repeats ?: 0,
            trials = emptyList(),
        )
    }

    private fun measure(
        seeds: Int,
        produce: (PreferenceCandidate, Random) -> PreferenceRunResult,
    ): ConvergenceStats {
        val bassErrors = mutableListOf<Double>()
        val trebleErrors = mutableListOf<Double>()
        val consistencies = mutableListOf<Double>()
        TRUTHS.forEachIndexed { index, truth ->
            repeat(seeds) { seed ->
                // Deterministic: the same seed pair always produces the same
                // run, so a regression in these numbers is a real change and
                // never a flake.
                val random = Random(index * 1_000L + seed)
                val result = produce(truth, random)
                bassErrors += abs(result.candidate.bassDb - truth.bassDb).toDouble()
                trebleErrors += abs(result.candidate.trebleDb - truth.trebleDb).toDouble()
                consistencies += result.consistency
            }
        }
        return ConvergenceStats(
            samples = bassErrors.size,
            meanBassErrorDb = bassErrors.average(),
            meanTrebleErrorDb = trebleErrors.average(),
            p90BassErrorDb = bassErrors.percentile(0.9),
            p90TrebleErrorDb = trebleErrors.percentile(0.9),
            meanConsistency = consistencies.average(),
        )
    }

    private fun List<Double>.percentile(fraction: Double): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val index = ((size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
