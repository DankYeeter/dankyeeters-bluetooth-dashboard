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
    /**
     * Whether connecting should hand the absolute-volume setting **back to
     * Android** by deleting the key.
     *
     * A third state that [absoluteVolumeEnabled] cannot express: `true`, `false`
     * and "no wish" are all it has, and none of them mean "undo whatever is
     * there". Without this, a wish once stored could only be replaced, never
     * withdrawn — the same dead end the codec picker had.
     *
     * Wins over [absoluteVolumeEnabled] when both are set; the editor never
     * produces that combination, but restored data might.
     */
    val absoluteVolumeSystemDefault: Boolean = false,
    /**
     * Per-device wishes for Bluetooth developer options, keyed by their
     * `Settings.Global` key (see [BluetoothDeveloperOptions]).
     *
     * Same caveat as [absoluteVolumeEnabled], and for the same reason: Android
     * keeps one global value, not one per headphone. Storing them per device
     * means "re-apply this whenever that device connects", so two headphones
     * with conflicting wishes do not coexist — the last to connect wins.
     */
    val developerOptions: Map<String, String> = emptyMap(),
    /**
     * Which A2DP codec to ask for when this device connects.
     *
     * Unlike [developerOptions] this one genuinely is per device — the API
     * takes a `BluetoothDevice`. What is not permanent is the result: the stack
     * renegotiates on every connect, which is why it is stored as a wish and
     * re-applied rather than set once.
     *
     * Needs the privileged helper; without it the applier reports that it could
     * not be attempted, never that it was left alone.
     */
    val codecPreference: CodecPreference? = null,
    /** Whether the profile is applied automatically on ACL connect. */
    val autoApply: Boolean = true,
    val lastSeenAtMillis: Long = 0L,
) {
    fun sanitized(): DeviceProfile = copy(
        name = name.trim().ifBlank { "Unnamed device" }.take(MAX_NAME_LENGTH),
        mediaVolumePercent = mediaVolumePercent?.coerceIn(0, 100),
        // Drop anything the registry no longer recognises. Restored data can
        // outlive the option that produced it, and writing an unknown value
        // into a system setting is not a risk worth carrying for a stale key.
        developerOptions = developerOptions.filter { (key, value) ->
            BluetoothDeveloperOptions.isKnownValue(key, value)
        },
        // Same reasoning, and it matters more here: this one is a *write* to
        // the Bluetooth stack, so a stale value is not a wrong label but a
        // renegotiation nobody asked for.
        codecPreference = codecPreference?.takeIf { it.isValid },
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

    /** The key was deleted, so Android's own default governs again. */
    data object AbsoluteVolumeReset : ProfileAction

    /**
     * A developer option was written and read back successfully.
     *
     * [needsBluetoothRestart] is carried so the UI can say the value is stored
     * but not yet in force — the stack reads these at startup.
     */
    data class DeveloperOptionSet(
        val key: String,
        val value: String,
        val needsBluetoothRestart: Boolean,
        val alreadySet: Boolean,
    ) : ProfileAction

    /**
     * A codec was requested and the helper **read back** that it took.
     *
     * The only action in this list that reports a privileged *write*. It is
     * emitted solely on a confirmed read-back; a request that was accepted but
     * not observed is a [CodecNotObserved], and the two are separate types so
     * no caller can collapse them into one green line.
     */
    data class CodecSet(val observed: String) : ProfileAction

    /**
     * The request went through and the codec still reads as something else.
     *
     * Not a failure and not a success: the stack may be mid-renegotiation, and
     * an app cannot tell that apart from a refusal. [detail] is the helper's
     * own words, including how long it waited.
     */
    data class CodecNotObserved(val observed: String, val detail: String) : ProfileAction

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
