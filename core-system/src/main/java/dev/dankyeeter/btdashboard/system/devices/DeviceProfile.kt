package dev.dankyeeter.btdashboard.system.devices

/**
 * What the app should do when a particular headphone connects.
 *
 * Every field except [deviceKey] and [name] is optional on purpose: "leave this
 * alone" and "set this to X" are genuinely different intentions, and a profile
 * that silently forced a volume the user never chose would be worse than no
 * profile at all. Null means "don't touch".
 */
data class DeviceProfile(
    /** SHA-256 based key from [DeviceKey]; never a raw MAC. */
    val deviceKey: String,
    /** User-facing name, pre-filled from the BT device name on first sight. */
    val name: String,
    /** Calibration preset id — also selects the line-art icon in the UI. */
    val calibrationPresetId: String? = null,
    /** Id of a stored compensation profile to activate on connect. */
    val compensationProfileId: String? = null,
    /** Media volume to restore, 0..100 percent of the stream's max. */
    val mediaVolumePercent: Int? = null,
    /**
     * Per-device wish for Android's absolute-volume feature. This maps onto a
     * single *global* setting (see [dev.dankyeeter.btdashboard.system.devices.AbsoluteVolumeGate]),
     * so it is applied on connect rather than being a per-device state the
     * system actually keeps.
     */
    val absoluteVolumeEnabled: Boolean? = null,
    /** Whether the profile is applied automatically on ACL connect. */
    val autoApply: Boolean = true,
    val lastSeenAtMillis: Long = 0L,
) {
    fun sanitized(): DeviceProfile = copy(
        name = name.trim().ifBlank { "Unnamed device" }.take(MAX_NAME_LENGTH),
        mediaVolumePercent = mediaVolumePercent?.coerceIn(0, 100),
    )

    companion object {
        const val MAX_NAME_LENGTH = 60
    }
}

/** One thing the applier did (or refused to do), for the UI and the tests. */
sealed interface ProfileAction {
    data class VolumeSet(val percent: Int) : ProfileAction
    data class CompensationApplied(val profileId: String) : ProfileAction
    data class AbsoluteVolumeSet(val enabled: Boolean) : ProfileAction
    data class Skipped(val what: String, val reason: String) : ProfileAction
}

/** Outcome of a connect event. */
sealed interface ApplyResult {
    /** The address was unusable (null/garbage) — nothing was attempted. */
    data object UnknownAddress : ApplyResult

    /** No stored profile for this device; the UI may offer to create one. */
    data class NoProfile(val deviceKey: String) : ApplyResult

    /** A profile exists but has auto-apply switched off. */
    data class AutoApplyDisabled(val profile: DeviceProfile) : ApplyResult

    data class Applied(
        val profile: DeviceProfile,
        val actions: List<ProfileAction>,
    ) : ApplyResult
}
