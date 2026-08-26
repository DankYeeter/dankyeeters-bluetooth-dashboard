package dev.dankyeeter.btdashboard.hearing

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Derives a real, per-device calibration from two measurements of the same
 * ears: a clinical audiogram (calibrated dB HL, from an ENT booth) and this
 * app's own threshold test through the headphone (internal dBFS, uncalibrated).
 *
 * ## Why this works
 *
 * Both curves describe the same hearing. The clinic measured it through a
 * calibrated transducer; the app measured it through the user's headphone. So
 * for each frequency the difference
 *
 *     D(f) = T_dBFS(f) − HL(f)
 *
 * contains everything that is *not* the ears: the unknown global offset
 * between the two scales (volume setting, sensitivity — meaningless, removed
 * as the mean) and, as D(f)'s deviation from that mean, the headphone's own
 * frequency response at this user's ear. A band the headphone plays louder is
 * heard at a lower dBFS threshold, so D dips there — the response deviation is
 * `mean(D) − D(f)`, positive where the device is loud, matching exactly the
 * `responseDeviationDb` convention of [CalibrationPreset.fromResponseDeviation]
 * ("positive = the headphone plays that band louder").
 *
 * This is the transfer-standard trick audiology itself uses, and it turns
 * every user who owns an ENT printout into a calibration source for their own
 * headphone — measured at their ear, seal and all, which for *their*
 * compensation is better than any coupler average. The bundled presets are
 * hand-read approximations of published rig measurements; this replaces them
 * with data.
 *
 * ## What it deliberately does not claim
 *
 * The result is a *shape*, like every preset in this app: the global offset is
 * discarded because it depends on the test volume. And it is a property of
 * device-plus-this-person's-ears, not of the device model in general — the
 * KDoc of the produced preset must say so.
 */
object CalibrationTransfer {

    /**
     * @param responseDeviationDb aligned with [TEST_FREQUENCIES_HZ], rounded to
     *   0.5 dB like the bundled presets — pass straight into
     *   [CalibrationPreset.fromResponseDeviation]
     * @param usedFrequenciesHz frequencies where both measurements overlapped;
     *   everything else in the list is interpolated between them
     * @param earSpreadDb the largest per-frequency disagreement between the
     *   left-ear and right-ear estimates. The device response has no side, so
     *   this spread is a quality figure: fit asymmetry plus measurement noise.
     * @param warnings honest caveats for the UI, never silently swallowed
     */
    data class Result(
        val responseDeviationDb: List<Double>,
        val usedFrequenciesHz: List<Int>,
        val earSpreadDb: Double,
        val warnings: List<String>,
    )

    /**
     * Minimum overlapping frequencies before a shape is worth deriving. Below
     * this the interpolation would invent more curve than the data provides.
     */
    const val MIN_OVERLAP = 5

    /**
     * Above this, one of the inputs is probably wrong (a bad seal during the
     * self-test, a typo in the clinic entry). The result still comes back —
     * with a warning — because the caller can see the curve and judge; a
     * silent refusal would hide the very discrepancy worth looking at.
     */
    const val SUSPICIOUS_DEVIATION_DB = 12.0

    /**
     * Derives the device response from both ears, or null when the overlap is
     * too thin to say anything.
     *
     * Inputs are per-frequency maps in each measurement's own scale: clinic in
     * dB HL, self-test thresholds in the app's dBFS (converged points only —
     * a floor-clipped threshold would masquerade as a quiet band of the
     * device). Frequencies need not match [TEST_FREQUENCIES_HZ]; only ones
     * present in both the clinic and self maps of the same ear are used.
     *
     * The two ears are averaged where both have data. Ear differences are a
     * property of the person and are already removed by the HL subtraction;
     * what remains in the spread between the ears is fit asymmetry and noise,
     * which averaging halves and [Result.earSpreadDb] reports.
     */
    fun derive(
        clinicLeftHl: Map<Int, Double>,
        clinicRightHl: Map<Int, Double>,
        selfLeftDbfs: Map<Int, Double>,
        selfRightDbfs: Map<Int, Double>,
    ): Result? {
        val leftDev = earDeviation(clinicLeftHl, selfLeftDbfs)
        val rightDev = earDeviation(clinicRightHl, selfRightDbfs)
        val frequencies = (leftDev.keys + rightDev.keys).sorted()
        if (frequencies.size < MIN_OVERLAP) return null

        val combined = frequencies.associateWith { hz ->
            val l = leftDev[hz]
            val r = rightDev[hz]
            when {
                l != null && r != null -> (l + r) / 2.0
                else -> l ?: r!!
            }
        }
        val earSpread = frequencies
            .mapNotNull { hz ->
                val l = leftDev[hz]
                val r = rightDev[hz]
                if (l != null && r != null) abs(l - r) else null
            }
            .maxOrNull() ?: 0.0

        val aligned = TEST_FREQUENCIES_HZ.map { hz ->
            roundToHalf(interpolateAt(hz, combined))
        }

        val warnings = buildList {
            val worst = aligned.maxOf { abs(it) }
            if (worst > SUSPICIOUS_DEVIATION_DB) {
                add(
                    "One band came out ${"%.1f".format(worst)} dB from flat — unusually " +
                        "large for a headphone response. Check the seal of the run and " +
                        "the entered clinic values before trusting it.",
                )
            }
            if (earSpread > 6.0) {
                add(
                    "The two ears disagree by up to ${"%.1f".format(earSpread)} dB about " +
                        "the device. That difference is fit or noise, not hearing — " +
                        "re-seating the headphone and re-running the test usually shrinks it.",
                )
            }
        }
        return Result(aligned, frequencies, earSpread, warnings)
    }

    /** mean(D) − D(f) for one ear, over the frequencies both measurements share. */
    private fun earDeviation(
        clinicHl: Map<Int, Double>,
        selfDbfs: Map<Int, Double>,
    ): Map<Int, Double> {
        val shared = clinicHl.keys intersect selfDbfs.keys
        if (shared.isEmpty()) return emptyMap()
        val difference = shared.associateWith { hz -> selfDbfs.getValue(hz) - clinicHl.getValue(hz) }
        val mean = difference.values.average()
        return difference.mapValues { (_, d) -> mean - d }
    }

    /**
     * Linear interpolation on a log-frequency axis, clamped to the outermost
     * known values — the same edge-hold every audio chart in this app uses,
     * because extrapolating a response slope past the last measured point
     * invents data.
     */
    private fun interpolateAt(hz: Int, known: Map<Int, Double>): Double {
        known[hz]?.let { return it }
        val sorted = known.keys.sorted()
        val below = sorted.lastOrNull { it < hz }
        val above = sorted.firstOrNull { it > hz }
        return when {
            below == null -> known.getValue(above!!)
            above == null -> known.getValue(below)
            else -> {
                val t = (ln(hz.toDouble()) - ln(below.toDouble())) /
                    (ln(above.toDouble()) - ln(below.toDouble()))
                known.getValue(below) * (1 - t) + known.getValue(above) * t
            }
        }
    }

    /** The bundled presets are entered at 0.5 dB steps; the derived one matches. */
    private fun roundToHalf(value: Double): Double = (value * 2).roundToInt() / 2.0
}
