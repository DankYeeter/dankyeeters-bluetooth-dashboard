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

    /**
     * The uid of the app behind a line, printed as `u/pid:10290/2154`.
     *
     * Carried alongside the session id so the app can say *who* is being
     * equalised rather than how many anonymous sessions there are. A number is
     * nothing a user can check against what they are hearing; a name is.
     */
    private val UID = Regex("""\bu/pid:(\d+)/\d+""")

    /** One playing media session, and the app it belongs to. */
    data class PlayingSession(val sessionId: Int, val uid: Int)

    /** Everything currently playing media, with its owner. Never session 0. */
    fun activeMediaPlayers(dumpsysAudio: String): Set<PlayingSession> =
        dumpsysAudio.lineSequence()
            .filter { CONFIG_LINE.containsMatchIn(it) }
            .filter { it.contains("state:started") }
            .filter { it.contains("usage=USAGE_MEDIA") }
            .mapNotNull { line ->
                // Session 0 is the output mix, not a player. Attaching there is
                // the global strategy's job, and over Bluetooth it is
                // measurably silent.
                val session = SESSION_ID.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: return@mapNotNull null
                PlayingSession(session, UID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1)
            }
            .toSet()

    /** Session ids of everything currently playing media. Never contains 0. */
    fun activeMediaSessions(dumpsysAudio: String): Set<Int> =
        activeMediaPlayers(dumpsysAudio).map { it.sessionId }.toSet()
}
