package dev.dankyeeter.btdashboard.monitor.link.live

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import kotlin.math.abs
import kotlin.math.roundToInt

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

/** One mode plus the encoded frame size that identifies it on the air. */
data class CodecModeSignature(val mode: CodecMode, val frameBytes: Int)

/** How the running mode was established. Ordered best first. */
enum class InferenceConfidence {
    /**
     * Matched against a band this app measured on *this* device while the mode
     * was pinned. No assumption about the link MTU survives here — the phone
     * watched the same headphone produce this packet rate under a known mode.
     */
    CALIBRATED,

    /**
     * Solved from the codec's frame geometry: exactly one (mode, MTU) pair in
     * the plausible set produces the frames-per-packet actually measured.
     * Arithmetic on measured counters, so DERIVED — but it does rest on the
     * link MTU being one of [CodecModeInference.PLAUSIBLE_MEDIA_MTUS].
     */
    ANALYTIC,

    /** Ambiguous, unverified, or not applicable. The UI shows the reason. */
    UNKNOWN,
}

/**
 * What the encoder is actually doing, as opposed to what it was configured to
 * do.
 *
 * The distinction is the entire point. `mCodecConfig`'s `mCodecSpecific1` says
 * which mode the *user* pinned, and on an untouched phone that is "none, run
 * adaptive". This says which rate the adaptive encoder settled on, which is the
 * question a listener actually has.
 */
data class ModeInference(
    val codec: CodecFamily?,
    /** Null whenever [confidence] is [InferenceConfidence.UNKNOWN]. */
    val mode: CodecMode?,
    val confidence: InferenceConfidence,
    /** Everything still possible. One entry means [mode] is that entry. */
    val candidates: List<CodecMode> = emptyList(),
    /**
     * MEASURED (as a ratio of two counters): encoded frames per media packet.
     *
     * The single most useful number here even when [mode] is unknown, because
     * for a fixed link it is an **exactly inverse-monotone** stand-in for the
     * bitrate: a bigger frame is a higher rate and fewer of them fit in a
     * packet. So "did it drop, and did it stay down" is answerable from this
     * alone, with no assumption about MTU, frame tables, or codec at all.
     */
    val framesPerPacket: Double? = null,
    /** DERIVED: the media payload size [mode] and [framesPerPacket] imply. */
    val impliedPayloadBytes: IntRange? = null,
    val reason: String = "",
) {
    /** Where this sits on the module's honesty scale. */
    val honesty: Honesty
        get() = when (confidence) {
            InferenceConfidence.CALIBRATED, InferenceConfidence.ANALYTIC -> Honesty.DERIVED
            InferenceConfidence.UNKNOWN -> Honesty.UNAVAILABLE
        }

    val nominalKbps: Int? get() = mode?.nominalKbps

    companion object {
        fun unknown(codec: CodecFamily?, reason: String, framesPerPacket: Double? = null) =
            ModeInference(
                codec = codec,
                mode = null,
                confidence = InferenceConfidence.UNKNOWN,
                framesPerPacket = framesPerPacket,
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
 * Works out which bitrate mode an adaptive codec is running in, from counters
 * the Bluetooth stack already keeps.
 *
 * ## The idea
 *
 * A codec with a fixed frame length in samples emits a fixed number of frames
 * per second. What the bitrate mode changes is how many **bytes** each of those
 * frames costs. The stack packs as many whole frames into a media packet as the
 * link MTU allows, so
 *
 * ```
 * framesPerPacket = floor((mtu - headers) / frameBytes(mode))
 * ```
 *
 * and for a fixed link, frames-per-packet is a per-mode signature. Both sides
 * of it are measured: `btif_a2dp_source` counts frames and packets, and the
 * ratio of two deltas is the frames-per-packet of that window.
 *
 * ## What it will not do
 *
 * The MTU of the A2DP **media** channel is not in any dump this app can read —
 * `dumpsys bluetooth_manager` prints the AVDTP *signalling* channel MTU and
 * nothing else. So the equation has two unknowns and one measurement, and the
 * analytic path only answers when exactly one (mode, MTU) pair in
 * [PLAUSIBLE_MEDIA_MTUS] fits. Two fits that disagree produce
 * [InferenceConfidence.UNKNOWN] with both listed, never the more likely one.
 *
 * [CodecModeCalibrator] removes the second unknown properly: pin a mode, watch
 * the packet rate, and the link has told you its own signature.
 */
object CodecModeInference {

    /**
     * A2DP media-channel MTUs worth testing against.
     *
     * Not a wish list — each is a size the transport actually produces:
     * 339/679/1021 are the DH5, 2-DH5 and 3-DH5 ACL payload limits that A2DP
     * implementations commonly clamp to; 663 and 1008 are caps AOSP's own
     * encoders apply; 672 is the AVDTP signalling MTU this device reports, kept
     * because some stacks use one size for both channels; 895 and 1024 are
     * widely seen negotiated values.
     *
     * Widening this list makes the analytic path *less* decisive, never more —
     * every entry added is another chance for two modes to both fit and for the
     * answer to fall back to UNKNOWN. That is the correct direction for a guess
     * to fail in.
     */
    val PLAUSIBLE_MEDIA_MTUS: List<Int> = listOf(339, 663, 672, 679, 895, 1008, 1021, 1024)

    /**
     * Bytes of packet header before the first codec frame: a 12-byte RTP header
     * plus a one-byte codec media header. Both values are tried because the
     * one-byte header is codec-specific and its presence must not decide the
     * answer — if 12 and 13 disagree about the mode, the result is UNKNOWN.
     */
    private val HEADER_BYTES = listOf(12, 13)

    /**
     * How far the measured frame rate may sit from the codec's fixed rate.
     *
     * Generous on purpose: the frame and packet counters are read from a text
     * dump microseconds apart and are not snapshotted together, which on the
     * real capture showed up as a 0.5% skew.
     */
    private const val FRAME_RATE_TOLERANCE = 0.05

    /**
     * How close frames-per-packet must sit to a whole number to be usable.
     *
     * A window that straddles a mode change averages two different packings and
     * lands between two integers. That window cannot identify either mode, and
     * saying so is better than rounding to whichever is nearer.
     */
    private const val INTEGER_TOLERANCE = 0.2

    /**
     * @param rawCodecName the `codecName:` the dump printed, when available.
     *   Preferred over [codec] for provider lookup — see
     *   [CodecModeSignatureRegistry].
     * @param calibration signatures previously learned on this device for this
     *   codec. Empty is normal and simply drops to the analytic path.
     */
    fun infer(
        codec: CodecFamily?,
        sampleRateHz: Int?,
        framesPerPacket: Double?,
        framesPerSecond: Double?,
        rawCodecName: String? = null,
        calibration: List<ModeSignatureSample> = emptyList(),
    ): ModeInference {
        if (codec == null || sampleRateHz == null) {
            return ModeInference.unknown(codec, "no negotiated codec to reason about")
        }
        val provider = CodecModeSignatureRegistry.providerFor(codec, rawCodecName)
            ?: return ModeInference.unknown(
                codec,
                "${codec.displayName} has no adjustable bitrate mode to infer",
            )
        provider.unverifiedReason?.let { return ModeInference.unknown(codec, it, framesPerPacket) }

        if (framesPerPacket == null || framesPerPacket <= 0.0) {
            return ModeInference.unknown(codec, "no packet counters yet — needs two polls")
        }

        // Mode-independent gate. A frame rate that disagrees with the codec's
        // fixed geometry means the stream is not what the config says, and
        // every step below would be arithmetic on the wrong premise.
        val expectedFrameRate = provider.framesPerSecond(sampleRateHz)
        if (expectedFrameRate != null && framesPerSecond != null && framesPerSecond > 0.0) {
            val error = abs(framesPerSecond - expectedFrameRate) / expectedFrameRate
            if (error > FRAME_RATE_TOLERANCE) {
                return ModeInference.unknown(
                    codec,
                    "measured ${framesPerSecond.roundToInt()} frames/s but " +
                        "${codec.displayName} at ${sampleRateHz / 1000} kHz emits " +
                        "${expectedFrameRate.roundToInt()} — the stream is not the negotiated one",
                    framesPerPacket,
                )
            }
        }

        val signatures = provider.signatures(sampleRateHz)
        if (signatures.isEmpty()) {
            return ModeInference.unknown(
                codec,
                "no verified frame sizes for ${codec.displayName} at ${sampleRateHz / 1000} kHz",
                framesPerPacket,
            )
        }

        calibrated(signatures, calibration, framesPerPacket)?.let { return it }

        val whole = framesPerPacket.roundToInt()
        if (abs(framesPerPacket - whole) > INTEGER_TOLERANCE || whole <= 0) {
            return ModeInference.unknown(
                codec,
                "frames per packet measured %.2f — this window straddles a change"
                    .format(framesPerPacket),
                framesPerPacket,
            )
        }
        return analytic(codec, signatures, whole, framesPerPacket)
    }

    /**
     * A band this phone measured on this headphone under a pinned mode.
     *
     * Beats the analytic path whenever it exists and is unambiguous, because it
     * carries no assumption at all: the link demonstrated the signature itself.
     */
    private fun calibrated(
        signatures: List<CodecModeSignature>,
        calibration: List<ModeSignatureSample>,
        framesPerPacket: Double,
    ): ModeInference? {
        if (calibration.isEmpty()) return null
        val hits = calibration.filter { framesPerPacket in it.framesPerPacket }
        val modes = hits.mapNotNull { hit ->
            signatures.firstOrNull { it.mode.rawValue == hit.modeRawValue }?.mode
        }.distinct()
        if (modes.size != 1) {
            // Overlapping bands mean the calibration itself cannot tell these
            // modes apart on this link. Falling through to the analytic path
            // would be answering a question the better evidence just declined.
            if (hits.isEmpty()) return null
            return ModeInference(
                codec = modes.firstOrNull()?.codec,
                mode = null,
                confidence = InferenceConfidence.UNKNOWN,
                candidates = modes,
                framesPerPacket = framesPerPacket,
                reason = "the calibrated bands for ${modes.joinToString { it.label }} overlap " +
                    "at this packet rate — recalibrate on a quieter link",
            )
        }
        val mode = modes.single()
        val frameBytes = signatures.first { it.mode == mode }.frameBytes
        val whole = framesPerPacket.roundToInt()
        return ModeInference(
            codec = mode.codec,
            mode = mode,
            confidence = InferenceConfidence.CALIBRATED,
            candidates = listOf(mode),
            framesPerPacket = framesPerPacket,
            impliedPayloadBytes = whole * frameBytes until (whole + 1) * frameBytes,
            reason = "matches the band measured on this device while ${mode.label} was pinned",
        )
    }

    private fun analytic(
        codec: CodecFamily,
        signatures: List<CodecModeSignature>,
        framesPerPacket: Int,
        measured: Double,
    ): ModeInference {
        val fits = signatures.filter { signature ->
            PLAUSIBLE_MEDIA_MTUS.any { mtu ->
                HEADER_BYTES.any { header ->
                    val usable = mtu - header
                    usable > 0 && usable / signature.frameBytes == framesPerPacket
                }
            }
        }
        val modes = fits.map { it.mode }
        return when (modes.size) {
            1 -> {
                val fit = fits.single()
                ModeInference(
                    codec = codec,
                    mode = fit.mode,
                    confidence = InferenceConfidence.ANALYTIC,
                    candidates = modes,
                    framesPerPacket = measured,
                    impliedPayloadBytes = framesPerPacket * fit.frameBytes until
                        (framesPerPacket + 1) * fit.frameBytes,
                    reason = "$framesPerPacket frames per packet fits only " +
                        "${fit.mode.label} (${fit.frameBytes} B/frame) across every " +
                        "plausible link MTU",
                )
            }

            0 -> ModeInference.unknown(
                codec,
                "$framesPerPacket frames per packet fits no known " +
                    "${codec.displayName} mode at any plausible link MTU",
                measured,
            )

            else -> ModeInference(
                codec = codec,
                mode = null,
                confidence = InferenceConfidence.UNKNOWN,
                candidates = modes,
                framesPerPacket = measured,
                reason = "$framesPerPacket frames per packet is consistent with " +
                    "${modes.joinToString { it.label }} — the link MTU is not readable, " +
                    "so calibration is the only way to tell them apart",
            )
        }
    }
}
