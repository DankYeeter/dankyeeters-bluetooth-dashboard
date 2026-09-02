package dev.dankyeeter.btdashboard.monitor.link.live

import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily

/** Everything this parser gets out of one `dumpsys bluetooth_manager`. */
data class A2dpLinkDump(
    val device: LiveDeviceSnapshot? = null,
    val codec: LiveCodecSnapshot? = null,
    val tx: A2dpTxStats? = null,
    /**
     * The `A2DP LDAC State:` block, when this build prints one **and** LDAC is
     * the codec actually configured. Null is the honest "not reported here".
     */
    val ldacStack: LdacStackState? = null,
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
 * Four things are read:
 *
 *  - the `A2dpStateMachine` block of the connected device, for the negotiated
 *    `mCodecConfig` (including `mCodecSpecific1`, which is LDAC's quality
 *    index) and for `mConnectionState` / `mIsPlaying`;
 *  - the adapter-wide `codecConfigOffloading` list, because it decides whether
 *    the counters below mean anything at all — a codec the controller encodes
 *    never passes through `btif_a2dp_source`, so its tx queue sits at zero and
 *    would read as a perfectly healthy link;
 *  - the `A2DP State:` block, which is `btif_a2dp_source`'s own media
 *    statistics dump;
 *  - the `A2DP LDAC State:` block, which is the one place on this phone that
 *    prints the LDAC encoder's **live** bitrate. See [LdacStackState].
 *
 * The last two are why the section boundaries below are strict rather than a
 * convenient grep, and they fail in opposite directions. `Counts (underflow)`
 * is printed by three different subsystems in the same dump — A2DP, the Hearing
 * Aid audio HAL and the LE Audio HAL client — and the other two sit at zero on
 * a phone with neither connected, so a whole-dump scan finds a real counter, a
 * zero and a zero in an order nothing guarantees. `Effective MTU:` is worse:
 * **every** codec prints one, and the six codecs that are not negotiated all
 * print `0`.
 *
 * ## No new regexes here
 *
 * The two section readers below are plain string work on purpose. Android's ICU
 * regex engine rejects lax JVM-isms — a bare `}` outside a character class
 * throws `PatternSyntaxException` — while the JVM the unit tests run on accepts
 * them, so a regex can pass every test and then kill this object's static init
 * on the phone. That happened once already; see the note on `NEGOTIATED_CONFIG`
 * in `CodecDecoding`. Label matching needs no regex, so it uses none.
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
        val ldacStack = readLdacStackState(dump)

        if (device == null) warnings += "no A2dpStateMachine block in dump"
        if (tx == null) warnings += "no 'A2DP State:' section in dump"

        val codec = device?.codec?.copy(
            isOffloaded = device.codec.family in offloaded,
        )
        // Only attach the LDAC block to an LDAC link. Every codec keeps a state
        // block whether or not it is the negotiated one, and a stale LDAC block
        // beside an AAC link would be a bitrate for a codec that is not running.
        val ldacForThisLink = ldacStack?.takeIf { codec?.family == CodecFamily.LDAC }
        if (codec?.family == CodecFamily.LDAC && ldacForThisLink == null) {
            warnings += "no 'A2DP LDAC State:' section in dump — this build does not " +
                "report LDAC's live bitrate"
        }
        return A2dpLinkDump(
            device = device?.device,
            codec = codec,
            tx = tx,
            ldacStack = ldacForThisLink,
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
     *
     * ## Why a block ends at column 0
     *
     * It used to end only at the next `A2dpStateMachine` header, i.e. at the end
     * of the dump when there was one device. That reached 850 lines past the
     * A2DP profile and into `Profile: HeadsetService`, whose own state machine
     * prints `mConnectionState: 2` — HFP's numeric spelling, which contains no
     * `STATE_CONNECTED` — and quietly turned a live LDAC link into "last
     * session". Every top-level `Profile:` header sits at column 0, so the block
     * ends there, the same boundary rule [readTxStats] uses.
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
            // The block's own header is indented, so an unindented non-blank
            // line is always the start of the next top-level section and never
            // part of this device. See the KDoc for what reading past it did.
            if (line.isNotBlank() && line.first() != ' ' && line.first() != '\t') {
                flush()
                continue
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
        val rawType = CODEC_TYPE.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val family = CodecDecoding.codecFamily(rawName, rawType)
            .takeIf { it != CodecFamily.UNKNOWN }
            ?: return null
        return LiveCodecSnapshot(
            family = family,
            // Both raw values are kept even though the family is already
            // decided by them: the name is what the mode-signature registry
            // matches on (it knows codecs CodecFamily has no entry for), and
            // the type is what labels a link the name could not identify.
            rawCodecName = rawName,
            rawCodecType = rawType,
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

    // ---- the LDAC encoder's own state ---------------------------------------

    /**
     * The `A2DP LDAC State:` block — the live bitrate, straight from the stack.
     *
     * Scoped exactly like [readTxStats] and for a sharper version of the same
     * reason: this dump prints one `A2DP <codec> State:` block per codec the
     * phone can do, so `Effective MTU:` appears seven times and six of them are
     * `0`. Reading the labels anywhere but inside this one block would pick up
     * whichever unnegotiated codec happened to sort first.
     *
     * A block whose `Config:` line says `Invalid` is the stack's own way of
     * saying "this codec is not the one running", and it is rejected here rather
     * than passed on as a bitrate of zero.
     *
     * ## Six rows, no verdicts
     *
     * The two `LDAC adaptive bit rate` rows are read alongside the rate because
     * the rate alone cannot say what happened between two polls; see
     * [LdacStackState.adaptiveBitrateAdjustments]. Like every other row they are
     * carried as read and nothing here decides what they mean — in particular a
     * reading of 990 kbps is a reading and not an event. The adaptive controller
     * tries that rung on its own and leaves it again within a single sample: 31
     * times in one 39-minute run, 30 of them for exactly one sample and none of
     * them with any loss (`docs/perf/T-011-messung.md`). A parser that treated
     * each such reading as something worth reporting would have raised 31 false
     * alarms in that run.
     */
    private fun readLdacStackState(dump: String): LdacStackState? {
        var inBlock = false
        var sawBlock = false
        var invalidConfig = false
        var qualityMode: String? = null
        var kbps: Int? = null
        var mtu: Int? = null
        var savedQueue: Int? = null
        var abrIndex: Int? = null
        var abrAdjustments: Long? = null

        for (raw in dump.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val unindented = line.first() != ' ' && line.first() != '\t'
            if (unindented) {
                inBlock = line.trim() == LDAC_STATE_HEADER
                if (inBlock) sawBlock = true
                continue
            }
            if (!inBlock) continue

            val body = line.trim()
            val label = body.substringBefore(':').trim()
            if (label == body) continue // a bare header line, no value
            val value = body.substringAfter(':').trim()
            when (label) {
                LDAC_CONFIG -> invalidConfig = value.equals("Invalid", ignoreCase = true)
                LDAC_QUALITY_MODE -> qualityMode = value.takeIf { it.isNotEmpty() }
                LDAC_BITRATE -> kbps = value.toIntOrNull()
                LDAC_EFFECTIVE_MTU -> mtu = value.toIntOrNull()
                LDAC_SAVED_QUEUE -> savedQueue = value.toIntOrNull()
                LDAC_ABR_INDEX -> abrIndex = value.toIntOrNull()
                LDAC_ABR_ADJUSTMENTS -> abrAdjustments = value.toLongOrNull()
            }
        }
        if (!sawBlock || invalidConfig) return null
        return LdacStackState(
            qualityMode = qualityMode,
            // A bitrate of zero is what a block prints when nothing is flowing.
            // Carrying it would draw a link that stopped; absent is the truth.
            transmissionKbps = kbps?.takeIf { it > 0 },
            effectiveMtu = mtu?.takeIf { it > 0 },
            savedTxQueueLength = savedQueue,
            // Both pass through exactly as printed, zero included: a rung of 0
            // and a count of 0 are answers, and the row not being there is the
            // only thing that reads as null.
            adaptiveBitrateIndex = abrIndex,
            adaptiveBitrateAdjustments = abrAdjustments,
        ).takeUnless { it.isEmpty }
    }

    // Labels exactly as the stack prints them, same rule as the tx block below:
    // a silent rename upstream should fail one obvious test rather than quietly
    // turn the live bitrate into "not reported".
    private const val LDAC_STATE_HEADER = "A2DP LDAC State:"
    private const val LDAC_CONFIG = "Config"
    private const val LDAC_QUALITY_MODE = "LDAC quality mode"
    private const val LDAC_BITRATE = "LDAC transmission bitrate (Kbps)"
    private const val LDAC_EFFECTIVE_MTU = "Effective MTU"
    private const val LDAC_SAVED_QUEUE = "LDAC saved transmit queue length"
    private const val LDAC_ABR_INDEX = "LDAC adaptive bit rate encode quality mode index"
    private const val LDAC_ABR_ADJUSTMENTS = "LDAC adaptive bit rate adjustments"

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
