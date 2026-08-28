package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.hearing.store.AudiogramStore

/**
 * Whether this person's own hearing test has moved over months — the one thing
 * a pile of stored runs can say that a single run cannot.
 *
 * ## The constraint this whole file is built around
 *
 * The project's own research (REPORT-2026-08-26, part 4) puts consumer hearing
 * tests at **8-17 dB RMSD** against clinical audiometry, and classic
 * Hughson-Westlake at only 60-77 % repeatability within 5 dB. Read that
 * honestly and it says: **the difference between two runs is noise.** Not
 * "mostly noise", not "noisy but indicative" — a 12 dB difference between
 * Tuesday and Thursday is an entirely ordinary thing for this measurement to
 * produce from ears that did not change at all.
 *
 * So a feature that reports "your hearing got worse" from two runs would be a
 * random number generator wired to a health claim. Everything below exists to
 * make that impossible:
 *
 *  - **Only comparable runs are compared at all.** Same headphone, same test
 *    volume, same calibration preset. Each of those three shifts every
 *    threshold by a fixed unknown amount, and a shift of the whole curve is
 *    exactly what a drift finding looks like.
 *  - **Clusters, not runs.** [RUNS_PER_CLUSTER] runs at each end, compared by
 *    their per-frequency medians. A median of three outvotes one lapse of
 *    attention; a single run cannot.
 *  - **Sustained, not once.** Every run in the recent cluster has to sit at or
 *    below the baseline median at a frequency before that frequency counts. One
 *    bad session that drags a median is not a trend.
 *  - **A pattern, not a point.** At least [MIN_DRIFT_FREQUENCIES] frequencies,
 *    and at least two of them neighbours on the audiometric scale. A threshold
 *    shift is a band of the spectrum; scattered single frequencies are what
 *    noise looks like.
 *  - **Months, not weeks.** [MIN_SPAN_DAYS] between the oldest and newest run.
 *    The claim is about long-term change, and a 15 dB move inside a fortnight
 *    is far more likely to be a different eartip than a different ear.
 *
 * The deliberate consequence is that this feature says **nothing** most of the
 * time, and will miss real changes that a clinic would catch. That is the right
 * trade for a phone app: a missed finding leaves the user exactly where they
 * were, an invented one sends them to a doctor over measurement noise.
 *
 * ## Why the clinical anchor is not the baseline
 *
 * The obvious idea — anchor the baseline to the clinical audiogram instead of
 * to the earliest runs — does not survive contact with the units. A clinical
 * audiogram is absolute dB HL; a run is uncalibrated dBFS at whatever volume
 * the phone was set to, and the offset between the two is unknown (that is the
 * premise of the entire module). [CalibrationTransfer] recovers a *shape* from
 * the pair, never a level. A baseline needs a level, so the anchor cannot
 * provide one, and the earliest comparable cluster — measured in the same units
 * as the recent one, through the same hardware — can.
 *
 * The anchor still matters here, just not as arithmetic: it is what a drift
 * notice sends the user back to a professional to re-take.
 */
object HearingDrift {

    /**
     * Runs at each end of the comparison.
     *
     * Three for the same reason the compensation uses three: it is the smallest
     * number whose median can outvote a single distracted session.
     */
    const val RUNS_PER_CLUSTER: Int = 3

    /** Both clusters, disjoint. Fewer runs than this cannot be split into two. */
    const val MIN_COMPARABLE_RUNS: Int = RUNS_PER_CLUSTER * 2

    /**
     * How far apart the oldest and newest comparable run have to be, in days.
     *
     * Roughly three months. Age-related hearing change is measured in years,
     * and everything that moves a self-test threshold inside a few weeks — a
     * different eartip, a cold, a noisier room, a new pair of glasses under the
     * earpads — is not hearing. Requiring a season between the ends does not
     * make the noise smaller, but it does make the boring explanations less
     * available.
     */
    const val MIN_SPAN_DAYS: Int = 90

    /**
     * How much worse a frequency's median has to be before it counts, in dB.
     *
     * Ten decibels is two audiometric steps and roughly twice the per-point
     * test-retest spread this kind of test is documented to have. It is applied
     * to the difference of two three-run *medians*, not of two runs, which
     * removes most of what is left — and even then it is only one of the four
     * conditions a frequency has to satisfy.
     *
     * Not a statistical threshold. Nobody here has the distribution to compute
     * one from; this is a deliberately blunt line drawn well outside the range
     * of ordinary jitter.
     */
    const val DRIFT_SHIFT_DB: Double = 10.0

    /**
     * How many frequencies have to show it, and they must include a neighbouring
     * pair.
     *
     * Two, plus the adjacency rule enforced in [driftingFrequencies]. A real
     * threshold shift affects a region of the cochlea, so it shows up as a band
     * of neighbouring frequencies; independent noise scatters. Requiring the
     * pattern rather than a count is what keeps the false-positive rate low
     * enough to put a health-adjacent sentence on screen.
     */
    const val MIN_DRIFT_FREQUENCIES: Int = 2

    /**
     * How many converged measurements a frequency needs in each cluster.
     *
     * Two of the three. A frequency measured once per cluster has no median at
     * all — its "median" is that one run, and one run is precisely what this
     * file refuses to draw conclusions from.
     */
    const val MIN_VALUES_PER_FREQUENCY: Int = 2

    private const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L

    /**
     * The runs that may be compared with each other, oldest first.
     *
     * Three filters, and each one removes a fixed offset that would otherwise
     * masquerade as drift:
     *
     *  - **Same headphone**, by exact key. Stricter than
     *    [AudiogramStore.selectionOf], which lets runs with no recorded device
     *    ride along so that data from older builds stays usable. That kindness
     *    is wrong here: a run whose device is unknown might be from the other
     *    headphone, and a headphone change moves every threshold at once —
     *    which is the exact signature this function is looking for. An
     *    unattributable run is therefore left out rather than guessed at.
     *  - **Same test volume**, through [AudiogramStore.isSameVolume], so the
     *    definition cannot drift away from the one the curve selection uses.
     *    dBFS thresholds only mean anything against the volume they were
     *    measured at.
     *  - **Same calibration preset.** The preset is what maps output level to
     *    threshold; changing it in the device profile re-scales the whole curve.
     *    Not part of the selection filters, because the selection compares runs
     *    that are all being medianed into one present-tense curve, while this
     *    compares a curve from last winter against one from this summer — over
     *    that span the preset genuinely can have been changed.
     *
     * The reference for volume and preset is the **newest** run on the device,
     * matching [AudiogramStore.currentVolumeFor]: what is current wins, and the
     * older runs it does not match are on the bench rather than deleted.
     */
    fun comparableRuns(runs: List<AudiogramRun>, deviceKey: String?): List<AudiogramRun> {
        if (deviceKey == null) return emptyList()
        val onDevice = runs.filter { it.deviceAddressHash == deviceKey }.sortedBy { it.timestampMillis }
        val newest = onDevice.lastOrNull() ?: return emptyList()
        return onDevice.filter {
            AudiogramStore.isSameVolume(it.volumeFraction, newest.volumeFraction) &&
                it.calibrationPresetId == newest.calibrationPresetId
        }
    }

    /**
     * The whole verdict, for one headphone.
     *
     * Pure and total: the screen has nothing to decide and every branch can be
     * tested without a UI, the same shape [LowToneArtifact.evaluate] has.
     *
     * @param deviceKey the connected headphone. Null answers
     *   [HearingDriftResult.NotEnoughData] with [HearingDriftResult.NotEnoughData.noDeviceConnected]
     *   set — with nothing on the head there is no set of runs that may be
     *   compared, and picking one would compare across hardware.
     */
    fun evaluate(
        runs: List<AudiogramRun>,
        deviceKey: String?,
        deviceName: String? = null,
    ): HearingDriftResult {
        val comparable = comparableRuns(runs, deviceKey)
        val spanDays = spanDaysOf(comparable)
        if (comparable.size < MIN_COMPARABLE_RUNS || spanDays < MIN_SPAN_DAYS) {
            return HearingDriftResult.NotEnoughData(
                comparableRuns = comparable.size,
                moreRunsNeeded = (MIN_COMPARABLE_RUNS - comparable.size).coerceAtLeast(0),
                spanDays = spanDays,
                moreDaysNeeded = (MIN_SPAN_DAYS - spanDays).coerceAtLeast(0),
                deviceName = deviceName,
                volumeFraction = comparable.lastOrNull()?.volumeFraction,
                noDeviceConnected = deviceKey == null,
            )
        }

        val baseline = comparable.take(RUNS_PER_CLUSTER)
        val recent = comparable.takeLast(RUNS_PER_CLUSTER)
        val baselineAtMillis = baseline.first().timestampMillis
        val latestAtMillis = recent.last().timestampMillis

        val shiftsByEar = Ear.entries.associateWith { shiftsFor(baseline, recent, it) }
        val perEar = shiftsByEar.mapNotNull { (ear, shifts) ->
            driftingFrequencies(shifts).takeIf { it.isNotEmpty() }?.let { EarDrift(ear, it) }
        }
        val largestShiftDb = shiftsByEar.values.flatten().maxOfOrNull { it.shiftDb } ?: 0.0

        return if (perEar.isEmpty()) {
            HearingDriftResult.Stable(
                comparableRuns = comparable.size,
                baselineAtMillis = baselineAtMillis,
                latestAtMillis = latestAtMillis,
                largestShiftDb = largestShiftDb,
            )
        } else {
            HearingDriftResult.DriftSuspected(
                comparableRuns = comparable.size,
                baselineAtMillis = baselineAtMillis,
                latestAtMillis = latestAtMillis,
                ears = perEar,
            )
        }
    }

    /** Whole days between the oldest and newest run; 0 for fewer than two. */
    private fun spanDaysOf(runs: List<AudiogramRun>): Int {
        if (runs.size < 2) return 0
        val span = runs.last().timestampMillis - runs.first().timestampMillis
        return (span / MILLIS_PER_DAY).toInt().coerceAtLeast(0)
    }

    /**
     * Per-frequency comparison of the two clusters for one ear.
     *
     * Converged points only, for the same reason [SelfTestThresholds] drops
     * them: a point at the level floor says "quieter than the app can ask",
     * which is a fact about the test, and the difference of two such facts is
     * not a change in hearing. A frequency without [MIN_VALUES_PER_FREQUENCY]
     * converged points in *both* clusters is left out entirely rather than
     * compared thinly.
     */
    fun shiftsFor(
        baseline: List<AudiogramRun>,
        recent: List<AudiogramRun>,
        ear: Ear,
    ): List<FrequencyShift> {
        val baselineValues = convergedByFrequency(baseline, ear)
        val recentValues = convergedByFrequency(recent, ear)
        return baselineValues.keys
            .intersect(recentValues.keys)
            .sorted()
            .mapNotNull { hz ->
                val before = baselineValues.getValue(hz)
                val after = recentValues.getValue(hz)
                if (before.size < MIN_VALUES_PER_FREQUENCY || after.size < MIN_VALUES_PER_FREQUENCY) {
                    return@mapNotNull null
                }
                val baselineMedian = MedianAudiogramAggregator.median(before)
                FrequencyShift(
                    frequencyHz = hz,
                    // Larger dBFS is a worse threshold, here as everywhere else
                    // in this module, so recent-minus-baseline is positive when
                    // hearing has got worse.
                    shiftDb = MedianAudiogramAggregator.median(after) - baselineMedian,
                    // "Sustained" in the literal sense: not one session pulling
                    // a median, but every recent session sitting on the worse
                    // side of where the baseline was.
                    everyRecentRunWorse = after.all { it >= baselineMedian },
                )
            }
    }

    private fun convergedByFrequency(runs: List<AudiogramRun>, ear: Ear): Map<Int, List<Double>> =
        runs.flatMap { it.points(ear) }
            .filter { it.converged }
            .groupBy { it.frequencyHz }
            .mapValues { (_, points) -> points.map { it.thresholdDb } }

    /**
     * Which frequencies actually count as drift, or nothing.
     *
     * Three conditions, and the third is the one that earns the feature its
     * sentence on screen:
     *
     *  1. the shift is at least [DRIFT_SHIFT_DB] in the worse direction;
     *  2. every run in the recent cluster is on the worse side of the baseline
     *     median there;
     *  3. at least [MIN_DRIFT_FREQUENCIES] such frequencies, **including a
     *     neighbouring pair** among the frequencies that were comparable at all.
     *
     * Condition 3 is what separates a hearing change from a coincidence.
     * Independent per-frequency noise scatters its outliers; a cochlear
     * threshold shift is a region, so it lands on 3 and 4 kHz together, or on 4
     * and 6. Requiring the shape rather than a count costs almost nothing in
     * sensitivity — a real shift is neighbourly — and removes most of what a
     * count alone would let through.
     */
    fun driftingFrequencies(shifts: List<FrequencyShift>): List<FrequencyShift> {
        val ordered = shifts.sortedBy { it.frequencyHz }
        val flagged = ordered.filter { it.shiftDb >= DRIFT_SHIFT_DB && it.everyRecentRunWorse }
        if (flagged.size < MIN_DRIFT_FREQUENCIES) return emptyList()
        val flaggedHz = flagged.map { it.frequencyHz }.toSet()
        val neighbouring = ordered.zipWithNext()
            .any { (a, b) -> a.frequencyHz in flaggedHz && b.frequencyHz in flaggedHz }
        return if (neighbouring) flagged else emptyList()
    }
}

/**
 * One frequency's comparison between the two clusters.
 *
 * @param shiftDb recent median minus baseline median, in the app's internal dB.
 *   Positive means worse hearing, matching every other scale in this module.
 * @param everyRecentRunWorse whether all recent runs sat at or beyond the
 *   baseline median here — see [HearingDrift.driftingFrequencies].
 */
data class FrequencyShift(
    val frequencyHz: Int,
    val shiftDb: Double,
    val everyRecentRunWorse: Boolean,
)

/** One ear with a sustained shift, and the frequencies carrying it. */
data class EarDrift(
    val ear: Ear,
    val shifts: List<FrequencyShift>,
) {
    val largestShiftDb: Double get() = shifts.maxOf { it.shiftDb }
    val frequenciesHz: List<Int> get() = shifts.map { it.frequencyHz }
}

/**
 * What the drift check can conclude. Three states and no fourth: either there
 * is not enough to compare, or there is and nothing moved, or something moved.
 */
sealed interface HearingDriftResult {

    /**
     * Not enough comparable runs, or not enough time between them.
     *
     * Carries what is missing rather than only saying that something is: the
     * screen has to be able to say "two more runs at this volume on these
     * headphones", because "not enough data" with no way to fix it is a dead
     * end.
     */
    data class NotEnoughData(
        val comparableRuns: Int,
        val moreRunsNeeded: Int,
        val spanDays: Int,
        val moreDaysNeeded: Int,
        val deviceName: String?,
        val volumeFraction: Double?,
        val noDeviceConnected: Boolean = false,
    ) : HearingDriftResult

    /**
     * Enough to compare, and nothing sustained moved.
     *
     * [largestShiftDb] is the biggest per-frequency move seen anyway, kept so
     * the explanation can be concrete about how much ordinary variation the
     * runs actually contain instead of implying there was none.
     */
    data class Stable(
        val comparableRuns: Int,
        val baselineAtMillis: Long,
        val latestAtMillis: Long,
        val largestShiftDb: Double,
    ) : HearingDriftResult

    /**
     * A sustained shift in at least one ear.
     *
     * Named "suspected" and never anything stronger. This is an uncalibrated
     * self-test compared against itself; what it can honestly support is
     * "something looks different, have it checked properly", and the wording on
     * screen goes no further.
     */
    data class DriftSuspected(
        val comparableRuns: Int,
        val baselineAtMillis: Long,
        val latestAtMillis: Long,
        val ears: List<EarDrift>,
    ) : HearingDriftResult {
        val largestShiftDb: Double get() = ears.maxOf { it.largestShiftDb }
    }
}
