package dev.dankyeeter.btdashboard.system.attach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsed against **real** device output, not an invented sample.
 *
 * `dumpsys` is a debugging surface with no compatibility promise: the day its
 * wording shifts, this parser silently returns nothing and the EQ silently
 * stops reaching Tidal. A fixture captured from the actual phone is what makes
 * that a red test rather than a mystery.
 */
class PlaybackSessionParserTest {

    private val realDump = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("dumpsys_audio_players.txt"),
    ) { "fixture missing" }.bufferedReader().readText()

    @Test
    fun `finds the session of the app that is actually playing`() {
        // Captured while Tidal was playing (uid 10400, session 8009) and Spotify
        // sat paused in the background (session 8137).
        assertEquals(setOf(8009), PlaybackSessionParser.activeMediaSessions(realDump))
    }

    @Test
    fun `ignores a paused player`() {
        assertTrue(
            "a paused player must not be equalised - nobody is listening to it",
            8137 !in PlaybackSessionParser.activeMediaSessions(realDump),
        )
    }

    @Test
    fun `ignores notification sounds and other non-music audio`() {
        // The fixture is full of SoundPool entries with USAGE_NOTIFICATION and
        // USAGE_ASSISTANCE_SONIFICATION. Equalising a notification chime with a
        // hearing-loss curve would be absurd, and they all report sessionId:0
        // anyway - which is the output mix, a different strategy's job entirely.
        assertTrue(0 !in PlaybackSessionParser.activeMediaSessions(realDump))
    }

    @Test
    fun `returns nothing when the output cannot be read`() {
        assertEquals(emptySet<Int>(), PlaybackSessionParser.activeMediaSessions(""))
        assertEquals(emptySet<Int>(), PlaybackSessionParser.activeMediaSessions("permission denied"))
    }

    @Test
    fun `picks up several players at once`() {
        val two = realDump + "\n" +
            "  AudioPlaybackConfiguration piid:9001 type:android.media.AudioTrack " +
            "u/pid:10500/999 state:started attr:AudioAttributes: usage=USAGE_MEDIA " +
            "content=CONTENT_TYPE_MUSIC tags= bundle=null sessionId:9099 mutedState:none"
        assertEquals(setOf(8009, 9099), PlaybackSessionParser.activeMediaSessions(two))
    }

    /**
     * The uid is what lets the app say "Tidal" instead of a session number.
     *
     * Pinned to the same real capture: `u/pid:10400/13838` sits on the very
     * line the session id comes from, so if the framework ever moves it, this
     * fails here rather than turning the EQ screen into a sentence with a hole
     * in it.
     */
    @Test
    fun `carries the uid of the app that is playing`() {
        assertEquals(
            setOf(PlaybackSessionParser.PlayingSession(sessionId = 8009, uid = 10400)),
            PlaybackSessionParser.activeMediaPlayers(realDump),
        )
    }
}
