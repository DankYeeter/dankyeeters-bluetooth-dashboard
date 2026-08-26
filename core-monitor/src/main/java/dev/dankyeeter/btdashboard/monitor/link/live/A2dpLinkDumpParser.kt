package dev.dankyeeter.btdashboard.monitor.link.live

import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily

/** Everything this parser gets out of one `dumpsys bluetooth_manager`. */
data class A2dpLinkDump(
    val device: LiveDeviceSnapshot? = null,
    val codec: LiveCodecSnapshot? = null,
    val tx: A2dpTxStats? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * Reads the live A2DP link out of `dumpsys bluetooth_manager`.
 *
 * A pure function over text, like every other parser here, and for the same
 * reason: `dumpsys` is a debugging surface with no compatibility promise, so
 * the only way to know it still works is a fixture captured from a real phone.
 *
 * ## What it takes and what it deliberately ignores
 *
 * Three things are read:
 *
 *  - the `A2dpStateMachine` block of the connected device, for the negotiated
 *    `mCodecConfig` (including `mCodecSpecific1`, which is LDAC's quality
 *    index) and for `mConnectionState` / `mIsPlaying`;
 *  - the adapter-wide `codecConfigOffloading` list, because it decides whether
 *    the counters below mean anything at all — a codec the controller encodes
 *    never passes through `btif_a2dp_source`, so its tx queue sits at zero and
 *    would read as a perfectly healthy link;
 *  - the `A2DP State:` block, which is `btif_a2dp_source`'s own media
 *    statistics dump.
 *
 * The last one is why the section boundaries below are strict rather than a
 * convenient grep. `Counts (underflow)` is printed by three different
 * subsystems in the same dump — A2DP, the Hearing Aid audio HAL and the LE
 * Audio HAL client — and the other two sit at zero on a phone with neither
 * connected. Scanning the whole dump for the label therefore finds a real
 * counter, a zero and a zero, in an order nothing guarantees.
 */
object A2dpLinkDumpParser {

    /** Real dumps redact MACs to `xx:xx:xx:xx:ab:cd`, so `x` is a valid digit. */
    private val MAC = Regex("(?:[0-9A-Fa-fxX]{2}:){5}[0-9A-Fa-fxX]{2}")

    private val STATE_MACHINE_HEADER = Regex("""A2dpStateMachine for\s+(\S+)""")
    private val CODEC_NAME = Regex("""codecName\s*[:=]\s*([A-Za-z0-9 _\-]+)""")
    private val CODEC_TYPE = Regex("""mCodecType\s*[:=]\s*(\d+)""")

    /** `mSampleRate:0x8(96000)`; the parenthesised value is the resolved one. */
    private val SAMPLE_RATE = Regex("""mSampleRate\s*[:=]\s*0x[0-9a-fA-F]+\((\d+)\)""")
    private val BITS = Regex("""mBitsPerSample\s*[:=]\s*0x[0-9a-fA-F]+\((\d+)\)""")
    private val CHANNEL_MODE = Regex("""mChannelMode\s*[:=]\s*0x([0-9a-fA-F]+)\(""")
    private val CODEC_SPECIFIC_1 = Regex("""mCodecSpecific1\s*[:=]\s*(-?\d+)""")

    fun parse(dump: String): A2dpLinkDump = try {
        parseInternal(dump)
    } catch (t: Throwable) {
        A2dpLinkDump(warnings = listOf("bluetooth_manager parse failed: ${t.javaClass.simpleName}"))
    }

    private fun parseInternal(dump: String): A2dpLinkDump {
        if (dump.isBlank()) return A2dpLinkDump(warnings = listOf("empty dump"))

        val warnings = mutableListOf<String>()
        val offloaded = offloadedCodecs(dump)
        val device = readStateMachine(dump)
        val tx = readTxStats(dump)

        if (device == null) warnings += "no A2dpStateMachine block in dump"
        if (tx == null) warnings += "no 'A2DP State:' section in dump"

        val codec = device?.codec?.copy(
            isOffloaded = device.codec.family in offloaded,
        )
        return A2dpLinkDump(
            device = device?.device,
            codec = codec,
            tx = tx,
            warnings = warnings,
        )
    }

    // ---- the connected device ----------------------------------------------

    private data class DeviceAndCodec(
        val device: LiveDeviceSnapshot,
        val codec: LiveCodecSnapshot?,
    )

    /**
     * The device the dump describes, preferring a connected one.
     *
     * Every *bonded* device keeps an `A2dpStateMachine` block carrying the
     * codec of its last session, so a headphone that has been off for a week
     * still prints `mCodecConfig: LDAC`. Picking the first block found would
     * therefore show a live-looking link for a device that is not there. A
     * connected block wins; if none is connected the first block is returned
     * with `isConnected = false`, which the UI shows as "last session" rather
     * than as now.
     */
    private fun readStateMachine(dump: String): DeviceAndCodec? {
        val activeAddress = activeAddress(dump)
        val found = mutableListOf<DeviceAndCodec>()

        var address: String? = null
        var connected = false
        var playing = false
        var codec: LiveCodecSnapshot? = null

        fun flush() {
            val addr = address ?: return
            found += DeviceAndCodec(
                device = LiveDeviceSnapshot(
                    address = addr,
                    isConnected = connected,
                    isActive = activeAddress != null && sameAddress(activeAddress, addr),
                    isPlaying = playing,
                ),
                codec = codec,
            )
            address = null
            connected = false
            playing = false
            codec = null
        }

        for (raw in dump.lineSequence()) {
            val line = raw.trimEnd()
            STATE_MACHINE_HEADER.find(line)?.let { m ->
                flush()
                address = MAC.find(m.groupValues[1])?.value ?: MAC.find(line)?.value
            }
            if (address == null) continue

            val body = line.trimStart()
            if (line.contains("mConnectionState")) {
                // "mConnectionState: STATE_DISCONNECTED, mLastConnectionState: ..."
                // — only the first field is the current one, and the second one
                // contains the word STATE_CONNECTING often enough that a
                // whole-line contains() check gets it backwards.
                val current = line.substringAfter("mConnectionState")
                    .substringAfter(':')
                    .substringBefore(',')
                connected = current.contains("STATE_CONNECTED")
            } else if (body.startsWith("mCurrentState")) {
                // Older dumps print the state machine's own state instead.
                // "Disconnected" does not contain "Connected" with this casing,
                // and "Connecting" is not "Connected", so plain contains works.
                connected = body.contains("Connected")
            }
            if (body.startsWith("mIsPlaying")) {
                playing = line.contains("true", ignoreCase = true)
            }
            // `codecConfig:` without the m-prefix is the older spelling. The
            // colon is required: `codecConfigOffloading` and
            // `codecConfigPriorities` are adapter-wide capability lists that
            // start with the same eleven characters and describe no link at all.
            if (body.startsWith("mCodecConfig") || body.startsWith("codecConfig:")) {
                codec = readCodec(line)
            }
        }
        flush()

        return found.firstOrNull { it.device.isConnected } ?: found.firstOrNull()
    }

    /** `active_a2dp_devices: [xx:xx:xx:xx:ab:cd]` or `mActiveDevice: <mac>`. */
    private fun activeAddress(dump: String): String? = dump.lineSequence()
        .filter { it.contains("active_a2dp_devices") || it.contains("mActiveDevice") }
        .mapNotNull { MAC.find(it)?.value }
        .firstOrNull()

    /**
     * A user-build dump redacts the first four octets, so two spellings of the
     * same headphone only agree on the tail. Same rule the codec sources use.
     */
    private fun sameAddress(a: String, b: String): Boolean =
        a.equals(b, ignoreCase = true) || a.takeLast(5).equals(b.takeLast(5), ignoreCase = true)

    private fun readCodec(line: String): LiveCodecSnapshot? {
        val rawName = CODEC_NAME.find(line)?.groupValues?.getOrNull(1)?.trim()
        val family = rawName
            ?.let(CodecDecoding::codecFamilyFromName)
            ?.takeIf { it != CodecFamily.UNKNOWN }
            ?: CODEC_TYPE.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.let(CodecDecoding::codecFamily)
            ?: return null
        return LiveCodecSnapshot(
            family = family,
            // Kept verbatim: the numeric type is ambiguous on this hardware
            // (LHDCv5 is type 7, which decodes to aptX Adaptive) and the name
            // is not. See LiveCodecSnapshot.rawCodecName.
            rawCodecName = rawName,
            sampleRateHz = SAMPLE_RATE.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull(),
            bitsPerSample = BITS.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull(),
            channelMode = CHANNEL_MODE.find(line)?.groupValues?.getOrNull(1)
                ?.toIntOrNull(16)
                ?.let(CodecDecoding::channelMode)
                ?: ChannelMode.UNKNOWN,
            codecSpecific1 = CODEC_SPECIFIC_1.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull(),
        )
    }

    /**
     * The codecs this controller encodes itself.
     *
     * The list is the adapter's `codecConfigOffloading` block, which is exactly
     * the set the hardware will take. On the Pixel 11 Pro it holds SBC, AAC and
     * Opus — LDAC is absent, which is what makes the tx-queue counters usable
     * on an LDAC link and useless on an AAC one.
     */
    private fun offloadedCodecs(dump: String): Set<CodecFamily> {
        val result = mutableSetOf<CodecFamily>()
        var inBlock = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("codecConfigOffloading")) {
                inBlock = true
                continue
            }
            if (!inBlock) continue
            if (!line.startsWith("{")) {
                inBlock = false
                continue
            }
            CODEC_NAME.find(line)?.groupValues?.getOrNull(1)
                ?.let(CodecDecoding::codecFamilyFromName)
                ?.takeIf { it != CodecFamily.UNKNOWN }
                ?.let(result::add)
        }
        return result
    }

    // ---- btif_a2dp_source media statistics -----------------------------------

    /**
     * The `A2DP State:` block, and nothing that merely looks like it.
     *
     * The block is top-level (column 0) and its body is indented, so it ends at
     * the next unindented non-blank line. See the class KDoc for why this is
     * not a grep.
     */
    private fun readTxStats(dump: String): A2dpTxStats? {
        val values = mutableMapOf<String, List<Long>>()
        var inBlock = false
        var sawBlock = false

        for (raw in dump.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val unindented = line.first() != ' ' && line.first() != '\t'
            if (unindented) {
                inBlock = line.trim() == "A2DP State:"
                if (inBlock) sawBlock = true
                continue
            }
            if (!inBlock) continue

            val label = line.substringBefore(':').trim()
            if (label == line.trim()) continue // "TxQueue:" style header, no values
            val numbers = line.substringAfter(':')
                .split('/')
                .mapNotNull { it.trim().toLongOrNull() }
            if (numbers.isNotEmpty()) values[label] = numbers
        }
        if (!sawBlock) return null

        fun at(label: String, index: Int): Long? = values[label]?.getOrNull(index)

        return A2dpTxStats(
            enqueueCount = at(COUNTS, 0),
            dequeueCount = at(COUNTS, 1),
            readBufCount = at(COUNTS, 2),
            framesPerPacketTotal = at(FRAMES, 0),
            framesPerPacketMax = at(FRAMES, 1)?.toInt(),
            framesPerPacketAvg = at(FRAMES, 2)?.toInt(),
            flushedCount = at(LOSS, 0),
            droppedCount = at(LOSS, 1),
            dropoutCount = at(LOSS, 2),
            maxDroppedCount = at(MAX_DROPPED, 0),
            underflowCount = at(UNDERFLOW_COUNT, 0),
            underflowBytes = at(UNDERFLOW_BYTES, 0),
            enqueueOverdue = at(ENQUEUE_DEVIATION, 0),
            enqueuePremature = at(ENQUEUE_DEVIATION, 1),
            dequeueOverdue = at(DEQUEUE_DEVIATION, 0),
            dequeuePremature = at(DEQUEUE_DEVIATION, 1),
        ).takeUnless { it.isEmpty }
    }

    // Labels exactly as `btif_a2dp_source_debug_dump` prints them. Kept as
    // constants because a silent rename upstream should fail one obvious test,
    // not quietly zero out the loss counters.
    private const val COUNTS = "Counts (enqueue/dequeue/readbuf)"
    private const val FRAMES = "Frames per packet (total/max/ave)"
    private const val LOSS = "Counts (flushed/dropped/dropouts)"
    private const val MAX_DROPPED = "Counts (max dropped)"
    private const val UNDERFLOW_COUNT = "Counts (underflow)"
    private const val UNDERFLOW_BYTES = "Bytes (underflow)"
    private const val ENQUEUE_DEVIATION = "Enqueue deviation counts (overdue/premature)"
    private const val DEQUEUE_DEVIATION = "Dequeue deviation counts (overdue/premature)"
}
