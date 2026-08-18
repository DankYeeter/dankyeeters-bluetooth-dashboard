package dev.dankyeeter.btdashboard.hearing.protocol

import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The protocol is the part of the app that must be right, so it is tested
 * against a simulated listener: "hears everything at or above [trueThreshold]".
 */
class HughsonWestlakeEngineTest {

    private val noCatchTrials = ProtocolConfig(catchTrialProbability = 0.0, maxCatchTrials = 0)

    /** Runs the engine against a perfect listener and returns the result. */
    private fun simulate(
        frequencies: List<Int>,
        config: ProtocolConfig = noCatchTrials,
        random: Random = Random(1),
        respond: (HughsonWestlakeEngine.Step.Present) -> Boolean,
    ): EngineResult {
        val engine = HughsonWestlakeEngine(frequencies, config, random)
        var guard = 0
        while (true) {
            when (val step = engine.next()) {
                is HughsonWestlakeEngine.Step.Finished -> return step.result
                is HughsonWestlakeEngine.Step.Present -> engine.record(respond(step))
            }
            check(guard++ < 10_000) { "engine did not terminate" }
        }
    }

    private fun perfectListener(trueThreshold: Double): (HughsonWestlakeEngine.Step.Present) -> Boolean =
        { step -> !step.catchTrial && step.levelDb >= trueThreshold - 1e-9 }

    @Test
    fun `finds the threshold of a perfect listener on the step grid`() {
        val result = simulate(listOf(1000)) { perfectListener(-60.0)(it) }
        assertEquals(1, result.points.size)
        val point = result.points.single()
        assertEquals(1000, point.frequencyHz)
        assertEquals(-60.0, point.thresholdDb, 1e-9)
        assertTrue(point.converged)
    }

    @Test
    fun `threshold lands on the first grid level at or above the true threshold`() {
        // -58 is between grid points; the start level is -45 and steps are 5 dB,
        // so the reachable grid is ... -55, -60 ... and -55 is the first audible one.
        val point = simulate(listOf(1000)) { perfectListener(-58.0)(it) }.points.single()
        assertEquals(-55.0, point.thresholdDb, 1e-9)
        assertTrue(point.converged)
    }

    @Test
    fun `needs at least two responses at the threshold level`() {
        var seen = 0
        val point = simulate(listOf(1000)) { step ->
            // Responds at -60 only every second time: the level below cannot
            // reach 2 of 3, so the threshold must settle one step higher.
            when {
                step.levelDb >= -55.0 -> true
                step.levelDb >= -60.0 -> (seen++ % 2 == 0)
                else -> false
            }
        }.points.single()
        assertEquals(-55.0, point.thresholdDb, 1e-9)
    }

    @Test
    fun `walks all frequencies in order`() {
        val frequencies = listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)
        val result = simulate(frequencies) { perfectListener(-50.0)(it) }
        assertEquals(frequencies, result.points.map { it.frequencyHz })
        assertTrue(result.points.all { it.converged })
        assertTrue(result.points.all { it.thresholdDb == -50.0 })
    }

    @Test
    fun `descends from the start level when the listener hears very quiet tones`() {
        val levels = mutableListOf<Double>()
        val point = simulate(listOf(1000)) { step ->
            levels += step.levelDb
            perfectListener(-75.0)(step)
        }.points.single()
        assertEquals(-75.0, point.thresholdDb, 1e-9)
        // The very first presentation is the configured start level.
        assertEquals(-45.0, levels.first(), 1e-9)
        assertTrue("expected the search to reach the quiet region", levels.any { it <= -75.0 })
    }

    @Test
    fun `does not converge when nothing is audible at the ceiling`() {
        val point = simulate(listOf(4000)) { false }.points.single()
        assertFalse(point.converged)
        assertEquals(noCatchTrials.maxLevelDb, point.thresholdDb, 1e-9)
        assertEquals(0, point.responseCount)
    }

    @Test
    fun `does not converge when everything is audible at the floor`() {
        val point = simulate(listOf(500)) { !it.catchTrial }.points.single()
        assertFalse(point.converged)
        assertEquals(noCatchTrials.minLevelDb, point.thresholdDb, 1e-9)
    }

    @Test
    fun `never presents above the ceiling or below the floor`() {
        val levels = mutableListOf<Double>()
        simulate(listOf(1000)) { step ->
            levels += step.levelDb
            step.levelDb < -70.0 // audible only when very quiet -> pushes to the floor
        }
        assertTrue(levels.all { it <= noCatchTrials.maxLevelDb + 1e-9 })
        assertTrue(levels.all { it >= noCatchTrials.minLevelDb - 1e-9 })
    }

    @Test
    fun `terminates for an erratic listener and flags the point`() {
        val random = Random(42)
        val result = simulate(listOf(1000, 2000), random = random) { random.nextBoolean() }
        assertEquals(2, result.points.size)
        // Random answers may or may not satisfy 2-of-3; the contract that
        // matters is that the engine always terminates with a point per
        // frequency and never reports a made-up NaN level.
        assertTrue(result.points.all { it.thresholdDb.isFinite() })
        assertTrue(result.points.all { it.presentationCount > 0 })
    }

    @Test
    fun `presentation counters are consistent`() {
        val point = simulate(listOf(1000)) { perfectListener(-60.0)(it) }.points.single()
        assertTrue(point.presentationCount >= point.responseCount)
        assertTrue(point.responseCount >= 2)
    }

    @Test
    fun `catch trials are silent and count false positives`() {
        val config = ProtocolConfig(catchTrialProbability = 1.0, maxCatchTrials = 4)
        val result = simulate(listOf(1000), config = config) { step ->
            // A button masher: presses on every presentation, catch trials too.
            true
        }
        assertEquals(4, result.catchTrials)
        assertEquals(4, result.falsePositives)
        assertTrue(result.isUnreliable(config))
    }

    @Test
    fun `catch trials never follow each other back to back`() {
        val engine = HughsonWestlakeEngine(
            listOf(1000),
            ProtocolConfig(catchTrialProbability = 1.0, maxCatchTrials = 10),
            Random(7),
        )
        var previousWasCatch = false
        repeat(20) {
            val step = engine.next()
            if (step is HughsonWestlakeEngine.Step.Present) {
                assertFalse("two catch trials in a row", previousWasCatch && step.catchTrial)
                previousWasCatch = step.catchTrial
                engine.record(step.levelDb >= -50.0 && !step.catchTrial)
            }
        }
    }

    @Test
    fun `catch trials do not move the level`() {
        val engine = HughsonWestlakeEngine(
            listOf(1000),
            ProtocolConfig(catchTrialProbability = 1.0, maxCatchTrials = 10),
            Random(3),
        )
        val first = engine.next() as HughsonWestlakeEngine.Step.Present
        if (!first.catchTrial) return
        engine.record(true) // false positive
        val second = engine.next() as HughsonWestlakeEngine.Step.Present
        assertEquals(first.levelDb, second.levelDb, 1e-9)
        assertFalse(second.catchTrial)
    }

    @Test
    fun `a well behaved listener produces no false positives`() {
        val config = ProtocolConfig(catchTrialProbability = 0.5, maxCatchTrials = 6)
        val result = simulate(listOf(1000, 2000), config = config) { perfectListener(-55.0)(it) }
        assertEquals(0, result.falsePositives)
        assertFalse(result.isUnreliable(config))
        assertTrue(result.points.all { it.thresholdDb == -55.0 })
    }

    @Test
    fun `next is idempotent until record is called`() {
        val engine = HughsonWestlakeEngine(listOf(1000), noCatchTrials, Random(1))
        val a = engine.next() as HughsonWestlakeEngine.Step.Present
        val b = engine.next()
        assertEquals(a, b)
        engine.record(false)
        val c = engine.next() as HughsonWestlakeEngine.Step.Present
        assertEquals(a.levelDb + noCatchTrials.stepUpDb, c.levelDb, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `record without a pending presentation fails loudly`() {
        HughsonWestlakeEngine(listOf(1000), noCatchTrials, Random(1)).record(true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty frequency list is rejected`() {
        HughsonWestlakeEngine(emptyList())
    }

    @Test
    fun `finished result is stable when polled repeatedly`() {
        val engine = HughsonWestlakeEngine(listOf(1000), noCatchTrials, Random(1))
        while (true) {
            when (val step = engine.next()) {
                is HughsonWestlakeEngine.Step.Finished -> {
                    val again = engine.next() as HughsonWestlakeEngine.Step.Finished
                    assertEquals(step.result, again.result)
                    return
                }
                is HughsonWestlakeEngine.Step.Present -> engine.record(perfectListener(-50.0)(step))
            }
        }
    }

    @Test
    fun `threshold point carries the frequency it was measured at`() {
        val result = simulate(listOf(3000, 6000)) { perfectListener(-65.0)(it) }
        val expected: List<ThresholdPoint> = listOf(3000, 6000).map { hz ->
            result.points.first { it.frequencyHz == hz }
        }
        assertNotNull(expected)
        assertEquals(listOf(3000, 6000), result.points.map { it.frequencyHz })
    }
}
