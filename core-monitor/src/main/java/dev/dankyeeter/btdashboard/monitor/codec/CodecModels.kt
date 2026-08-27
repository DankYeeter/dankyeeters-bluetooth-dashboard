package dev.dankyeeter.btdashboard.monitor.codec

/**
 * A connected Bluetooth audio device as far as the monitor cares about it.
 * Deliberately a plain data class (no [android.bluetooth.BluetoothDevice]) so
 * every layer above this one is unit-testable on the JVM.
 */
data class BtAudioDevice(
    val address: String,
    val name: String,
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
)

/** Codec families we can name. Anything else stays [UNKNOWN] with a raw id. */
enum class CodecFamily(val displayName: String) {
    SBC("SBC"),
    AAC("AAC"),
    APTX("aptX"),
    APTX_HD("aptX HD"),
    APTX_ADAPTIVE("aptX Adaptive"),
    LDAC("LDAC"),
    LC3("LC3"),
    OPUS("Opus"),

    /**
     * LHDC v5, which the Pixel 11 Pro build prints as `codecName:LHDCv5` on
     * codec **type 7** — the id this app used to hand to aptX Adaptive.
     * Reachable by name only: no numeric id names it, because the same 7 is
     * something else on another build.
     */
    LHDC_V5("LHDC v5"),

    /**
     * A codec that *is* negotiated and whose type id we can read, but whose
     * identity we cannot establish.
     *
     * Distinct from [UNKNOWN], where nothing was readable at all. The id is the
     * only identifying thing such a link has, so it is carried into the label
     * by [label] rather than being guessed into a brand name.
     */
    VENDOR("Vendor codec"),

    UNKNOWN("Unknown"),
    ;

    /**
     * The text to put on screen for this family.
     *
     * Only [VENDOR] differs from [displayName], and it differs because
     * "Vendor codec" on its own leaves the user with nothing to look up, while
     * "Vendor codec (type 7)" is a fact they can search for. Every other family
     * ignores the id — the name is already the whole answer.
     */
    fun label(rawCodecType: Int? = null): String =
        if (this == VENDOR && rawCodecType != null) "$displayName (type $rawCodecType)" else displayName
}

/**
 * One codec, as a single text source described it.
 *
 * The two halves are only meaningful together: the name settles what the codec
 * is, and the id is what labels it in the case where the name settled nothing.
 */
data class CodecReading(val family: CodecFamily, val rawCodecType: Int? = null) {
    val label: String get() = family.label(rawCodecType)
}

/**
 * Everything we managed to read about the negotiated codec. Every field is
 * nullable on purpose: OEM implementations of `getCodecStatus()` return
 * partial or nonsensical values often enough that "unknown" must be a normal,
 * displayable state rather than an error.
 */
data class CodecStatus(
    val family: CodecFamily,
    val rawCodecType: Int? = null,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val channelMode: ChannelMode = ChannelMode.UNKNOWN,
    /** Only aptX Adaptive / LDAC report anything usable here; often null. */
    val bitrateKbps: Int? = null,
    /** Codecs the remote device advertised as selectable, if readable. */
    val selectableCodecs: List<CodecFamily> = emptyList(),
    /** Which mechanism produced these values. Never hidden from the user. */
    val readVia: CodecReadPath = CodecReadPath.SYSTEM_API,
) {
    val label: String get() = family.label(rawCodecType)

    /** Short badge line for the dashboard, e.g. "LDAC · 96 kHz · 24 bit". */
    val badge: String
        get() = buildList {
            add(family.label(rawCodecType))
            sampleRateHz?.let { add(formatSampleRate(it)) }
            bitsPerSample?.let { add("$it bit") }
            bitrateKbps?.let { add("$it kbps") }
        }.joinToString(" · ")
}

enum class ChannelMode { MONO, STEREO, DUAL_CHANNEL, UNKNOWN }

/** Result of a codec read. "Unsupported" is an expected, non-error outcome. */
sealed interface CodecReadResult {
    data class Available(val status: CodecStatus) : CodecReadResult

    /** The API exists but refused (privileged, wrong profile state, OEM stub). */
    data class Unsupported(val reason: String) : CodecReadResult

    /** Something threw. Kept separate so the UI can word it differently. */
    data class Failed(val reason: String) : CodecReadResult
}

// Locale.US on purpose: the app is English-only, and a German device locale
// must not turn "44.1 kHz" into "44,1 kHz".
internal fun formatSampleRate(hz: Int): String =
    if (hz % 1000 == 0) {
        "${hz / 1000} kHz"
    } else {
        String.format(java.util.Locale.US, "%.1f kHz", hz / 1000.0)
    }
