package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.effects.AudioFlingerEffectParser
import dev.dankyeeter.btdashboard.monitor.effects.ForeignEqDetector
import dev.dankyeeter.btdashboard.monitor.effects.PsOutputParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignEqTest {

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("dumps/$name")) {
            "missing test resource dumps/$name"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `effect chains parse with session, name and client pid`() {
        val snapshot = AudioFlingerEffectParser.parse(load("audio_flinger_wavelet.txt"))
        assertEquals(2, snapshot.chains.size)

        val foreign = snapshot.chains.first { it.sessionId == 145 }.effects.single()
        assertEquals("Equalizer", foreign.name)
        assertTrue(foreign.enabled)
        assertEquals(listOf(8421), foreign.clientPids)

        val ours = snapshot.chains.first { it.sessionId == 0 }.effects.single()
        assertEquals("DynamicsProcessing", ours.name)
        assertEquals(listOf(9033), ours.clientPids)
    }

    @Test
    fun `foreign equalizer is detected and attributed to its package`() {
        val snapshot = AudioFlingerEffectParser.parse(load("audio_flinger_wavelet.txt"))
        val warnings = ForeignEqDetector.detect(
            snapshot = snapshot,
            ownPid = 9033,
            pidToPackage = mapOf(8421 to "com.pittvandewitt.wavelet"),
        )
        assertEquals(1, warnings.size)
        assertEquals("Equalizer active from Wavelet", warnings.single().message)
        assertEquals(145, warnings.single().sessionId)
    }

    @Test
    fun `our own dynamics processing never warns`() {
        val snapshot = AudioFlingerEffectParser.parse(load("audio_flinger_clean.txt"))
        assertTrue(ForeignEqDetector.detect(snapshot, ownPid = 9033).isEmpty())
    }

    @Test
    fun `unresolved pids still produce a warning`() {
        val snapshot = AudioFlingerEffectParser.parse(load("audio_flinger_wavelet.txt"))
        val warning = ForeignEqDetector.detect(snapshot, ownPid = 9033).single()
        assertEquals("Equalizer active from PID 8421", warning.message)
    }

    @Test
    fun `an empty or unparseable dump degrades quietly`() {
        assertTrue(AudioFlingerEffectParser.parse("").chains.isEmpty())
        assertTrue(AudioFlingerEffectParser.parse("¯\\_(ツ)_/¯").warnings.isNotEmpty())
    }

    @Test
    fun `effect name matching covers the usual suspects`() {
        assertTrue(ForeignEqDetector.isEqualizerEffect("Equalizer"))
        assertTrue(ForeignEqDetector.isEqualizerEffect("DynamicsProcessing"))
        assertTrue(ForeignEqDetector.isEqualizerEffect("Loudness Enhancer"))
        assertTrue(!ForeignEqDetector.isEqualizerEffect("Visualizer"))
        assertTrue(!ForeignEqDetector.isEqualizerEffect("AEC"))
    }

    @Test
    fun `ps output parses in both layouts`() {
        val narrow = """
            PID NAME
            8421 com.pittvandewitt.wavelet
            9033 dev.dankyeeter.btdashboard
        """.trimIndent()
        assertEquals(
            mapOf(8421 to "com.pittvandewitt.wavelet", 9033 to "dev.dankyeeter.btdashboard"),
            PsOutputParser.parse(narrow),
        )

        val wide = """
            USER           PID  PPID     VSZ    RSS WCHAN            ADDR S NAME
            u0_a231       8421  1023 14803252 143216 do_epoll_wait      0 S com.pittvandewitt.wavelet
            root           412     2       0      0 kthread            0 S [kworker/0:2]
        """.trimIndent()
        val parsed = PsOutputParser.parse(wide)
        assertEquals("com.pittvandewitt.wavelet", parsed[8421])
        assertTrue(parsed.none { it.value.startsWith("[") })
    }
}
