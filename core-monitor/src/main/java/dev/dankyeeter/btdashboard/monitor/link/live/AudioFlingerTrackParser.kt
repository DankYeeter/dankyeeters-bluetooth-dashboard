package dev.dankyeeter.btdashboard.monitor.link.live

/** One row of an AudioFlinger output thread's track table. */
data class MixerTrack(
    val pid: Int,
    val uid: Int,
    val sessionId: Int?,
    val portId: Int?,
    val sampleRateHz: Int?,
    val format: PcmFormat?,
    val channelMask: Int?,
    val underruns: Long?,
    val flushed: Long?,
)

/** One output thread plus the tracks currently mixed into it. */
data class MixerThreadDump(
    val output: MixerOutputSnapshot,
    val tracks: List<MixerTrack> = emptyList(),
)

data class AudioFlingerDump(
    val threads: List<MixerThreadDump> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    /**
     * The thread the Bluetooth route is on, if one is up.
     *
     * A phone has half a dozen output threads and only one of them is feeding
     * the headphone; the rest are the speaker, the haptics and several that
     * have never been used since boot. Reading underruns off the wrong one is
     * how a monitor ends up permanently reporting zero.
     */
    val bluetoothThread: MixerThreadDump?
        get() = threads.firstOrNull { it.output.isBluetoothRoute && !it.output.isInStandby }
            ?: threads.firstOrNull { it.output.isBluetoothRoute }
}

/**
 * Reads output threads and their per-track counters from
 * `dumpsys media.audio_flinger`.
 *
 * ## Why the row regexes anchor on content, not on columns
 *
 * The track table is a fixed-width layout with twenty-odd columns, and slicing
 * it by position is the obvious approach and the wrong one. Two of the columns
 * contain spaces of their own — `Client(pid/uid)` prints as `9137/  10360` and
 * the latency column as `189.01 k` — so a naive whitespace split silently
 * shifts every field after them. Positions also move between builds, and
 * AudioFlinger prints the *same* row format in two places with different
 * leading text: the live table, and the thread's own event log where each row
 * is prefixed by a timestamp and `AT::add (0x…)`.
 *
 * Anchoring on two unmistakable shapes instead — the `pid/uid` pair near the
 * front, and the `…false 000006FA 22050 0 f 0 0 false false` tail — survives
 * all of that, and is why the same function can be tested against rows captured
 * from either place.
 */
object AudioFlingerTrackParser {

    private val THREAD_HEADER =
        Regex("""^Output thread\s+\S+,\s*name\s+(\S+?),""")
    private val SAMPLE_RATE = Regex("""^Sample rate:\s*(\d+)""")
    private val CHANNEL_COUNT = Regex("""^Channel count:\s*(\d+)""")
    private val HAL_FORMAT = Regex("""^HAL format:\s*0x([0-9a-fA-F]+)""")
    private val OUTPUT_DEVICES = Regex("""^Output devices:\s*0x([0-9a-fA-F]+)\s*\(([^)]*)\)""")
    private val STANDBY = Regex("""^Standby:\s*(yes|no)""")
    private val FAST_MIXER_UNDERRUNS = Regex("""\bunderruns=(\d+)""")
    private val NORMAL_UNDERRUNS =
        Regex("""Normal mixer raw underrun counters:\s*partial=(\d+)\s+empty=(\d+)""")
    private val TRACKS_HEADER = Regex("""^(\d+)\s+Tracks\b""")
    private val EFFECT_CHAINS = Regex("""^\d+\s+Effect Chains\b""")

    /**
     * Where a thread block genuinely ends.
     *
     * Named explicitly rather than "the next line at column 0", because a
     * thread block is not consistently indented: AudioFlinger prints
     * `Bluetooth latency modes are enabled` and two siblings unindented in the
     * *middle* of every playback thread, right before the underrun counters and
     * the track table. Ending the block there dropped exactly the two things
     * this parser exists for.
     */
    private val BLOCK_END = Regex(
        """^(Input thread|Historical Thread Log|Patches:|Device Effects:|Sound Dose:|Power )""",
    )

    /**
     * `9137/  10360    2473    1240 T  0x600 00000001 00000003  48000`
     * — pid, uid, session, port id, state, flags, format, channel mask, rate.
     */
    private val ROW_HEAD = Regex(
        """(\d+)/\s*(\d+)\s+(\d+)\s+(\d+)\s+\S+\s+0x[0-9A-Fa-f]+\s+""" +
            """([0-9A-Fa-f]{8})\s+([0-9A-Fa-f]{8})\s+(\d+)""",
    )

    /**
     * `false 00009600  12000       0 f         0    12000      false        false`
     * — PortMuted, Server, FrmCnt, FrmRdy, F, Underruns, Flushed, BitPerfect,
     * InternalMute. The two trailing booleans are what make this unambiguous.
     */
    private val ROW_TAIL = Regex(
        """(?:true|false)\s+[0-9A-Fa-f]{8}\s+\d+\s+\d+\s+\S\s+(\d+)\s+(\d+)\s+""" +
            """(?:true|false)\s+(?:true|false)""",
    )

    fun parse(dump: String): AudioFlingerDump = try {
        parseInternal(dump)
    } catch (t: Throwable) {
        AudioFlingerDump(warnings = listOf("audio_flinger parse failed: ${t.javaClass.simpleName}"))
    }

    private fun parseInternal(dump: String): AudioFlingerDump {
        if (dump.isBlank()) return AudioFlingerDump(warnings = listOf("empty dump"))

        val threads = mutableListOf<MixerThreadDump>()
        var builder: ThreadBuilder? = null
        var inTrackTable = false

        fun flush() {
            builder?.let { threads += it.build() }
            builder = null
            inTrackTable = false
        }

        for (raw in dump.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val body = line.trimStart()

            THREAD_HEADER.find(line)?.let { m ->
                flush()
                builder = ThreadBuilder(m.groupValues[1])
            }
            if (BLOCK_END.containsMatchIn(line)) {
                flush()
                continue
            }
            val current = builder ?: continue

            if (TRACKS_HEADER.containsMatchIn(body)) {
                inTrackTable = true
                continue
            }
            if (EFFECT_CHAINS.containsMatchIn(body)) {
                // The thread's `Local log:` follows, and it replays historic
                // rows in the identical format. They are past events, not the
                // current mix, so the table is closed here.
                inTrackTable = false
                continue
            }
            if (inTrackTable) {
                parseTrackRow(line)?.let(current.tracks::add)
                continue
            }

            current.absorb(body)
        }
        flush()

        val warnings = if (threads.isEmpty()) listOf("no output threads found in dump") else emptyList()
        return AudioFlingerDump(threads, warnings)
    }

    /**
     * One track row, from either the live table or a thread's event log.
     *
     * Public because it is the piece worth pinning to a fixture on its own:
     * every field the loss view shows comes out of this one line.
     */
    fun parseTrackRow(line: String): MixerTrack? {
        val head = ROW_HEAD.find(line) ?: return null
        val tail = ROW_TAIL.find(line, startIndex = head.range.last)
        return MixerTrack(
            pid = head.groupValues[1].toIntOrNull() ?: return null,
            uid = head.groupValues[2].toIntOrNull() ?: return null,
            sessionId = head.groupValues[3].toIntOrNull(),
            portId = head.groupValues[4].toIntOrNull(),
            format = PcmFormat.of(head.groupValues[5].toIntOrNull(16)),
            channelMask = head.groupValues[6].toIntOrNull(16),
            sampleRateHz = head.groupValues[7].toIntOrNull(),
            underruns = tail?.groupValues?.getOrNull(1)?.toLongOrNull(),
            flushed = tail?.groupValues?.getOrNull(2)?.toLongOrNull(),
        )
    }

    private class ThreadBuilder(val name: String) {
        val tracks = mutableListOf<MixerTrack>()
        var sampleRateHz: Int? = null
        var channelCount: Int? = null
        var halFormat: PcmFormat? = null
        var deviceMask: Int? = null
        var deviceNames: String? = null
        var standby = false
        var fastUnderruns: Long? = null
        var partialUnderruns: Long? = null
        var emptyUnderruns: Long? = null

        /**
         * First value wins for every field.
         *
         * The thread's own header is printed before the HAL's YAML block, and
         * that block repeats several of the same key names for the HAL stream
         * rather than the thread. Taking the first keeps the thread's answer.
         */
        fun absorb(body: String) {
            SAMPLE_RATE.find(body)?.let { m ->
                if (sampleRateHz == null) sampleRateHz = m.groupValues[1].toIntOrNull()
            }
            CHANNEL_COUNT.find(body)?.let { m ->
                if (channelCount == null) channelCount = m.groupValues[1].toIntOrNull()
            }
            HAL_FORMAT.find(body)?.let { m ->
                if (halFormat == null) halFormat = PcmFormat.of(m.groupValues[1].toIntOrNull(16))
            }
            OUTPUT_DEVICES.find(body)?.let { m ->
                if (deviceMask == null) {
                    deviceMask = m.groupValues[1].toIntOrNull(16)
                    deviceNames = m.groupValues[2]
                }
            }
            STANDBY.find(body)?.let { m -> standby = m.groupValues[1] == "yes" }
            if (body.startsWith("numTracks=") || body.contains("writeErrors=")) {
                FAST_MIXER_UNDERRUNS.find(body)?.let { m ->
                    fastUnderruns = m.groupValues[1].toLongOrNull()
                }
            }
            NORMAL_UNDERRUNS.find(body)?.let { m ->
                partialUnderruns = m.groupValues[1].toLongOrNull()
                emptyUnderruns = m.groupValues[2].toLongOrNull()
            }
        }

        fun build() = MixerThreadDump(
            output = MixerOutputSnapshot(
                threadName = name,
                outputDeviceMask = deviceMask,
                outputDeviceNames = deviceNames,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                halFormat = halFormat,
                isInStandby = standby,
                fastMixerUnderruns = fastUnderruns,
                normalMixerPartialUnderruns = partialUnderruns,
                normalMixerEmptyUnderruns = emptyUnderruns,
            ),
            tracks = tracks.toList(),
        )
    }
}
