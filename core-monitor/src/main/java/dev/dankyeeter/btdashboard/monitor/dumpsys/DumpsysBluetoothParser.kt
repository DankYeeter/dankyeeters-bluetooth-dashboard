package dev.dankyeeter.btdashboard.monitor.dumpsys

import dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily

/** One device as reconstructed from a dump. Everything optional by design. */
data class DumpsysDevice(
    val address: String,
    val name: String? = null,
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val codec: CodecFamily? = null,
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

        fun edit(address: String, block: (DumpsysDevice) -> DumpsysDevice) {
            val current = devices[address] ?: DumpsysDevice(address)
            devices[address] = block(current)
        }

        for (rawLine in dump.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
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
                val trailing = line.substringAfter(mac).trim()
                    .removePrefix("-").removePrefix(":").trim()
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
                    line.contains("mActiveDevice", true)
                ) {
                    edit(mac) { it.copy(isActive = true) }
                }
            }

            val device = cursor ?: continue

            NAME_AFTER_MAC.find(line)?.groupValues?.getOrNull(1)?.let { n ->
                if (n.isNotBlank() && !MAC.containsMatchIn(n)) edit(device) { it.copy(name = n) }
            }

            if (line.contains("mIsPlaying", true) || line.contains("A2DP playing", true) ||
                line.contains("isA2dpPlaying", true)
            ) {
                val playing = line.contains("true", true) || line.contains("STATE_PLAYING", true)
                edit(device) { it.copy(isPlaying = playing) }
            }

            // The selected config comes first; the "codecsSelectable" list that
            // follows must not overwrite it with whatever the remote supports.
            val isCapabilityLine = line.contains("selectable", true) ||
                line.contains("capabilit", true)
            if (!isCapabilityLine && devices[device]?.codec == null) {
                CODEC_NAME.find(line)?.groupValues?.getOrNull(1)?.let { name ->
                    val family = CodecDecoding.codecFamilyFromName(name)
                    if (family != CodecFamily.UNKNOWN) edit(device) { it.copy(codec = family) }
                }
            }
            if (devices[device]?.codec == null) {
                CODEC_TYPE.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { raw ->
                    val family = CodecDecoding.codecFamily(raw)
                    if (family != CodecFamily.UNKNOWN) edit(device) { it.copy(codec = family) }
                }
            }
            SAMPLE_RATE.find(line)?.let { m ->
                firstInt(m.groupValues)?.let { hz -> edit(device) { it.copy(sampleRateHz = hz) } }
            }
            BITS.find(line)?.let { m ->
                firstInt(m.groupValues)?.let { b -> edit(device) { it.copy(bitsPerSample = b) } }
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

    private fun firstInt(groups: List<String>): Int? =
        groups.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
}
