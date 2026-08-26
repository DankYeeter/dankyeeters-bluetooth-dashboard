package dev.dankyeeter.btdashboard.privileged

import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily

/**
 * What travels over the codec operations, and the translation to the integers
 * `BluetoothCodecConfig` expects.
 *
 * Kept apart from the reflection in [HelperBluetooth] for the same reason
 * [CodecDecoding] is kept apart from `A2dpCodecStatusSource`: the fiddly part
 * is the bitmask arithmetic, and that part must be unit-testable without a
 * phone. This is the *write* direction; [CodecDecoding] is the read direction,
 * and `PrivilegedCodecTest` checks that a value survives a round trip through
 * both.
 */

/** Channel modes as they cross the Binder — plain values, not AOSP bitmasks. */
object ChannelModes {
    const val UNSPECIFIED = 0
    const val MONO = 1
    const val STEREO = 2
    const val DUAL = 3

    fun toChannelMode(value: Int): ChannelMode = when (value) {
        MONO -> ChannelMode.MONO
        STEREO -> ChannelMode.STEREO
        DUAL -> ChannelMode.DUAL_CHANNEL
        else -> ChannelMode.UNKNOWN
    }

    fun fromChannelMode(mode: ChannelMode): Int = when (mode) {
        ChannelMode.MONO -> MONO
        ChannelMode.STEREO -> STEREO
        ChannelMode.DUAL_CHANNEL -> DUAL
        ChannelMode.UNKNOWN -> UNSPECIFIED
    }
}

/**
 * One codec wish.
 *
 * Zero means "do not state a preference for this field, let the stack pick".
 * That is a real third option next to a concrete value, and it is the default:
 * a user who wants LDAC and has no opinion about bit depth should not silently
 * be forcing 16 bit.
 */
data class CodecRequest(
    val family: CodecFamily,
    val sampleRateHz: Int = 0,
    val bitsPerSample: Int = 0,
    /** One of [ChannelModes]. */
    val channelMode: Int = ChannelModes.UNSPECIFIED,
    /**
     * LDAC playback quality, as AOSP encodes it in `codecSpecific1`:
     * 1000 = highest bitrate, 1001 = standard, 1002 = best effort/connection,
     * 1003 = adaptive. Zero means "do not state a preference".
     */
    val ldacQuality: Long = 0L,
)

/**
 * What the helper actually saw after the call — never what it asked for.
 *
 * [matched] is deliberately three-valued:
 *  - `true`  the read-back agrees with every field that was requested;
 *  - `false` it does not, which means either the stack refused or it has not
 *    finished renegotiating. **Those two cannot be told apart from here**, so
 *    the [note] says so rather than the code guessing;
 *  - `null`  nothing was requested, i.e. this is a plain read.
 */
data class CodecObservation(
    /** [CodecFamily.name] as read back, or empty when it could not be read. */
    val family: String,
    val sampleRateHz: Int = 0,
    val bitsPerSample: Int = 0,
    /** One of [ChannelModes]. */
    val channelMode: Int = ChannelModes.UNSPECIFIED,
    val ldacQuality: Long = 0L,
    val matched: Boolean? = null,
    /** Codec families the remote device advertised, if readable. */
    val selectable: List<String> = emptyList(),
    /** Free text for the UI. Always says what was observed, never a verdict. */
    val note: String = "",
) {
    val codecFamily: CodecFamily
        get() = CodecFamily.entries.firstOrNull { it.name == family } ?: CodecFamily.UNKNOWN

    val selectableFamilies: List<CodecFamily>
        get() = selectable.mapNotNull { name -> CodecFamily.entries.firstOrNull { it.name == name } }

    /** "LDAC · 96 kHz · 24 bit", for a message the user reads. */
    val summary: String
        get() = buildList {
            add(codecFamily.displayName)
            if (sampleRateHz > 0) add("$sampleRateHz Hz")
            if (bitsPerSample > 0) add("$bitsPerSample bit")
            ChannelModes.toChannelMode(channelMode)
                .takeIf { it != ChannelMode.UNKNOWN }
                ?.let { add(it.name.lowercase()) }
        }.joinToString(" · ")
}

/**
 * What the helper saw about one device's HD-audio switch.
 *
 * Both flags are tri-state on purpose, mirroring AOSP's own
 * `OPTIONAL_CODECS_*_UNKNOWN`:
 *
 *  - [supported] null means the stack has not established whether the headphone
 *    offers anything beyond SBC. A device that has been bonded but never
 *    connected reads exactly this, and reporting it as "not supported" would
 *    grey out the control for a headphone that supports HD audio fine.
 *  - [enabled] null means nobody has chosen. Android then applies its own rule
 *    — on where supported — so the effective answer is usually "on", but "on
 *    because you asked" and "on because nobody said otherwise" are different
 *    facts and the second one is undoable.
 */
data class HdAudioObservation(
    val supported: Boolean?,
    val enabled: Boolean?,
    /** Free text for the UI. Says what was observed, never a verdict. */
    val note: String = "",
)

/** AOSP's `BluetoothA2dp.OPTIONAL_CODECS_*` values, passed through unchanged. */
object OptionalCodecs {
    const val PREF_UNKNOWN = -1
    const val PREF_DISABLED = 0
    const val PREF_ENABLED = 1

    /** True for the three values [PREF_UNKNOWN]..[PREF_ENABLED] and nothing else. */
    fun isWritablePreference(value: Int): Boolean =
        value == PREF_UNKNOWN || value == PREF_DISABLED || value == PREF_ENABLED

    /**
     * AOSP returns the same -1/0/1 triple from both getters, so one translation
     * serves both. `SUPPORT_UNKNOWN` and `PREF_UNKNOWN` are both -1.
     */
    fun toTriState(value: Int?): Boolean? = when (value) {
        PREF_ENABLED -> true
        PREF_DISABLED -> false
        else -> null
    }

    fun fromTriState(enabled: Boolean?): Int = when (enabled) {
        true -> PREF_ENABLED
        false -> PREF_DISABLED
        null -> PREF_UNKNOWN
    }
}

/**
 * Values → the bitmasks `BluetoothCodecConfig` is built from.
 *
 * The tables are the same ones [CodecDecoding] reads, written out again in the
 * other direction rather than inverted at runtime: an inverted map would make
 * "44.1 kHz is not offered for writing" impossible to express separately from
 * "44.1 kHz cannot be read", and those are different decisions.
 */
object A2dpCodecMasks {

    /** AOSP `BluetoothCodecConfig.SAMPLE_RATE_NONE` and friends are all 0. */
    const val NONE = 0

    private val sampleRateMasks = linkedMapOf(
        44_100 to 0x1,
        48_000 to 0x2,
        88_200 to 0x4,
        96_000 to 0x8,
        176_400 to 0x10,
        192_000 to 0x20,
    )

    private val bitsMasks = linkedMapOf(16 to 0x1, 24 to 0x2, 32 to 0x4)

    private val channelMasks = linkedMapOf(
        ChannelModes.MONO to 0x1,
        ChannelModes.STEREO to 0x2,
        ChannelModes.DUAL to 0x4,
    )

    /** Sample rates the editor offers, ascending. */
    val offeredSampleRatesHz: List<Int> = sampleRateMasks.keys.toList()

    val offeredBitsPerSample: List<Int> = bitsMasks.keys.toList()

    /**
     * The AOSP codec type id for a family, or null when we must not guess.
     *
     * [CodecFamily.APTX_ADAPTIVE] is deliberately absent: its id is a vendor
     * value that has moved between Android versions (see
     * [CodecDecoding.aptxAdaptiveVendorIds] — a *set* of observed ids, not a
     * constant). Reading one and naming it is safe; writing one means picking
     * a number and hoping, and a wrong codec type is a request to renegotiate
     * into something nobody asked for.
     */
    fun codecType(family: CodecFamily): Int? = when (family) {
        CodecFamily.SBC -> CodecDecoding.SOURCE_CODEC_TYPE_SBC
        CodecFamily.AAC -> CodecDecoding.SOURCE_CODEC_TYPE_AAC
        CodecFamily.APTX -> CodecDecoding.SOURCE_CODEC_TYPE_APTX
        CodecFamily.APTX_HD -> CodecDecoding.SOURCE_CODEC_TYPE_APTX_HD
        CodecFamily.LDAC -> CodecDecoding.SOURCE_CODEC_TYPE_LDAC
        CodecFamily.LC3 -> CodecDecoding.SOURCE_CODEC_TYPE_LC3
        CodecFamily.OPUS -> CodecDecoding.SOURCE_CODEC_TYPE_OPUS
        CodecFamily.APTX_ADAPTIVE, CodecFamily.UNKNOWN -> null
    }

    /** Every family the app is willing to *ask* for, in the editor's order. */
    val writableFamilies: List<CodecFamily> =
        CodecFamily.entries.filter { codecType(it) != null }

    /**
     * The wire value for "hand the codec decision back to the system".
     *
     * Not an AOSP codec type: those are non-negative, so this can never collide.
     * Sent through the existing `setCodecPreference` call on purpose — a new
     * binder method would need a protocol version bump, and an *old* helper
     * receiving this value rejects it loudly in `rejectRaw` instead of pinning
     * some codec it mistook the sentinel for.
     */
    const val SYSTEM_DEFAULT_SENTINEL = -2

    /** 0 (`SAMPLE_RATE_NONE`) for "no preference"; null for a value we do not know. */
    fun sampleRateMask(hz: Int): Int? = if (hz == 0) NONE else sampleRateMasks[hz]

    fun bitsMask(bitsPerSample: Int): Int? =
        if (bitsPerSample == 0) NONE else bitsMasks[bitsPerSample]

    fun channelMask(channelMode: Int): Int? =
        if (channelMode == ChannelModes.UNSPECIFIED) NONE else channelMasks[channelMode]

    /**
     * Whether a request is expressible at all, with the reason when it is not.
     *
     * Checked on both sides: the app so the user gets a sentence instead of a
     * round trip, the helper because it is the side that must not be talked
     * into building a config out of numbers it did not recognise. Both sides
     * run [rejectRaw] — the same code on the same values, so the two can not
     * drift into disagreeing about what is legal.
     */
    fun reject(request: CodecRequest): String? {
        val type = codecType(request.family)
            ?: return "${request.family.displayName} cannot be requested — this app has no " +
                "stable codec id for it on this Android version"
        return rejectRaw(
            rawCodecType = type,
            sampleRateHz = request.sampleRateHz,
            bitsPerSample = request.bitsPerSample,
            channelMode = request.channelMode,
            ldacQuality = request.ldacQuality,
        )
    }

    /**
     * The same check against the raw integers that cross the Binder.
     *
     * The helper cannot re-derive a [CodecRequest] from them without first
     * trusting them, which is the wrong order — so it validates the numbers it
     * was actually handed.
     */
    fun rejectRaw(
        rawCodecType: Int,
        sampleRateHz: Int,
        bitsPerSample: Int,
        channelMode: Int,
        ldacQuality: Long,
    ): String? {
        val family = CodecDecoding.codecFamily(rawCodecType)
        return when {
            codecType(family) != rawCodecType ->
                "codec type $rawCodecType is not one this app is willing to request"

            sampleRateMask(sampleRateHz) == null ->
                "$sampleRateHz Hz is not an A2DP sample rate"

            bitsMask(bitsPerSample) == null ->
                "$bitsPerSample bit is not an A2DP sample depth"

            channelMask(channelMode) == null ->
                "channel mode $channelMode is not one this app knows"

            ldacQuality != 0L && ldacQuality !in LDAC_QUALITIES ->
                "LDAC quality $ldacQuality is not one of $LDAC_QUALITIES"

            ldacQuality != 0L && family != CodecFamily.LDAC ->
                "an LDAC playback quality only means anything on LDAC"

            else -> null
        }
    }

    /** The four values AOSP defines for LDAC's `codecSpecific1`. */
    val LDAC_QUALITIES: List<Long> = listOf(1000L, 1001L, 1002L, 1003L)

    /** Wording for the editor. The kbps figures are AOSP's own 44.1/48 kHz pair. */
    fun ldacQualityLabel(quality: Long): String = when (quality) {
        1000L -> "Sound quality (909 kbps)"
        1001L -> "Standard (606 kbps)"
        1002L -> "Connection quality (303 kbps)"
        1003L -> "Adaptive bitrate"
        else -> "Leave alone"
    }
}
