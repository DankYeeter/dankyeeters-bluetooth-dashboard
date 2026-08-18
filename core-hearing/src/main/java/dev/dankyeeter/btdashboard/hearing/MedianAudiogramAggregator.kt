package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear

/**
 * Per-frequency **median** across runs, as specified in PLAN.md. The median is
 * chosen over the mean on purpose: a single distracted or badly seated run
 * should not drag the active curve, and the user can still delete outliers by
 * hand.
 *
 * A frequency is only included if at least one run measured it. A point counts
 * as converged when the majority of contributing points converged.
 */
class MedianAudiogramAggregator : AudiogramAggregator {

    override fun aggregate(runs: List<AudiogramRun>): Audiogram = Audiogram(
        runIds = runs.map { it.id },
        left = aggregateEar(runs, Ear.LEFT),
        right = aggregateEar(runs, Ear.RIGHT),
    )

    private fun aggregateEar(runs: List<AudiogramRun>, ear: Ear): List<ThresholdPoint> {
        val byFrequency = runs.flatMap { it.points(ear) }.groupBy { it.frequencyHz }
        return byFrequency.entries
            .sortedBy { it.key }
            .map { (frequencyHz, points) ->
                ThresholdPoint(
                    frequencyHz = frequencyHz,
                    thresholdDb = median(points.map { it.thresholdDb }),
                    responseCount = points.sumOf { it.responseCount },
                    presentationCount = points.sumOf { it.presentationCount },
                    converged = points.count { it.converged } * 2 >= points.size,
                )
            }
    }

    companion object {
        /** Even sample counts average the two middle values. */
        fun median(values: List<Double>): Double {
            require(values.isNotEmpty()) { "median of an empty list" }
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
