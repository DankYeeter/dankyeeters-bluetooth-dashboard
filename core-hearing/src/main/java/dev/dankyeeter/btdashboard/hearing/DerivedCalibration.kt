package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.hearing.fit.DeviceFormFactor

/**
 * A calibration [CalibrationTransfer] derived for one headphone, as stored.
 *
 * ## What this record actually is
 *
 * The bundled presets in [BundledCalibrationPresets] are shapes hand-read from
 * published rig measurements of a *device model*. This one is measured: the
 * clinic's dB HL and this app's own dBFS thresholds describe the same ears, so
 * their difference is everything that is not the ears — see [CalibrationTransfer]
 * for the derivation.
 *
 * And that "everything that is not the ears" is where the honesty line runs. It
 * is not the response of the headphone *model*; it is the response of this
 * headphone **on this person's head**, seal, pinna, insertion depth and all.
 * For that person's own compensation that is strictly better than any coupler
 * average — and for anybody else it is worthless. Every string this record
 * produces for the UI says so.
 *
 * @param deviceKey the headphone this was derived for
 *   ([dev.dankyeeter.btdashboard.system.devices.DeviceKey] hash). One
 *   derivation per key: a second one for the same device replaces the first,
 *   because two disagreeing answers about one headphone is not a state anyone
 *   can act on.
 * @param deviceName the name at derivation time, for display. Kept alongside
 *   the key like [AudiogramRun.deviceName], so the record can still say what it
 *   belongs to once the device is gone.
 * @param responseDeviationDb aligned with [TEST_FREQUENCIES_HZ], in the
 *   convention of [CalibrationPreset.fromResponseDeviation] (positive = the
 *   headphone plays that band louder). Stored in that convention rather than as
 *   [CalibrationPreset.offsetsDb] so the sign flip happens in exactly one place,
 *   the factory.
 * @param earSpreadDb [CalibrationTransfer.Result.earSpreadDb] — the quality
 *   figure, kept because the warnings that were raised from it are only
 *   meaningful next to the number.
 * @param warnings the caveats the derivation produced, verbatim. Persisted, not
 *   re-derived: they are about the run that produced this, and the runs behind
 *   it can be deleted afterwards.
 * @param sourceRunIds the runs the self-test medians came from. Provenance
 *   only — nothing recomputes from them, and they may well be gone.
 */
data class DerivedCalibration(
    val deviceKey: String,
    val deviceName: String?,
    val responseDeviationDb: List<Double>,
    val earSpreadDb: Double,
    val warnings: List<String>,
    val createdAtMillis: Long,
    val sourceRunIds: List<String>,
) {

    /**
     * What to call the headphone on screen.
     *
     * A run can reach the store with no name — an older build, or a device the
     * app never got a name for — and "Derived for null" is not a sentence. Two
     * spellings because the two call sites need different articles: this one
     * completes "Derived for …", [presetDeviceName] completes "your …".
     */
    val displayDeviceName: String
        get() = deviceName?.takeIf { it.isNotBlank() } ?: "this headphone"

    private val presetDeviceName: String
        get() = deviceName?.takeIf { it.isNotBlank() } ?: "headphone"

    /**
     * This derivation as a [CalibrationPreset] the compensation can be computed
     * with.
     *
     * `approximate = false` is the one flag worth arguing about, and it is
     * correct per the factory's own meaning: [CalibrationPreset.approximate]
     * documents itself as "the numbers are eyeballed from published charts, not
     * real data", and these numbers are neither eyeballed nor published. They
     * come from two measurements of these ears. The provenance strings carry
     * the caveat that actually applies — that this describes a device *plus a
     * person*, at one wearing — and the UI prints them.
     *
     * [DeviceFormFactor.UNKNOWN] on purpose. That field records which
     * measurement rig class a preset's numbers came from (an over-ear head
     * simulator and an IEM coupler are not comparable, which is why the bundled
     * presets carry it), and this transfer used no rig at all: it was measured
     * at a real ear, so the distinction the field encodes simply does not apply
     * here. Claiming either value would be claiming a coupler that was never
     * involved.
     */
    fun toPreset(): CalibrationPreset = CalibrationPreset.fromResponseDeviation(
        id = presetIdFor(deviceKey),
        displayName = "Measured — your $presetDeviceName",
        dataSource = "Derived from your clinical audiogram + your own test runs",
        measurementRig = "Your own ears, through this headphone",
        targetCurve = "None — the clinical audiogram is the reference",
        formFactor = DeviceFormFactor.UNKNOWN,
        responseDeviationDb = responseDeviationDb,
        approximate = false,
        notes = "Real measured data, not an approximation from a chart — but it " +
            "describes this headphone on your ears, at the fit you had during " +
            "those runs, and not the model in general. Re-derive after a change " +
            "of tips or pads. The two ears disagreed by up to " +
            "${"%.1f".format(earSpreadDb)} dB about the device; that difference " +
            "is fit and noise, not hearing.",
    )

    companion object {
        /**
         * Prefix that keeps a derived preset id out of the bundled namespace.
         *
         * The id has to be stable across launches — it is written into every
         * run and every saved profile — so it is a function of the device key
         * rather than anything generated.
         */
        const val ID_PREFIX: String = "derived_"

        fun presetIdFor(deviceKey: String): String = ID_PREFIX + deviceKey

        /** True for any id this class produces, whether or not it still exists. */
        fun isDerivedId(id: String?): Boolean = id != null && id.startsWith(ID_PREFIX)
    }
}

/**
 * The self-test side of the transfer: one threshold per frequency per ear,
 * pooled across the runs the curve is built from.
 *
 * Median rather than mean, for the same reason [MedianAudiogramAggregator] uses
 * one: a single lapse in attention should not decide a frequency, and three
 * runs is the smallest set where it can be outvoted.
 *
 * **Unconverged points are dropped, not medianed.** A point that did not
 * converge sat on the level floor or ceiling, which means "quieter than the app
 * can ask" or "louder than it is allowed to ask" — a statement about the test's
 * own limits. Let one into the transfer and its clipped value is subtracted
 * from a real clinical threshold, so the limit of the measurement comes out the
 * other end looking like a band this headphone plays quietly. That is the one
 * way this derivation can invent a device response out of nothing, so it is cut
 * off at the source. [CalibrationTransfer.derive] documents the same
 * requirement on its own inputs.
 *
 * A frequency where no run converged is simply absent from the map — the same
 * sparse-map convention [ClinicalAudiogram] uses, where a missing key means
 * "not measured" and never "0".
 */
object SelfTestThresholds {

    fun medianPerFrequency(runs: List<AudiogramRun>, ear: Ear): Map<Int, Double> =
        runs.flatMap { it.points(ear) }
            .filter { it.converged }
            .groupBy { it.frequencyHz }
            .mapValues { (_, points) -> MedianAudiogramAggregator.median(points.map { it.thresholdDb }) }
            .toSortedMap()
}
