package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysBluetoothParser
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpLinkDumpParser
import dev.dankyeeter.btdashboard.monitor.link.live.AudioFlingerTrackParser
import dev.dankyeeter.btdashboard.monitor.link.live.PlayingStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * The same corpus, cut short in every way a real read can be cut short.
 *
 * ## Why truncation is its own class of bug
 *
 * `dumpsys` output arrives over a pipe from a process this app does not own. It
 * is truncated when the shell helper dies mid-write, when a timeout fires, when
 * the buffer the caller allocated runs out — and `bt_manager_truncated.txt`
 * exists because that has been seen. A parser that indexes past the end of a
 * section it assumed complete throws there and nowhere else, and the *only*
 * visible symptom is a live panel that goes blank on a busy phone.
 *
 * Every cut here is a fixed fraction of the file plus a deterministic mid-line
 * cut inside the same line, so a failure names one reproducible prefix rather
 * than a seed. Randomised fuzzing would find more, and would find it on a
 * different run each time; this finds the same thing every time, which is what
 * makes it a regression test.
 *
 * The bar is not "does not crash". It is [ParserInvariants] in full: a prefix
 * that cuts a section in half must degrade to absence, never to a struct of
 * zeros that reads on screen as a healthy silent link.
 */
@RunWith(Parameterized::class)
class FixtureTruncationTest(private val name: String, private val fixture: File) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> =
            RepoTree.dumpFixtures.map { arrayOf(it.name, it) }

        /** Tenths of the file, both ends included. */
        private val FRACTIONS = (0..10).map { it / 10.0 }
    }

    private val text: String by lazy { fixture.readText() }

    @Test
    fun `every prefix parses without throwing and degrades to absence`() {
        cutPoints().forEach { cut ->
            ParserInvariants.assertAll("$name cut at $cut/${text.length}", text.take(cut))
        }
    }

    /**
     * The zero-length cut, stated on its own because it is the one case where
     * "honest absence" can be written down exactly.
     *
     * Nothing in, nothing out — and a warning rather than silence, because the
     * caller has to be able to tell "the dump said no device" from "there was no
     * dump".
     */
    @Test
    fun `an empty read produces absence and says so`() {
        val snapshot = DumpsysBluetoothParser.parse("")
        assertEquals(emptyList<Any>(), snapshot.devices)
        assertTrue("empty dump produced no warning", snapshot.warnings.isNotEmpty())

        val link = A2dpLinkDumpParser.parse("")
        assertNull(link.device)
        assertNull(link.codec)
        assertNull(link.tx)
        assertNull(link.ldacStack)
        assertTrue("empty dump produced no warning", link.warnings.isNotEmpty())

        val flinger = AudioFlingerTrackParser.parse("")
        assertEquals(emptyList<Any>(), flinger.threads)
        assertNull(flinger.bluetoothThread)
        assertTrue("empty dump produced no warning", flinger.warnings.isNotEmpty())

        assertEquals(emptyList<Any>(), PlayingStreamParser.playingStreams(""))
    }

    /**
     * Where to cut: each tenth, plus the middle of the line that tenth lands in.
     *
     * The mid-line cuts are the ones that matter. A cut on a line boundary only
     * removes whole lines, which is what every line-oriented parser here already
     * handles; a cut inside `mCodecConfig:{codecName:LD` is what leaves a half
     * key-value pair for a regex to match against.
     */
    private fun cutPoints(): List<Int> {
        val length = text.length
        val tenths = FRACTIONS.map { (length * it).toInt().coerceIn(0, length) }
        val midLine = tenths.map { offset ->
            val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
            val lineEnd = text.indexOf('\n', offset).takeIf { it >= 0 } ?: length
            ((lineStart + lineEnd) / 2).coerceIn(0, length)
        }
        return (tenths + midLine).distinct().sorted()
    }
}
