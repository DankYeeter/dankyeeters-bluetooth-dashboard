package dev.dankyeeter.btdashboard.system.devices

/**
 * The Bluetooth settings that belong to the *phone* rather than to one
 * headphone, and — just as deliberately — the ones that do not, with the reason
 * they cannot be offered.
 *
 * ## Why this exists next to [BluetoothDeveloperOptions]
 *
 * That registry describes the same `Settings.Global` keys, but as a **wish
 * re-applied when a chosen device connects**. This one is the other half of the
 * same truth: the keys are global, so there is also a value in force right now,
 * independent of any profile, and until this panel existed there was no way to
 * see or change it without opening some headphone's profile — which implied a
 * per-device setting that does not exist.
 *
 * The two are not duplicates and must not be collapsed. "Set AVRCP 1.4 whenever
 * the Bathys connects" and "AVRCP is 1.4 right now" answer different questions,
 * and a user who only ever wants the second should not have to express it as
 * the first.
 */
object BluetoothSystemControls {

    /**
     * The globals this panel will write, in the order they are shown.
     *
     * Exactly [BluetoothDeveloperOptions.all], on purpose rather than by
     * coincidence: a key this app is willing to write per device is a key it is
     * willing to write globally, and two lists that could drift apart would
     * eventually offer an option in one place and not the other with no reason
     * a reader could find.
     *
     * All four were verified on a Pixel 11 Pro / Android 17 by writing a value,
     * reading it back and deleting it again — the same evidence the applier
     * demands at runtime, done by hand once so the registry is not a guess.
     */
    val writableGlobals: List<DeveloperOption> = BluetoothDeveloperOptions.all
}

/**
 * A Bluetooth knob that genuinely affects audio and that this app **cannot**
 * change, shown read-only with its live value and the reason.
 *
 * ## Why show them at all
 *
 * Because leaving them out is the dishonest option. Someone looking for "A2DP
 * hardware offload" — the switch that decides whether this app's EQ is even in
 * the signal path — finds Android's own Developer Options offering it and this
 * app silently not, and has no way to tell "we chose not to" from "we could
 * not" from "we forgot". A row that names the mechanism and quotes the refusal
 * answers all three at once.
 *
 * @param liveValueKey the system property whose value the UI reads and shows.
 *   Unset is the normal state and is not an error: it means the stack's own
 *   built-in default applies, which the UI says in those words.
 */
data class ReadOnlySystemSetting(
    val label: String,
    val liveValueKey: String,
    val explanation: String,
    /** Why it cannot be changed. Concrete, and quoting the real refusal. */
    val whyReadOnly: String,
)

/**
 * The Bluetooth switches Android's Developer Options offers that this app has
 * to show read-only.
 *
 * ## The one reason, stated once
 *
 * Every entry here is a **system property**, not a `Settings.Global` key.
 * WRITE_SECURE_SETTINGS — the permission the helper grants this app — reaches
 * settings and nothing else. Setting a property is a different mechanism
 * entirely: `init` owns the property space and enforces per-property SELinux
 * rules on who may write, and `persist.bluetooth.*` is not writable by the
 * shell domain. Verified on the device rather than assumed:
 *
 * ```
 * $ setprop persist.bluetooth.a2dp_offload.disabled false
 * Failed to set property 'persist.bluetooth.a2dp_offload.disabled' to 'false'.
 * See dmesg for error reason.
 * ```
 *
 * The helper runs as `u:r:shell:s0` (uid 2000, confirmed with `id`), so it hits
 * exactly the same wall — being the helper does not help here. Root would, and
 * this app does not have it and does not ask for it.
 *
 * ## Why they are not simply offered anyway
 *
 * Because [SecureSettingsController] would accept them. Writing
 * `persist.bluetooth.a2dp_offload.disabled` as a *setting* creates a
 * `Settings.Global` entry of that name which writes cleanly, reads back
 * cleanly, and is connected to nothing — a green checkmark on an option that
 * never did anything. That is precisely the false all-clear the read-back
 * exists to prevent.
 */
object BluetoothReadOnlySettings {

    val a2dpHardwareOffload = ReadOnlySystemSetting(
        label = "A2DP hardware offload",
        liveValueKey = "persist.bluetooth.a2dp_offload.disabled",
        explanation = "Whether Bluetooth audio is encoded by a dedicated chip instead " +
            "of by the main processor. Offload saves battery. It also moves the audio " +
            "out of the path this app's equaliser sits in, which is why it is worth " +
            "seeing even though it cannot be changed here.",
        whyReadOnly = "This switch is a system property, not a setting. Properties are " +
            "owned by the system's init process, which refuses writes from the shell — " +
            "and the helper is the shell. Only a rooted phone can change it.",
    )

    val maxConnectedAudioDevices = ReadOnlySystemSetting(
        label = "Maximum connected audio devices",
        liveValueKey = "persist.bluetooth.maxconnectedaudiodevices",
        explanation = "How many audio devices may stay connected at once. More " +
            "connections share the same radio, so raising it can cost stability on " +
            "the one you are actually listening to.",
        whyReadOnly = "Also a system property, and refused for the same reason: the " +
            "helper runs as the shell, and the shell may not write it.",
    )

    val hciSnoopLog = ReadOnlySystemSetting(
        label = "Bluetooth HCI snoop log",
        liveValueKey = "persist.bluetooth.btsnooplogmode",
        explanation = "Records everything the Bluetooth radio sends and receives, for " +
            "debugging. No effect on sound quality; listed so this panel is the whole " +
            "picture rather than the flattering part of it.",
        whyReadOnly = "A system property again — the shell may not write it, so neither " +
            "can the helper.",
    )

    val all: List<ReadOnlySystemSetting> =
        listOf(a2dpHardwareOffload, maxConnectedAudioDevices, hciSnoopLog)
}

/** Reads system properties. Trivial on Android, and untestable without a port. */
interface SystemPropertyReader {
    /** Null when the property is unset — the normal state, not a failure. */
    fun read(key: String): String?
}

/** Answers "unset" for everything, so tests need no Android. */
object NoSystemPropertyReader : SystemPropertyReader {
    override fun read(key: String): String? = null
}

// ---- restarting the stack ---------------------------------------------------

/** What happened when Bluetooth was cycled. */
sealed interface BluetoothRestartOutcome {
    /** Off and on again, both states confirmed by the stack before returning. */
    data object Restarted : BluetoothRestartOutcome

    /**
     * The commands ran and the adapter did not reach the expected state.
     *
     * Distinct from [Unavailable]: something was attempted, and the radio may
     * now be off. The UI has to say that rather than implying nothing happened.
     */
    data class Failed(val detail: String) : BluetoothRestartOutcome

    /** No helper. Nothing was attempted, so the radio is untouched. */
    data class Unavailable(val reason: String) : BluetoothRestartOutcome
}

/**
 * Turns Bluetooth off and on again.
 *
 * ## Why the app offers this at all
 *
 * Because [DeveloperOption.needsBluetoothRestart] has been telling users to do
 * it by hand since the option registry existed. "Stored. Turn Bluetooth off and
 * on for it to take effect." is honest and is also a dead end in a project that
 * does not allow them: the app knows exactly what needs to happen, can do it,
 * and was sending the user to the quick settings panel instead.
 *
 * ## Why it needs the privileged helper
 *
 * `BluetoothAdapter.disable()` was removed for ordinary apps; what remains is
 * `cmd bluetooth_manager disable` / `enable`, which needs shell. Verified on
 * the device: as uid 2000 both return `Success`, and `wait-for-state:STATE_OFF`
 * / `STATE_ON` confirm the transition rather than leaving it to a sleep.
 *
 * ## Why it is not a whitelist entry in the helper
 *
 * It changes the state of the phone. The helper's whitelist is classified
 * read-only, and putting a mutating command behind that door is the confusion
 * the operation enum exists to prevent — so this travels as its own typed,
 * explicitly-mutating operation, exactly like granting the settings permission
 * does.
 */
interface BluetoothRestartController {

    /** False when the helper is not running. */
    fun isAvailable(): Boolean

    suspend fun restart(): BluetoothRestartOutcome
}

/** Stands in when the helper is absent. Never claims a restart happened. */
object UnavailableBluetoothRestartController : BluetoothRestartController {
    override fun isAvailable(): Boolean = false

    override suspend fun restart(): BluetoothRestartOutcome =
        BluetoothRestartOutcome.Unavailable(
            "the privileged helper is not running, so Bluetooth cannot be restarted from here",
        )
}
