package dev.dankyeeter.btdashboard.hearing.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The protocol is the part of this feature that must be right, so it is measured
 * against a listener whose preference is known ([SimulatedListener]) rather than
 * only asserted about.
 */
class PreferenceEngineTest {

    private val protocol = PreferenceProtocol()
    private val leadIn = protocol.searchPlan.filter { it.phase == TrialPhase.LEAD_IN }
    private val refine = protocol.searchPlan.filter { it.phase == TrialPhase.REFINE }

    // ---- shape ---------------------------------------------------------------

    @Test
    fun `a run is ten comparisons`() {
        assertEquals(10, protocol.trialsPerRun)
        assertEquals(8, protocol.searchPlan.size)
        assertEquals(2, protocol.repeatTrials)
    }

    @Test
    fun `the plan is four lead-in, four refine, and both diagonals`() {
        val leadIn = protocol.searchPlan.filter { it.phase == TrialPhase.LEAD_IN }
        val refine = protocol.searchPlan.filter { it.phase == TrialPhase.REFINE }
        assertEquals(4, leadIn.size)
        assertEquals(4, refine.size)
        assertTrue(refine.any { it.axis == PreferenceAxis.BOTH })
        assertTrue(refine.any { it.axis == PreferenceAxis.TILT })
        // Both axes get a halving staircase, and neither is only asked once.
        assertEquals(3, protocol.searchPlan.count { it.axis == PreferenceAxis.BASS })
        assertEquals(3, protocol.searchPlan.count { it.axis == PreferenceAxis.TREBLE })
    }

    @Test
    fun `every comparison offers two different curves`() {
        val engine = PreferenceEngine(random = Random(1))
        val listener = SimulatedListener(PreferenceCandidate(9f, -6f), Random(1))
        var guard = 0
        while (true) {
            when (val step = engine.next()) {
                is PreferenceEngine.Step.Finished -> return
                is PreferenceEngine.Step.Compare -> {
                    assertNotEquals("identical candidates at ${step.index}", step.a, step.b)
                    engine.record(listener.answer(step))
                }
            }
            check(guard++ < 100)
        }
    }

    @Test
    fun `next is idempotent until record is called`() {
        val engine = PreferenceEngine(random = Random(4))
        val first = engine.next() as PreferenceEngine.Step.Compare
        assertEquals(first, engine.next())
        engine.record(PreferenceChoice.A)
        assertNotEquals(first, engine.next())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `record without a pending comparison fails loudly`() {
        PreferenceEngine(random = Random(1)).record(PreferenceChoice.A)
    }

    @Test
    fun `the finished result is stable when polled repeatedly`() {
        val engine = PreferenceEngine(random = Random(9))
        val listener = SimulatedListener(PreferenceCandidate(4f, -2f), Random(9))
        runToCompletion(engine, listener)
        val a = engine.next() as PreferenceEngine.Step.Finished
        val b = engine.next() as PreferenceEngine.Step.Finished
        assertEquals(a.result, b.result)
    }

    @Test
    fun `which side is A is not fixed`() {
        // Same deterministic listener, many runs: if the louder candidate were
        // always slot A, a listener who always answers A would converge to the
        // top corner every time. The slot assignment is a coin, so it does not.
        val sides = (1..40).map { seed ->
            val engine = PreferenceEngine(random = Random(seed.toLong()))
            val step = engine.next() as PreferenceEngine.Step.Compare
            step.a.bassDb > step.b.bassDb
        }
        assertTrue("A was always the higher candidate", sides.any { it })
        assertTrue("A was never the higher candidate", sides.any { !it })
    }

    // ---- convergence ---------------------------------------------------------

    @Test
    fun `one run converges on a planted preference`() {
        val stats = PreferenceConvergenceReport.singleRun(protocol)
        println("preference: single 10-trial run   $stats")
        // Measured 0.51 / 0.63 dB at the time of writing; the bounds leave room
        // for a protocol tweak but not for a regression.
        assertTrue(
            "single-run mean error too large: $stats",
            stats.meanBassErrorDb < 0.9 && stats.meanTrebleErrorDb < 0.9,
        )
        assertTrue("single-run p90 too large: $stats", stats.p90ErrorDb <= 2.5)
    }

    @Test
    fun `a pool of three songs beats a single run`() {
        val single = PreferenceConvergenceReport.singleRun(protocol)
        val pooled = PreferenceConvergenceReport.pooled(protocol, runsPerPool = 3)
        println("preference: pool of three songs   $pooled")
        assertTrue(
            "pooling three runs did not improve on one: single=$single pooled=$pooled",
            pooled.meanErrorDb < single.meanErrorDb,
        )
    }

    @Test
    fun `five songs beat three`() {
        val three = PreferenceConvergenceReport.pooled(protocol, runsPerPool = 3)
        val five = PreferenceConvergenceReport.pooled(protocol, runsPerPool = 5)
        println("preference: pool of five songs    $five")
        assertTrue("more songs made it worse: three=$three five=$five", five.meanErrorDb <= three.meanErrorDb)
    }

    /**
     * The ordering claim [PreferenceProtocol] makes in prose, measured.
     *
     * A diagonal answered while one axis is already right trades that axis away
     * to fix the other, so the per-axis polish has to come last. Putting it
     * first instead is the obvious alternative plan, and it is worse.
     */
    @Test
    fun `diagonals before the per-axis polish, not after`() {
        val shipped = PreferenceConvergenceReport.singleRun(protocol)
        val reversed = PreferenceConvergenceReport.singleRun(
            protocol.copy(searchPlan = leadIn + refine.reversed()),
        )
        println("preference: polish-first variant  $reversed")
        assertTrue(
            "the shipped order should win: shipped=$shipped reversed=$reversed",
            shipped.meanErrorDb < reversed.meanErrorDb,
        )
    }

    /**
     * The other half of the ordering claim: the diagonals are small on purpose.
     * Doubling their step lets each one trade away more of an axis than the
     * per-axis trials behind it can win back.
     */
    @Test
    fun `the diagonals are kept small`() {
        val shipped = PreferenceConvergenceReport.singleRun(protocol)
        val wide = PreferenceConvergenceReport.singleRun(
            protocol.copy(
                searchPlan = leadIn + refine.map {
                    if (it.axis == PreferenceAxis.BOTH || it.axis == PreferenceAxis.TILT) {
                        it.copy(stepDb = it.stepDb * 2f)
                    } else {
                        it
                    }
                },
            ),
        )
        println("preference: wide-diagonal variant $wide")
        assertTrue(
            "wider diagonals should be worse: shipped=$shipped wide=$wide",
            shipped.meanErrorDb < wide.meanErrorDb,
        )
    }

    /** The extra four trials over a six-trial run have to buy something. */
    @Test
    fun `ten trials beat six`() {
        val six = PreferenceConvergenceReport.singleRun(protocol.copy(searchPlan = leadIn))
        val ten = PreferenceConvergenceReport.singleRun(protocol)
        println("preference: six-trial variant     $six")
        assertTrue("the four extra trials bought nothing: six=$six ten=$ten", ten.meanErrorDb < six.meanErrorDb)
    }

    @Test
    fun `it finds the corners of the parameter space`() {
        listOf(
            PreferenceCandidate(9f, 6f),
            PreferenceCandidate(-6f, -6f),
            PreferenceCandidate(9f, -6f),
            PreferenceCandidate(-6f, 6f),
        ).forEach { truth ->
            val random = Random(17)
            val result = runToCompletion(
                PreferenceEngine(random = random),
                // A decisive listener: the question here is whether the
                // staircase can reach a clamped corner at all, not how it
                // behaves under noise.
                SimulatedListener(truth, random, indifference = 0.5, temperature = 0.5),
            )
            assertEquals(
                "bass at $truth",
                truth.bassDb.toDouble(),
                result.candidate.bassDb.toDouble(),
                1.6,
            )
            assertEquals(
                "treble at $truth",
                truth.trebleDb.toDouble(),
                result.candidate.trebleDb.toDouble(),
                1.6,
            )
        }
    }

    // ---- no difference -------------------------------------------------------

    @Test
    fun `a listener who never hears a difference ends flat`() {
        val engine = PreferenceEngine(random = Random(2))
        var guard = 0
        val result = run {
            while (true) {
                when (val step = engine.next()) {
                    is PreferenceEngine.Step.Finished -> return@run step.result
                    is PreferenceEngine.Step.Compare -> engine.record(PreferenceChoice.NO_DIFFERENCE)
                }
                check(guard++ < 100)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        assertEquals(PreferenceCandidate.NEUTRAL, result.candidate)
        // Nothing was answered decisively, so there was nothing worth repeating.
        assertEquals(0, result.repeats)
        assertEquals(0.0, result.consistency, 1e-9)
        // The run still ends: the search trials all happen, the validation ones
        // simply have no material.
        assertEquals(protocol.searchPlan.size, result.trials.size)
    }

    @Test
    fun `a listener with no opinion about treble still gets a bass answer`() {
        val random = Random(5)
        val result = runToCompletion(
            PreferenceEngine(random = random),
            // Treble is weightless in this listener's utility, which makes every
            // pure-treble comparison land inside the indifference band.
            SimulatedListener(PreferenceCandidate(6f, 0f), random, indifference = 2.0, temperature = 1.0),
        )
        assertEquals(6.0, result.candidate.bassDb.toDouble(), 2.0)
    }

    // ---- consistency ---------------------------------------------------------

    @Test
    fun `a steady listener scores full consistency`() {
        val random = Random(11)
        val result = runToCompletion(
            PreferenceEngine(random = random),
            SimulatedListener(PreferenceCandidate(6f, -4f), random, indifference = 0.5, temperature = 0.2),
        )
        assertEquals(2, result.repeats)
        assertEquals(1.0, result.consistency, 1e-9)
    }

    @Test
    fun `a listener who contradicts every repeat scores zero`() {
        val engine = PreferenceEngine(random = Random(13))
        val seen = mutableMapOf<Set<PreferenceCandidate>, PreferenceChoice>()
        var guard = 0
        val result = run {
            while (true) {
                when (val step = engine.next()) {
                    is PreferenceEngine.Step.Finished -> return@run step.result
                    is PreferenceEngine.Step.Compare -> {
                        val key = setOf(step.a, step.b)
                        val earlier = seen[key]
                        val choice = if (earlier == null) {
                            // First time: always take the louder candidate, so
                            // every search trial is decisive and repeatable.
                            if (step.a.bassDb + step.a.trebleDb > step.b.bassDb + step.b.trebleDb) {
                                PreferenceChoice.A
                            } else {
                                PreferenceChoice.B
                            }
                        } else {
                            // Second time: pick the other curve, whichever slot
                            // it landed in.
                            val previous = if (earlier == PreferenceChoice.A) step.b else step.a
                            if (previous == step.a) PreferenceChoice.A else PreferenceChoice.B
                        }
                        seen.getOrPut(key) { choice }
                        engine.record(choice)
                    }
                }
                check(guard++ < 100)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        assertEquals(2, result.repeats)
        assertEquals(0.0, result.consistency, 1e-9)
    }

    @Test
    fun `a no-difference answer to a repeat is half agreement, not a contradiction`() {
        val engine = PreferenceEngine(random = Random(19))
        var guard = 0
        val result = run {
            while (true) {
                when (val step = engine.next()) {
                    is PreferenceEngine.Step.Finished -> return@run step.result
                    is PreferenceEngine.Step.Compare -> engine.record(
                        if (step.repeat) PreferenceChoice.NO_DIFFERENCE else PreferenceChoice.A,
                    )
                }
                check(guard++ < 100)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        assertEquals(2, result.repeats)
        assertEquals(0.5, result.consistency, 1e-9)
    }

    @Test
    fun `repeats never move the estimate`() {
        val engine = PreferenceEngine(random = Random(23))
        var beforeValidation: PreferenceCandidate? = null
        var guard = 0
        while (true) {
            when (val step = engine.next()) {
                is PreferenceEngine.Step.Finished -> break
                is PreferenceEngine.Step.Compare -> {
                    if (step.repeat && beforeValidation == null) {
                        beforeValidation = engine.currentEstimate
                    }
                    engine.record(PreferenceChoice.A)
                }
            }
            check(guard++ < 100)
        }
        assertEquals(beforeValidation, engine.currentEstimate)
    }

    // ---- carry-over ----------------------------------------------------------

    @Test
    fun `a pair carried over from another song is asked again`() {
        val borrowed = PreferenceRepeatPair(
            a = PreferenceCandidate(7f, 1f),
            b = PreferenceCandidate(-5f, 1f),
            chosen = PreferenceCandidate(7f, 1f),
        )
        val engine = PreferenceEngine(carryOver = listOf(borrowed), random = Random(29))
        val repeats = mutableListOf<Set<PreferenceCandidate>>()
        var guard = 0
        while (true) {
            when (val step = engine.next()) {
                is PreferenceEngine.Step.Finished -> break
                is PreferenceEngine.Step.Compare -> {
                    if (step.repeat) repeats += setOf(step.a, step.b)
                    engine.record(PreferenceChoice.A)
                }
            }
            check(guard++ < 100)
        }
        assertTrue("the borrowed pair was not re-asked: $repeats", repeats.contains(borrowed.key))
        // And only one is borrowed, so the run still checks itself as well.
        assertEquals(2, repeats.size)
        assertEquals(1, repeats.count { it == borrowed.key })
    }

    @Test
    fun `a carried-over pair the listener shrugged at is not re-asked`() {
        val indecisive = PreferenceRepeatPair(
            a = PreferenceCandidate(3f, 0f),
            b = PreferenceCandidate(-3f, 0f),
            chosen = null,
        )
        val engine = PreferenceEngine(carryOver = listOf(indecisive), random = Random(31))
        val repeats = mutableListOf<Set<PreferenceCandidate>>()
        var guard = 0
        while (true) {
            when (val step = engine.next()) {
                is PreferenceEngine.Step.Finished -> break
                is PreferenceEngine.Step.Compare -> {
                    if (step.repeat) repeats += setOf(step.a, step.b)
                    engine.record(PreferenceChoice.A)
                }
            }
            check(guard++ < 100)
        }
        assertTrue(repeats.none { it == indecisive.key })
    }

    @Test
    fun `a run starting from the pool's answer stays near it when nothing changes`() {
        val start = PreferenceCandidate(4f, -2f)
        val random = Random(37)
        val result = runToCompletion(
            PreferenceEngine(startingEstimate = start, random = random),
            SimulatedListener(start, random, indifference = 2.0, temperature = 1.0),
        )
        assertEquals(start.bassDb.toDouble(), result.candidate.bassDb.toDouble(), 2.0)
        assertEquals(start.trebleDb.toDouble(), result.candidate.trebleDb.toDouble(), 2.0)
    }

    @Test
    fun `the result never leaves the parameter space`() {
        PreferenceConvergenceReport.TRUTHS.forEach { truth ->
            val random = Random(truth.hashCode().toLong())
            val result = runToCompletion(
                PreferenceEngine(random = random),
                SimulatedListener(truth, random, indifference = 0.1, temperature = 0.1),
            )
            assertEquals(result.candidate, result.candidate.clamped())
            assertEquals(result.candidate, result.candidate.quantised())
        }
    }
}
