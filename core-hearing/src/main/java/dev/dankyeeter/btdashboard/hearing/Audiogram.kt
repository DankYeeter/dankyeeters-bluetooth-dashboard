package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.hearing.level.VolumeGuard
import dev.dankyeeter.btdashboard.audio.eq.Ear

/**
 * Data models for hearing-test results.
 *
 * STAGE A NOTE: this module is a contract-only skeleton. The Hughson-Westlake
 * state machine (Worker B) and the compensation math (Worker C, strictly
 * against COMPENSATION.md) fill in the implementations. Do not change these
 * signatures without telling the other workers.
 *
 * Honesty rule that applies to every number in here: these are
 * audiometry-inspired consumer values, not clinical dB HL. They are only
 * meaningful *relative* to each other.
 */

/** Test frequencies of the modified Hughson-Westlake protocol, in Hz. */
val TEST_FREQUENCIES_HZ: List<Int> = listOf(250, 500, 1000, 2000, 3000, 4000, 6000, 8000)

/**
 * One measured threshold.
 *
 * @param frequencyHz one of [TEST_FREQUENCIES_HZ]
 * @param thresholdDb lowest level with >= 2 of 3 responses, in the app's
 *   internal dB scale (dBFS attenuation mapped through the device calibration
 *   preset — never claim dB HL)
 * @param responseCount how many ascending runs produced a response
 * @param presentationCount how many presentations were needed
 * @param converged false if the run hit the level ceiling/floor without a
 *   proper 2-of-3 crossing; such points must be marked in the UI
 */
data class ThresholdPoint(
    val frequencyHz: Int,
    val thresholdDb: Double,
    val responseCount: Int = 0,
    val presentationCount: Int = 0,
    val converged: Boolean = true,
)

/** The listening mode of the headphone during the run; stored, never readable. */
enum class AncMode { ANC_ON, TRANSPARENCY, OFF, UNKNOWN }

/**
 * One complete test run for one user + device, both ears.
 *
 * @param calibrationPresetId id of the device calibration preset in use
 *   (e.g. "focal_bathys", "noble_encore", "generic_uncalibrated")
 * @param ambientNoiseDbA measured ambient level before the run, or null if the
 *   mic check was skipped/denied. A warning, never a blocker.
 */
data class AudiogramRun(
    val id: String,
    val timestampMillis: Long,
    val deviceAddressHash: String?,
    val calibrationPresetId: String,
    val ancMode: AncMode,
    val ambientNoiseDbA: Double?,
    val left: List<ThresholdPoint>,
    val right: List<ThresholdPoint>,
    /**
     * Name of the headphone the run was measured through, for display.
     *
     * A hearing curve is a property of the pair ear-plus-headphone, not of the
     * ear alone - the same person measures differently through different
     * drivers. The hash above is the identity; the name is kept alongside so a
     * run can say which device it belongs to even after that device is gone.
     */
    val deviceName: String? = null,
    /**
     * Fraction of the maximum media volume the run was measured at.
     *
     * Thresholds in dBFS only mean anything against the volume they were
     * measured under; a run at 40 % and a run at 70 % describe different
     * physical windows and must never share a median. Stored per run so the
     * selection can enforce exactly that.
     */
    val volumeFraction: Double = VolumeGuard.TEST_VOLUME_FRACTION,
) {
    fun points(ear: Ear): List<ThresholdPoint> = when (ear) {
        Ear.LEFT -> left
        Ear.RIGHT -> right
    }
}

/**
 * The active audiogram derived from several runs. Per plan: the user performs
 * 3+ runs and the per-frequency **median** across runs becomes the active
 * curve; individual outlier runs can be deleted and retaken.
 */
data class Audiogram(
    val runIds: List<String>,
    val left: List<ThresholdPoint>,
    val right: List<ThresholdPoint>,
) {
    fun points(ear: Ear): List<ThresholdPoint> = when (ear) {
        Ear.LEFT -> left
        Ear.RIGHT -> right
    }
}

/** Aggregates several runs into the active audiogram. Implemented by Worker B. */
interface AudiogramAggregator {
    fun aggregate(runs: List<AudiogramRun>): Audiogram
}

/**
 * Rewrites a measured audiogram from the app's internal dBFS frame into the
 * loss frame NAL-R actually takes.
 *
 * NAL-R's formula (COMPENSATION.md §3) wants H_T(f) in dB HL: positive,
 * zero = no loss. The Hughson-Westlake engine stores thresholds as dBFS
 * attenuation — negative, −90…−6 — and for a long time those raw values went
 * into the calculator unchanged. Every term of the formula then came out
 * negative, the ≥0 clamp flattened all of it, and the generated curve
 * prescribed exactly nothing for every realistic measurement. The existing
 * tests fed hand-written positive values and could not see it.
 *
 * Without an absolute calibration the honest loss figure is relative: how far
 * each frequency falls short of this person's own best converged threshold.
 * The reference is global across both ears on purpose — a worse left ear is a
 * loss of the left ear, and a per-ear reference would erase exactly that
 * asymmetry, which is the one thing the per-ear prescription exists for.
 * Unconverged points ride along: at the floor they sit near the reference and
 * produce no phantom loss, at the ceiling the large loss is real enough to
 * keep.
 *
 * An all-flat measurement maps to zero loss everywhere and prescribes
 * nothing — the same behaviour a flat clinical audiogram gets, which is what
 * makes the two sources comparable.
 */
fun Audiogram.asRelativeLossHl(): Audiogram {
    val reference = (left + right)
        .filter { it.converged }
        .minOfOrNull { it.thresholdDb }
        ?: return this
    fun rebase(points: List<ThresholdPoint>) = points.map { point ->
        point.copy(thresholdDb = (point.thresholdDb - reference).coerceAtLeast(0.0))
    }
    return copy(left = rebase(left), right = rebase(right))
}
