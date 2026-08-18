package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.fit.DeviceFormFactor

/**
 * Contract for the compensation math (Worker C, implemented strictly against
 * COMPENSATION.md — do not improvise the formula here).
 *
 * The implemented rule is NAL-R (see [NalR] and [NalRCompensationCalculator]);
 * the "threshold minus reference times a factor" sketch in the Stage A comment
 * was superseded by COMPENSATION.md, which is authoritative.
 */

/** Default of the user-facing intensity slider, per COMPENSATION.md step 4. */
const val DEFAULT_INTENSITY: Float = 0.6f

/**
 * Protocol-level partial factor. COMPENSATION.md folds partial compensation
 * entirely into the intensity slider `s` (default 0.6 of full NAL-R), so the
 * extra factor kept by the Stage A interface stays at 1.0 by default; the
 * calculator multiplies the two.
 */
const val DEFAULT_PARTIAL_FACTOR: Float = 1.0f

/**
 * A saved compensation profile: the audiogram it came from plus the user's
 * tuning, and the resulting EQ settings.
 *
 * @param intensity user-facing slider `s`, 0.0 (flat) .. 1.0 (full NAL-R).
 *   Multiplies [partialFactor], never replaces it.
 * @param partialFactor additional protocol-level partial compensation factor.
 *   Effective strength is `intensity * partialFactor`.
 */
data class CompensationProfile(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val audiogram: Audiogram,
    val calibrationPresetId: String,
    val ancMode: AncMode,
    val intensity: Float = DEFAULT_INTENSITY,
    val partialFactor: Float = DEFAULT_PARTIAL_FACTOR,
    val eq: EqSettings,
)

/** Turns an audiogram into per-ear band gains. */
interface CompensationCalculator {
    /**
     * @param intensity 0.0..1.0 user slider
     * @param partialFactor protocol partial compensation factor
     * @return sanitized [EqSettings] including negative pre-gain headroom
     */
    fun compute(
        audiogram: Audiogram,
        calibrationPresetId: String,
        intensity: Float,
        partialFactor: Float,
    ): EqSettings
}

/**
 * Device calibration preset: measured frequency-response offsets from public
 * measurement databases, used as a *shape* correction only.
 *
 * Presets carry their provenance because over-ear rigs and IEM couplers are not
 * comparable; the UI shows this so the numbers are never mistaken for absolute
 * calibrated levels.
 */
data class CalibrationPreset(
    val id: String,
    val displayName: String,
    val dataSource: String,      // e.g. "Crinacle", "Rtings"
    val measurementRig: String,  // e.g. "GRAS 43AG-7 (over-ear)"
    val targetCurve: String,     // e.g. "Harman OE 2018"
    /**
     * Threshold correction in dB per entry of [TEST_FREQUENCIES_HZ]: the value
     * that COMPENSATION.md step 3.2 subtracts from the raw threshold to obtain
     * the device-corrected `H_T(f)`.
     *
     * Sign convention: this is the *negated* response deviation. If a headphone
     * plays a band 3 dB louder than its target curve, the measured threshold
     * comes out 3 dB too low, so the correction stored here is `-3.0` and
     * `H_T = threshold - (-3) = threshold + 3`. Build presets through
     * [fromResponseDeviation] and this stays invisible.
     */
    val offsetsDb: List<Double>,
    /** Drives the mandatory fit check: IEMs always, over-ears optionally. */
    val formFactor: DeviceFormFactor = DeviceFormFactor.OVER_EAR,
    /** True while the numbers are eyeballed from published charts, not real data. */
    val approximate: Boolean = true,
    /** Free-text provenance shown in the UI next to the preset. */
    val notes: String = "",
) {
    init {
        require(offsetsDb.size == TEST_FREQUENCIES_HZ.size) {
            "offsetsDb must align with TEST_FREQUENCIES_HZ"
        }
    }

    /** Mandatory fit check before a test run, per PLAN.md ("all IEMs"). */
    val requiresFitCheck: Boolean get() = formFactor.fitCheckMandatory

    /** One-line provenance summary for the UI. */
    fun provenanceLine(): String = buildString {
        append(dataSource)
        append(" · ")
        append(measurementRig)
        append(" · target ")
        append(targetCurve)
        if (approximate) append(" · APPROXIMATE")
    }

    companion object {
        /**
         * Builds a preset from published *response deviation* values (positive
         * = the headphone reproduces that band louder than [targetCurve]),
         * which is how measurement databases publish them. The stored
         * [offsetsDb] are the negated values — see the field doc.
         */
        fun fromResponseDeviation(
            id: String,
            displayName: String,
            dataSource: String,
            measurementRig: String,
            targetCurve: String,
            formFactor: DeviceFormFactor,
            responseDeviationDb: List<Double>,
            approximate: Boolean = true,
            notes: String = "",
        ): CalibrationPreset = CalibrationPreset(
            id = id,
            displayName = displayName,
            dataSource = dataSource,
            measurementRig = measurementRig,
            targetCurve = targetCurve,
            offsetsDb = responseDeviationDb.map { -it },
            formFactor = formFactor,
            approximate = approximate,
            notes = notes,
        )
    }
}

// The physical coupling of the device (over-ear rig vs. IEM coupler — not
// comparable) is modelled once, in hearing.fit.DeviceFormFactor, because the
// fit check is its other consumer.

/** Lookup for bundled presets; "generic_uncalibrated" must always exist. */
interface CalibrationPresetRepository {
    fun all(): List<CalibrationPreset>
    fun byId(id: String): CalibrationPreset?

    companion object {
        const val GENERIC_ID = "generic_uncalibrated"
    }
}
