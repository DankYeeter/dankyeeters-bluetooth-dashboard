package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.monitor.link.live.LinkEvent
import dev.dankyeeter.btdashboard.monitor.link.live.LiveLinkSource
import dev.dankyeeter.btdashboard.monitor.link.live.toMonitorEvent
import dev.dankyeeter.btdashboard.monitor.shell.ShellResult
import dev.dankyeeter.btdashboard.monitor.shell.UnavailableShellRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The differencing half of the live view: deltas between two polls, and the
 * events that come out of them.
 *
 * The dumps handed to the fake shell start life as the **real** Pixel 11
 * capture and are then edited in ways the device itself would produce — the
 * connection state flipped, a counter advanced. That split is deliberate:
 * `LiveLinkParserTest` pins the parsers to untouched captures, and this file
 * only ever needs two readings that differ, which no single capture can be.
 */
class LiveLinkSourceTest {

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("dumps/$name"),
    ) { "fixture $name missing" }.bufferedReader().readText()

    private val baseBt by lazy { fixture("bt_manager_pixel11_ldac_txqueue.txt") }
    private val baseFlinger by lazy { fixture("audio_flinger_pixel11_threads.txt") }
    private val basePlayers by lazy { fixture("audio_players_tidal.txt") }

    /** The same dump with the link up and the stream running. */
    private fun connected(bt: String = baseBt): String = bt
        .replace("mConnectionState: STATE_DISCONNECTED", "mConnectionState: STATE_CONNECTED")
        .replace("mIsPlaying: false", "mIsPlaying: true")
        .replace("active_a2dp_devices: []", "active_a2dp_devices: [xx:xx:xx:xx:ab:cd]")

    /**
     * Rewrites one `label : a / b / c` row of the `A2DP State:` block.
     *
     * Line-based rather than a substring replace, so the test does not depend
     * on the exact run of padding spaces the device happened to print — that is
     * formatting, and pinning it here would make an unrelated build change look
     * like a delta bug.
     */
    private fun setCounter(dump: String, label: String, values: String): String =
        dump.lineSequence().joinToString("\n") { line ->
            if (line.trim().startsWith(label)) line.substringBefore(':') + ": " + values else line
        }

    /** Edits the negotiated `mCodecConfig` line and nothing that looks like it. */
    private fun editCodecConfig(dump: String, edit: (String) -> String): String =
        dump.lineSequence().joinToString("\n") { line ->
            if (line.trim().startsWith("mCodecConfig")) edit(line) else line
        }

    private fun withLdacOffloaded(dump: String): String =
        dump.lineSequence().flatMap { line ->
            if (line.trim().startsWith("codecConfigOffloading")) {
                sequenceOf(line, "    {codecName:LDAC,mCodecType:4,mCodecPriority:0}")
            } else {
                sequenceOf(line)
            }
        }.joinToString("\n")

    private fun shellOf(bt: String, flinger: String = baseFlinger, audio: String = basePlayers) =
        FakeShellRunner(
            mapOf(
                "dumpsys bluetooth_manager" to ShellResult(0, bt),
                "dumpsys media.audio_flinger" to ShellResult(0, flinger),
                "dumpsys audio" to ShellResult(0, audio),
            ),
        )

    @Test
    fun `no shell identity yields a warning rather than a blank reading`() = runTest {
        val snapshot = LiveLinkSource(UnavailableShellRunner).readOnce()
        assertTrue(snapshot.isEmpty)
        assertTrue(snapshot.warnings.any { it.contains("helper") })
    }

    /**
     * A cumulative counter on its own says nothing about the last two seconds.
     * The first poll of a session therefore carries the totals and no deltas,
     * which is why every loss field is nullable rather than defaulted to zero.
     */
    @Test
    fun `the first poll carries counters but no deltas`() = runTest {
        val snapshot = LiveLinkSource(shellOf(connected())).readOnce()
        assertEquals(389_197L, snapshot.tx?.enqueueCount)
        assertNull(snapshot.txDelta)
        assertTrue(snapshot.inputs.single().underrunDelta == null)
    }

    @Test
    fun `a rise in the loss counters becomes one loss event`() = runTest {
        val clock = TestClock(1_000L)
        val first = LiveLinkSource(shellOf(connected()), clock::now).readOnce()

        clock.advance(2_000L)
        var worse = setCounter(connected(), "Counts (enqueue/dequeue/readbuf)", "389597 / 854736 / 1240579")
        worse = setCounter(worse, "Counts (flushed/dropped/dropouts)", "1 / 7 / 2")
        worse = setCounter(worse, "Counts (underflow)", "791")
        val source = LiveLinkSource(shellOf(worse), clock::now)
        val second = source.readOnce(first)

        val delta = requireNotNull(second.txDelta)
        assertEquals(2_000L, delta.windowMs)
        assertEquals(400L, delta.enqueued)
        assertEquals(7L, delta.dropped)
        assertEquals(2L, delta.dropouts)
        assertEquals(3L, delta.underflows)
        assertTrue(delta.hasLoss)
        assertEquals(200.0, requireNotNull(delta.packetsPerSecond), 0.001)

        val events = source.eventsBetween(first, second)
        val loss = events.filterIsInstance<LinkEvent.LossDetected>().single()
        assertEquals(7L, loss.txDropped)
        assertEquals(2L, loss.txDropouts)
        assertEquals(3L, loss.txUnderflows)
        assertTrue(loss.detail.contains("dropped packet"))
    }

    /**
     * Counters only fall when the thing counting them restarted. "This window
     * cannot be measured" and "nothing happened in this window" look identical
     * on a chart and mean opposite things, so a fall produces no delta at all.
     */
    @Test
    fun `a counter reset produces no delta rather than a negative one`() = runTest {
        val clock = TestClock(1_000L)
        val first = LiveLinkSource(shellOf(connected()), clock::now).readOnce()
        clock.advance(2_000L)
        val restarted = connected().replace(
            "Counts (enqueue/dequeue/readbuf)                        : 389197",
            "Counts (enqueue/dequeue/readbuf)                        : 12",
        )
        val second = LiveLinkSource(shellOf(restarted), clock::now).readOnce(first)
        assertNull(second.txDelta)
    }

    /**
     * An offloaded codec never passes through `btif_a2dp_source`, so its
     * counters sit wherever the last host-encoded session left them. Showing
     * them would be a frozen, perfectly healthy-looking link.
     */
    @Test
    fun `tx counters are withheld when the controller does the encoding`() = runTest {
        val snapshot = LiveLinkSource(shellOf(withLdacOffloaded(connected()))).readOnce()
        assertEquals(true, snapshot.codec?.isOffloaded)
        assertNull(snapshot.tx)
        assertTrue(snapshot.warnings.any { it.contains("controller") })
    }

    @Test
    fun `pinning an LDAC quality is reported as a mode change with its nominal rate`() = runTest {
        val clock = TestClock(0L)
        val first = LiveLinkSource(shellOf(connected()), clock::now).readOnce()
        clock.advance(2_000L)
        val pinned = editCodecConfig(connected()) {
            it.replace(Regex("""mCodecSpecific1:-?\d+"""), "mCodecSpecific1:1000")
        }
        val source = LiveLinkSource(shellOf(pinned), clock::now)
        val second = source.readOnce(first)

        assertEquals(LdacQualityMode.NOT_PINNED, first.ldac?.mode)
        assertEquals(LdacQualityMode.HIGH_QUALITY, second.ldac?.mode)
        assertEquals(990, second.ldac?.nominalKbps)

        val change = source.eventsBetween(first, second)
            .filterIsInstance<LinkEvent.LdacModeChanged>()
            .single()
        assertEquals(990, change.nominalKbps)
        assertTrue(change.detail.contains("990 kbps"))
    }

    @Test
    fun `connecting and starting the stream each produce an event`() = runTest {
        val clock = TestClock(0L)
        val idle = LiveLinkSource(shellOf(baseBt), clock::now).readOnce()
        clock.advance(2_000L)
        val source = LiveLinkSource(shellOf(connected()), clock::now)
        val live = source.readOnce(idle)

        val types = source.eventsBetween(idle, live)
        assertTrue(types.any { it is LinkEvent.ConnectionChanged && it.isConnected })
        assertTrue(types.any { it is LinkEvent.PlaybackChanged && it.isPlaying })
    }

    @Test
    fun `an unchanged link writes nothing to the timeline`() = runTest {
        val clock = TestClock(0L)
        val source = LiveLinkSource(shellOf(connected()), clock::now)
        val first = source.readOnce()
        clock.advance(2_000L)
        val second = source.readOnce(first)
        assertEquals(emptyList<LinkEvent>(), source.eventsBetween(first, second))
    }

    /**
     * The bitrate column of the timeline must stay empty for an adaptive link.
     * A column that quietly falls back to the codec's headline number is the
     * exact thing this module exists not to do.
     */
    @Test
    fun `an adaptive mode change carries no bitrate into the timeline`() {
        val event = LinkEvent.LdacModeChanged(
            timestampMs = 5L,
            from = LdacQualityMode.HIGH_QUALITY,
            to = LdacQualityMode.NOT_PINNED,
            nominalKbps = null,
            detail = "LDAC quality mode changed from High quality to Adaptive (stack default)",
        )
        val mapped = event.toMonitorEvent("xx:xx:xx:xx:ab:cd", "Noble FoKus Prestige Encore")
        assertEquals(MonitorEventType.BITRATE_MODE_CHANGED, mapped.type)
        assertNull(mapped.bitrateKbps)
    }

    @Test
    fun `a loss event maps to the dropout row of the timeline`() {
        val mapped = LinkEvent.LossDetected(
            timestampMs = 5L,
            windowMs = 2_000L,
            inputUnderruns = 3,
            mixerUnderruns = 0,
            txDropped = 0,
            txDropouts = 0,
            txUnderflows = 0,
            detail = "Audio loss: 3 app underrun(s)",
        ).toMonitorEvent(null, null)
        assertEquals(MonitorEventType.DROPOUT, mapped.type)
        assertNull(mapped.codec)
    }

    @Test
    fun `a codec change is reported with both families`() = runTest {
        val clock = TestClock(0L)
        val first = LiveLinkSource(shellOf(connected()), clock::now).readOnce()
        clock.advance(2_000L)
        val sbc = editCodecConfig(connected()) {
            it.replace("codecName:LDAC,mCodecType:4", "codecName:SBC,mCodecType:0")
        }
        val source = LiveLinkSource(shellOf(sbc), clock::now)
        val second = source.readOnce(first)
        val change = source.eventsBetween(first, second)
            .filterIsInstance<LinkEvent.CodecChanged>()
            .single()
        assertEquals(CodecFamily.LDAC, change.from)
        assertEquals(CodecFamily.SBC, change.to)
        assertNull("SBC is not LDAC, so there is no LDAC state to report", second.ldac)
    }
}
