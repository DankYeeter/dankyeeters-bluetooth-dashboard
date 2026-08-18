package dev.dankyeeter.btdashboard.hearing.fit

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
import dev.dankyeeter.btdashboard.hearing.protocol.ProtocolConfig
import kotlin.math.abs

/**
 * Quick seal/placement check.
 *
 * Low frequencies are the first thing a broken seal loses, so a short 125/250 Hz
 * mini-threshold run is compared against the user's stored low-frequency
 * baseline. A large deviation means the earphones sit differently than when the
 * baseline was taken — usually a bad seal, sometimes a swapped eartip.
 *
 * This is *not* a real fit test: those use the earbud's internal feedback mic,
 * which no third-party Android app can reach (Milestone 3, BLE reverse
 * engineering). The UI must say so.
 *
 * Per PLAN.md the step is mandatory for IEM device profiles and optional for
 * over-ears — see [DeviceFormFactor.fitCheckMandatory].
 */
object FitCheck {

    /** Probe tones, deliberately below the audiogram range. */
    val FREQUENCIES_HZ: List<Int> = listOf(125, 250)

    /** Shorter and without catch trials: this is a 20-second sanity check. */
    val PROTOCOL: ProtocolConfig = ProtocolConfig(
        startLevelDb = -50.0,
        maxPresentationsPerFrequency = 16,
        catchTrialProbability = 0.0,
        maxCatchTrials = 0,
    )

    /** Mean absolute deviation above which the seal is called into question. */
    const val WARN_DEVIATION_DB: Double = 8.0

    fun evaluate(probe: List<ThresholdPoint>, baseline: FitBaseline?, ear: Ear): FitCheckResult {
        if (probe.isEmpty()) return FitCheckResult.Inconclusive("The fit probe produced no usable measurement.")
        val reference = baseline?.forEar(ear).orEmpty()
        if (reference.isEmpty()) {
            return FitCheckResult.BaselineStored(FitBaseline.fromProbe(ear, probe, baseline))
        }

        val deltas = probe.mapNotNull { point ->
            reference[point.frequencyHz]?.let { point.thresholdDb - it }
        }
        if (deltas.isEmpty()) {
            return FitCheckResult.BaselineStored(FitBaseline.fromProbe(ear, probe, baseline))
        }

        val meanAbs = deltas.sumOf { abs(it) } / deltas.size
        val meanSigned = deltas.sum() / deltas.size
        return if (meanAbs >= WARN_DEVIATION_DB) {
            FitCheckResult.Warning(deviationDb = meanSigned, message = warningText(meanSigned))
        } else {
            FitCheckResult.Good(deviationDb = meanSigned)
        }
    }

    private fun warningText(meanSigned: Double): String = if (meanSigned > 0) {
        "Bass tones needed about ${meanSigned.toInt()} dB more level than in your baseline. " +
            "That usually means a leaky seal — reseat the earphones (or try a larger eartip) and probe again."
    } else {
        "Bass tones were about ${abs(meanSigned).toInt()} dB louder than in your baseline. " +
            "The fit differs from the reference; check that the earphones sit as usual before testing."
    }
}

/** Device shape, decides whether the fit check may be skipped. */
enum class DeviceFormFactor(val fitCheckMandatory: Boolean) {
    IN_EAR(true),
    OVER_EAR(false),
    UNKNOWN(false),
}

/** Stored low-frequency reference per ear: frequency -> threshold in dBFS. */
data class FitBaseline(
    val left: Map<Int, Double> = emptyMap(),
    val right: Map<Int, Double> = emptyMap(),
) {
    fun forEar(ear: Ear): Map<Int, Double> = when (ear) {
        Ear.LEFT -> left
        Ear.RIGHT -> right
    }

    val isEmpty: Boolean get() = left.isEmpty() && right.isEmpty()

    companion object {
        fun fromProbe(ear: Ear, probe: List<ThresholdPoint>, previous: FitBaseline?): FitBaseline {
            val measured = probe.associate { it.frequencyHz to it.thresholdDb }
            val base = previous ?: FitBaseline()
            return when (ear) {
                Ear.LEFT -> base.copy(left = measured)
                Ear.RIGHT -> base.copy(right = measured)
            }
        }
    }
}

sealed interface FitCheckResult {
    /** First probe for this ear: nothing to compare against, so it becomes the reference. */
    data class BaselineStored(val baseline: FitBaseline) : FitCheckResult

    data class Good(val deviationDb: Double) : FitCheckResult
    data class Warning(val deviationDb: Double, val message: String) : FitCheckResult
    data class Inconclusive(val message: String) : FitCheckResult
}
