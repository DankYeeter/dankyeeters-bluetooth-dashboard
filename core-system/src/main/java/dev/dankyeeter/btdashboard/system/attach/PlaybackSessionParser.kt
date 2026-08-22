package dev.dankyeeter.btdashboard.system.attach

/**
 * Pulls the audio session ids of currently playing media out of `dumpsys audio`.
 *
 * ## Why this exists
 *
 * Attaching an equaliser to another app's audio needs nothing but that app's
 * session id — verified on Tidal, which never broadcasts
 * `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`: given the id from outside, the
 * effect attached and the music audibly dropped.
 *
 * So the broadcast was never the requirement, only the convenient path. An
 * ordinary app cannot see foreign session ids — `getActivePlaybackConfigurations`
 * anonymises them to `sessionId=0` — but the privileged helper reads the same
 * data the framework prints for itself.
 *
 * ## What is deliberately ignored
 *
 * Only `state:started` media playback counts. Paused players (Spotify sitting in
 * the background) would otherwise pull the EQ onto sessions nobody is listening
 * to, and notification blips and SoundPool effects — every one of which reports
 * `sessionId:0` anyway — are not music and must never be equalised.
 *
 * Kept as a pure function over text so it can be tested against real device
 * output instead of a hand-written approximation of it.
 */
object PlaybackSessionParser {

    /**
     * The one line shape that matters, as the framework prints it:
     *
     * ```
     * AudioPlaybackConfiguration piid:8047 ... state:started attr:AudioAttributes:
     *   usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC ... sessionId:8009 ...
     * ```
     */
    private val CONFIG_LINE = Regex("""AudioPlaybackConfiguration\b.*""")
    private val SESSION_ID = Regex("""\bsessionId:(\d+)""")

    /** Session ids of everything currently playing media. Never contains 0. */
    fun activeMediaSessions(dumpsysAudio: String): Set<Int> =
        dumpsysAudio.lineSequence()
            .filter { CONFIG_LINE.containsMatchIn(it) }
            .filter { it.contains("state:started") }
            .filter { it.contains("usage=USAGE_MEDIA") }
            .mapNotNull { line -> SESSION_ID.find(line)?.groupValues?.get(1)?.toIntOrNull() }
            // Session 0 is the output mix, not a player. Attaching there is the
            // global strategy's job, and over Bluetooth it is measurably silent.
            .filter { it > 0 }
            .toSet()
}
