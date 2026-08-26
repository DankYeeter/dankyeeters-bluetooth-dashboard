package dev.dankyeeter.btdashboard.monitor.link.live

import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily

/**
 * How much a number on this screen is worth.
 *
 * This exists because the whole live view is one honesty problem. Half of what
 * a user wants to know about a Bluetooth link — "what bitrate is LDAC running
 * at right now" — is simply not readable on a stock Pixel, and the tempting fix
 * is to print the codec's headline figure and let it look like a measurement.
 * That is the failure this app is supposed to be the opposite of, so every
 * value that reaches the UI carries what kind of value it is.
 */
enum class Honesty(val label: String) {
    /** Read from a counter or a field the system maintains. A fact. */
    MEASURED("measured"),

    /** Arithmetic on measured values only (a delta, a rate). Still a fact. */
    DERIVED("derived"),

    /**
     * The number the spec says this configuration uses. Correct as a label for
     * the mode, and *not* evidence that this many bits crossed the air.
     */
    NOMINAL("nominal"),

    /** Correlates with the thing asked about but is not it. Must be labelled. */
    PROXY("proxy"),

    /** Not readable on this device. The UI shows the reason, never a guess. */
    UNAVAILABLE("not available"),
}

// ---- input side -------------------------------------------------------------

/** AudioFlinger's PCM formats, as printed in the per-track dump column. */
enum class PcmFormat(val rawValue: Int, val label: String, val bits: Int?) {
    PCM_16_BIT(0x1, "16 bit", 16),
    PCM_8_BIT(0x2, "8 bit", 8),
    PCM_32_BIT(0x3, "32 bit", 32),
    PCM_8_24_BIT(0x4, "24 bit (in 32)", 24),
    PCM_FLOAT(0x5, "float", 32),
    PCM_24_BIT_PACKED(0x6, "24 bit packed", 24),
    OTHER(-1, "other", null),
    ;

    companion object {
        fun of(rawValue: Int?): PcmFormat? =
            rawValue?.let { raw -> entries.firstOrNull { it.rawValue == raw } ?: OTHER }
    }
}

/**
 * One app that is putting audio into the mixer right now, and what happened to
 * it on the way out.
 *
 * Two dumps are joined here by pid: `dumpsys audio` knows *who* is playing and
 * at what sample rate it handed the framework, `dumpsys media.audio_flinger`
 * knows the PCM format the mixer track actually carries and — the part that
 * matters for dropouts — that track's underrun counter.
 */
data class InputStreamSnapshot(
    /** MEASURED: app uid, from `u/pid:<uid>/<pid>`. */
    val uid: Int,
    /** MEASURED: app pid. The join key between the two dumps. */
    val pid: Int,
    /** MEASURED: audio session id; 0 means the app never announced one. */
    val sessionId: Int?,
    /** MEASURED: the rate the app feeds the framework, before any resampling. */
    val sampleRateHz: Int?,
    /** MEASURED: channel count, from the player's channel mask bit count. */
    val channelCount: Int?,
    /** MEASURED: mixer-track PCM format. Null when no track could be matched. */
    val pcmFormat: PcmFormat? = null,
    /** MEASURED: the sample rate of the mixer track, which may differ from [sampleRateHz]. */
    val trackSampleRateHz: Int? = null,
    val isSpatialized: Boolean = false,
    /** MEASURED: `USAGE_MEDIA` and friends, verbatim. */
    val usage: String? = null,
    /** MEASURED: `CONTENT_TYPE_MUSIC` and friends, verbatim. */
    val contentType: String? = null,
    /**
     * MEASURED: AudioFlinger's cumulative underrun count for this track — the
     * number of times the mixer asked for data this app had not produced yet.
     * Cumulative since the track was created, so only [underrunDelta] means
     * anything about *now*.
     */
    val underrunCount: Long? = null,
    /** DERIVED: [underrunCount] minus the same track's count at the previous poll. */
    val underrunDelta: Long? = null,
    /** MEASURED: cumulative frames flushed on this track (seeks, stalls). */
    val flushedCount: Long? = null,
    /** DERIVED: [flushedCount] minus the previous poll's. */
    val flushedDelta: Long? = null,
)

/**
 * The AudioFlinger output thread the Bluetooth route is attached to.
 *
 * This is the last place the audio is still PCM. Its underruns are a different
 * failure from the tx-queue's: here the *mixer* ran dry, there the *radio* did.
 * Telling them apart is most of what "I had dropouts and saw nothing" needs.
 */
data class MixerOutputSnapshot(
    /** MEASURED: e.g. `AudioOut_15`. Identifies the thread across polls. */
    val threadName: String,
    /** MEASURED: the raw `Output devices:` mask, e.g. 0x80 for A2DP. */
    val outputDeviceMask: Int?,
    /** MEASURED: the decoded device names the mask printed, verbatim. */
    val outputDeviceNames: String?,
    /** MEASURED: mixer output rate in Hz — what the encoder is actually fed. */
    val sampleRateHz: Int?,
    val channelCount: Int?,
    /** MEASURED: the HAL-side PCM format of this thread. */
    val halFormat: PcmFormat?,
    /** MEASURED: `Standby: yes` — the thread is parked, nothing is flowing. */
    val isInStandby: Boolean,
    /** MEASURED: FastMixer underrun counter, cumulative. Null on threads with no FastMixer. */
    val fastMixerUnderruns: Long? = null,
    /** DERIVED: [fastMixerUnderruns] minus the previous poll's. */
    val fastMixerUnderrunDelta: Long? = null,
    /** MEASURED: `Normal mixer raw underrun counters: partial=`, cumulative. */
    val normalMixerPartialUnderruns: Long? = null,
    /** MEASURED: `... empty=`, cumulative. The worse of the two. */
    val normalMixerEmptyUnderruns: Long? = null,
    /** DERIVED: increase in [normalMixerEmptyUnderruns] since the previous poll. */
    val normalMixerEmptyDelta: Long? = null,
) {
    /**
     * Whether this thread is the one feeding a Bluetooth *media* link.
     *
     * Deliberately not "any Bluetooth device". `AUDIO_DEVICE_OUT_BLUETOOTH_SCO`
     * and its two siblings also print `BLUETOOTH` in the name and sit two bits
     * below A2DP in the mask — and SCO is the call path, an 8 or 16 kHz mono
     * stream that has nothing to do with the link this screen is about.
     * Matching it would pin the whole live view to the wrong thread the moment
     * a phone call started.
     */
    val isBluetoothRoute: Boolean
        get() = outputDeviceNames?.let { names ->
            names.contains("BLUETOOTH_A2DP", ignoreCase = true) ||
                names.contains("BLE_", ignoreCase = true)
        } == true ||
            (outputDeviceMask != null && outputDeviceMask and BLUETOOTH_A2DP_MASK != 0)

    companion object {
        /** AUDIO_DEVICE_OUT_BLUETOOTH_A2DP | _HEADPHONES | _SPEAKER. SCO excluded. */
        const val BLUETOOTH_A2DP_MASK = 0x80 or 0x100 or 0x200
    }
}

// ---- link side --------------------------------------------------------------

/**
 * What the LDAC encoder was told to do.
 *
 * ## The thing this deliberately does not claim
 *
 * The values here describe the **configuration**, never the instantaneous
 * bitrate. `mCodecSpecific1` is the user's Developer-Options choice, and on an
 * untouched phone it is `0` — the framework never writes one, so the stack runs
 * its default, which is ABR. ABR then moves between 990/660/330 inside the
 * encoder without telling anybody: it is not in `dumpsys` and, on the Pixel 11
 * Pro build this was measured on, not in `logcat` either. So [NOT_PINNED] and
 * [ADAPTIVE] both report a null [LdacState.nominalKbps], and the reason is
 * carried in [LdacState.note] rather than papered over with "990".
 */
enum class LdacQualityMode(val label: String) {
    /** `mCodecSpecific1 = 1000` — pinned to the top rate. */
    HIGH_QUALITY("High quality"),

    /** `1001` — pinned to the middle rate. */
    STANDARD("Standard"),

    /** `1002` — pinned to the connection-first rate. */
    CONNECTION_PRIORITY("Connection priority"),

    /** `1003` — the user explicitly asked for adaptive. */
    ADAPTIVE("Adaptive bitrate"),

    /**
     * `0` — nobody ever picked one, so the stack uses its own default. On AOSP
     * that default is ABR, which is why this is a distinct value and not folded
     * into [ADAPTIVE]: "adaptive because it was asked for" and "adaptive
     * because nothing was asked for" look the same on the air and completely
     * different in the settings the user can change.
     */
    NOT_PINNED("Adaptive (stack default)"),

    UNKNOWN("Unknown"),
    ;

    val isAdaptive: Boolean get() = this == ADAPTIVE || this == NOT_PINNED
}

/**
 * LDAC's configured rate, as far as it is knowable.
 *
 * @property mode MEASURED — read straight out of `mCodecConfig`'s `mCodecSpecific1`.
 * @property nominalKbps NOMINAL — the rate the spec assigns to [mode] at the
 *   negotiated sample rate. Null whenever [mode] is adaptive, because there is
 *   no single rate to name.
 * @property liveBitrateHonesty always [Honesty.UNAVAILABLE] on the builds
 *   measured so far. Kept as a field rather than a constant so a device that
 *   *does* report a running bitrate can raise it without the UI changing.
 * @property note a sentence the UI can print verbatim explaining the above.
 */
data class LdacState(
    val mode: LdacQualityMode,
    val nominalKbps: Int? = null,
    val liveBitrateHonesty: Honesty = Honesty.UNAVAILABLE,
    val note: String = "",
) {
    companion object {

        /**
         * LDAC's rate ladder, which is not one ladder but two.
         *
         * The encoder emits a fixed number of frames per second, so the byte
         * rate follows the sample-rate family: the 44.1/88.2 kHz family runs
         * 909/606/303 and the 48/96 kHz family runs 990/660/330. Reporting
         * "990" on a 44.1 kHz link is off by 8% — small enough to look right
         * and wrong enough to be a lie.
         */
        fun nominalKbps(mode: LdacQualityMode, sampleRateHz: Int?): Int? {
            val fortyFourFamily = sampleRateHz == 44_100 || sampleRateHz == 88_200
            return when (mode) {
                LdacQualityMode.HIGH_QUALITY -> if (fortyFourFamily) 909 else 990
                LdacQualityMode.STANDARD -> if (fortyFourFamily) 606 else 660
                LdacQualityMode.CONNECTION_PRIORITY -> if (fortyFourFamily) 303 else 330
                else -> null
            }
        }

        fun modeOf(codecSpecific1: Long?): LdacQualityMode = when (codecSpecific1) {
            null -> LdacQualityMode.UNKNOWN
            0L -> LdacQualityMode.NOT_PINNED
            1000L -> LdacQualityMode.HIGH_QUALITY
            1001L -> LdacQualityMode.STANDARD
            1002L -> LdacQualityMode.CONNECTION_PRIORITY
            1003L -> LdacQualityMode.ADAPTIVE
            else -> LdacQualityMode.UNKNOWN
        }

        /** Builds the state, including the sentence that explains what is missing. */
        fun from(codecSpecific1: Long?, sampleRateHz: Int?): LdacState {
            val mode = modeOf(codecSpecific1)
            return LdacState(
                mode = mode,
                nominalKbps = nominalKbps(mode, sampleRateHz),
                liveBitrateHonesty = Honesty.UNAVAILABLE,
                note = when {
                    mode == LdacQualityMode.NOT_PINNED -> ADAPTIVE_NOTE_DEFAULT
                    mode == LdacQualityMode.ADAPTIVE -> ADAPTIVE_NOTE_CHOSEN
                    mode == LdacQualityMode.UNKNOWN -> "LDAC quality index not readable in this dump."
                    else -> PINNED_NOTE
                },
            )
        }

        private const val ADAPTIVE_NOTE_DEFAULT =
            "No LDAC quality is pinned, so the stack runs adaptive bitrate. " +
                "The rate it picks moment to moment is inside the encoder and is not " +
                "reported by the system, so no live kbps figure can be shown. " +
                "Pinning a quality in Developer options makes the mode readable."

        private const val ADAPTIVE_NOTE_CHOSEN =
            "LDAC is set to adaptive bitrate. The rate it picks moment to moment is " +
                "inside the encoder and is not reported by the system, so no live kbps " +
                "figure can be shown."

        private const val PINNED_NOTE =
            "LDAC is pinned to a fixed quality, so the rate below is the one the " +
                "encoder targets. It is the spec figure for the mode, not a measurement " +
                "of what crossed the air."
    }
}

/** The negotiated link, as `mCodecConfig` reports it. Every field MEASURED. */
data class LiveCodecSnapshot(
    val family: CodecFamily,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val channelMode: ChannelMode = ChannelMode.UNKNOWN,
    /** Raw `mCodecSpecific1`; meaning is codec-specific (LDAC: quality index). */
    val codecSpecific1: Long? = null,
    /**
     * MEASURED: whether the controller encodes this codec, from the adapter's
     * `codecConfigOffloading` list.
     *
     * It decides whether any of [A2dpTxStats] means anything: offloaded codecs
     * never touch `btif_a2dp_source`, so its counters stay frozen and reading
     * them as "no dropouts" would be exactly wrong.
     */
    val isOffloaded: Boolean = false,
) {
    /** Host-side encoding is the case where the tx-queue counters are live. */
    val isEncodedOnHost: Boolean get() = !isOffloaded
}

/**
 * `btif_a2dp_source`'s own media statistics, from the `A2DP State:` block.
 *
 * Every counter here is cumulative since the Bluetooth stack started, which is
 * why [A2dpTxDelta] exists — a total of 788 underflows says nothing about
 * whether the last two seconds were fine.
 *
 * Only valid while the codec is encoded on the host; see
 * [LiveCodecSnapshot.isOffloaded].
 */
data class A2dpTxStats(
    /** Media packets handed to the tx queue. */
    val enqueueCount: Long? = null,
    /** Packets the L2CAP layer took off it. */
    val dequeueCount: Long? = null,
    /** PCM buffer reads from the audio HAL. */
    val readBufCount: Long? = null,
    val framesPerPacketTotal: Long? = null,
    val framesPerPacketMax: Int? = null,
    /**
     * MEASURED, but see [LinkLiveSnapshot] before showing it: how many codec
     * frames fit in one packet moves with the encoder's output size, so it
     * *correlates* with the LDAC rate. It is not a rate and must never be
     * relabelled as one.
     */
    val framesPerPacketAvg: Int? = null,
    /** Queue flushes — the stack threw away buffered audio to catch up. */
    val flushedCount: Long? = null,
    /** Packets dropped because the queue was full. Audible. */
    val droppedCount: Long? = null,
    /** Distinct dropout episodes, as the stack counts them. Audible. */
    val dropoutCount: Long? = null,
    /** Largest single drop burst. */
    val maxDroppedCount: Long? = null,
    /** Times the encoder had no PCM ready — the source starved, not the radio. */
    val underflowCount: Long? = null,
    val underflowBytes: Long? = null,
    /** Media-timer ticks that ran late; a scheduling-pressure signal. */
    val enqueueOverdue: Long? = null,
    val enqueuePremature: Long? = null,
    val dequeueOverdue: Long? = null,
    val dequeuePremature: Long? = null,
) {
    /** True when the block was found but held nothing readable. */
    val isEmpty: Boolean get() = enqueueCount == null && underflowCount == null
}

/**
 * The change in [A2dpTxStats] between two polls, plus the window it covers.
 *
 * DERIVED throughout. This — not the totals — is what a dropout looks like.
 */
data class A2dpTxDelta(
    val windowMs: Long,
    val enqueued: Long = 0,
    val dropped: Long = 0,
    val dropouts: Long = 0,
    val flushed: Long = 0,
    val underflows: Long = 0,
    val underflowBytes: Long = 0,
    val framesEncoded: Long = 0,
) {
    /** Anything the user would have heard. */
    val hasLoss: Boolean get() = dropped > 0 || dropouts > 0 || underflows > 0

    /** DERIVED: media packets per second across the window. */
    val packetsPerSecond: Double?
        get() = if (windowMs > 0) enqueued * 1000.0 / windowMs else null

    /**
     * DERIVED: codec frames per second across the window.
     *
     * The closest thing to a measured throughput this device offers. It is a
     * *frame* rate, not a bit rate: the stack counts frames and never counts
     * bytes, so converting this to kbps would require assuming the very LDAC
     * mode we cannot read.
     */
    val framesPerSecond: Double?
        get() = if (windowMs > 0) framesEncoded * 1000.0 / windowMs else null
}

/** The device the link belongs to. */
data class LiveDeviceSnapshot(
    val address: String,
    val name: String? = null,
    val isConnected: Boolean = false,
    val isActive: Boolean = false,
    /** MEASURED: `mIsPlaying` — the A2DP stream is started, not merely connected. */
    val isPlaying: Boolean = false,
)

/**
 * One complete reading of the audio path, from the app to the radio.
 *
 * Read it left to right: [inputs] is what the apps produce, [mixer] is where it
 * is mixed and where the PCM side can starve, [codec]/[ldac] is what the link
 * was negotiated to carry, and [tx]/[txDelta] is what the Bluetooth stack
 * managed to push out. A dropout shows up in exactly one of those places, and
 * which one it is, is the diagnosis.
 */
data class LinkLiveSnapshot(
    val timestampMs: Long,
    val device: LiveDeviceSnapshot? = null,
    val codec: LiveCodecSnapshot? = null,
    /** Present only when [codec] is LDAC. */
    val ldac: LdacState? = null,
    val tx: A2dpTxStats? = null,
    /** Null on the first poll of a session — there is nothing to subtract from. */
    val txDelta: A2dpTxDelta? = null,
    val inputs: List<InputStreamSnapshot> = emptyList(),
    val mixer: MixerOutputSnapshot? = null,
    /** Why a section is missing. Shown, not swallowed. */
    val warnings: List<String> = emptyList(),
) {
    /** Whether anything at all could be read. Drives the empty state. */
    val isEmpty: Boolean
        get() = device == null && codec == null && tx == null && inputs.isEmpty() && mixer == null

    /**
     * DERIVED: total per-app underruns in this window. The number the user is
     * really asking about when they say "it was choppy".
     */
    val inputUnderrunDelta: Long
        get() = inputs.sumOf { it.underrunDelta ?: 0L }

    /** Whether this poll saw audible loss anywhere on the path. */
    val hasLossThisWindow: Boolean
        get() = txDelta?.hasLoss == true ||
            inputUnderrunDelta > 0 ||
            (mixer?.fastMixerUnderrunDelta ?: 0L) > 0 ||
            (mixer?.normalMixerEmptyDelta ?: 0L) > 0
}
