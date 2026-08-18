package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.EqSettings

/**
 * Contract for the compensation math (Worker C, implemented strictly against
 * COMPENSATION.md — do not improvise the formula here).
 *
 * Baseline shape: per ear and frequency,
 *   `gain = (threshold - ISO 226 reference) * partialFactor`,
 * mapped onto the 10 EQ bands, with the partial factor deliberately well below
 * 1.0 (Mimi-style "split the difference") and a user-facing intensity slider.
 */

/**
 * A saved compensation profile: the audiogram it came from plus the user's
 * tuning, and the resulting EQ settings.
 *
 * @param intensity user-facing slider, 0.0 (flat) .. 1.0 (full configured
 *   compensation). Multiplies [partialFactor], never replaces it.
 * @param partialFactor the protocol-level partial compensation factor
 *   (~0.3–0.5); intentionally not 1:1, which would sound unnatural and clip.
 */
data class CompensationProfile(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val audiogram: Audiogram,
    val calibrationPresetId: String,
    val ancMode: AncMode,
    val intensity: Float = 1.0f,
    val partialFactor: Float = 0.4f,
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
    /** Offset in dB per entry of [TEST_FREQUENCIES_HZ]. */
    val offsetsDb: List<Double>,
) {
    init {
        require(offsetsDb.size == TEST_FREQUENCIES_HZ.size) {
            "offsetsDb must align with TEST_FREQUENCIES_HZ"
        }
    }
}

/** Lookup for bundled presets; "generic_uncalibrated" must always exist. */
interface CalibrationPresetRepository {
    fun all(): List<CalibrationPreset>
    fun byId(id: String): CalibrationPreset?

    companion object {
        const val GENERIC_ID = "generic_uncalibrated"
    }
}
