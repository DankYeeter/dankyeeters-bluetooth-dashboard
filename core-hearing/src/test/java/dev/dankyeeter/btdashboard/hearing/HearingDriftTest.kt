package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.hearing.level.VolumeGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The drift check, and above all the two things it must never do: flag noise,
 * and compare runs that are not comparable.
 *
 * The rules are pinned twice over. The example tests below fix each condition
 * on its own — a shift that is big enough but not sustained, one that is
 * sustained but at scattered frequencies, runs from two headphones, runs at two
 * volumes. The property tests then hammer the whole rule with pseudo-random
 * data, because the failure that matters here is statistical rather than
 * logical: a rule can satisfy every example above and still fire on one trial
 * in twenty, and one trial in twenty is a health-adjacent sentence shown to a
 * user whose hearing did not change.
 *
 * The jitter in those property tests is not decoration. The project's own
 * research (REPORT-2026-08-26 part 4) reports consumer hearing tests at 8-17 dB
 * RMSD against clinical audiometry and classic pure-tone audiometry repeating
 * within 5 dB only 60-77 % of the time. The second of those figures is the one
 * that describes this situation — the same test, on the same ears, through the
 * same hardware — and it implies a per-point standard deviation of roughly 4 to
 * 6 dB. The tests use 5 dB, in the middle of that.
 */
class HearingDriftTest {

    private val device = "device-a"
    private val otherDevice = "device-b"
    private val preset = "focal_bathys"

    private fun day(n: Int): Long = n * 24L * 60L * 60L * 1000L

    private fun run(
        id: String,
        atDay: Int,
        thresholds: Map<Int, Double>,
        deviceKey: String? = device,
        volume: Double = VolumeGuard.TEST_VOLUME_FRACTION,
        calibrationPresetId: String = preset,
        converged: Boolean = true,
    ): AudiogramRun {
        val points = TEST_FREQUENCIES_HZ.map { hz ->
            ThresholdPoint(hz, thresholds.getValue(hz), converged = converged)
        }
        return AudiogramRun(
            id = id,
            timestampMillis = day(atDay),
            deviceAddressHash = deviceKey,
            calibrationPresetId = calibrationPresetId,
            ancMode = AncMode.UNKNOWN,
            ambientNoiseDbA = null,
            left = points,
            right = points,
            deviceName = "Test headphones",
            volumeFraction = volume,
        )
    }

    /** A flat curve at [db], the shape everything below perturbs. */
    private fun flat(db: Double = -50.0): Map<Int, Double> =
        TEST_FREQUENCIES_HZ.associateWith { db }

    /** Six runs spread over half a year, all identical. */
    private fun stableRuns(): List<AudiogramRun> = listOf(
        run("b1", 0, flat()),
        run("b2", 7, flat()),
        run("b3", 14, flat()),
        run("r1", 180, flat()),
        run("r2", 187, flat()),
        run("r3", 194, flat()),
    )

    /**
     * Six runs where [shifted] frequencies are [byDb] worse in the recent three.
     * Positive dB is worse, matching the module's dBFS convention.
     */
    private fun driftingRuns(shifted: List<Int>, byDb: Double): List<AudiogramRun> {
        val after = flat().mapValues { (hz, db) -> if (hz in shifted) db + byDb else db }
        return listOf(
            run("b1", 0, flat()),
            run("b2", 7, flat()),
            run("b3", 14, flat()),
            run("r1", 180, after),
            run("r2", 187, after),
            run("r3", 194, after),
        )
    }

    // ---- not enough data -----------------------------------------------------

    @Test
    fun `no runs at all is not enough data, and says how many are missing`() {
        val result = HearingDrift.evaluate(emptyList(), device)
        val notEnough = result as HearingDriftResult.NotEnoughData
        assertEquals(0, notEnough.comparableRuns)
        assertEquals(HearingDrift.MIN_COMPARABLE_RUNS, notEnough.moreRunsNeeded)
    }

    @Test
    fun `five comparable runs is one short and says so`() {
        val runs = stableRuns().dropLast(1)
        val notEnough = HearingDrift.evaluate(runs, device, deviceName = "Test headphones")
            as HearingDriftResult.NotEnoughData
        assertEquals(5, notEnough.comparableRuns)
        assertEquals(1, notEnough.moreRunsNeeded)
        // The screen has to be able to name the headphone and the level, so a
        // "not enough runs" line can say what to do rather than only what is
        // wrong.
        assertEquals("Test headphones", notEnough.deviceName)
        assertEquals(VolumeGuard.TEST_VOLUME_FRACTION, notEnough.volumeFraction!!, 1e-9)
    }

    @Test
    fun `enough runs too close together is not enough data either`() {
        // Six runs inside a fortnight. The count is satisfied; the claim is not.
        val runs = listOf(0, 1, 2, 3, 4, 5).mapIndexed { i, d -> run("r$i", d, flat()) }
        val notEnough = HearingDrift.evaluate(runs, device) as HearingDriftResult.NotEnoughData
        assertEquals(0, notEnough.moreRunsNeeded)
        assertTrue("the missing days must be named", notEnough.moreDaysNeeded > 0)
    }

    @Test
    fun `with nothing connected there is no set of runs that may be compared`() {
        val notEnough =
            HearingDrift.evaluate(stableRuns(), deviceKey = null) as HearingDriftResult.NotEnoughData
        assertTrue(notEnough.noDeviceConnected)
        assertEquals(0, notEnough.comparableRuns)
    }

    // ---- the comparability filters -------------------------------------------

    @Test
    fun `runs from another headphone are excluded from the comparison`() {
        val mixed = stableRuns() + listOf(
            run("x1", 30, flat(), deviceKey = otherDevice),
            run("x2", 40, flat(), deviceKey = otherDevice),
        )
        assertEquals(6, HearingDrift.comparableRuns(mixed, device).size)
        assertEquals(2, HearingDrift.comparableRuns(mixed, otherDevice).size)
    }

    @Test
    fun `a run with no recorded device is left out rather than guessed at`() {
        val mixed = stableRuns() + run("legacy", 30, flat(), deviceKey = null)
        assertTrue(HearingDrift.comparableRuns(mixed, device).none { it.id == "legacy" })
    }

    @Test
    fun `runs at another test volume are excluded from the comparison`() {
        // The newest run decides which volume counts, exactly as the curve
        // selection decides it.
        val mixed = stableRuns() + run("quiet", 200, flat(), volume = 0.4)
        val comparable = HearingDrift.comparableRuns(mixed, device)
        assertEquals(listOf("quiet"), comparable.map { it.id })
    }

    @Test
    fun `runs measured through another calibration preset are excluded`() {
        val mixed = stableRuns().dropLast(1) +
            run("r3", 194, flat(), calibrationPresetId = "generic_uncalibrated")
        val comparable = HearingDrift.comparableRuns(mixed, device)
        assertEquals(listOf("r3"), comparable.map { it.id })
    }

    @Test
    fun `a genuine drift measured through two headphones is not reported as one`() {
        // The same 15 dB shift, but the recent cluster is on the other device.
        // Nothing may be concluded: a headphone change moves every threshold at
        // once, which is precisely this signature.
        val after = flat().mapValues { (hz, db) -> if (hz >= 4000) db + 15.0 else db }
        val runs = listOf(
            run("b1", 0, flat()),
            run("b2", 7, flat()),
            run("b3", 14, flat()),
            run("r1", 180, after, deviceKey = otherDevice),
            run("r2", 187, after, deviceKey = otherDevice),
            run("r3", 194, after, deviceKey = otherDevice),
        )
        assertTrue(HearingDrift.evaluate(runs, device) is HearingDriftResult.NotEnoughData)
    }

    // ---- stable --------------------------------------------------------------

    @Test
    fun `identical runs over six months are stable`() {
        val stable = HearingDrift.evaluate(stableRuns(), device) as HearingDriftResult.Stable
        assertEquals(6, stable.comparableRuns)
        assertEquals(0.0, stable.largestShiftDb, 1e-9)
        assertEquals(day(0), stable.baselineAtMillis)
    }

    @Test
    fun `an improvement is never reported as drift`() {
        // Thresholds getting better is the opposite direction, and there is
        // nothing to warn anybody about.
        val better = driftingRuns(TEST_FREQUENCIES_HZ, byDb = -20.0)
        assertTrue(HearingDrift.evaluate(better, device) is HearingDriftResult.Stable)
    }

    // ---- drift ---------------------------------------------------------------

    @Test
    fun `a sustained 15 dB shift at neighbouring frequencies is reported`() {
        val drift = HearingDrift.evaluate(driftingRuns(listOf(4000, 6000), 15.0), device)
            as HearingDriftResult.DriftSuspected
        assertEquals(2, drift.ears.size)
        val left = drift.ears.first { it.ear == Ear.LEFT }
        assertEquals(listOf(4000, 6000), left.frequenciesHz)
        assertEquals(15.0, left.largestShiftDb, 1e-9)
    }

    @Test
    fun `one frequency is a point, not a pattern`() {
        assertTrue(
            HearingDrift.evaluate(driftingRuns(listOf(4000), 20.0), device)
                is HearingDriftResult.Stable,
        )
    }

    @Test
    fun `scattered frequencies are what noise looks like, so they do not count`() {
        // 250 and 4000 are both far out, and they are nowhere near each other.
        // A cochlear threshold shift is a region of the spectrum.
        assertTrue(
            HearingDrift.evaluate(driftingRuns(listOf(250, 4000), 20.0), device)
                is HearingDriftResult.Stable,
        )
    }

    @Test
    fun `a shift just under the threshold is not reported`() {
        val justUnder = HearingDrift.DRIFT_SHIFT_DB - 0.5
        assertTrue(
            HearingDrift.evaluate(driftingRuns(listOf(4000, 6000), justUnder), device)
                is HearingDriftResult.Stable,
        )
    }

    @Test
    fun `one very bad recent run cannot carry a median on its own`() {
        // Two normal recent runs and one enormous outlier. The median of three
        // survives it, and the sustained rule refuses it a second time.
        val outlier = flat().mapValues { (hz, db) -> if (hz >= 4000) db + 60.0 else db }
        val runs = listOf(
            run("b1", 0, flat()),
            run("b2", 7, flat()),
            run("b3", 14, flat()),
            run("r1", 180, flat()),
            run("r2", 187, flat()),
            run("r3", 194, outlier),
        )
        assertTrue(HearingDrift.evaluate(runs, device) is HearingDriftResult.Stable)
    }

    @Test
    fun `a shift that one recent run does not share is not sustained`() {
        // Two of three recent runs are 20 dB worse; the third is better than the
        // old median. The medians would clear the threshold, and the sustained
        // rule is the thing that stops it.
        val worse = flat().mapValues { (hz, db) -> if (hz >= 4000) db + 20.0 else db }
        val better = flat().mapValues { (_, db) -> db - 5.0 }
        val runs = listOf(
            run("b1", 0, flat()),
            run("b2", 7, flat()),
            run("b3", 14, flat()),
            run("r1", 180, worse),
            run("r2", 187, worse),
            run("r3", 194, better),
        )
        val shifts = HearingDrift.shiftsFor(
            runs.take(3),
            runs.takeLast(3),
            Ear.LEFT,
        )
        assertTrue(
            "the median did clear the bar",
            shifts.first { it.frequencyHz == 4000 }.shiftDb >= HearingDrift.DRIFT_SHIFT_DB,
        )
        assertTrue(
            "but not every recent run is on the worse side",
            !shifts.first { it.frequencyHz == 4000 }.everyRecentRunWorse,
        )
        assertTrue(HearingDrift.evaluate(runs, device) is HearingDriftResult.Stable)
    }

    @Test
    fun `hollow points take no part`() {
        // Every recent point hit the test's own ceiling. That is a fact about
        // the measurement, and comparing it against real thresholds would report
        // the app's limits as the user's hearing.
        val ceiling = flat(db = -6.0)
        val runs = listOf(
            run("b1", 0, flat()),
            run("b2", 7, flat()),
            run("b3", 14, flat()),
            run("r1", 180, ceiling, converged = false),
            run("r2", 187, ceiling, converged = false),
            run("r3", 194, ceiling, converged = false),
        )
        assertTrue(HearingDrift.evaluate(runs, device) is HearingDriftResult.Stable)
    }

    // ---- properties ----------------------------------------------------------

    /** Per-point test-retest spread; see the class KDoc for where 5 dB comes from. */
    private val jitterSd = 5.0

    private fun jittered(base: Map<Int, Double>, random: Random): Map<Int, Double> =
        base.mapValues { (_, db) -> db + random.nextGaussian() * jitterSd }

    private fun noisyRuns(random: Random, shift: Map<Int, Double> = emptyMap()): List<AudiogramRun> {
        val after = flat().mapValues { (hz, db) -> db + (shift[hz] ?: 0.0) }
        return listOf(
            run("b1", 0, jittered(flat(), random)),
            run("b2", 7, jittered(flat(), random)),
            run("b3", 14, jittered(flat(), random)),
            run("r1", 180, jittered(after, random)),
            run("r2", 187, jittered(after, random)),
            run("r3", 194, jittered(after, random)),
        )
    }

    /**
     * The property this whole feature stands or falls on.
     *
     * Ears that did not change, measured with the spread this kind of test
     * actually has, must never produce a drift notice. Not "rarely" — the
     * output is a sentence about somebody's hearing, and there is no acceptable
     * rate of inventing one.
     */
    @Test
    fun `noise-level jitter never flags drift`() {
        val flagged = (1..TRIALS).count { seed ->
            val verdict = HearingDrift.evaluate(noisyRuns(Random(seed.toLong())), device)
            verdict is HearingDriftResult.DriftSuspected
        }
        assertEquals("$flagged of $TRIALS runs of pure noise were reported as drift", 0, flagged)
    }

    /**
     * The other half: a real change has to survive the same noise.
     *
     * Not every trial, and the number is the honest measure of what this
     * feature can do. Measured on this tree: 299 of 300 seeds, against 0 of 300
     * false positives from the same jitter in the test above. The bound below
     * is set a little under the measured figure so that a harmless shift in the
     * arithmetic does not turn this into a flake, and far enough above chance
     * that a rule which stopped detecting anything would fail.
     *
     * The trials it misses are ones where the noise happened to mask the shift,
     * which is a missed finding rather than an invented one. That is the trade
     * this feature is deliberately built around.
     */
    @Test
    fun `a genuine sustained 15 dB shift is detected through the same noise`() {
        val shift = TEST_FREQUENCIES_HZ.associateWith { 15.0 }
        val detected = (1..TRIALS).count { seed ->
            val verdict = HearingDrift.evaluate(noisyRuns(Random(seed.toLong()), shift), device)
            verdict is HearingDriftResult.DriftSuspected
        }
        assertTrue(
            "only $detected of $TRIALS genuine 15 dB shifts were detected",
            detected >= TRIALS * 95 / 100,
        )
    }

    /**
     * Mixing conditions cannot manufacture a finding, however much noise is on
     * top: with every second run on the other headphone there are never enough
     * comparable ones to compare.
     */
    @Test
    fun `mixed devices never produce anything but not-enough-data`() {
        (1..TRIALS).forEach { seed ->
            val random = Random(seed.toLong())
            val runs = noisyRuns(random).mapIndexed { index, run ->
                if (index % 2 == 0) run.copy(deviceAddressHash = otherDevice) else run
            }
            assertTrue(
                "seed $seed produced a verdict from runs on two headphones",
                HearingDrift.evaluate(runs, device) is HearingDriftResult.NotEnoughData,
            )
        }
    }

    /** The same for volumes: half the runs at another level leaves too few. */
    @Test
    fun `mixed volumes never produce anything but not-enough-data`() {
        (1..TRIALS).forEach { seed ->
            val random = Random(seed.toLong())
            val runs = noisyRuns(random).mapIndexed { index, run ->
                if (index % 2 == 0) run.copy(volumeFraction = 0.4) else run
            }
            assertTrue(
                "seed $seed produced a verdict from runs at two volumes",
                HearingDrift.evaluate(runs, device) is HearingDriftResult.NotEnoughData,
            )
        }
    }

    private companion object {
        /**
         * Enough seeds that a one-in-a-hundred false positive would show up,
         * few enough that the suite stays fast. Fixed seeds, so a failure is
         * reproducible rather than a flake.
         */
        const val TRIALS = 300
    }
}
