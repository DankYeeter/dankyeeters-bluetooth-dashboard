package dev.dankyeeter.btdashboard.monitor.dumpsys

import dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily

/** One device as reconstructed from a dump. Everything optional by design. */
data class DumpsysDevice(
    val address: String,
    val name: String? = null,
    val isActive: Boolean = false,
    /** `mConnectionState: STATE_CONNECTED` — a bonded device is not a connected one. */
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val codec: CodecFamily? = null,
    /**
     * The `mCodecType:` the dump printed, kept so a [CodecFamily.VENDOR] link
     * can be labelled with the id that is the only thing identifying it.
     */
    val rawCodecType: Int? = null,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val rssiDbm: Int? = null,
)

data class DumpsysSnapshot(
    val devices: List<DumpsysDevice> = emptyList(),
    /** Parser notes (unrecognised sections, truncated dump) for the UI/logs. */
    val warnings: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = devices.isEmpty()
}

/**
 * Last-resort parser for `dumpsys bluetooth_manager` (hierarchy level 3).
 *
 * The format is not an API: it differs between Android versions and between
 * Pixel 8 Pro and Pixel 11 Pro, and MAC addresses are partially redacted
 * (`xx:xx:xx:xx:12:34`) on user builds. The parser is therefore written as a
 * tolerant line scanner rather than a grammar: it recognises the handful of
 * lines it understands, attributes them to the device cursor that is currently
 * open, and ignores everything else. **It must degrade, never crash** — the
 * only public entry point catches everything and returns what it has.
 */
object DumpsysBluetoothParser {

    /** Real dumps redact MACs to `xx:xx:xx:xx:ab:cd`, so `x` is a valid digit. */
    private val MAC = Regex("(?:[0-9A-Fa-fxX]{2}:){5}[0-9A-Fa-fxX]{2}")

    private val CODEC_NAME = Regex("""codecName\s*[:=]\s*([A-Za-z0-9 _\-]+)""")
    private val CODEC_TYPE = Regex("""mCodecType\s*[:=]\s*(\d+)""")
    // Both "mSampleRate:0x4(96000)" and "mSampleRate: 96000" occur.
    private val SAMPLE_RATE = Regex("""mSampleRate\s*[:=]\s*(?:0x[0-9a-fA-F]+\((\d+)\)|(\d+))""")
    private val BITS = Regex("""mBitsPerSample\s*[:=]\s*(?:0x[0-9a-fA-F]+\((\d+)\)|(\d+))""")
    private val RSSI = Regex("""[Rr]ssi\s*[:=]\s*(-?\d+)""")
    private val NAME_AFTER_MAC = Regex("""^\s*\[?\s*(?:name|Name)\s*[:=]\s*(.+?)\s*$""")

    fun parse(dump: String): DumpsysSnapshot = try {
        parseInternal(dump)
    } catch (t: Throwable) {
        DumpsysSnapshot(warnings = listOf("dumpsys parse failed: ${t.javaClass.simpleName}"))
    }

    private fun parseInternal(dump: String): DumpsysSnapshot {
        if (dump.isBlank()) return DumpsysSnapshot(warnings = listOf("empty dump"))

        val devices = LinkedHashMap<String, DumpsysDevice>()
        val warnings = mutableListOf<String>()
        var cursor: String? = null
        var sawBluetoothSection = false
        // Devices whose codec came from a real `mCodecConfig:` line; nothing
        // weaker is allowed to overwrite those.
        val authoritative = mutableSetOf<String>()
        var inIgnoredBlock = false

        fun edit(address: String, block: (DumpsysDevice) -> DumpsysDevice) {
            val current = devices[address] ?: DumpsysDevice(address)
            devices[address] = block(current)
        }

        for (rawLine in dump.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue

            val headerCandidate = line.trimStart()
            if (IGNORED_BLOCK_HEADERS.any { headerCandidate.startsWith(it) }) {
                inIgnoredBlock = true
            } else if (inIgnoredBlock && !headerCandidate.startsWith("{")) {
                // The block ends at the first line that is not one of its
                // `{codecName:...}` entries.
                inIgnoredBlock = false
            }
            if (line.contains("Bluetooth Status", true) ||
                line.contains("BluetoothManagerService", true) ||
                line.contains("AdapterService", true)
            ) {
                sawBluetoothSection = true
            }

            val mac = MAC.find(line)?.value
            if (mac != null) {
                cursor = mac
                edit(mac) { it }
                // "AA:..:FF Noble FoKus Prestige Encore" — trailing text is a name.
                // The active device is printed as "AA:..:FF: Focal Bathys <- ACTIVE",
                // so the marker has to come off or it ends up in the name.
                val trailing = line.substringAfter(mac).trim()
                    .removePrefix("-").removePrefix(":").trim()
                    .substringBefore("<-")
                    .trim()
                    .trim('[', ']', '"')
                // Reject anything that looks structural (key:value, braces) —
                // a device name is plain trailing text.
                if (trailing.isNotEmpty() && trailing.length <= 64 &&
                    !trailing.contains('{') && !trailing.contains(':') &&
                    !trailing.contains('=')
                ) {
                    edit(mac) { d -> if (d.name == null) d.copy(name = trailing) else d }
                }
                if (line.contains("mCurrentDevice", true) || line.contains("ActiveDevice", true) ||
                    line.contains("mActiveDevice", true) ||
                    // "AA:..:FF: Focal Bathys <- ACTIVE"
                    line.contains("<- ACTIVE", true) ||
                    // "=== A2dpStateMachine for AA:..:FF (Active) ===" is the
                    // clearest active marker the dump has.
                    line.contains("(Active)", true)
                ) {
                    edit(mac) { it.copy(isActive = true) }
                }
            }

            val device = cursor ?: continue

            NAME_AFTER_MAC.find(line)?.groupValues?.getOrNull(1)?.let { n ->
                if (n.isNotBlank() && !MAC.containsMatchIn(n)) edit(device) { it.copy(name = n) }
            }

            if (line.contains("mConnectionState", true)) {
                // Every bonded device keeps an A2dpStateMachine block with the
                // codec of its last session. Without this, a headphone that has
                // been off for a week still reports a codec.
                if (line.contains("STATE_CONNECTED", true)) {
                    edit(device) { it.copy(isConnected = true) }
                } else if (line.contains("STATE_DISCONNECTED", true)) {
                    edit(device) { it.copy(isConnected = false) }
                }
            }

            if (line.contains("mIsPlaying", true) || line.contains("A2DP playing", true) ||
                line.contains("isA2dpPlaying", true)
            ) {
                val playing = line.contains("true", true) || line.contains("STATE_PLAYING", true)
                edit(device) { it.copy(isPlaying = playing) }
            }

            // Only `mCodecConfig:` states what is actually negotiated *now*.
            // Three other things in the same dump look just like it and are all
            // wrong to read: the adapter-wide `codecConfigOffloading` list
            // (which starts with SBC and is not per-device), the
            // `mCodecsSelectableCapabilities` list that follows every config,
            // and the `rec[n]: ... CODEC_CONFIG_CHANGED` state-machine history,
            // which carries whatever was negotiated hours ago. Reading any of
            // them reported SBC on a live aptX HD link.
            val trimmed = line.trimStart()
            if (trimmed.startsWith("mCodecConfig")) {
                readCodecFrom(line)?.let { config ->
                    authoritative += device
                    edit(device) {
                        it.copy(
                            codec = config.family,
                            rawCodecType = config.rawCodecType ?: it.rawCodecType,
                            sampleRateHz = config.sampleRateHz ?: it.sampleRateHz,
                            bitsPerSample = config.bitsPerSample ?: it.bitsPerSample,
                        )
                    }
                }
            } else if (device !in authoritative && !inIgnoredBlock && !isHistoryLine(trimmed)) {
                // Tolerance for OEM dumps that never print `mCodecConfig`.
                if (devices[device]?.codec == null) {
                    readCodecFrom(line)?.let { config ->
                        edit(device) {
                            it.copy(
                                codec = config.family,
                                rawCodecType = config.rawCodecType ?: it.rawCodecType,
                                sampleRateHz = config.sampleRateHz ?: it.sampleRateHz,
                                bitsPerSample = config.bitsPerSample ?: it.bitsPerSample,
                            )
                        }
                    }
                }
            }
            if (!line.contains("codecName", true)) {
                RSSI.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { rssi ->
                    // Sanity gate: real RSSI is roughly -100..0 dBm.
                    if (rssi in -127..0) edit(device) { it.copy(rssiDbm = rssi) }
                }
            }
        }

        if (!sawBluetoothSection) {
            warnings += "unrecognised dump format — no Bluetooth section header found"
        }
        if (devices.isEmpty()) warnings += "no devices found in dump"
        return DumpsysSnapshot(devices.values.toList(), warnings)
    }

    /**
     * The first group that matched, as a **physical** quantity.
     *
     * Zero is rejected rather than carried, and that is not tidiness. The
     * patterns above accept two spellings, `0x4(96000)` and a bare `96000`, and
     * the bare branch starts matching at the very character the hex branch does
     * — so whenever the parenthesised value is missing or is not a number, the
     * bare branch matches the `0` of the `0x` prefix and nothing else. Two ways
     * that happens for real: `mBitsPerSample:0x0(NONE)`, which every
     * unnegotiated capability entry prints, and a dump cut mid-line at
     * `mSampleRate:0x4(9600`, which is what a truncated `dumpsys` read looks
     * like.
     *
     * Both used to yield a confident `0`, and a rate of 0 Hz or a depth of 0
     * bits is not a measurement — it is the parser failing while looking like it
     * succeeded, which is the one outcome this file must not have.
     */
    private fun firstInt(groups: List<String>): Int? =
        groups.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 }

    /** Blocks whose `{codecName:...}` entries describe capabilities, not the live link. */
    private val IGNORED_BLOCK_HEADERS = listOf(
        "codecConfigOffloading",
        "codecConfigPriorities",
        "mCodecsSelectableCapabilities",
        "mCodecsLocalCapabilities",
    )

    /** `rec[12]: time=... CODEC_CONFIG_CHANGED ...` — a past negotiation, not the current one. */
    private fun isHistoryLine(trimmed: String): Boolean =
        trimmed.startsWith("rec[") || trimmed.contains("CODEC_CONFIG_CHANGED")

    private data class ParsedCodec(
        val family: CodecFamily,
        val rawCodecType: Int?,
        val sampleRateHz: Int?,
        val bitsPerSample: Int?,
    )

    /**
     * Reads one `{codecName:...}` blob. A capability entry lists several rates
     * as `0x3(44100|48000)`; only a single concrete value is accepted, so a
     * range can never be mistaken for the negotiated rate.
     *
     * The name outranks the type — see [CodecDecoding.codecFamily] — because a
     * dump always prints both and only one of the two is trustworthy.
     */
    private fun readCodecFrom(line: String): ParsedCodec? {
        val rawType = CODEC_TYPE.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val family = CodecDecoding.codecFamily(
            codecName = CODEC_NAME.find(line)?.groupValues?.getOrNull(1),
            rawType = rawType,
        ).takeIf { it != CodecFamily.UNKNOWN } ?: return null
        return ParsedCodec(
            family = family,
            rawCodecType = rawType,
            sampleRateHz = SAMPLE_RATE.find(line)?.let { firstInt(it.groupValues) },
            bitsPerSample = BITS.find(line)?.let { firstInt(it.groupValues) },
        )
    }
}
