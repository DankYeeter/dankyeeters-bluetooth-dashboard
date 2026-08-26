package dev.dankyeeter.btdashboard.monitor.link.live

/**
 * One `AudioPlaybackConfiguration` line, reduced to the fields the live view
 * needs. Everything here is MEASURED — verbatim from the framework's own dump.
 */
data class PlayingStream(
    val uid: Int,
    val pid: Int,
    val sessionId: Int?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val isSpatialized: Boolean,
    val usage: String?,
    val contentType: String?,
)

/**
 * Pulls what each app is *feeding in* out of `dumpsys audio`.
 *
 * ## Why not reuse `PlaybackSessionParser`
 *
 * `:core-system`'s parser answers a different question — "which session id may
 * the equaliser attach to" — and is deliberately narrow about it: media only,
 * started only, session 0 discarded. This one is the top of the monitor's
 * signal chain, so it wants the format as well, and it must keep the entries
 * that the other one is right to throw away. An app playing at 44.1 kHz into a
 * 96 kHz link is the exact thing the user is trying to see, and so is a
 * notification that interrupts music, and neither survives the attach filter.
 *
 * The two therefore stay separate rather than one growing a flag: they are
 * asked different questions by different screens, and merging them means the
 * next change to one silently changes the other.
 *
 * `FormatInfo{...}` is where the interesting part lives. It is a recent
 * addition to the framework dump, so every field it carries is optional here —
 * a build that does not print it must degrade to "unknown rate", not to no
 * players at all.
 */
object PlayingStreamParser {

    private val CONFIG_LINE = Regex("""AudioPlaybackConfiguration\b""")

    /** `u/pid:10400/13838` — uid first, then pid. Not the other way round. */
    private val UID_PID = Regex("""\bu/pid:(\d+)/(\d+)""")
    private val SESSION_ID = Regex("""\bsessionId:(\d+)""")
    private val USAGE = Regex("""\busage=(\w+)""")
    private val CONTENT = Regex("""\bcontent=(\w+)""")
    private val SPATIALIZED = Regex("""isSpatialized=(true|false)""")
    private val CHANNEL_MASK = Regex("""channelMask=0x([0-9a-fA-F]+)""")
    private val SAMPLE_RATE = Regex("""sampleRate=(\d+)""")

    /**
     * Every app whose track is `state:started` right now.
     *
     * Paused and idle entries are dropped because they are not in the signal
     * chain; a paused Spotify sitting behind Tidal would otherwise show up as a
     * second input and make the mixer look busier than it is.
     */
    fun playingStreams(dumpsysAudio: String): List<PlayingStream> = try {
        dumpsysAudio.lineSequence()
            .filter { CONFIG_LINE.containsMatchIn(it) }
            .filter { it.contains("state:started") }
            .mapNotNull(::parseLine)
            .toList()
    } catch (t: Throwable) {
        emptyList()
    }

    private fun parseLine(line: String): PlayingStream? {
        val ids = UID_PID.find(line) ?: return null
        return PlayingStream(
            uid = ids.groupValues[1].toIntOrNull() ?: return null,
            pid = ids.groupValues[2].toIntOrNull() ?: return null,
            sessionId = SESSION_ID.find(line)?.groupValues?.get(1)?.toIntOrNull(),
            // A zero here means "the framework never printed a real rate", not
            // "this app plays at 0 Hz" — SoundPool entries all report 0.
            sampleRateHz = SAMPLE_RATE.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it > 0 },
            channelCount = CHANNEL_MASK.find(line)?.groupValues?.get(1)
                ?.toIntOrNull(16)
                ?.takeIf { it != 0 }
                ?.countOneBits(),
            isSpatialized = SPATIALIZED.find(line)?.groupValues?.get(1) == "true",
            usage = USAGE.find(line)?.groupValues?.get(1),
            contentType = CONTENT.find(line)?.groupValues?.get(1),
        )
    }
}
