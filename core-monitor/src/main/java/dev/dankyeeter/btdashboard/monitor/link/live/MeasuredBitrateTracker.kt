package dev.dankyeeter.btdashboard.monitor.link.live

import kotlin.math.abs

/**
 * One reported move of the measured bitrate: the two levels and why it counted.
 *
 * Both figures are MEASURED kbps read out of the stack, never spec numbers.
 */
data class BitrateStep(
    val timestampMs: Long,
    /** The level the timeline last reported, or null for the first one of a link. */
    val fromKbps: Int?,
    /** The level that has now held long enough to be worth a line. */
    val toKbps: Int,
    /** Which of the two significance rules let this one through. */
    val reason: BitrateStepReason,
) {
    /** True when the link got worse. The direction a listener actually notices. */
    val fell: Boolean get() = fromKbps != null && toKbps < fromKbps
}

/** Why a settled level was worth reporting. Documented on [MeasuredBitrateTracker]. */
enum class BitrateStepReason {
    /** The first settled level of a link. There is nothing to compare it to. */
    FIRST_READING,

    /** It crossed into a different eqmid class of the codec's ladder. */
    QUALITY_CLASS,

    /** It moved by at least [MeasuredBitrateTracker.MIN_STEP_KBPS] without changing class. */
    LARGE_STEP,
}

/**
 * Decides when a change in the **measured** LDAC bitrate is worth an event.
 *
 * ## The problem this solves
 *
 * The rate is now read directly rather than inferred, which means every poll
 * carries a real number — and ABR moves that number constantly. Measured on the
 * device over one session: the encoder pendulums between 492 and 660 kbps on a
 * clean link with music playing, and also visits 330 and 396. Firing an event on
 * each transition would put a line on the timeline every few seconds and bury
 * the connects, codec changes and dropouts the timeline exists for.
 *
 * Filtering by size alone does not fix it. The 492 to 660 swing is 168 kbps —
 * larger than a genuine ladder step from 330 to 396 — so any threshold that lets
 * the real move through also lets the wobble through.
 *
 * ## The rule
 *
 * Two gates, in order, and a level has to pass both.
 *
 *  1. **Settling.** A level must be seen on [SUSTAIN_READINGS] consecutive
 *     readings, all within [LEVEL_TOLERANCE_KBPS] of each other, before it can be
 *     reported at all. This is the part that kills the pendulum: a rate that
 *     flips back on the next poll never establishes a level, so it never reaches
 *     gate two. It is also why the gate counts *readings* rather than
 *     milliseconds — the panel's poll rate is a user setting between 1 and 5
 *     seconds, and a fixed time window would mean three samples at one rate and
 *     one sample at another.
 *
 *  2. **Significance.** A settled level is reported when it either lands in a
 *     different [QualityClass] from the last reported one — a genuine move
 *     across the codec's ladder — or differs from it by at least
 *     [MIN_STEP_KBPS] without changing class, which catches the intermediate
 *     steps the ladder has no rung for.
 *
 * The consequence is deliberate: this **under**-reports. A brief genuine dip
 * that recovers within [SUSTAIN_READINGS] polls produces no event, and a slow
 * drift of 60 kbps inside one class produces none either. That is the right
 * direction for a timeline to fail in — a missing line costs a user nothing,
 * while a timeline that cries wolf every four seconds costs them the whole
 * feature. The graphs still plot every reading, so nothing is hidden; this only
 * decides what is worth *writing down*.
 *
 * ## Statefulness
 *
 * This holds the settling state across readings, which is why it is a class and
 * not a function of two snapshots. One instance belongs to one polling run;
 * [reset] drops the state when the link changes underneath it, because a level
 * established on a different codec or a different headphone is not a baseline
 * for this one.
 */
class MeasuredBitrateTracker {

    private var reportedKbps: Int? = null
    private var candidateKbps: Int? = null
    private var candidateCount: Int = 0

    /** What the timeline last reported, for callers that need the baseline. */
    val lastReportedKbps: Int? get() = reportedKbps

    /**
     * Feeds one reading and returns a step when one is due.
     *
     * @param kbps the measured rate, or null when this poll could not read one.
     *   A null does not reset the settled level — a single unreadable poll in the
     *   middle of a steady stretch is a gap in observation, not a change in the
     *   link — but it does interrupt a run that had not settled yet.
     * @param sampleRateHz the negotiated rate, which decides which ladder the
     *   quality classes are measured against.
     */
    fun onReading(timestampMs: Long, kbps: Int?, sampleRateHz: Int?): BitrateStep? {
        if (kbps == null || kbps <= 0) {
            candidateKbps = null
            candidateCount = 0
            return null
        }

        val candidate = candidateKbps
        if (candidate != null && abs(kbps - candidate) <= LEVEL_TOLERANCE_KBPS) {
            candidateCount++
        } else {
            candidateKbps = kbps
            candidateCount = 1
        }
        if (candidateCount < SUSTAIN_READINGS) return null

        // Report the newest reading rather than the one that opened the run: the
        // level has held, and the freshest figure is the one the panel is showing
        // beside the timeline.
        val settled = kbps
        val previous = reportedKbps
        val reason = when {
            previous == null -> BitrateStepReason.FIRST_READING
            qualityClass(settled, sampleRateHz) != qualityClass(previous, sampleRateHz) ->
                BitrateStepReason.QUALITY_CLASS

            abs(settled - previous) >= MIN_STEP_KBPS -> BitrateStepReason.LARGE_STEP
            else -> null
        } ?: run {
            // Settled, but not far enough from what was already reported. Keep
            // the reported level where it is so a slow drift cannot ratchet the
            // baseline across a class boundary one insignificant step at a time.
            return null
        }

        reportedKbps = settled
        candidateKbps = settled
        candidateCount = SUSTAIN_READINGS
        return BitrateStep(
            timestampMs = timestampMs,
            fromKbps = previous,
            toKbps = settled,
            reason = reason,
        )
    }

    /** Forgets everything. Call when the codec or the device changed. */
    fun reset() {
        reportedKbps = null
        candidateKbps = null
        candidateCount = 0
    }

    /** One rung of the codec's own ladder, as a band rather than a point. */
    enum class QualityClass { LOW, MID, HIGH }

    companion object {

        /**
         * Consecutive readings a level must survive before it can be reported.
         *
         * Three, because two is what the pendulum itself produces when a poll
         * lands either side of a swing, and four would mean a genuine move takes
         * twenty seconds to reach the timeline at the 5 s poll rate.
         */
        const val SUSTAIN_READINGS = 3

        /**
         * How far two readings may sit apart and still count as the same level.
         *
         * Small: the stack prints a whole kbps figure and it sits still while the
         * encoder does, so this is slack for rounding rather than a band.
         */
        const val LEVEL_TOLERANCE_KBPS = 8

        /**
         * The smallest same-class move worth a line, in kbps.
         *
         * A hundred sits above the 66 kbps gap between the two lowest measured
         * ABR steps (330 to 396) and below the 168 kbps of the 492-to-660 swing,
         * so on its own it would let the wobble through — which is exactly why
         * the settling gate runs first and this one second.
         */
        const val MIN_STEP_KBPS = 100
    }
}

/**
 * Which rung of the LDAC ladder a measured rate belongs to.
 *
 * Boundaries sit at the midpoints between the ladder's rungs for the negotiated
 * sample-rate family, so an intermediate step the ladder has no rung for still
 * lands in exactly one class. Defaults to the 48/96 kHz ladder when the sample
 * rate is unknown, because that is the one every measurement so far was taken on
 * and the alternative is refusing to classify at all.
 */
internal fun qualityClass(
    kbps: Int,
    sampleRateHz: Int?,
): MeasuredBitrateTracker.QualityClass {
    val fortyFourFamily = sampleRateHz == 44_100 || sampleRateHz == 88_200
    val low = if (fortyFourFamily) 303 else 330
    val mid = if (fortyFourFamily) 606 else 660
    val high = if (fortyFourFamily) 909 else 990
    return when {
        kbps < (low + mid) / 2 -> MeasuredBitrateTracker.QualityClass.LOW
        kbps < (mid + high) / 2 -> MeasuredBitrateTracker.QualityClass.MID
        else -> MeasuredBitrateTracker.QualityClass.HIGH
    }
}
