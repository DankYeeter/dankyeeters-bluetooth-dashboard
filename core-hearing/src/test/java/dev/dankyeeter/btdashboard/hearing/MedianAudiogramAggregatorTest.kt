package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MedianAudiogramAggregatorTest {

    private val aggregator = MedianAudiogramAggregator()

    private fun run(id: String, left: Map<Int, Double>, right: Map<Int, Double> = left) = AudiogramRun(
        id = id,
        timestampMillis = 0,
        deviceAddressHash = null,
        calibrationPresetId = "generic_uncalibrated",
        ancMode = AncMode.UNKNOWN,
        ambientNoiseDbA = null,
        left = left.map { ThresholdPoint(it.key, it.value, responseCount = 2, presentationCount = 5) },
        right = right.map { ThresholdPoint(it.key, it.value, responseCount = 2, presentationCount = 5) },
    )

    @Test
    fun `median of three runs ignores a single outlier`() {
        val runs = listOf(
            run("a", mapOf(1000 to -60.0)),
            run("b", mapOf(1000 to -55.0)),
            run("c", mapOf(1000 to -20.0)), // distracted run
        )
        val audiogram = aggregator.aggregate(runs)
        assertEquals(-55.0, audiogram.points(Ear.LEFT).single().thresholdDb, 1e-9)
        assertEquals(listOf("a", "b", "c"), audiogram.runIds)
    }

    @Test
    fun `even run counts average the two middle values`() {
        val runs = listOf(
            run("a", mapOf(1000 to -60.0)),
            run("b", mapOf(1000 to -50.0)),
        )
        assertEquals(-55.0, aggregator.aggregate(runs).points(Ear.LEFT).single().thresholdDb, 1e-9)
    }

    @Test
    fun `ears are aggregated independently`() {
        val runs = listOf(
            run("a", left = mapOf(1000 to -60.0), right = mapOf(1000 to -30.0)),
            run("b", left = mapOf(1000 to -62.0), right = mapOf(1000 to -34.0)),
            run("c", left = mapOf(1000 to -58.0), right = mapOf(1000 to -32.0)),
        )
        val audiogram = aggregator.aggregate(runs)
        assertEquals(-60.0, audiogram.points(Ear.LEFT).single().thresholdDb, 1e-9)
        assertEquals(-32.0, audiogram.points(Ear.RIGHT).single().thresholdDb, 1e-9)
    }

    @Test
    fun `frequencies come out sorted and cover the union of all runs`() {
        val runs = listOf(
            run("a", mapOf(8000 to -40.0, 250 to -55.0)),
            run("b", mapOf(1000 to -50.0, 250 to -57.0)),
        )
        assertEquals(listOf(250, 1000, 8000), aggregator.aggregate(runs).points(Ear.LEFT).map { it.frequencyHz })
    }

    @Test
    fun `a frequency measured by only one run keeps that value`() {
        val runs = listOf(run("a", mapOf(6000 to -42.0)), run("b", mapOf(1000 to -50.0)))
        val point = aggregator.aggregate(runs).points(Ear.LEFT).first { it.frequencyHz == 6000 }
        assertEquals(-42.0, point.thresholdDb, 1e-9)
    }

    @Test
    fun `convergence is decided by the majority of contributing points`() {
        val flaky = AudiogramRun(
            id = "c", timestampMillis = 0, deviceAddressHash = null,
            calibrationPresetId = "x", ancMode = AncMode.UNKNOWN, ambientNoiseDbA = null,
            left = listOf(ThresholdPoint(1000, -50.0, converged = false)),
            right = emptyList(),
        )
        val majorityGood = aggregator.aggregate(
            listOf(run("a", mapOf(1000 to -55.0)), run("b", mapOf(1000 to -52.0)), flaky),
        )
        assertTrue(majorityGood.points(Ear.LEFT).single().converged)

        val majorityBad = aggregator.aggregate(listOf(flaky, flaky.copy(id = "d")))
        assertFalse(majorityBad.points(Ear.LEFT).single().converged)
    }

    @Test
    fun `counters are summed so the UI can show the effort behind a point`() {
        val point = aggregator.aggregate(
            listOf(run("a", mapOf(1000 to -50.0)), run("b", mapOf(1000 to -50.0))),
        ).points(Ear.LEFT).single()
        assertEquals(4, point.responseCount)
        assertEquals(10, point.presentationCount)
    }

    @Test
    fun `an empty run list yields an empty audiogram`() {
        val audiogram = aggregator.aggregate(emptyList())
        assertTrue(audiogram.left.isEmpty() && audiogram.right.isEmpty())
    }

    @Test
    fun `median helper handles odd and even sizes`() {
        assertEquals(2.0, MedianAudiogramAggregator.median(listOf(3.0, 1.0, 2.0)), 1e-9)
        assertEquals(2.5, MedianAudiogramAggregator.median(listOf(4.0, 1.0, 2.0, 3.0)), 1e-9)
    }
}
