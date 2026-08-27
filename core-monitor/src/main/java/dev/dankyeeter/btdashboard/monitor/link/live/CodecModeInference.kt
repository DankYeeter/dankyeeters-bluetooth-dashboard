package dev.dankyeeter.btdashboard.monitor.link.live

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily

/**
 * One selectable bitrate mode of one codec.
 *
 * [rawValue] is the number that goes into `codecSpecific1` to pin it, which is
 * also the key the calibration store uses — so a learned signature survives a
 * rename of [label] and stays wrong-proof across codecs that happen to share
 * mode names.
 */
data class CodecMode(
    val codec: CodecFamily,
    val rawValue: Long,
    val label: String,
    /** NOMINAL: the spec figure for this mode at the sample rate it was built for. */
    val nominalKbps: Int?,
)

/**
 * One mode plus its encoded frame size in bytes.
 *
 * [frameBytes] is real arithmetic on verified constants and is no longer read by
 * [CodecModeInference]: what it was for — identifying a mode from how many
 * frames fit in a packet — needed a packet counter this stack does not have. It
 * stays with the calibration seam, which is the only thing that could ever use a
 * per-mode signature again.
 */
data class CodecModeSignature(val mode: CodecMode, val frameBytes: Int)

/** How the running rate was established. Ordered best first. */
enum class InferenceConfidence {
    /**
     * The Bluetooth stack printed the rate and this read it. No arithmetic, no
     * table, no assumption — see [LdacStackState].
     */
    MEASURED,

    /** Not reported on this build for this codec. The UI shows the reason. */
    UNKNOWN,
}

/**
 * What the encoder is actually doing, as opposed to what it was configured to
 * do.
 *
 * The distinction is the entire point. `mCodecConfig`'s `mCodecSpecific1` says
 * which mode the *user* pinned, and on an untouched phone that is "none, run
 * adaptive". This says what the adaptive encoder is producing, which is the
 * question a listener actually has.
 */
data class ModeInference(
    val codec: CodecFamily?,
    /**
     * The named mode, when the stack's own quality token maps onto one. Null is
     * ordinary and not a failure: under ABR there *is* no named mode, and
     * [measuredKbps] is the answer regardless.
     */
    val mode: CodecMode?,
    val confidence: InferenceConfidence,
    /**
     * MEASURED: what the stack says is going out right now, in kbps.
     *
     * The field the panel and both graphs are drawn from. Under ABR it moves on
     * its own and uses steps the mode ladder has no rung for.
     */
    val measuredKbps: Int? = null,
    /** MEASURED, verbatim: the stack's quality-mode token, e.g. `ABR`. */
    val qualityModeLabel: String? = null,
    /** MEASURED: the media channel's effective MTU, where the stack printed it. */
    val effectiveMtu: Int? = null,
    val reason: String = "",
) {
    /** Where this sits on the module's honesty scale. */
    val honesty: Honesty
        get() = when (confidence) {
            InferenceConfidence.MEASURED -> Honesty.MEASURED
            InferenceConfidence.UNKNOWN -> Honesty.UNAVAILABLE
        }

    /**
     * The spec figure for [mode], and only that.
     *
     * Kept apart from [measuredKbps] rather than merged, because "the number the
     * table assigns this mode" and "the number the radio is carrying" are
     * different claims and this module's whole job is not to blur them.
     */
    val nominalKbps: Int? get() = mode?.nominalKbps

    companion object {
        fun unknown(codec: CodecFamily?, reason: String) =
            ModeInference(
                codec = codec,
                mode = null,
                confidence = InferenceConfidence.UNKNOWN,
                reason = reason,
            )
    }
}

/**
 * Per-codec frame geometry. One implementation per codec, and a codec with no
 * verified constants gets one that says so rather than one that guesses.
 */
interface CodecModeSignatures {

    /**
     * The family this provider is for. Non-null: a codec worth a provider is a
     * codec worth naming, so [CodecFamily] has an entry for each of them.
     */
    val codec: CodecFamily

    /**
     * Codec names, uppercased and stripped of `-` and `_`, that this provider
     * claims. Matched as a prefix against the `codecName:` the dump prints,
     * which is more trustworthy than the numeric type — see
     * [CodecModeSignatureRegistry].
     */
    val codecNames: Set<String>

    /**
     * Encoded frames per second at this sample rate — **independent of the
     * bitrate mode**, because a codec with a fixed frame length in samples
     * emits the same number of frames per second whatever it spends on each.
     *
     * That independence is what makes it a sanity check rather than a signal:
     * if the measured frame rate does not match this, the link is not what the
     * config claims and no mode inference below it is worth anything.
     */
    fun framesPerSecond(sampleRateHz: Int): Double?

    /** The modes and their encoded frame sizes. Empty when unverified. */
    fun signatures(sampleRateHz: Int): List<CodecModeSignature>

    /**
     * Non-null when this codec's constants have not been verified against a
     * real device. The string is shown to the user in place of a mode.
     */
    val unverifiedReason: String?
}

/**
 * Reads what the encoder is running at out of the stack's own fields, and
 * refuses honestly when no field carries it.
 *
 * ## What this used to be, and why none of that is left
 *
 * This object used to reconstruct the LDAC bitrate from `btif_a2dp_source`'s
 * counters. The idea was that a codec with a fixed frame length emits a fixed
 * number of frames per second, that the bitrate decides how many **bytes** each
 * frame costs, and that the stack fits `floor((mtu - headers) / frameBytes)`
 * whole frames into each media packet — so frames-per-packet would be a
 * per-mode signature, measured on both sides.
 *
 * The device falsified the premise. Two measurements did it:
 *
 *  - the **enqueue counter ticks at a constant ~50/s in every mode**, including
 *    pinned 990 and pinned 330. It counts 20 ms media-timer ticks, not radio
 *    packets, so it is not the packet side of that equation and there is no
 *    packet side available;
 *  - consequently frames-divided-by-enqueues is `playing duty cycle x 15`.
 *    Pinned 990 measured 13.5, pinned 330 measured 10.5. Both figures are duty
 *    cycles. Neither carries any information about the mode.
 *
 * That also retired the "plausible media MTU" table this used to solve against:
 * the MTU is now simply printed (`Effective MTU: 883`), and nothing needs
 * solving.
 *
 * ## What it is now
 *
 * A direct read. `dumpsys bluetooth_manager`'s `A2DP LDAC State:` block prints
 * the quality-mode token and the transmission bitrate in kbps, live, and under
 * ABR that bitrate is what the encoder has settled on this second. When the
 * block is there the answer is [InferenceConfidence.MEASURED]; when it is not,
 * the answer is [InferenceConfidence.UNKNOWN] with the reason — never the
 * counters again, and never the codec's headline number.
 *
 * [CodecModeSignatureRegistry] is still consulted, but only to say *why* a
 * codec has no rate: "no adjustable bitrate mode" and "this codec has one and
 * this build does not print it" are different sentences.
 */
object CodecModeInference {

    /**
     * @param rawCodecName the `codecName:` the dump printed, when available.
     *   Preferred over [codec] for provider lookup — see
     *   [CodecModeSignatureRegistry].
     * @param stack the codec's own state block, when the build printed one.
     *   This is the only source of a rate.
     */
    fun infer(
        codec: CodecFamily?,
        sampleRateHz: Int?,
        rawCodecName: String? = null,
        stack: LdacStackState? = null,
    ): ModeInference {
        if (codec == null || sampleRateHz == null) {
            return ModeInference.unknown(codec, "no negotiated codec to reason about")
        }

        val measured = stack?.transmissionKbps
        if (measured != null) {
            return measured(codec, sampleRateHz, stack, measured)
        }

        val provider = CodecModeSignatureRegistry.providerFor(codec, rawCodecName)
            ?: return ModeInference.unknown(
                codec,
                "${codec.displayName} has no adjustable bitrate mode to report",
            )
        provider.unverifiedReason?.let { return ModeInference.unknown(codec, it) }

        // A codec that *does* have named modes, on a build that does not print
        // its live rate. This is the honest end of the road: the packet counters
        // that used to be tried here were falsified as a rate source, so there
        // is nothing left to fall back to.
        return ModeInference.unknown(
            codec,
            "this build does not print an \"A2DP ${codec.displayName} State\" section, " +
                "so the rate ${codec.displayName} is running at is not readable on it",
        )
    }

    /**
     * The stack's own reading, with the mode named only when it names it.
     *
     * The token is matched against the codec's pinnable modes so a pinned link
     * still gets a label, and left null under ABR — where there genuinely is no
     * named mode and [ModeInference.measuredKbps] is the whole answer.
     */
    private fun measured(
        codec: CodecFamily,
        sampleRateHz: Int,
        stack: LdacStackState,
        measuredKbps: Int,
    ): ModeInference {
        val token = stack.qualityMode?.trim()
        val mode = namedMode(codec, sampleRateHz, token)
        return ModeInference(
            codec = codec,
            mode = mode,
            confidence = InferenceConfidence.MEASURED,
            measuredKbps = measuredKbps,
            qualityModeLabel = token,
            effectiveMtu = stack.effectiveMtu,
            reason = when {
                stack.isAdaptive == true ->
                    "the Bluetooth stack reports $measuredKbps kbps going out right now " +
                        "under adaptive bitrate"

                mode != null ->
                    "the Bluetooth stack reports ${mode.label} at $measuredKbps kbps"

                // An unfamiliar token is passed through rather than swallowed:
                // the rate is still a measurement, and the label is still what
                // the stack wrote down.
                token != null ->
                    "the Bluetooth stack reports quality mode \"$token\" at $measuredKbps kbps"

                else -> "the Bluetooth stack reports $measuredKbps kbps going out right now"
            },
        )
    }

    /** The pinnable mode whose spelling matches the stack's token, if any. */
    private fun namedMode(codec: CodecFamily, sampleRateHz: Int, token: String?): CodecMode? {
        if (token.isNullOrEmpty()) return null
        val normalised = token.uppercase().replace("_", "").replace(" ", "")
        val provider = CodecModeSignatureRegistry.providerFor(codec) ?: return null
        return provider.signatures(sampleRateHz)
            .map { it.mode }
            .firstOrNull { mode ->
                STACK_TOKENS_BY_MODE[mode.rawValue]?.contains(normalised) == true
            }
    }

    /**
     * The stack's quality-mode tokens, per `codecSpecific1` value.
     *
     * Kept as a lookup rather than derived from the labels because the two
     * vocabularies are unrelated: the app says "Connection priority" where the
     * stack says `LOW`. A token that appears in none of these is not forced into
     * one — it reaches the screen as itself.
     */
    private val STACK_TOKENS_BY_MODE: Map<Long, Set<String>> = mapOf(
        1000L to setOf("HIGH", "HQ", "HIGHQUALITY"),
        1001L to setOf("MID", "SQ", "STANDARD"),
        1002L to setOf("LOW", "MQ", "CONNECTIONPRIORITY"),
    )
}
