package dev.dankyeeter.btdashboard.monitor.link.live

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import kotlin.math.roundToInt

/**
 * Frame geometry for the codecs whose constants are verified, and honest
 * refusals for the ones whose are not.
 *
 * ## Why the stubs exist rather than being left out
 *
 * A missing provider and an unverified one produce the same UNKNOWN, but they
 * say different things to the next person: "this codec has no adjustable
 * bitrate" against "this codec has one and we have not measured its frames
 * yet". [CodecModeSignatures.unverifiedReason] carries that difference all the
 * way to the screen, which is also the only thing stopping someone filling the
 * table in from a marketing page.
 *
 * ## Why providers are matched by name as well as by family
 *
 * `CodecDecoding` now decides the family by name first, so this no longer has
 * to undo a misdecoded one — a `codecName:LHDCv5` link arrives as
 * [CodecFamily.LHDC_V5] rather than as aptX Adaptive. The name lookup stays
 * because it answers a question the family cannot: a source that reads the
 * codec through the framework API gets a number and no name at all, and a
 * type it cannot place lands here as [CodecFamily.VENDOR] — one family shared
 * by every unidentified codec, which is exactly the kind of key that must not
 * select a provider. A name matches a provider or nothing does.
 *
 * ## Codecs deliberately absent
 *
 * SBC, AAC, Opus, aptX and aptX HD have no per-mode signature here. SBC's
 * bitpool is genuinely adjustable, but on this hardware SBC, AAC and Opus are
 * **offloaded to the controller** — the host never sees a frame or a packet of
 * them, so there is nothing to count and no inference to make at any accuracy.
 * See [LinkObservability].
 */
object CodecModeSignatureRegistry {

    private val providers: List<CodecModeSignatures> = listOf(
        LdacModeSignatures,
        LhdcV5ModeSignatures,
        AptxAdaptiveModeSignatures,
    )

    /**
     * The provider for a link, preferring the codec name the dump printed.
     *
     * Name first, the same order `CodecDecoding` resolves a family in and for
     * the same reason: the name is what the stack actually wrote down, the
     * numeric type is an id OEMs reuse. [CodecFamily.VENDOR] deliberately
     * matches no provider — it names no codec, so it can select none.
     */
    fun providerFor(family: CodecFamily?, rawCodecName: String? = null): CodecModeSignatures? {
        val name = rawCodecName?.trim()?.uppercase()?.replace("-", "")?.replace("_", "")
        if (!name.isNullOrEmpty()) {
            providers.firstOrNull { provider ->
                provider.codecNames.any { name.startsWith(it) }
            }?.let { return it }
        }
        return family?.let { f -> providers.firstOrNull { it.codec == f } }
    }
}

/**
 * LDAC.
 *
 * ## The two constants, and how they were checked
 *
 * LDAC encodes a fixed **128 samples per channel per frame** (libldac's
 * `LDACBT_ENC_LSU`), so the frame rate is `sampleRate / 128` and does not move
 * with the bitrate mode. Frame size in bytes follows from the mode's bitrate:
 *
 * ```
 * frameBytes = kbps * 1000 / 8 / (sampleRate / 128)
 * ```
 *
 * which comes out at 330/220/110 bytes for the 44.1 and 48 kHz family and
 * exactly half that — 165/110/55 — for 88.2 and 96 kHz, because those rates
 * emit twice as many frames per second for the same bitrate.
 *
 * The 128 is not taken on faith. The verbatim capture in
 * `bt_manager_pixel11_ldac_txqueue.txt` recorded 4,693,895 encoded frames over
 * an LDAC 96 kHz session that connected at 15:14:21 and dropped at 17:24:49 —
 * a 7,828 second window. At 128 samples per frame that is 750 frames/s and
 * 6,258 seconds of encoding, 80% of the window, which matches a session with
 * the handful of pause/resume events the same dump lists. At 256 samples per
 * frame it would be 375 frames/s and 12,517 seconds of encoding — an hour and
 * a half longer than the link existed. The alternative is not merely less
 * likely, it is impossible, and `CodecModeInferenceTest` keeps that check.
 */
object LdacModeSignatures : CodecModeSignatures {

    override val codec: CodecFamily = CodecFamily.LDAC
    override val codecNames: Set<String> = setOf("LDAC")
    override val unverifiedReason: String? = null

    /** Samples per channel in one LDAC frame. Mode-independent. */
    const val FRAME_SAMPLES = 128

    val highQuality = CodecMode(CodecFamily.LDAC, 1000L, "High quality", null)
    val standard = CodecMode(CodecFamily.LDAC, 1001L, "Standard", null)
    val connectionPriority = CodecMode(CodecFamily.LDAC, 1002L, "Connection priority", null)

    /** Every mode that can be pinned, best first. Drives calibration order. */
    val pinnableModes: List<CodecMode> = listOf(highQuality, standard, connectionPriority)

    override fun framesPerSecond(sampleRateHz: Int): Double? =
        sampleRateHz.takeIf { it > 0 }?.let { it.toDouble() / FRAME_SAMPLES }

    override fun signatures(sampleRateHz: Int): List<CodecModeSignature> {
        val framesPerSecond = framesPerSecond(sampleRateHz) ?: return emptyList()
        return pinnableModes.mapNotNull { mode ->
            val kbps = LdacState.nominalKbps(LdacState.modeOf(mode.rawValue), sampleRateHz)
                ?: return@mapNotNull null
            CodecModeSignature(
                mode = mode.copy(nominalKbps = kbps),
                frameBytes = (kbps * 1000.0 / 8.0 / framesPerSecond).roundToInt(),
            )
        }
    }
}

/**
 * LHDC v5 — advertised as a local capability by this phone (codec type 7,
 * priority 5002, up to 192 kHz) but never negotiated with the headphones on
 * hand, so nothing about its framing has been observed here.
 *
 * LHDC's frame length in samples is not published in a form worth encoding from
 * memory, and getting it wrong does not fail loudly: a wrong frame size still
 * produces a plausible-looking mode, just the wrong one. Left refusing until a
 * real LHDC link can be captured and its frame rate falsified the same way
 * LDAC's was.
 */
object LhdcV5ModeSignatures : CodecModeSignatures {
    override val codec: CodecFamily = CodecFamily.LHDC_V5

    /**
     * Matched on the bare stem, not on "LHDCV5": [CodecFamily.LHDC_V5] is the
     * only LHDC this app names, so a dump printing some other LHDC version
     * should still be refused with LHDC's reason rather than fall through to
     * "no adjustable bitrate mode", which would be a claim about the codec.
     */
    override val codecNames: Set<String> = setOf("LHDC")
    override val unverifiedReason: String =
        "LHDC v5 frame geometry has not been verified on a real link, so its bitrate " +
            "mode cannot be inferred yet"

    override fun framesPerSecond(sampleRateHz: Int): Double? = null
    override fun signatures(sampleRateHz: Int): List<CodecModeSignature> = emptyList()
}

/**
 * aptX Adaptive.
 *
 * Adaptive by construction — it moves continuously between roughly 279 and
 * 420 kbps rather than stepping between a handful of named levels. That is a
 * different shape of problem from LDAC's three-step ladder: there is no small
 * set of frame sizes for frames-per-packet to select between, so even with the
 * constants in hand the signature approach would not resolve a rate.
 *
 * Recorded as its own refusal rather than folded into "unverified", because no
 * amount of measurement will make the ladder method work here — this one needs
 * a different mechanism, not more data. Frames-per-packet is still reported and
 * is still an inverse-monotone rate indicator, which is the part that does
 * transfer.
 */
object AptxAdaptiveModeSignatures : CodecModeSignatures {
    override val codec: CodecFamily = CodecFamily.APTX_ADAPTIVE
    override val codecNames: Set<String> = setOf("APTXADAPTIVE")
    override val unverifiedReason: String =
        "aptX Adaptive varies its rate continuously rather than in named steps, so " +
            "frames-per-packet cannot identify a mode"

    override fun framesPerSecond(sampleRateHz: Int): Double? = null
    override fun signatures(sampleRateHz: Int): List<CodecModeSignature> = emptyList()
}
