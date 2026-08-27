package dev.dankyeeter.btdashboard.system.attach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * [PlaybackSessionParser] against every dump fixture in the repository, whole
 * and truncated.
 *
 * ## Why the sweep reaches outside this module
 *
 * The parser decides which audio session the equaliser attaches to. It is fed
 * whatever the privileged helper managed to read out of `dumpsys audio`, which
 * is a pipe that can end anywhere, and the failure it must never have is a
 * *wrong* session id: attaching an effect to somebody else's stream is audible
 * and looks like a bug in the other app.
 *
 * `:core-monitor` holds the only corpus of real dumps in this repository, so the
 * sweep reads it from disk rather than settling for the one copy that lives
 * here. Most of those files are `bluetooth_manager` output and contain no
 * playback configurations at all — which is the point: for those, the honest
 * answer is the empty set, and a parser that manufactured a session id out of
 * an unrelated dump would be caught here and nowhere else.
 *
 * Cut points are fixed fractions plus a deterministic mid-line cut, so a failure
 * names one reproducible prefix instead of a seed.
 */
@RunWith(Parameterized::class)
class PlaybackSessionSweepTest(private val name: String, private val fixture: File) {

    companion object {

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> {
            val files = File(repoRoot(), "core-monitor/src/test/resources/dumps")
                .listFiles().orEmpty().filter { it.isFile } +
                File(repoRoot(), "core-system/src/test/resources/dumpsys_audio_players.txt")
            require(files.size > 1) { "dump corpus not found under ${repoRoot()}" }
            return files.sortedBy { it.name }.map { arrayOf(it.name, it) }
        }

        /**
         * The checkout, found by climbing to the build's own settings file.
         *
         * Gradle's working directory for an Android unit test is the module
         * directory, but that is a default rather than a promise, so the class
         * output directory is the fallback.
         */
        private fun repoRoot(): File {
            fun climb(start: File): File? = generateSequence(start) { it.parentFile }
                .take(12)
                .firstOrNull {
                    File(it, "settings.gradle.kts").isFile && File(it, "core-monitor").isDirectory
                }

            val fromWorkingDir = climb(File(System.getProperty("user.dir") ?: ".").absoluteFile)
            val fromClasses = runCatching {
                climb(
                    File(
                        PlaybackSessionSweepTest::class.java.protectionDomain!!.codeSource
                            .location.toURI(),
                    ),
                )
            }.getOrNull()
            return requireNotNull(fromWorkingDir ?: fromClasses) {
                "repository root not found from ${System.getProperty("user.dir")}"
            }
        }
    }

    private val text: String by lazy { fixture.readText() }

    @Test
    fun `whole and truncated, it only ever reports real playing sessions`() {
        cutPoints().forEach { cut ->
            assertInvariants("$name cut at $cut/${text.length}", text.take(cut))
        }
        assertInvariants(name, text)
    }

    /**
     * Everything that is true of the result for any input.
     *
     * The last check is the one that matters: the session count is re-derived
     * from the text by the parser's own three stated conditions, so a parser
     * that started matching a fourth kind of line — a paused player, an alarm,
     * a notification — is caught even on a fixture nobody wrote expectations
     * for.
     */
    private fun assertInvariants(label: String, dump: String) {
        val players = PlaybackSessionParser.activeMediaPlayers(dump)
        val sessions = PlaybackSessionParser.activeMediaSessions(dump)

        assertEquals(
            "$label: the two entry points disagree",
            players.map { it.sessionId }.toSet(),
            sessions,
        )
        players.forEach { player ->
            assertTrue(
                "$label: session ${player.sessionId} is not a player — 0 is the output mix",
                player.sessionId > 0,
            )
            assertTrue(
                "$label: uid ${player.uid} is neither a real uid nor the -1 that means unknown",
                player.uid >= 0 || player.uid == -1,
            )
        }
        if (!dump.contains("AudioPlaybackConfiguration")) {
            assertEquals(
                "$label: sessions appeared from a dump with no playback configurations",
                emptySet<Any>(),
                players,
            )
        }

        val candidates = dump.lineSequence().count { line ->
            line.contains("AudioPlaybackConfiguration") &&
                line.contains("state:started") &&
                line.contains("usage=USAGE_MEDIA")
        }
        assertTrue(
            "$label: ${players.size} sessions came out of $candidates qualifying lines",
            players.size <= candidates,
        )
    }

    /** Each tenth of the file, plus the middle of the line that tenth lands in. */
    private fun cutPoints(): List<Int> {
        val length = text.length
        val tenths = (0..10).map { (length * it / 10).coerceIn(0, length) }
        val midLine = tenths.map { offset ->
            val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
            val lineEnd = text.indexOf('\n', offset).takeIf { it >= 0 } ?: length
            ((lineStart + lineEnd) / 2).coerceIn(0, length)
        }
        return (tenths + midLine).distinct().sorted()
    }
}
