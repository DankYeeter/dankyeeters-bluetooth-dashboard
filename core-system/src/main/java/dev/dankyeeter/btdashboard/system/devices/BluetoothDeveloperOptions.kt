package dev.dankyeeter.btdashboard.system.devices

/** One selectable value of a [DeveloperOption], with the wording the UI shows. */
data class OptionValue(val raw: String, val label: String)

/**
 * A Bluetooth developer option this app is willing to touch.
 *
 * These are the same switches Android's own Developer Options screen writes:
 * plain `Settings.Global` entries, writable by anything holding
 * WRITE_SECURE_SETTINGS. Nothing here is a private API.
 *
 * @param needsBluetoothRestart the Bluetooth stack reads these at startup, so
 *   a change only takes effect after Bluetooth is turned off and on again. The
 *   UI must say so, otherwise the user changes a value, hears no difference,
 *   and reasonably concludes the app does nothing.
 * @param caution shown before the change. Non-null wherever a wrong value can
 *   degrade something the user will notice.
 */
data class DeveloperOption(
    val key: String,
    val label: String,
    val explanation: String,
    val values: List<OptionValue>,
    val needsBluetoothRestart: Boolean,
    val caution: String? = null,
) {
    fun labelFor(raw: String): String = values.firstOrNull { it.raw == raw }?.label ?: raw
}

/**
 * The registry of options the app offers per device.
 *
 * ## What "per device" means here, honestly
 *
 * Every one of these is a **global** setting — Android keeps exactly one value,
 * not one per headphone. "Per device" means the app re-applies the stored wish
 * whenever that device connects, exactly as [DeviceProfile.absoluteVolumeEnabled]
 * already does. Two headphones with conflicting wishes do not coexist; the last
 * one to connect wins, and the UI says so.
 *
 * ## What cannot be known in advance
 *
 * Whether a given build honours a given key. Reading tells us nothing: an
 * unset key reads `null` whether it is unsupported or simply untouched, and
 * `bluetooth_disable_absolute_volume` — which demonstrably works on this
 * phone — reads `null` too. So the applier writes and then reads back, and
 * reports "did not stick" rather than claiming success. Whether the *stack*
 * then acts on the value is a further step no app can observe, which is why
 * [needsBluetoothRestart] is stated rather than verified.
 *
 * Absolute volume is deliberately **not** in this registry even though it is
 * the same kind of setting: it already has its own typed field, its own gate
 * and its own UI. Two code paths writing one key is how a value ends up
 * fighting itself.
 */
object BluetoothDeveloperOptions {

    val avrcpVersion = DeveloperOption(
        key = "bluetooth_avrcp_version",
        label = "AVRCP version",
        explanation = "The remote-control profile: track titles, play/pause from the " +
            "headphone, and the volume handshake. Newer is not always better — some " +
            "headphones only expose metadata correctly on an older version.",
        values = listOf(
            OptionValue("avrcp13", "1.3"),
            OptionValue("avrcp14", "1.4"),
            OptionValue("avrcp15", "1.5"),
            OptionValue("avrcp16", "1.6"),
        ),
        needsBluetoothRestart = true,
        caution = "Changing this can stop track titles or the headphone's volume " +
            "buttons from working. Change it back if something breaks.",
    )

    val mapVersion = DeveloperOption(
        key = "bluetooth_map_version",
        label = "MAP version",
        explanation = "Message access — what a car reads your texts from. No effect " +
            "on audio quality.",
        values = listOf(
            OptionValue("map12", "1.2"),
            OptionValue("map13", "1.3"),
            OptionValue("map14", "1.4"),
        ),
        needsBluetoothRestart = true,
    )

    val pbapVersion = DeveloperOption(
        key = "bluetooth_pbap_client_version",
        label = "PBAP client version",
        explanation = "Phone-book access. Like MAP, nothing to do with how music " +
            "sounds.",
        values = listOf(
            OptionValue("pbap12", "1.2"),
            OptionValue("pbap13", "1.3"),
        ),
        needsBluetoothRestart = true,
    )

    /**
     * Verified to stick on a Pixel 8 Pro / Android 16 by writing it, reading it
     * back and restoring it — the same evidence the applier demands at runtime.
     */
    val showDevicesWithoutNames = DeveloperOption(
        key = "bluetooth_show_devices_without_names",
        label = "Show devices without names",
        explanation = "Lists nearby devices that advertise no name, as raw addresses. " +
            "Useful when a headphone will not show up in the pairing list; noisy " +
            "otherwise.",
        values = listOf(
            OptionValue("0", "Off"),
            OptionValue("1", "On"),
        ),
        // Read by the Settings UI each time it draws the list, not cached by the
        // stack at startup.
        needsBluetoothRestart = false,
    )

    val all: List<DeveloperOption> = listOf(
        avrcpVersion,
        mapVersion,
        pbapVersion,
        showDevicesWithoutNames,
    )

    /**
     * ## The rest of Android's Bluetooth developer switches, and why none of
     * them are here
     *
     * The list above looks short next to the Bluetooth section of Developer
     * Options, and that is not an oversight. Most of what is on that screen is
     * **not** a `Settings.Global` entry at all — AOSP's Settings app writes a
     * system property for it:
     *
     *  - *Disable Bluetooth A2DP hardware offload* →
     *    `persist.bluetooth.a2dp_offload.disabled`
     *  - *Enable Bluetooth HCI snoop log* → `persist.bluetooth.btsnooplogmode`
     *  - *Maximum connected Bluetooth audio devices* →
     *    `persist.bluetooth.maxconnectedaudiodevices`
     *
     * WRITE_SECURE_SETTINGS does not touch system properties. Offering these
     * through [SecureSettingsController] would create a `Settings.Global` key
     * of the same name that writes cleanly, reads back cleanly, and does
     * **nothing** — a green checkmark for an option that was never connected to
     * anything. That is precisely the false all-clear the read-back exists to
     * prevent, so they stay out.
     *
     * The A2DP-offload one is the painful omission: it is the switch that would
     * answer whether the app's EQ is even in the signal path (see HANDOVER, the
     * acoustic test). A shell can set it — `setprop` is what ADB does — so the
     * privileged helper could in principle offer it. That would mean a second
     * *mutating* privileged operation and a whitelist entry for a write
     * command, which is a decision worth making deliberately rather than as a
     * side effect of filling out a list.
     *
     * `ble_scan_always_enabled` *is* a real `Settings.Global` key, but it is a
     * Location setting rather than an audio one: it governs whether apps may
     * scan while Bluetooth is off. Turning it off to see what happens can break
     * location for unrelated apps, and this app has no business there.
     *
     * The codec pickers on that same screen are the interesting case: they are
     * neither settings nor properties. Android's own UI calls
     * `BluetoothA2dp.setCodecConfigPreference` directly, which is why codecs
     * are handled by the privileged helper and not by this registry — see
     * [CodecPreference].
     */
    fun byKey(key: String): DeveloperOption? = all.firstOrNull { it.key == key }

    /**
     * Stored value meaning "clear this key on every connect".
     *
     * Distinct from the key being absent from the profile (no wish at all):
     * this one actively deletes whatever any earlier write — from this app or
     * elsewhere — left in `Settings.Global`, restoring the stack's default.
     */
    const val USE_SYSTEM_DEFAULT = "use_system_default"

    /** Whether [raw] is one of the values [key] declares. Guards restored data. */
    fun isKnownValue(key: String, raw: String): Boolean =
        (raw == USE_SYSTEM_DEFAULT && byKey(key) != null) ||
            byKey(key)?.values?.any { it.raw == raw } == true
}

// ---- codec preferences ------------------------------------------------------

/**
 * A per-device codec wish.
 *
 * ## Why this is not a [DeveloperOption]
 *
 * Every option above is a `Settings.Global` string, written through
 * [SecureSettingsController]. A codec is not a setting anywhere in Android:
 * the picker in Developer Options calls `BluetoothA2dp.setCodecConfigPreference`
 * directly, guarded by BLUETOOTH_PRIVILEGED, and there is no shell command for
 * it either (`cmd bluetooth_manager` offers only enable/disable/enableBle/
 * disableBle/factoryReset/wait-for-state — checked on the device). So it goes
 * through the privileged helper as a typed operation, and it needs its own
 * shape rather than being forced into the string-keyed registry.
 *
 * ## Why the codec is a plain string
 *
 * `CodecFamily` lives in `:core-monitor`, which `:core-system` does not depend
 * on and should not start to. The value stored here is the *name* of a
 * `CodecFamily` constant, validated against [BluetoothCodecOptions] the same
 * way [DeviceProfile.developerOptions] validates its raw values — one shape for
 * "a stored wish the registry still recognises", not two.
 * `PrivilegedCodecTest` asserts that the list here and the codecs the helper is
 * actually willing to request stay identical.
 *
 * Zero means "state no preference for this field": a user who wants LDAC and
 * has no opinion about bit depth must not silently be forcing 16 bit.
 *
 * ## What "per device" means, honestly
 *
 * Unlike the options above, this one really *is* per device — the API takes a
 * `BluetoothDevice`. What is not per device is whether it lasts: the stack
 * renegotiates on every connect, so the wish is re-applied on connect exactly
 * like the others.
 */
data class CodecPreference(
    /** The name of a `CodecFamily` constant, e.g. `"LDAC"`. */
    val codec: String,
    val sampleRateHz: Int = 0,
    val bitsPerSample: Int = 0,
    /** 0 unspecified, 1 mono, 2 stereo, 3 dual channel. */
    val channelMode: Int = 0,
    /** AOSP's LDAC `codecSpecific1`: 1000..1003, or 0 for no preference. */
    val ldacQuality: Long = 0L,
) {
    val isValid: Boolean get() = BluetoothCodecOptions.isKnown(this)
}

/**
 * What this module will accept in a [CodecPreference].
 *
 * Only validation lives here — the translation to AOSP's bitmasks belongs next
 * to the reflection that uses it, in `:app`. Splitting it that way keeps
 * `:core-system` free of the Bluetooth internals while still refusing to store
 * a value nothing could act on.
 */
object BluetoothCodecOptions {

    /**
     * Codec families this app is willing to *request*.
     *
     * aptX Adaptive is absent on purpose: its codec id is a vendor value that
     * has moved between Android versions, so it can be read and named but not
     * asked for without guessing a number.
     */
    /**
     * "Give the decision back to the stack" — stored like a codec wish, applied
     * on every connect like one, but written at the stack's default priority
     * instead of pinning anything. Not in [codecs]: it is an action the device
     * can never fail to support, so it must not take part in the
     * available/unavailable sorting that list feeds.
     */
    const val SYSTEM_DEFAULT = "SYSTEM_DEFAULT"

    val codecs: List<String> = listOf("SBC", "AAC", "APTX", "APTX_HD", "LDAC", "LC3", "OPUS")

    val sampleRatesHz: List<Int> = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)

    val bitsPerSample: List<Int> = listOf(16, 24, 32)

    /** 1 mono, 2 stereo, 3 dual channel. Mirrors `ChannelModes` in `:app`. */
    val channelModes: List<Int> = listOf(1, 2, 3)

    val ldacQualities: List<Long> = listOf(1000L, 1001L, 1002L, 1003L)

    fun isKnown(preference: CodecPreference): Boolean =
        // System Default carries no sub-settings: there is nothing to force
        // when the whole point is to stop forcing things.
        (preference.codec == SYSTEM_DEFAULT &&
            preference.sampleRateHz == 0 && preference.bitsPerSample == 0 &&
            preference.channelMode == 0 && preference.ldacQuality == 0L) ||
        preference.codec in codecs &&
            (preference.sampleRateHz == 0 || preference.sampleRateHz in sampleRatesHz) &&
            (preference.bitsPerSample == 0 || preference.bitsPerSample in bitsPerSample) &&
            (preference.channelMode == 0 || preference.channelMode in channelModes) &&
            (preference.ldacQuality == 0L || preference.ldacQuality in ldacQualities) &&
            // A playback quality only exists on LDAC; carrying one anywhere
            // else means the stored profile disagrees with itself.
            (preference.ldacQuality == 0L || preference.codec == "LDAC")

    fun channelModeLabel(mode: Int): String = when (mode) {
        1 -> "Mono"
        2 -> "Stereo"
        3 -> "Dual channel"
        else -> "Use System Default"
    }

    /** The kbps figures are AOSP's own, for 44.1/48 kHz — what a phone streams. */
    fun ldacQualityLabel(quality: Long): String = when (quality) {
        1000L -> "Sound quality (909 kbps)"
        1001L -> "Standard (606 kbps)"
        1002L -> "Connection quality (303 kbps)"
        1003L -> "Adaptive bitrate"
        else -> "Use System Default"
    }
}

/** What happened when a codec was requested. Never a bare boolean. */
sealed interface CodecApplyOutcome {
    /** Requested **and read back**: the device is on this codec now. */
    data class Applied(val observed: String) : CodecApplyOutcome

    /**
     * The call was accepted and the read-back says something else.
     *
     * Deliberately not called "failed": the stack may be mid-renegotiation, and
     * nothing reachable from an app can tell that apart from a refusal. [detail]
     * carries what the helper actually saw, including how long it waited.
     */
    data class NotObserved(val observed: String, val detail: String) : CodecApplyOutcome

    /** No helper, no permission, or a codec this build cannot express. */
    data class Unavailable(val reason: String) : CodecApplyOutcome
}

/**
 * Sets an A2DP codec for one device.
 *
 * The implementation is in `:app` because only that module can reach the
 * privileged helper. Declared here as an interface for the same reason
 * [SecureSettingsController] is: the applier must stay testable with plain
 * fakes and no device.
 *
 * **This is the first operation in the whole app that changes something on the
 * phone through privileged access.** Everything the helper did before it was
 * read-only — three dumpsys/ps commands. That is why it has its own interface,
 * its own outcome type and its own branch in [DeviceProfileApplier], rather
 * than a boolean threaded through the settings path.
 */
interface CodecPreferenceController {

    /** False when the helper is not running. Never confused with "no codecs". */
    fun isAvailable(): Boolean

    /**
     * Requests [preference] for [address] and reports what was **observed**
     * afterwards, in the spirit of [SecureSettingsController.write]: a call
     * that returned without throwing is not evidence.
     */
    suspend fun apply(address: String, preference: CodecPreference): CodecApplyOutcome
}

/**
 * Stands in when the privileged helper is not installed.
 *
 * Says "cannot check", never "no". A controller that answered "not applied"
 * when it simply could not look would be the same lie as an empty codec list.
 */
object UnavailableCodecPreferenceController : CodecPreferenceController {
    override fun isAvailable(): Boolean = false

    override suspend fun apply(address: String, preference: CodecPreference): CodecApplyOutcome =
        CodecApplyOutcome.Unavailable(
            "the privileged helper is not running, so the codec cannot be set or checked",
        )
}

/**
 * Reads and writes `Settings.Global` entries.
 *
 * Android-free so the applier stays testable with plain fakes, in the same
 * style as [MediaVolumeController] and [AbsoluteVolumeController].
 */
interface SecureSettingsController {

    /** Whether WRITE_SECURE_SETTINGS is granted. False means every write is refused. */
    fun isWritable(): Boolean

    /** Current value, or null when the key is unset — which is not the same as unsupported. */
    fun read(key: String): String?

    /**
     * Writes [value] and confirms it by reading back.
     *
     * Returns false when the value did not land, which is the only evidence
     * available that a build does not accept a key. A write that reports
     * success without reading back would turn "this phone ignores it" into a
     * green checkmark.
     */
    fun write(key: String, value: String): Boolean

    /**
     * Removes the key entirely, confirmed by a null read-back.
     *
     * This is what "Use System Default" really is: an unset key is the state a
     * fresh phone is in, and the stack falls back to its own default. Writing
     * some guessed "default value" instead would freeze today's default into
     * the settings database.
     */
    fun clear(key: String): Boolean
}
