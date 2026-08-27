package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.CodecModeCalibrator
import dev.dankyeeter.btdashboard.monitor.link.live.CodecModePinner
import dev.dankyeeter.btdashboard.monitor.link.live.InMemoryCodecModeSignatureStore
import dev.dankyeeter.btdashboard.monitor.link.live.InferenceConfidence
import dev.dankyeeter.btdashboard.monitor.link.live.LdacModeSignatures
import dev.dankyeeter.btdashboard.monitor.link.live.LiveLinkSource
import dev.dankyeeter.btdashboard.monitor.shell.ShellResult
import dev.dankyeeter.btdashboard.monitor.shell.ShellRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calibration: pinning each bitrate mode long enough to learn what it looks
 * like on one particular link.
 *
 * ## What these tests are for now
 *
 * Two of the three reasons this class existed are gone: the rate it was
 * reconstructing is printed outright on this build, and the ratio it learns was
 * falsified as a mode signature (see `CodecModeInference`). So the tests below
 * split in two.
 *
 * The **refusals** are the live behaviour, and the newest of them is the point:
 * a link whose bitrate the phone already reports must be turned down before it
 * is touched, because this is the one operation in the module that changes the
 * device and each mode it pins is an audible gap in the music.
 *
 * The **mechanics** — learns three bands, stores them, restores the previous
 * mode even when a step fails — are kept because the seam is kept. A codec that
 * neither prints its rate nor is offloaded would need exactly this shape, and a
 * half-finished run that left a headphone pinned to 330 kbps would be a worse
 * bug than never calibrating at all.
 */
class CodecModeCalibrationTest {

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("dumps/$name"),
    ) { "fixture $name missing" }.bufferedReader().readText()

    private val address = "xx:xx:xx:xx:ab:cd"

    /**
     * A pretend LDAC link whose packing depends on the pinned mode.
     *
     * Counters accumulate rather than being recomputed, because a counter that
     * went backwards when the mode changed would be read — correctly — as a
     * stack restart, and the calibrator would learn nothing.
     *
     * The frame rate is held at a real 750/s (96 kHz over a 128-sample frame),
     * because the inference gate checks exactly that before it will believe any
     * packing. An earlier version of this fake advanced the counters by round
     * numbers instead and was rejected by its own production code, which is the
     * gate doing its job: [FRAMES_PER_READ] frames across [MILLIS_PER_READ] is
     * 750/s, and every packing used here divides it evenly.
     */
    private class FakeLdacLink(base: String) {
        private val connected = base
            .replace("mConnectionState: STATE_DISCONNECTED", "mConnectionState: STATE_CONNECTED")
            .replace("mIsPlaying: false", "mIsPlaying: true")

        var pinned: Long = 0L
        var packingByMode = mapOf(0L to 12, 1000L to 4, 1001L to 6, 1002L to 12)
        private var packets = 0L
        private var frames = 0L

        fun dump(): String {
            val framesPerPacket = packingByMode[pinned] ?: 12
            frames += FRAMES_PER_READ
            packets += FRAMES_PER_READ / framesPerPacket
            var out = rewrite(connected, "Counts (enqueue/dequeue/readbuf)", "$packets / $packets / $packets")
            out = rewrite(out, "Frames per packet (total/max/ave)", "$frames / $framesPerPacket / $framesPerPacket")
            return out.lineSequence().joinToString("\n") { line ->
                if (line.trim().startsWith("mCodecConfig")) {
                    line.replace(Regex("""mCodecSpecific1:-?\d+"""), "mCodecSpecific1:$pinned")
                } else {
                    line
                }
            }
        }

        private fun rewrite(dump: String, label: String, values: String) =
            dump.lineSequence().joinToString("\n") { line ->
                if (line.trim().startsWith(label)) line.substringBefore(':') + ": " + values else line
            }

        companion object {
            /** 4 s of encoding per reading, at LDAC's 750 frames/s for 96 kHz. */
            const val FRAMES_PER_READ = 3_000L
            const val MILLIS_PER_READ = 4_000L
        }
    }

    private class FakePinner(private val link: FakeLdacLink) : CodecModePinner {
        val requested = mutableListOf<Long>()
        var refuse: Set<Long> = emptySet()

        override suspend fun pinMode(address: String, codec: CodecFamily, modeRawValue: Long): Long? {
            requested += modeRawValue
            if (modeRawValue in refuse) return null
            link.pinned = modeRawValue
            return modeRawValue
        }
    }

    private class DynamicShell(
        private val bt: () -> String,
        private val flinger: String,
        private val audio: String,
    ) : ShellRunner {
        override val isAvailable = true
        override suspend fun run(command: List<String>): ShellResult = when {
            command.contains("bluetooth_manager") -> ShellResult(0, bt())
            command.contains("media.audio_flinger") -> ShellResult(0, flinger)
            else -> ShellResult(0, audio)
        }
    }

    private class Rig(base: String, flinger: String, audio: String) {
        val link = FakeLdacLink(base)
        val pinner = FakePinner(link)
        val store = InMemoryCodecModeSignatureStore()
        private var now = 0L
        val source = LiveLinkSource(
            shell = DynamicShell(link::dump, flinger, audio),
            // Each reading advances the clock by the window the fake link's
            // counters cover, so the derived frame rate comes out at the real
            // 750/s. Without an advancing clock the window is zero and no delta
            // exists at all.
            clock = { now += FakeLdacLink.MILLIS_PER_READ; now },
        )
        val calibrator = CodecModeCalibrator(source, pinner, store) { now }
    }

    private fun rig() = Rig(
        base = fixture("bt_manager_pixel11_ldac_txqueue.txt"),
        flinger = fixture("audio_flinger_pixel11_threads.txt"),
        audio = fixture("audio_players_tidal.txt"),
    )

    @Test
    fun `learns one signature per mode and puts the link back`() = runTest {
        val rig = rig()
        val report = rig.calibrator.calibrate(address, deviceKey = address)

        assertTrue(report.succeeded)
        assertEquals(3, report.learned.size)
        assertEquals(
            listOf(4.0, 6.0, 12.0),
            report.learned.map { (it.framesPerPacket.start + it.framesPerPacket.endInclusive) / 2 },
        )
        assertEquals(listOf(1000L, 1001L, 1002L), report.learned.map { it.modeRawValue })

        // The link started unpinned (codecSpecific1 = 0), so that is where it
        // has to end up - restoring to "High quality" because that was the last
        // thing measured would silently take ABR away from the user.
        assertEquals(0L, report.previousModeRawValue)
        assertTrue(report.restored)
        assertEquals(0L, rig.link.pinned)
        assertEquals(0L, rig.pinner.requested.last())
    }

    @Test
    fun `stores what it learned under the device and codec`() = runTest {
        val rig = rig()
        rig.calibrator.calibrate(address, deviceKey = address)
        val stored = rig.store.signatures(address, "LDAC")
        assertEquals(3, stored.size)
        assertTrue(stored.all { it.sampleRateHz == 96_000 })
    }

    /**
     * What a finished run no longer buys, and why that is right.
     *
     * The bands are still learned and still stored — the seam works — but
     * nothing reads them, because what they measure is
     * `A2dpTxDelta.framesPerEnqueue` and the enqueue counter turned out to be a
     * 20 ms media timer rather than a radio packet. A live reading on this
     * fixture (which has no LDAC state section) therefore stays UNKNOWN, and it
     * must: a calibrated-looking label built on that ratio would be a duty cycle
     * wearing a bitrate's name.
     */
    @Test
    fun `a finished run does not turn duty-cycle bands into a claimed mode`() = runTest {
        val rig = rig()
        rig.calibrator.calibrate(address, deviceKey = address)
        assertEquals(3, rig.store.signatures(address, "LDAC").size)

        rig.link.pinned = 0L
        rig.link.packingByMode = rig.link.packingByMode + (0L to 4)
        val first = rig.source.readOnce()
        val second = rig.source.readOnce(first)

        assertEquals(InferenceConfidence.UNKNOWN, second.modeInference.confidence)
        assertNull(second.modeInference.mode)
        assertNull(second.modeInference.measuredKbps)
    }

    /**
     * The gate that matters now. On a build that prints the rate outright there
     * is nothing to learn by pinning — and pinning costs the user three audible
     * renegotiations, so it must be refused before the link is touched.
     */
    @Test
    fun `refuses to calibrate a link whose rate the phone already reports`() = runTest {
        val live = fixture("bt_manager_pixel11_ldac_state_abr.txt")
        val store = InMemoryCodecModeSignatureStore()
        val pinner = FakePinner(FakeLdacLink(""))
        val calibrator = CodecModeCalibrator(
            LiveLinkSource(DynamicShell({ live }, "", ""), clock = { 1_000L }),
            pinner,
            store,
        )

        val report = calibrator.calibrate(address, deviceKey = address)

        assertFalse(report.succeeded)
        assertTrue(report.note, report.note.contains("directly"))
        assertTrue("the link must not have been touched", pinner.requested.isEmpty())
    }

    /**
     * A refused mode must not abort the run, and — the part that matters — must
     * not leave the link on whatever the previous mode happened to be.
     */
    @Test
    fun `a refused mode is skipped and the link is still restored`() = runTest {
        val rig = rig()
        rig.pinner.refuse = setOf(LdacModeSignatures.standard.rawValue)

        val report = rig.calibrator.calibrate(address, deviceKey = address)

        assertEquals(2, report.learned.size)
        assertEquals(1, report.skipped.size)
        assertTrue(requireNotNull(report.skipped.single().skippedReason).contains("refused"))
        assertTrue(report.restored)
        assertEquals(0L, rig.link.pinned)
    }

    /**
     * Checked before anything is pinned. Telling the user why after three
     * audible renegotiations produced nothing is not the same service.
     */
    @Test
    fun `refuses to touch the link when nothing is playing`() = runTest {
        val silent = fixture("bt_manager_pixel11_ldac_txqueue.txt")
            .replace("mConnectionState: STATE_DISCONNECTED", "mConnectionState: STATE_CONNECTED")
        val rig = Rig(silent, fixture("audio_flinger_pixel11_threads.txt"), fixture("audio_players_tidal.txt"))
        // Rig's own fake flips mIsPlaying on; undo that by pinning the dump.
        val stillSilent = silent.replace("mIsPlaying: true", "mIsPlaying: false")
        val quiet = CodecModeCalibrator(
            LiveLinkSource(
                shell = DynamicShell({ stillSilent }, "", ""),
                clock = { 1_000L },
            ),
            rig.pinner,
            rig.store,
        )

        val report = quiet.calibrate(address, deviceKey = address)
        assertFalse(report.succeeded)
        assertTrue(report.note.contains("nothing is playing"))
        assertTrue("the link must not have been touched", rig.pinner.requested.isEmpty())
    }

    /**
     * An offloaded codec has no host counters at all, so there is nothing to
     * measure and no reason to disturb the link trying.
     */
    @Test
    fun `refuses to calibrate a codec the controller encodes`() = runTest {
        val offloaded = fixture("bt_manager_pixel11_ldac_txqueue.txt")
            .replace("mConnectionState: STATE_DISCONNECTED", "mConnectionState: STATE_CONNECTED")
            .replace("mIsPlaying: false", "mIsPlaying: true")
            .lineSequence()
            .flatMap { line ->
                if (line.trim().startsWith("codecConfigOffloading")) {
                    sequenceOf(line, "    {codecName:LDAC,mCodecType:4,mCodecPriority:0}")
                } else {
                    sequenceOf(line)
                }
            }
            .joinToString("\n")

        val store = InMemoryCodecModeSignatureStore()
        val link = FakeLdacLink("")
        val pinner = FakePinner(link)
        val calibrator = CodecModeCalibrator(
            LiveLinkSource(
                shell = DynamicShell({ offloaded }, "", ""),
                clock = { 1_000L },
            ),
            pinner,
            store,
        )

        val report = calibrator.calibrate(address, deviceKey = address)
        assertFalse(report.succeeded)
        assertTrue(report.note.contains("controller"))
        assertTrue(pinner.requested.isEmpty())
    }
}
