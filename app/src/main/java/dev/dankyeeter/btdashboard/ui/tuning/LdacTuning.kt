package dev.dankyeeter.btdashboard.ui.tuning

import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.privileged.PrivilegedCodec
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import dev.dankyeeter.btdashboard.system.devices.CodecPreferenceController
import dev.dankyeeter.btdashboard.system.devices.DeviceKey
import dev.dankyeeter.btdashboard.system.devices.DeviceProfile
import dev.dankyeeter.btdashboard.ui.screens.monitor.rawAddressFor
import dev.dankyeeter.btdashboard.ui.screens.monitor.redactAddresses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the bitrate control has to say about its last attempt.
 *
 * A separate state rather than a boolean because setting a codec preference has
 * three outcomes, not two: applied and read back, accepted but not observed, and
 * "could not even ask". Whichever screen the user tapped, the sentence is the
 * same one — see [CodecApplyOutcome] for why the middle one is not a failure.
 *
 * It lives here rather than beside the Monitoring panel because two screens now
 * carry the same chips, and two copies of this would eventually disagree about
 * whether a request was in flight.
 */
data class LdacTuningState(
    /** True while a request is in flight, so the chips cannot be double-tapped. */
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/**
 * The playback-quality ladder, and the one rule for which chip is lit.
 *
 * ## Why this is a separate object rather than a table in each panel
 *
 * The Monitoring panel and the Bluetooth tab draw the same four chips for the
 * same link. When each owned its own list and its own "which one is selected"
 * rule, they could — and in an earlier shape did — disagree about the state of
 * one headphone. [selected] is that rule, written once.
 *
 * ## Structured for the next codec, not only for LDAC
 *
 * LDAC is the only A2DP codec on this phone with a playback-quality knob the
 * stack will take a request for ([PINNABLE_CODECS] is a set for that reason,
 * not for symmetry). A second codec with a quality index joins by entering that
 * set and supplying a ladder; nothing in the UI hard-codes "LDAC" beyond asking
 * [supportsQualityPinning].
 */
object LdacQuality {

    /** No quality stored: the stack runs its own default, which is ABR. */
    const val NONE = 0L

    const val HIGH_QUALITY = 1000L
    const val STANDARD = 1001L
    const val CONNECTION_PRIORITY = 1002L

    /** Adaptive bitrate — what the chips call ABR, and the resting state. */
    const val ADAPTIVE = 1003L

    /**
     * The four values that can be *asked for*, in the order the chips show them.
     *
     * [NONE] is absent on purpose: it is the state of never having chosen, and
     * it cannot be requested. Asking for adaptive explicitly is [ADAPTIVE].
     */
    val pinnable: List<Long> = listOf(HIGH_QUALITY, STANDARD, CONNECTION_PRIORITY, ADAPTIVE)

    /** Codec families whose playback quality this app can pin. */
    val PINNABLE_CODECS: Set<String> = setOf("LDAC")

    fun supportsQualityPinning(codec: String?): Boolean = codec in PINNABLE_CODECS

    /**
     * The chip's own rate figure, or "ABR" where no single rate exists.
     *
     * [LdacState.nominalKbps] is asked rather than a table written here, so the
     * 44.1/88.2 and 48/96 kHz ladders stay in one place. A screen with no live
     * link passes a null sample rate and gets the 48 kHz family, which is what
     * a phone streams.
     */
    fun chipLabel(quality: Long, sampleRateHz: Int? = null): String =
        LdacState.nominalKbps(LdacState.modeOf(quality), sampleRateHz)
            ?.let { "$it kbps" }
            ?: "ABR"

    /** The code a live-observed mode was requested as, or [NONE] for "never pinned". */
    fun codeOf(mode: LdacQualityMode?): Long = when (mode) {
        LdacQualityMode.HIGH_QUALITY -> HIGH_QUALITY
        LdacQualityMode.STANDARD -> STANDARD
        LdacQualityMode.CONNECTION_PRIORITY -> CONNECTION_PRIORITY
        LdacQualityMode.ADAPTIVE -> ADAPTIVE
        else -> NONE
    }

    /**
     * The quality a stored profile asks for, or [NONE] when it asks for nothing.
     *
     * A quality carried on a non-LDAC wish is ignored rather than shown: the
     * store's own validity check already refuses that combination, and reading
     * it here would light a chip for a link that will never run it.
     */
    fun storedQuality(profile: DeviceProfile?): Long =
        profile?.codecPreference
            ?.takeIf { supportsQualityPinning(it.codec) }
            ?.ldacQuality
            ?: NONE

    /**
     * Which chip is lit, from the persisted wish first and the live link second.
     *
     * The stored preference wins because it is what will happen on the next
     * connect — the promise the control makes. With nothing stored, a screen
     * that can see the link says what the link is doing; a screen that cannot
     * passes null and lands on [ADAPTIVE], which is the truth about an unpinned
     * LDAC link rather than a guess.
     */
    fun selected(stored: Long, observed: LdacQualityMode? = null): Long = when {
        stored != NONE -> stored
        codeOf(observed) != NONE -> codeOf(observed)
        else -> ADAPTIVE
    }

    /**
     * The profile that stores [quality] for this device.
     *
     * Two shapes, and the difference is the whole "withdraw a wish" problem the
     * rest of this editor already solved once for absolute volume:
     *
     *  - a pinned rate stores a codec wish for LDAC carrying that quality. The
     *    codec has to travel with it: [CodecPreference] holds the quality, and a
     *    quality on anything but LDAC is a profile that disagrees with itself.
     *  - [ADAPTIVE] clears the quality instead of storing 1003. Adaptive is what
     *    an unpinned link already does, so storing a request for it would mean
     *    renegotiating the codec on every connect to reach the state the stack
     *    was in anyway. A profile that asked for nothing else is left asking for
     *    nothing, rather than gaining an LDAC wish it never had.
     */
    fun withQuality(profile: DeviceProfile, quality: Long): DeviceProfile {
        val existing = profile.codecPreference
        return when {
            quality == ADAPTIVE || quality == NONE -> profile.copy(
                codecPreference = existing
                    ?.takeIf { supportsQualityPinning(it.codec) }
                    ?.copy(ldacQuality = NONE)
                    ?: existing,
            )

            else -> profile.copy(
                codecPreference = (existing ?: CodecPreference(codec = LDAC))
                    .copy(codec = LDAC, ldacQuality = quality),
            )
        }
    }

    private const val LDAC = "LDAC"
}

/**
 * Pinning a playback quality: stored on the device, requested on the link.
 *
 * ## Why one object for two screens
 *
 * The Monitoring panel used to own this path outright — resolve the address,
 * hand a [CodecPreference] to the privileged helper, read the outcome back — and
 * it set the link only for as long as it stayed connected. The Bluetooth tab now
 * offers the same four chips, and a second copy of that path would have been two
 * places to keep honest about the same renegotiation. It is one place, and it
 * does the half the panel never did: it **writes the choice into the device
 * profile**, which is what makes it survive the reconnect the stack performs on
 * every connect.
 *
 * ## The order is deliberate
 *
 * The profile is written *before* the link is touched. Storing is the part that
 * cannot fail for want of a helper, and a user without the privileged helper
 * running must still end up with a wish that applies the next time the helper is
 * there. The live request is then attempted and reported separately, so
 * "stored" and "in force right now" are never collapsed into one claim.
 */
object LdacTuning {

    private val _state = MutableStateFlow(LdacTuningState())

    /** Busy and last outcome, shared by every screen that shows the chips. */
    val state: StateFlow<LdacTuningState> = _state.asStateFlow()

    /** One request at a time: each renegotiates, and the second would race the first's read-back. */
    private val gate = Mutex()

    /**
     * Stores [quality] for a device and asks the live link for it.
     *
     * @param deviceKey the hashed key, when the caller already has one — the
     *   Bluetooth tab does, because it is editing that device's profile.
     * @param shownAddress the address a live panel is displaying, which on a
     *   user build is redacted. Resolved to the real one through the A2DP
     *   profile; see [rawAddressFor] for why falling back to it is not allowed.
     */
    suspend fun pin(quality: Long, deviceKey: String? = null, shownAddress: String? = null) {
        if (_state.value.busy) return
        if (!gate.tryLock()) return
        try {
            _state.value = LdacTuningState(busy = true)
            val connected = runCatching { MonitorGraph.codecSource.connectedDevices() }
                .getOrDefault(emptyList())
            val device = resolveDevice(deviceKey, shownAddress, connected)
            val key = deviceKey ?: device?.address?.let(DeviceKey::fromAddress)

            val persisted = key != null && runCatching { store(key, device?.name, quality) }.isSuccess
            val outcome = apply(device?.address, quality)

            _state.value = LdacTuningState(
                busy = false,
                // Everything below the UI works in raw addresses and some of it
                // quotes them back — the helper's own rejection sentence names
                // the address it was handed. Redacting on the way out keeps that
                // useful without putting a real MAC on screen.
                message = redactAddresses(tuningSentence(outcome, persisted)),
                messageIsError = outcome is CodecApplyOutcome.Unavailable && !persisted,
            )
        } finally {
            gate.unlock()
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * The connected device this request is about, by key or by shown address.
     *
     * Null when Android is not listing it on the A2DP profile — which is a real
     * outcome (the profile is still written) and never a reason to guess.
     */
    private fun resolveDevice(
        deviceKey: String?,
        shownAddress: String?,
        connected: List<BtAudioDevice>,
    ): BtAudioDevice? = when {
        deviceKey != null ->
            connected.firstOrNull { DeviceKey.fromAddress(it.address) == deviceKey }

        else -> rawAddressFor(shownAddress, connected)
            ?.let { raw -> connected.firstOrNull { it.address == raw } }
    }

    /** Writes the wish into the device's profile, creating a stub if it has none. */
    private suspend fun store(deviceKey: String, name: String?, quality: Long) {
        val profiles = SystemGraph.deviceProfiles
        val existing = profiles.profileFor(deviceKey)
            ?: DeviceProfile(
                deviceKey = deviceKey,
                name = name?.takeIf { it.isNotBlank() } ?: "Unnamed device",
            )
        profiles.save(LdacQuality.withQuality(existing, quality))
    }

    /**
     * Asks the live link, through the same controller the profile applier uses.
     *
     * `:app` installs one object in both `PrivilegedCodec` and `SystemGraph`
     * precisely so there is a single answer about whether the helper is there;
     * when it is not, the stand-in cannot take preferences and the user is told
     * that rather than shown a control that silently did nothing.
     */
    private suspend fun apply(address: String?, quality: Long): CodecApplyOutcome {
        val controller = PrivilegedCodec.controller() as? CodecPreferenceController
        return when {
            address == null -> CodecApplyOutcome.Unavailable(
                "Android is not listing this headphone on the A2DP profile, so there is " +
                    "no live link to change",
            )

            controller == null -> CodecApplyOutcome.Unavailable(
                "the privileged helper is not running, so LDAC quality cannot be set",
            )

            else -> runCatching {
                controller.apply(address, CodecPreference("LDAC", ldacQuality = quality))
            }.getOrElse { CodecApplyOutcome.Unavailable(it.message ?: "the request threw") }
        }
    }
}

/**
 * One sentence for both halves: what was stored, and what the link did about it.
 *
 * Kept apart from the coroutine above so it can be read — and tested — as what
 * it is: the place where "saved" and "in force" are not allowed to become one
 * claim. A request that could not be made is still worth reporting as stored,
 * because the profile will make it again on the next connect.
 */
internal fun tuningSentence(outcome: CodecApplyOutcome, persisted: Boolean): String {
    val stored = if (persisted) {
        "Stored for this headphone and asked for again on every connect. "
    } else {
        ""
    }
    val live = when (outcome) {
        is CodecApplyOutcome.Applied ->
            "LDAC is now ${outcome.observed} — read back, not just requested."

        // Not worded as a failure: nothing an app can reach tells a refusal
        // apart from a renegotiation still in flight.
        is CodecApplyOutcome.NotObserved ->
            "The link still reads ${outcome.observed}: ${outcome.detail}."

        is CodecApplyOutcome.Unavailable ->
            if (persisted) {
                "It was not changed on the link right now — ${outcome.reason}."
            } else {
                "LDAC quality was not changed — ${outcome.reason}."
            }
    }
    return stored + live
}
