package dev.dankyeeter.btdashboard.monitor.codec

/**
 * Pure decoding of the integer/bitmask values `BluetoothCodecConfig` hands out.
 *
 * This lives apart from the reflection wrapper so the fiddly part — bitmasks
 * that OEMs fill in inconsistently — is covered by plain JVM unit tests.
 *
 * Codec type ids follow AOSP `BluetoothCodecConfig.SOURCE_CODEC_TYPE_*`, which
 * fixes 0..6 and nothing above. Every id past that is vendor territory where
 * the same number means different codecs on different builds, so this file
 * names none of them — see [codecFamily].
 */
object CodecDecoding {

    const val SOURCE_CODEC_TYPE_SBC = 0
    const val SOURCE_CODEC_TYPE_AAC = 1
    const val SOURCE_CODEC_TYPE_APTX = 2
    const val SOURCE_CODEC_TYPE_APTX_HD = 3
    const val SOURCE_CODEC_TYPE_LDAC = 4
    const val SOURCE_CODEC_TYPE_LC3 = 5
    const val SOURCE_CODEC_TYPE_OPUS = 6

    /**
     * Decodes a numeric codec type. **A fallback**: prefer the two-argument
     * [codecFamily] whenever the source also printed a name.
     *
     * No id here maps to aptX Adaptive, and that absence is the point. This
     * used to claim 7, 8, 9 and 10 for it, on the theory that a vendor id which
     * moves between Android versions can be covered by listing every value ever
     * observed. A Pixel 11 Pro dump settles it the other way:
     * `{codecName:LHDCv5,mCodecType:7,...}`. The set was not merely incomplete,
     * it was wrong — every LHDC link on that build was badged "aptX Adaptive"
     * in the device list, the profile editor and the monitor at once. A number
     * that names two different codecs names neither, so ids we cannot place
     * become [CodecFamily.VENDOR] and carry the number itself to the screen.
     */
    fun codecFamily(rawType: Int?): CodecFamily = when (rawType) {
        null -> CodecFamily.UNKNOWN
        SOURCE_CODEC_TYPE_SBC -> CodecFamily.SBC
        SOURCE_CODEC_TYPE_AAC -> CodecFamily.AAC
        SOURCE_CODEC_TYPE_APTX -> CodecFamily.APTX
        SOURCE_CODEC_TYPE_APTX_HD -> CodecFamily.APTX_HD
        SOURCE_CODEC_TYPE_LDAC -> CodecFamily.LDAC
        SOURCE_CODEC_TYPE_LC3 -> CodecFamily.LC3
        SOURCE_CODEC_TYPE_OPUS -> CodecFamily.OPUS
        // Negative values are not codec types at all — they are the "invalid"
        // and "not read" markers the framework and this app pass around — so
        // they stay UNKNOWN instead of becoming a vendor codec whose label
        // would print a number that identifies nothing.
        else -> if (rawType >= 0) CodecFamily.VENDOR else CodecFamily.UNKNOWN
    }

    /**
     * The family for a link, deciding by **name** and only then by number.
     *
     * The name is what the Bluetooth stack itself wrote down for this link; the
     * numeric type is an id vendors reuse. When the two disagree the name is
     * the one that is right, which is the whole lesson of type 7 being LHDCv5
     * on one build and something else on another.
     *
     * Written as one function rather than left to each caller because the
     * obvious spelling of it at the call site is subtly wrong: `name?.let
     * (::codecFamilyFromName)` yields UNKNOWN — not null — for a name that
     * matches nothing, so the elvis fallback to the number never runs and a
     * readable type is thrown away.
     */
    fun codecFamily(codecName: String?, rawType: Int?): CodecFamily =
        codecFamilyFromName(codecName)
            .takeIf { it != CodecFamily.UNKNOWN }
            ?: codecFamily(rawType)

    /**
     * Matches a codec name from text sources (dumpsys, broadcasts).
     *
     * Separators are normalised before matching because the same codec is
     * spelled `AptX-HD` in dumpsys, `aptX_HD` in some broadcasts and `aptX HD`
     * in others — and "APTX" is a prefix of all of them, so an unnormalised
     * name falls through to plain aptX and silently downgrades the badge.
     *
     * Only versions that have actually been seen printed get a name. `LHDCv5`
     * has; a bare `LHDC` has not, and it falls through to the numeric path and
     * its honest "Vendor codec (type N)" rather than being assumed to be v5.
     */
    fun codecFamilyFromName(name: String?): CodecFamily {
        val n = name?.trim()?.uppercase()?.replace('_', ' ')?.replace('-', ' ')
            ?: return CodecFamily.UNKNOWN
        return when {
            n.contains("LHDC") && n.contains("V5") -> CodecFamily.LHDC_V5
            n.contains("ADAPTIVE") -> CodecFamily.APTX_ADAPTIVE
            n.contains("APTX HD") || n.contains("APTXHD") -> CodecFamily.APTX_HD
            n.contains("APTX") -> CodecFamily.APTX
            n.contains("LDAC") -> CodecFamily.LDAC
            n.contains("AAC") -> CodecFamily.AAC
            n.contains("SBC") -> CodecFamily.SBC
            n.contains("LC3") -> CodecFamily.LC3
            n.contains("OPUS") -> CodecFamily.OPUS
            else -> CodecFamily.UNKNOWN
        }
    }

    /** `mCodecConfig:{...}`, `codecConfig:{...}` or `codecConfig{...}`. */
    private val NEGOTIATED_CONFIG = Regex("""m?[Cc]odec[Cc]onfig\s*[:=]?\s*\{([^}]*)}""")
    private val CODEC_NAME_FIELD = Regex("""codecName\s*[:=]\s*([^,}\]]+)""")
    private val CODEC_TYPE_FIELD = Regex("""mCodecType\s*[:=]\s*(\d+)""")

    /**
     * Decodes a whole `BluetoothCodecStatus`-shaped blob of text.
     *
     * The A2DP codec-change broadcast carries the status object itself, and its
     * class is hidden — `toString()` is the only portable way to read it. That
     * string holds the negotiated config **and** every codec the phone and the
     * headphone could have agreed on instead, so scanning all of it for a brand
     * name asks the wrong question: it answers with whichever codec this file's
     * `when` order reaches first, from a list of possibilities.
     *
     * That is not hypothetical. On a Pixel 11 Pro an LDAC link
     * (`codecName:LDAC,mCodecType:4`) was announced in the monitor's event list
     * as "Codec is now aptX HD", because aptX HD sits in the capability list
     * that follows the config and "APTX HD" is tested before "LDAC".
     *
     * So the negotiated section is isolated first and only then decoded, by the
     * same name-first rule as every other source.
     */
    fun codecFromStatusText(statusText: String?): CodecReading {
        val text = statusText?.takeIf { it.isNotBlank() }
            ?: return CodecReading(CodecFamily.UNKNOWN)
        val scope = NEGOTIATED_CONFIG.find(text)?.groupValues?.getOrNull(1)
            // No config section, but the blob is still field-shaped: take the
            // *first* `codecName`, which is where every AOSP spelling seen so
            // far prints the negotiated one. Never the whole text.
            ?: text.takeIf { it.contains("codecName", ignoreCase = true) }
            // Not a blob at all — some sources hand over a bare codec name.
            ?: return CodecReading(codecFamilyFromName(text))
        val name = CODEC_NAME_FIELD.find(scope)?.groupValues?.getOrNull(1)?.trim()
        val rawType = CODEC_TYPE_FIELD.find(scope)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return CodecReading(codecFamily(name, rawType), rawType)
    }

    private val sampleRates = linkedMapOf(
        0x1 to 44_100,
        0x2 to 48_000,
        0x4 to 88_200,
        0x8 to 96_000,
        0x10 to 176_400,
        0x20 to 192_000,
    )

    private val bitDepths = linkedMapOf(0x1 to 16, 0x2 to 24, 0x4 to 32)

    /**
     * A *selected* config is supposed to carry exactly one bit. When an OEM
     * hands us a capability mask instead we refuse to guess and return null —
     * "unknown" is honest, a wrong 192 kHz badge is not.
     */
    fun sampleRate(mask: Int): Int? = singleBitValue(mask, sampleRates)

    fun bitsPerSample(mask: Int): Int? = singleBitValue(mask, bitDepths)

    fun channelMode(mask: Int): ChannelMode = when (mask) {
        0x1 -> ChannelMode.MONO
        0x2 -> ChannelMode.STEREO
        0x4 -> ChannelMode.DUAL_CHANNEL
        else -> ChannelMode.UNKNOWN
    }

    /** Every sample rate advertised in a capability mask, ascending. */
    fun supportedSampleRates(mask: Int): List<Int> =
        sampleRates.filterKeys { it and mask != 0 }.values.sorted()

    private fun <T> singleBitValue(mask: Int, table: Map<Int, T>): T? {
        if (mask == 0 || mask.countOneBits() != 1) return null
        return table[mask]
    }

    /**
     * LDAC's `codecSpecific1` encodes the quality index rather than a bitrate.
     * AOSP values: 1000 = 990/909 kbps, 1001 = 660/606, 1002 = 330/303,
     * 1003 = adaptive. The kbps figures are the 96 kHz/44.1 kHz pair; we report
     * the 44.1/48 kHz figure because that is what phones actually stream.
     */
    fun ldacBitrateKbps(codecSpecific1: Long): Int? = when (codecSpecific1) {
        1000L -> 909
        1001L -> 606
        1002L -> 303
        else -> null
    }
}
