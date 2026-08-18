package dev.dankyeeter.btdashboard.hearing

import kotlin.math.ln

/**
 * The NAL-R prescription rule (Byrne & Dillon 1986), exactly as specified in
 * COMPENSATION.md section 2.
 *
 * ```
 * PTA   = (H500 + H1000 + H2000) / 3      (per ear)
 * X     = 0.15 * PTA
 * IG(f) = X + 0.31 * H_T(f) + C(f)        clamped to >= 0
 * ```
 *
 * Nothing in here is level-dependent — NAL-R is a linear rule. The partial /
 * intensity scaling and all safety clamps live in [NalRCompensationCalculator];
 * this object is the pure prescription so it can be unit-tested against the
 * published numbers on its own.
 */
object NalR {

    /** Frequencies of the C(f) correction table in COMPENSATION.md. */
    val CORRECTION_FREQUENCIES_HZ: List<Int> = listOf(250, 500, 1000, 2000, 3000, 4000, 6000)

    /** C(f) in dB, index-aligned with [CORRECTION_FREQUENCIES_HZ]. */
    val CORRECTION_DB: List<Double> = listOf(-17.0, -8.0, 1.0, -1.0, -2.0, -2.0, -2.0)

    /** Frequencies whose thresholds form the pure-tone average. */
    val PTA_FREQUENCIES_HZ: List<Int> = listOf(500, 1000, 2000)

    /**
     * C(f), linearly interpolated on log frequency. Outside the table the edge
     * value is held, which gives the spec's `C(8000) = C(6000) = -2 dB`.
     */
    fun correctionDb(frequencyHz: Double): Double = logInterpolate(
        xs = CORRECTION_FREQUENCIES_HZ.map { it.toDouble() },
        ys = CORRECTION_DB,
        x = frequencyHz,
    )

    /** `PTA = (H500 + H1000 + H2000) / 3`, from device-corrected thresholds. */
    fun pureToneAverage(thresholdsDb: (Int) -> Double): Double =
        PTA_FREQUENCIES_HZ.sumOf(thresholdsDb) / PTA_FREQUENCIES_HZ.size

    /**
     * Insertion gain `IG(f) = 0.15 * PTA + 0.31 * H_T(f) + C(f)`, clamped to
     * be non-negative (NAL-R never prescribes attenuation).
     */
    fun insertionGainDb(frequencyHz: Double, thresholdDb: Double, ptaDb: Double): Double =
        (0.15 * ptaDb + 0.31 * thresholdDb + correctionDb(frequencyHz)).coerceAtLeast(0.0)
}

/**
 * Piecewise-linear interpolation on a logarithmic frequency axis; outside the
 * support the edge value is held (never extrapolated). [xs] must be sorted
 * ascending and positive.
 */
internal fun logInterpolate(xs: List<Double>, ys: List<Double>, x: Double): Double {
    require(xs.size == ys.size && xs.isNotEmpty()) { "xs/ys must be non-empty and aligned" }
    if (x <= xs.first()) return ys.first()
    if (x >= xs.last()) return ys.last()
    val lx = ln(x)
    for (i in 0 until xs.size - 1) {
        val a = xs[i]
        val b = xs[i + 1]
        if (x in a..b) {
            val t = (lx - ln(a)) / (ln(b) - ln(a))
            return ys[i] + t * (ys[i + 1] - ys[i])
        }
    }
    return ys.last()
}
