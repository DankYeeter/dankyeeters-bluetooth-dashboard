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

    /**
     * Correlates with the thing asked about but is not it. Must be labelled.
     *
     * Nothing carries this today, and the reason is worth keeping: the one value
     * that did — frames per packet, offered as a stand-in for the LDAC rate —
     * turned out not to correlate with the rate at all (see
     * [A2dpTxDelta.framesPerEnqueue]), and the rate itself turned out to be
     * directly readable. Both halves of that are a warning about this category:
     * a proxy is a claim that something tracks something else, and it needs the
     * same evidence as any other claim here.
     */
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
 * The Bluetooth stack's own `A2DP LDAC State:` section, read verbatim.
 *
 * ## Why this type exists, and what it replaced
 *
 * `dumpsys bluetooth_manager` prints, for the negotiated LDAC link:
 *
 * ```
 * LDAC quality mode                                : ABR
 * LDAC transmission bitrate (Kbps)                 : 396
 * LDAC saved transmit queue length                 : 0
 * LDAC adaptive bit rate encode quality mode index : 4
 * LDAC adaptive bit rate adjustments               : 3
 * Effective MTU: 883
 * ```
 *
 * [transmissionKbps] is the **live** figure. Under ABR it is the rate the
 * adaptive encoder has settled on right now, and it uses intermediate steps
 * rather than only the 990/660/330 ladder — 330, 396, 492 and 660 were all
 * measured on one Pixel 11 Pro session, and 990 appeared only while a quality
 * was pinned. So this is the answer to "what bitrate is LDAC running at", and it
 * is MEASURED, not inferred.
 *
 * Before this section was found, the module tried to reconstruct the rate from
 * `btif_a2dp_source`'s packet counters. That reconstruction is falsified — see
 * [A2dpTxDelta.framesPerEnqueue] — and this type is what replaced it.
 *
 * ## The two adaptive-bitrate rows, and why a reading is not enough
 *
 * [transmissionKbps] is a sample of a value that moves between polls.
 * [adaptiveBitrateAdjustments] is the stack's own count of every move it made,
 * so it also counts the changes that fell between two readings and that no
 * sequence of samples can recover. Measured: run B of 2026-09-01 counted **nine**
 * adjustments over 101 s at a 1.44 s cadence, while the sampled rates for the
 * same window hold eight dwell segments and therefore at most seven visible
 * changes (`docs/perf/T-007-aufnahme.md`, section 3.4). At least two changes
 * happened where nobody was looking.
 *
 * Any change rate computed from the sampled rate alone therefore undercounts,
 * by an amount only this counter can name.
 *
 * ## Absent is a real answer
 *
 * Only some builds and only some codecs print a section like this. Nothing here
 * is defaulted or guessed: a missing section produces a null [LdacStackState],
 * and the panel then falls back to saying the rate is not observable, which on
 * such a build is the truth.
 *
 * ## What null does not distinguish
 *
 * On [adaptiveBitrateIndex] and [adaptiveBitrateAdjustments], null is "the row
 * was not printed" **and** "the row was printed but its value could not be
 * read" — a digit string past `Int`/`Long` range, or a truncated read that left
 * no digits at all. Both arrive as null and cannot be told apart. This is
 * stated rather than fixed because no reader exists that could act on the
 * difference; a second state invented ahead of its consumer would be a guess
 * about how it is meant to be used.
 *
 * A negative value, unlike a value out of range, is carried through as printed.
 * The `takeIf { it > 0 }` that guards [transmissionKbps] and [effectiveMtu]
 * cannot be copied here, because 0 is a legal rung and a legal count on these
 * two rows; no stack has ever printed a negative one.
 */
data class LdacStackState(
    /**
     * MEASURED, verbatim: the `LDAC quality mode` token, e.g. `ABR`, `HIGH`.
     *
     * Kept as the raw string rather than parsed into an enum, because the set
     * of tokens is the stack's business and a value this app has never seen
     * must reach the screen as itself rather than as "unknown".
     */
    val qualityMode: String? = null,
    /** MEASURED: `LDAC transmission bitrate (Kbps)`. Live under ABR. */
    val transmissionKbps: Int? = null,
    /** MEASURED: `Effective MTU` — the media channel's, for this codec's block. */
    val effectiveMtu: Int? = null,
    /** MEASURED: `LDAC saved transmit queue length`. Backlog, not throughput. */
    val savedTxQueueLength: Int? = null,
    /**
     * MEASURED, verbatim: `LDAC adaptive bit rate encode quality mode index` —
     * the rung the adaptive encoder stands on, as the stack numbers it.
     *
     * Carried as the stack's own number and **never** mapped to a bitrate. The
     * measured pairs are 660 kbps at index 1, 492 kbps at index 3
     * (`docs/perf/T-007-aufnahme.md`, section 3.4) and 396 kbps at index 4 in
     * the capture this parser is pinned to — so the index does not order the
     * ladder the way a reader would guess, and only three of its rungs have ever
     * been seen. [transmissionKbps] is the rate; this is the rung.
     *
     * A value of 0 is a rung, not a missing row: absence is null — and so is a
     * value that could not be read, which null does not distinguish from
     * absence. See "What null does not distinguish" above.
     */
    val adaptiveBitrateIndex: Int? = null,
    /**
     * MEASURED: `LDAC adaptive bit rate adjustments` — how often the adaptive
     * encoder has changed rung, cumulative since the stack started.
     *
     * Cumulative, so a window's worth of changes is the difference between two
     * readings; a single reading says only how much the encoder has moved in
     * this stack's lifetime. Zero is a real count — a link that has not moved —
     * and a build that does not print the row reads as null, as does a printed
     * value that could not be read. See "What null does not distinguish" above.
     */
    val adaptiveBitrateAdjustments: Long? = null,
) {
    /** True when the section was found but held nothing worth carrying. */
    val isEmpty: Boolean get() = qualityMode == null && transmissionKbps == null

    /**
     * Whether the stack says this link is running adaptive, or null when the
     * token is one this app has not seen before.
     *
     * Null rather than false on an unknown token: "not ABR" and "a mode nobody
     * here recognises" are different claims, and only the first one licenses the
     * UI to call the rate pinned.
     */
    val isAdaptive: Boolean?
        get() = qualityMode?.trim()?.uppercase()?.let { token ->
            when {
                token in ADAPTIVE_TOKENS -> true
                token in PINNED_TOKENS -> false
                else -> null
            }
        }

    companion object {
        /** The stack's spelling for adaptive bitrate. */
        private val ADAPTIVE_TOKENS = setOf("ABR")

        /** Tokens seen for a fixed eqmid. Anything else passes through as unknown. */
        private val PINNED_TOKENS = setOf("HIGH", "MID", "STANDARD", "LOW", "HQ", "SQ", "MQ")
    }
}

/**
 * What the LDAC encoder was told to do.
 *
 * ## Configuration, not rate
 *
 * The values here describe the **configuration**. `mCodecSpecific1` is the
 * user's Developer-Options choice, and on an untouched phone it is `0` — the
 * framework never writes one, so the stack runs its default, which is ABR. That
 * makes [NOT_PINNED] and [ADAPTIVE] carry a null [LdacState.nominalKbps]: there
 * is no single spec figure to name for a mode that moves.
 *
 * What the *encoder is actually doing* now comes from [LdacStackState], which is
 * a measurement rather than a table. The two are kept apart because they answer
 * different questions: this one is what the user chose, that one is what the
 * radio is carrying.
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
 * LDAC's rate: what was configured, and — where the stack prints it — what is
 * actually being sent.
 *
 * @property mode MEASURED — read straight out of `mCodecConfig`'s `mCodecSpecific1`.
 * @property nominalKbps NOMINAL — the rate the spec assigns to [mode] at the
 *   negotiated sample rate. Null whenever [mode] is adaptive, because there is
 *   no single rate to name.
 * @property stack MEASURED — the stack's own `A2DP LDAC State:` block, when this
 *   build prints one. Null is the honest "this build does not report it".
 * @property liveBitrateHonesty [Honesty.MEASURED] when [stack] carried a
 *   bitrate, [Honesty.UNAVAILABLE] when it did not. This is the field the panel
 *   branches on, so a build without the section keeps the old honest refusal
 *   without any other code changing.
 * @property note a sentence the UI can print verbatim explaining the above.
 */
data class LdacState(
    val mode: LdacQualityMode,
    val nominalKbps: Int? = null,
    val stack: LdacStackState? = null,
    val liveBitrateHonesty: Honesty = Honesty.UNAVAILABLE,
    val note: String = "",
) {

    /** MEASURED: what the encoder is sending right now, or null on a build without the section. */
    val measuredKbps: Int? get() = stack?.transmissionKbps

    /**
     * Whether this link is running adaptive, preferring the stack's own word.
     *
     * The stack is asked first because it reports what the encoder is doing,
     * while `mCodecSpecific1` reports what somebody asked for. They agree on
     * every link measured so far; when they cannot both be had, the measurement
     * wins.
     */
    val isAdaptive: Boolean get() = stack?.isAdaptive ?: mode.isAdaptive

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

        /**
         * Builds the state, including the sentence that explains what it is.
         *
         * [stack] decides which of two worlds this link is in. With it the rate
         * is a measurement and the note says so; without it the note is the old
         * refusal, which on a build that prints no LDAC section is still exactly
         * true.
         */
        fun from(
            codecSpecific1: Long?,
            sampleRateHz: Int?,
            stack: LdacStackState? = null,
        ): LdacState {
            val mode = modeOf(codecSpecific1)
            val measured = stack?.transmissionKbps
            val adaptive = stack?.isAdaptive ?: mode.isAdaptive
            return LdacState(
                mode = mode,
                nominalKbps = nominalKbps(mode, sampleRateHz),
                stack = stack,
                liveBitrateHonesty =
                    if (measured != null) Honesty.MEASURED else Honesty.UNAVAILABLE,
                note = when {
                    measured != null && adaptive -> MEASURED_ADAPTIVE_NOTE
                    measured != null -> MEASURED_PINNED_NOTE
                    mode == LdacQualityMode.NOT_PINNED -> ADAPTIVE_NOTE_DEFAULT
                    mode == LdacQualityMode.ADAPTIVE -> ADAPTIVE_NOTE_CHOSEN
                    mode == LdacQualityMode.UNKNOWN -> "LDAC quality index not readable in this dump."
                    else -> PINNED_NOTE
                },
            )
        }

        private const val MEASURED_ADAPTIVE_NOTE =
            "LDAC is running adaptive bitrate, and this phone's Bluetooth stack prints " +
                "the rate it has settled on. The figure is that reading, not a spec " +
                "number: it is what the encoder is producing right now and it moves on " +
                "its own.\n\n" +
                "Adaptive uses steps in between the headline ones — 330, 396, 492 and " +
                "660 kbps were all measured on one session — and it was never seen to " +
                "reach 990 by itself. Pinning High quality is the only way observed to " +
                "get 990."

        private const val MEASURED_PINNED_NOTE =
            "LDAC is pinned to a fixed quality. The first figure is the spec rate for " +
                "that mode; the second is what the stack reports it is actually " +
                "sending, so the two can be compared instead of trusted."

        private const val ADAPTIVE_NOTE_DEFAULT =
            "No LDAC quality is pinned, so the stack runs adaptive bitrate. " +
                "This build does not print an \"A2DP LDAC State\" section, so the rate " +
                "the encoder picks moment to moment cannot be read on it. " +
                "Pinning a quality in Developer options at least makes the mode readable."

        private const val ADAPTIVE_NOTE_CHOSEN =
            "LDAC is set to adaptive bitrate, and this build does not print an " +
                "\"A2DP LDAC State\" section, so the rate the encoder picks moment to " +
                "moment cannot be read on it."

        private const val PINNED_NOTE =
            "LDAC is pinned to a fixed quality, so the rate below is the one the " +
                "encoder targets. It is the spec figure for the mode, not a measurement " +
                "of what crossed the air."
    }
}

/** The negotiated link, as `mCodecConfig` reports it. Every field MEASURED. */
data class LiveCodecSnapshot(
    val family: CodecFamily,
    /**
     * MEASURED: the `codecName:` the dump printed, verbatim.
     *
     * [family] is now decided by this name first, so the two no longer
     * disagree — but the name still says more than the family can. [CodecFamily]
     * has one entry per codec this app can badge, while the name distinguishes
     * codecs it cannot, which is what [CodecModeSignatureRegistry] matches on.
     */
    val rawCodecName: String? = null,
    /**
     * MEASURED: the `mCodecType:` the dump printed.
     *
     * Only interesting when the name identified nothing: a
     * [CodecFamily.VENDOR] link is labelled with this number, because it is
     * then the only fact about the codec's identity anyone has.
     */
    val rawCodecType: Int? = null,
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
    /**
     * The stack's enqueue counter.
     *
     * Named "media packets" by `btif_a2dp_source`, and **not** a count of radio
     * packets. Measured on the device: it ticks at a constant ~50/s while audio
     * plays, in every LDAC mode including pinned 990 and pinned 330. That is the
     * media timer's 20 ms period, not the air. It is a fine liveness and
     * duty-cycle signal and it is not a throughput signal.
     */
    val enqueueCount: Long? = null,
    /** Packets the L2CAP layer took off it. */
    val dequeueCount: Long? = null,
    /** PCM buffer reads from the audio HAL. */
    val readBufCount: Long? = null,
    val framesPerPacketTotal: Long? = null,
    val framesPerPacketMax: Int? = null,
    /**
     * MEASURED, and **not** a rate indicator. Never render this.
     *
     * `btif_a2dp_source` labels it "frames per packet", which invites reading it
     * as the encoder's packing and therefore as an inverse stand-in for the
     * bitrate. It is not. Because [enqueueCount] counts 20 ms timer ticks rather
     * than radio packets, frames-divided-by-enqueues is
     * `playing duty cycle x 15` and carries no mode information at all: on the
     * device, pinned 990 gave 13.5 and pinned 330 gave 10.5, which is the duty
     * cycle of those two runs and nothing else.
     *
     * Kept only because it is what the dump says. The live rate comes from
     * [LdacStackState.transmissionKbps].
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
 * A Bluetooth-stack counter that is allowed to say audio was lost.
 *
 * Identifiers, not words for a screen. What the panel prints for these two —
 * "dropped packets", "stack dropouts" — is display text and stays with the
 * display, which is also the module that knows about plurals and sentences;
 * what belongs here is the *set*, because which counters count is a fact about
 * the measurement rather than about the screen. The two halves are held
 * together by the `when` that names them being exhaustive: a channel added to
 * this enum does not compile until the panel has a word for it.
 *
 * The set itself, with the values of one window, is [A2dpTxDelta.lossByChannel].
 */
enum class TxLossChannel {
    /** `Counts (flushed/dropped/dropouts)`, middle field: packets the full queue threw away. */
    DROPPED_PACKETS,

    /** Same row, last field: distinct dropout episodes as the stack counts them. */
    STACK_DROPOUTS,
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
    /**
     * **The** definition of what this window lost, per channel, in the order the
     * screen names them. Every other answer on this subject is derived from it.
     *
     * There used to be three of these, written out by hand in three files: this
     * class asked `dropped > 0 || dropouts > 0`, the graph model added
     * `dropped + dropouts`, and the panel's loss row rebuilt the same list a
     * third time out of the raw fields. All three agreed, none of them was
     * connected to the others, and a channel could therefore be added or taken
     * away in one of them while the other two carried on as before — which is
     * exactly how a whole channel once went unpinned (QA-002, QA-010). Anything
     * that wants to know what counts as loss reads this map; a change here
     * moves the boolean, the graph mark and the sentence together or not at all.
     *
     * [underflows] is deliberately not one of the channels. The device runs put
     * the counter on the wrong side of the question twice over: it stayed at 0
     * through the 990 arm where stack dropouts ran throughout
     * (`docs/perf/T-008-experimente.md`, section 3) and rose from 2 to 25
     * across 39 minutes of flawless playback
     * with nothing dropped (`docs/perf/T-011-messung.md`). A window whose only
     * moving counter is underflow is not loss, and treating it as loss painted
     * a red line about every 100 s of a clean run (AK-T009-24).
     *
     * The counter keeps its value and its place on screen; it lost the verdict,
     * not the visibility. Encoder starvation is a *rate*, and it is judged from
     * [underflowsPerSecond] by `EncoderStarvationTripwire` — where the incident
     * of 2026-08-28 sat at ~49/s against a resting 0.59/min, three orders of
     * magnitude apart.
     *
     * Every channel is listed with the value it actually has, zero included, so
     * that a reader can tell "this channel counted nothing" from "this channel
     * is not asked about" — the caller decides which of the two it needs.
     */
    val lossByChannel: Map<TxLossChannel, Long>
        get() = mapOf(
            TxLossChannel.DROPPED_PACKETS to dropped,
            TxLossChannel.STACK_DROPOUTS to dropouts,
        )

    /** Anything the user would have heard, from [lossByChannel] and nowhere else. */
    val hasLoss: Boolean get() = lossByChannel.values.any { it > 0 }

    /**
     * How much was lost in this window, added across [lossByChannel].
     *
     * A count of *events*, not of marks: one window with 525 dropped packets is
     * one mark on the graph and 525 here. Whoever needs marks counts windows
     * with [hasLoss].
     */
    val lossCount: Long get() = lossByChannel.values.sum()

    /**
     * DERIVED: enqueue ticks per second across the window.
     *
     * A liveness signal, not a throughput one — see [A2dpTxStats.enqueueCount]
     * for why this sits near 50/s in every mode. It stays because a line that
     * dips to zero really does mean the stack stopped handing audio over, which
     * is worth seeing; it is never the rate series when a measured bitrate is
     * available.
     */
    val packetsPerSecond: Double?
        get() = if (windowMs > 0) enqueued * 1000.0 / windowMs else null

    /**
     * DERIVED: encoder underflows per second across the window.
     *
     * The only honest way to state this counter. [underflows] on its own is a
     * difference over whatever window the poll happened to cover, and the poll
     * interval is a parameter the user can change — so "3 underflows" means
     * something different at 500 ms than at 2 s, while "1.5 per second" means
     * the same thing at both. Null when the window is degenerate, never zero:
     * a window of no duration has no rate.
     *
     * This is what `EncoderStarvationTripwire` triggers on. The rate measured
     * during the 2026-08-28 incident was about 49/s; a healthy link sits at 0.
     */
    val underflowsPerSecond: Double?
        get() = if (windowMs > 0) underflows * 1000.0 / windowMs else null

    /**
     * DERIVED: codec frames per second across the window.
     *
     * A *frame* rate, and mode-independent by construction: LDAC's frame is a
     * fixed 128 samples per channel, so at 96 kHz a link that is encoding at all
     * produces 750 frames/s whatever bitrate it spends on them. Useful as a
     * "is the encoder actually running" check and for nothing else.
     */
    val framesPerSecond: Double?
        get() = if (windowMs > 0) framesEncoded * 1000.0 / windowMs else null

    /**
     * DERIVED encoder-duty diagnostic. **Not** a packing and not a rate.
     *
     * Frames encoded divided by enqueue ticks. It was built as
     * "frames per packet" — the inverse-monotone stand-in for the LDAC rate the
     * whole old inference rested on — and the device falsified that: enqueues
     * are 20 ms media-timer ticks, not radio packets, so this ratio is
     * `playing duty cycle x 15`. Pinned 990 measured 13.5 and pinned 330
     * measured 10.5, i.e. both runs reported their duty cycle and neither
     * reported its mode.
     *
     * Renamed rather than deleted so that nothing can read it as packing by
     * accident, and so the old graphs' series has a name that says what it is.
     * The rate lives in [LdacStackState.transmissionKbps].
     */
    val framesPerEnqueue: Double?
        get() = if (enqueued > 0) framesEncoded.toDouble() / enqueued else null
}

/**
 * Whether the host can see this codec's stream at all.
 *
 * The single most important caveat on the whole panel. A codec the controller
 * encodes never passes through `btif_a2dp_source`, so every packet, frame and
 * loss counter in this module is either absent or a leftover from some earlier
 * host-encoded session. There is no partial view and no degraded estimate:
 * either the host encodes and everything below is measured, or it does not and
 * nothing is.
 */
enum class LinkObservability(val label: String) {
    /** The host encodes. Tx counters, frame rates and mode inference all apply. */
    HOST_ENCODED("host-encoded — the stream is observable"),

    /**
     * The controller encodes. On this hardware that is SBC, AAC and Opus. The
     * negotiated codec, sample rate and bit depth are still readable; nothing
     * about throughput or loss is.
     */
    OFFLOADED("offloaded to the controller — the host cannot observe the stream"),

    /** No codec was readable, so which of the two applies is unknown. */
    UNKNOWN("no negotiated codec — observability unknown"),
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
    /**
     * LDAC's rate, present only when [codec] is LDAC.
     *
     * Carries both halves: [LdacState.mode] is what the user pinned (on an
     * untouched phone, nothing — so the stack runs adaptive), and
     * [LdacState.measuredKbps] is what the stack says the encoder is sending
     * right now, where the build prints it.
     */
    val ldac: LdacState? = null,
    /**
     * What the encoder is **running** at, from the stack's own fields. Never
     * null: when no build field carries it, it holds
     * [InferenceConfidence.UNKNOWN] and the reason why.
     */
    val modeInference: ModeInference = ModeInference.unknown(null, "no reading yet"),
    /** Whether any of the throughput and loss figures below apply at all. */
    val observability: LinkObservability = LinkObservability.UNKNOWN,
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

    /**
     * Whether this poll saw audible loss anywhere on the path.
     *
     * Encoder underflows are not one of the channels asked — see
     * [A2dpTxDelta.hasLoss] for the measurements that took them out of it.
     */
    val hasLossThisWindow: Boolean
        get() = txDelta?.hasLoss == true ||
            inputUnderrunDelta > 0 ||
            (mixer?.fastMixerUnderrunDelta ?: 0L) > 0 ||
            (mixer?.normalMixerEmptyDelta ?: 0L) > 0
}
