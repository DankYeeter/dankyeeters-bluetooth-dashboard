package dev.dankyeeter.btdashboard.hearing.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that turns a pool of song-runs into the curve somebody listens to.
 *
 * This is the part of the feature with the most leverage per line: a wrong
 * combination rule produces a plausible number that nobody can tell is wrong by
 * looking at it.
 */
class PreferencePoolTest {

    private var nextId = 0

    private fun run(
        bass: Float,
        treble: Float = 0f,
        consistency: Double = 1.0,
        label: String = "song-${nextId}",
        source: PreferenceLabelSource = PreferenceLabelSource.TRACK,
        at: Long = (nextId++).toLong(),
    ) = PreferenceRun(
        id = "id-$at-$label",
        label = label,
        labelSource = source,
        createdAtMillis = at,
        candidate = PreferenceCandidate(bass, treble),
        consistency = consistency,
    )

    // ---- the median --------------------------------------------------------

    @Test
    fun `one song is its own answer`() {
        val aggregate = PreferencePool.aggregate(listOf(run(4f, -2f)))
        assertEquals(PreferenceCandidate(4f, -2f), aggregate.candidate)
        assertEquals(1, aggregate.runCount)
    }

    @Test
    fun `three songs give the middle one`() {
        val aggregate = PreferencePool.aggregate(listOf(run(1f), run(5f), run(3f)))
        assertEquals(3f, aggregate.candidate.bassDb)
    }

    /**
     * The reason for a median rather than a mean, in one test. One song
     * mastered with a heavy low end comes back several decibels low; a mean
     * would carry that error into the answer in full, and the listener would
     * live with it.
     */
    @Test
    fun `one wild song does not move the answer`() {
        val sane = listOf(run(4f), run(5f), run(4f), run(5f))
        val withOutlier = sane + run(-6f)
        val before = PreferencePool.aggregate(sane).candidate.bassDb
        val after = PreferencePool.aggregate(withOutlier).candidate.bassDb
        assertTrue("the outlier moved the answer by ${after - before} dB", kotlin.math.abs(after - before) <= 0.5f)
        // The mean over the same data would have moved by nearly two decibels.
        val mean = withOutlier.map { it.candidate.bassDb }.average()
        assertTrue("the mean should be visibly worse here", kotlin.math.abs(mean - 4.5) > 1.5)
    }

    @Test
    fun `an even pool takes the midpoint of the middle two`() {
        val aggregate = PreferencePool.aggregate(listOf(run(2f), run(4f)))
        assertEquals(3f, aggregate.candidate.bassDb)
    }

    @Test
    fun `the two axes are medianed separately`() {
        // Bass says 2, 4, 6 and treble says 6, 4, 2 in the opposite order. A
        // rule that picked "the middle run" would return whichever run happened
        // to be middle on one axis.
        val aggregate = PreferencePool.aggregate(
            listOf(run(2f, 6f), run(4f, 4f), run(6f, 2f)),
        )
        assertEquals(4f, aggregate.candidate.bassDb)
        assertEquals(4f, aggregate.candidate.trebleDb)
    }

    // ---- the weighting -----------------------------------------------------

    @Test
    fun `a run the listener contradicted themselves in does not vote`() {
        val aggregate = PreferencePool.aggregate(
            listOf(run(4f, consistency = 1.0), run(5f, consistency = 1.0), run(-6f, consistency = 0.0)),
        )
        // With the zero-weight run dropped the remaining two straddle 4.5.
        assertEquals(4.5f, aggregate.candidate.bassDb)
    }

    @Test
    fun `a steadier run pulls harder than a wobbly one`() {
        val weighted = PreferencePool.aggregate(
            listOf(
                run(2f, consistency = 0.25),
                run(6f, consistency = 1.0),
                run(7f, consistency = 1.0),
            ),
        ).candidate.bassDb
        val unweighted = PreferencePool.aggregate(
            listOf(run(2f), run(6f), run(7f)),
        ).candidate.bassDb
        assertEquals(6f, unweighted)
        assertTrue("weighting should not drag it below the plain median", weighted >= unweighted)
    }

    @Test
    fun `when nothing is consistent the plain median still answers`() {
        val aggregate = PreferencePool.aggregate(
            listOf(run(2f, consistency = 0.0), run(4f, consistency = 0.0), run(9f, consistency = 0.0)),
        )
        assertEquals(4f, aggregate.candidate.bassDb)
        assertEquals(3, aggregate.runCount)
    }

    @Test
    fun `the weighted median is the plain median when the weights are equal`() {
        listOf(
            listOf(1f),
            listOf(1f, 3f),
            listOf(1f, 3f, 9f),
            listOf(-6f, -2f, 2f, 6f),
            listOf(0f, 1f, 2f, 3f, 4f),
        ).forEach { values ->
            val weighted = PreferencePool.weightedMedian(values, List(values.size) { 1.0 })
            val sorted = values.sorted()
            val plain = if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
            }
            assertEquals("median of $values", plain, weighted)
        }
    }

    // ---- pool management ---------------------------------------------------

    @Test
    fun `re-running the same song replaces its old answer`() {
        val first = run(2f, label = "Blue Monday", at = 1)
        val second = run(6f, label = "blue monday ", at = 2)
        val pool = PreferencePool.add(PreferencePool.add(emptyList(), first), second)
        assertEquals(1, pool.size)
        assertEquals(6f, pool.single().candidate.bassDb)
    }

    @Test
    fun `two songs with no readable label are two songs`() {
        val a = run(2f, label = "", source = PreferenceLabelSource.NONE, at = 1)
        val b = run(6f, label = "", source = PreferenceLabelSource.NONE, at = 2)
        val pool = PreferencePool.add(PreferencePool.add(emptyList(), a), b)
        assertEquals(2, pool.size)
    }

    @Test
    fun `the pool holds ten songs and drops the oldest`() {
        var pool = emptyList<PreferenceRun>()
        repeat(PreferencePool.MAX_RUNS + 4) { index ->
            pool = PreferencePool.add(pool, run(index.toFloat() % 9f, label = "song-$index", at = index.toLong()))
        }
        assertEquals(PreferencePool.MAX_RUNS, pool.size)
        assertEquals("song-4", pool.first().label)
        assertEquals("song-13", pool.last().label)
    }

    @Test
    fun `removing a song takes it out of the answer`() {
        val odd = run(-6f, label = "odd", at = 9)
        val pool = listOf(run(4f), run(4f), odd)
        val without = PreferencePool.remove(pool, odd.id)
        assertEquals(2, without.size)
        assertEquals(4f, PreferencePool.aggregate(without).candidate.bassDb)
    }

    // ---- spread and verdicts -----------------------------------------------

    @Test
    fun `songs that agree report no spread`() {
        val aggregate = PreferencePool.aggregate(listOf(run(4f, 2f), run(4f, 2f), run(4f, 2f)))
        assertEquals(0f, aggregate.bassSpreadDb)
        assertEquals(0f, aggregate.trebleSpreadDb)
        assertFalse(aggregate.varied)
        assertEquals(PreferenceVerdict.CONSISTENT, aggregate.verdict)
    }

    @Test
    fun `songs that disagree say so, and the median is still applied`() {
        val aggregate = PreferencePool.aggregate(listOf(run(-2f), run(4f), run(9f)))
        assertEquals(11f, aggregate.bassSpreadDb)
        assertTrue(aggregate.varied)
        assertEquals(PreferenceVerdict.VARIED, aggregate.verdict)
        assertEquals(4f, aggregate.candidate.bassDb)
    }

    @Test
    fun `spread counts every song, including ones that do not vote`() {
        val aggregate = PreferencePool.aggregate(
            listOf(run(4f), run(4f), run(-6f, consistency = 0.0)),
        )
        assertEquals(4f, aggregate.candidate.bassDb)
        assertEquals(10f, aggregate.bassSpreadDb)
    }

    @Test
    fun `a wobbly pool is reported as mixed`() {
        val aggregate = PreferencePool.aggregate(
            listOf(run(4f, consistency = 0.5), run(4f, consistency = 0.5), run(4f, consistency = 0.5)),
        )
        assertEquals(PreferenceVerdict.MIXED, aggregate.verdict)
    }

    @Test
    fun `no preference is a real answer, not a weak one`() {
        val aggregate = PreferencePool.aggregate(
            listOf(run(0.5f, -0.5f), run(0f), run(-0.5f, 0.5f)),
            finalCheck = FinalCheck.FLAT_WON,
        )
        assertTrue(aggregate.neutral)
        assertEquals(PreferenceVerdict.NEUTRAL, aggregate.verdict)
    }

    @Test
    fun `a curve that loses blind against flat is reported as weak`() {
        val aggregate = PreferencePool.aggregate(
            listOf(run(6f), run(6f), run(6f)),
            finalCheck = FinalCheck.FLAT_WON,
        )
        assertEquals(PreferenceVerdict.WEAK, aggregate.verdict)
    }

    @Test
    fun `an empty pool says nothing at all`() {
        val aggregate = PreferencePool.aggregate(emptyList())
        assertEquals(PreferenceVerdict.NONE, aggregate.verdict)
        assertEquals(PreferenceCandidate.NEUTRAL, aggregate.candidate)
        assertFalse(aggregate.thin)
    }

    @Test
    fun `one or two songs are thin, three are not`() {
        assertTrue(PreferencePool.aggregate(listOf(run(4f))).thin)
        assertTrue(PreferencePool.aggregate(listOf(run(4f), run(4f))).thin)
        assertFalse(PreferencePool.aggregate(listOf(run(4f), run(4f), run(4f))).thin)
    }

    @Test
    fun `the answer never leaves the parameter space`() {
        val aggregate = PreferencePool.aggregate(listOf(run(9f, 6f), run(9f, 6f), run(9f, 6f)))
        assertEquals(aggregate.candidate, aggregate.candidate.clamped())
    }

    // ---- carry-over --------------------------------------------------------

    @Test
    fun `carry-over pairs come newest first and skip the shrugs`() {
        val decisive = PreferenceTrial(
            index = 0,
            phase = TrialPhase.LEAD_IN,
            axis = PreferenceAxis.BASS,
            a = PreferenceCandidate(6f, 0f),
            b = PreferenceCandidate(-6f, 0f),
            choice = PreferenceChoice.A,
        )
        val shrug = decisive.copy(
            index = 1,
            a = PreferenceCandidate(3f, 0f),
            b = PreferenceCandidate(-3f, 0f),
            choice = PreferenceChoice.NO_DIFFERENCE,
        )
        val newer = decisive.copy(
            index = 2,
            a = PreferenceCandidate(0f, 6f),
            b = PreferenceCandidate(0f, -6f),
            choice = PreferenceChoice.B,
        )
        val pool = listOf(
            run(4f, at = 1).copy(trials = listOf(decisive, shrug)),
            run(4f, at = 2).copy(trials = listOf(newer)),
        )
        val pairs = PreferencePool.carryOverPairs(pool)
        assertEquals(2, pairs.size)
        assertEquals(setOf(newer.a, newer.b), pairs.first().key)
        assertTrue(pairs.none { it.key == setOf(shrug.a, shrug.b) })
    }

    @Test
    fun `a repeated trial is never offered as a carry-over pair`() {
        val repeat = PreferenceTrial(
            index = 8,
            phase = TrialPhase.VALIDATE,
            axis = PreferenceAxis.BASS,
            a = PreferenceCandidate(6f, 0f),
            b = PreferenceCandidate(-6f, 0f),
            choice = PreferenceChoice.A,
            repeat = true,
        )
        assertTrue(PreferencePool.carryOverPairs(listOf(run(4f).copy(trials = listOf(repeat)))).isEmpty())
    }
}
