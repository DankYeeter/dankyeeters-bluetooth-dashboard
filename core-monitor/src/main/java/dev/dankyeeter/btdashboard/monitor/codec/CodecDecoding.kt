package dev.dankyeeter.btdashboard.monitor.codec

/**
 * Pure decoding of the integer/bitmask values `BluetoothCodecConfig` hands out.
 *
 * This lives apart from the reflection wrapper so the fiddly part — bitmasks
 * that OEMs fill in inconsistently — is covered by plain JVM unit tests.
 *
 * Codec type ids follow AOSP `BluetoothCodecConfig.SOURCE_CODEC_TYPE_*`.
 * Qualcomm builds add vendor ids for aptX Adaptive that are *not* stable
 * across Android versions, hence [aptxAdaptiveVendorIds] being a set of
 * observed values rather than a constant.
 */
object CodecDecoding {

    const val SOURCE_CODEC_TYPE_SBC = 0
    const val SOURCE_CODEC_TYPE_AAC = 1
    const val SOURCE_CODEC_TYPE_APTX = 2
    const val SOURCE_CODEC_TYPE_APTX_HD = 3
    const val SOURCE_CODEC_TYPE_LDAC = 4
    const val SOURCE_CODEC_TYPE_LC3 = 5
    const val SOURCE_CODEC_TYPE_OPUS = 6

    /** aptX Adaptive ids seen in the wild on Qualcomm/OEM builds. */
    val aptxAdaptiveVendorIds: Set<Int> = setOf(7, 8, 9, 10)

    fun codecFamily(rawType: Int?): CodecFamily = when (rawType) {
        null -> CodecFamily.UNKNOWN
        SOURCE_CODEC_TYPE_SBC -> CodecFamily.SBC
        SOURCE_CODEC_TYPE_AAC -> CodecFamily.AAC
        SOURCE_CODEC_TYPE_APTX -> CodecFamily.APTX
        SOURCE_CODEC_TYPE_APTX_HD -> CodecFamily.APTX_HD
        SOURCE_CODEC_TYPE_LDAC -> CodecFamily.LDAC
        SOURCE_CODEC_TYPE_LC3 -> CodecFamily.LC3
        SOURCE_CODEC_TYPE_OPUS -> CodecFamily.OPUS
        in aptxAdaptiveVendorIds -> CodecFamily.APTX_ADAPTIVE
        else -> CodecFamily.UNKNOWN
    }

    /**
     * Matches a codec name from text sources (dumpsys, broadcasts).
     *
     * Separators are normalised before matching because the same codec is
     * spelled `AptX-HD` in dumpsys, `aptX_HD` in some broadcasts and `aptX HD`
     * in others — and "APTX" is a prefix of all of them, so an unnormalised
     * name falls through to plain aptX and silently downgrades the badge.
     */
    fun codecFamilyFromName(name: String?): CodecFamily {
        val n = name?.trim()?.uppercase()?.replace('_', ' ')?.replace('-', ' ')
            ?: return CodecFamily.UNKNOWN
        return when {
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
