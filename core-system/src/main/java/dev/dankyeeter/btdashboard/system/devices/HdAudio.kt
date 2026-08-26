package dev.dankyeeter.btdashboard.system.devices

/**
 * Android's per-device "HD audio" switch — what AOSP calls *optional codecs*.
 *
 * ## What it actually is
 *
 * The row labelled "HD audio" in Android's own Bluetooth device settings is
 * `BluetoothA2dp.setOptionalCodecsEnabled`. Turned off, the stack refuses to
 * negotiate anything but SBC for that headphone, no matter what
 * [CodecPreference] asks for. That makes it the gate *in front of* the codec
 * picker rather than another entry in it: with HD audio off, a request for LDAC
 * is not refused loudly — it simply comes back as SBC, and the codec section can
 * only report that the read-back disagreed, without being able to say why.
 *
 * ## Why it is genuinely per device
 *
 * Unlike everything in [BluetoothDeveloperOptions], the stack stores this one
 * *per bonded device*, in its own database. Setting it for one headphone does
 * not touch another, and the value survives a disconnect. It is therefore the
 * only Bluetooth setting in this app that is a real per-device state rather
 * than a global re-applied on connect — worth saying out loud, because the rest
 * of the editor spends a lot of words explaining the opposite.
 *
 * ## Why it needs the privileged helper
 *
 * All three methods are `@SystemApi` guarded by BLUETOOTH_PRIVILEGED, which no
 * ordinary app holds. `com.android.shell` does — verified on the device,
 * `granted=true` — and the helper runs as that uid, so the calls are reachable
 * from inside it and nowhere else. There is no shell command and no
 * `Settings.Global` key that would do it instead.
 */
enum class HdAudioPreference {
    /** `OPTIONAL_CODECS_PREF_ENABLED`. */
    ENABLE,

    /** `OPTIONAL_CODECS_PREF_DISABLED` — pins the link to SBC. */
    DISABLE,

    /**
     * `OPTIONAL_CODECS_PREF_UNKNOWN`, and a real third state rather than a
     * synonym for one of the other two.
     *
     * AOSP treats it as "the user has never expressed a preference" and falls
     * back to its own rule — enable them where the headphone supports them. It
     * is the same idea as [BluetoothDeveloperOptions.USE_SYSTEM_DEFAULT]: a
     * stored wish must be withdrawable, not only replaceable.
     */
    SYSTEM_DEFAULT;

    /**
     * The value a read-back must show for this wish to be satisfied.
     *
     * Null for [SYSTEM_DEFAULT] because that is genuinely what the stack
     * reports back for "unknown" — so the comparison stays a plain equality and
     * no caller has to special-case the third state.
     */
    fun asEnabled(): Boolean? = when (this) {
        ENABLE -> true
        DISABLE -> false
        SYSTEM_DEFAULT -> null
    }

    companion object {
        /** The wording the editor and the result sentences share. */
        fun label(preference: HdAudioPreference?): String = when (preference) {
            ENABLE -> "On"
            DISABLE -> "Off — SBC only"
            SYSTEM_DEFAULT -> "Use System Default"
            null -> "Leave alone"
        }
    }
}

/** What may honestly be claimed about HD audio for one device right now. */
sealed interface HdAudioState {

    /**
     * The stack answered.
     *
     * @param supported whether the headphone offers anything beyond SBC at all.
     *   False makes the toggle pointless rather than broken, and the UI says so.
     * @param enabled null when the stack reports `OPTIONAL_CODECS_PREF_UNKNOWN`,
     *   i.e. nobody has chosen yet. Deliberately not folded into `true`: "on
     *   because Android decided" and "on because you asked" become the same
     *   sentence otherwise, and only one of them is something the user did.
     */
    data class Known(val supported: Boolean, val enabled: Boolean?) : HdAudioState

    /** No helper, device not bonded, or the call failed. Never a silent false. */
    data class Unreadable(val reason: String) : HdAudioState
}

/**
 * What happened when HD audio was set. Never a bare boolean, for the same
 * reason [CodecApplyOutcome] is not one: the call returning is not evidence.
 */
sealed interface HdAudioOutcome {
    /** Written **and read back**. [enabled] is what the read-back said. */
    data class Applied(val enabled: Boolean?) : HdAudioOutcome

    /** The write was accepted and the read-back says something else. */
    data class NotObserved(val detail: String) : HdAudioOutcome

    /** No helper, no permission, or this build does not expose the API. */
    data class Unavailable(val reason: String) : HdAudioOutcome
}

/**
 * Reads and writes the per-device HD-audio switch.
 *
 * Implemented in `:app`, for the same reason [CodecPreferenceController] is:
 * only that module can reach the privileged helper's Binder. Declared here as
 * an interface so the applier stays testable with plain fakes and no device.
 */
interface HdAudioController {

    /** False when the helper is not running. Never confused with "not supported". */
    fun isAvailable(): Boolean

    suspend fun read(address: String): HdAudioState

    /** Writes [preference] and reports what was **observed** afterwards. */
    suspend fun apply(address: String, preference: HdAudioPreference): HdAudioOutcome
}

/**
 * Stands in when the privileged helper is not installed.
 *
 * Answers "cannot check", never "not supported" — the second would grey out a
 * toggle for a headphone that supports HD audio perfectly well.
 */
object UnavailableHdAudioController : HdAudioController {
    private const val NO_HELPER =
        "the privileged helper is not running, so HD audio can be neither set nor checked"

    override fun isAvailable(): Boolean = false

    override suspend fun read(address: String): HdAudioState = HdAudioState.Unreadable(NO_HELPER)

    override suspend fun apply(address: String, preference: HdAudioPreference): HdAudioOutcome =
        HdAudioOutcome.Unavailable(NO_HELPER)
}
