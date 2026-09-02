package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.link.live.InferenceConfidence
import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.monitor.link.live.LinkEvent
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LinkObservability
import dev.dankyeeter.btdashboard.monitor.link.live.LiveLinkSource
import dev.dankyeeter.btdashboard.monitor.link.live.toMonitorEvent
import dev.dankyeeter.btdashboard.monitor.shell.ShellResult
import dev.dankyeeter.btdashboard.monitor.shell.UnavailableShellRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** The capture that carries the `A2DP LDAC State:` block. Already connected and playing. */
    private val ldacStateBt by lazy { fixture("bt_manager_pixel11_ldac_state_abr.txt") }

    /**
     * The same live capture with one number moved: the LDAC transmission
     * bitrate. That is exactly what the device does when ABR steps, and moving
     * only that field keeps every other fact in the dump honest.
     */
    private fun atBitrate(kbps: Int): String =
        setCounter(connected(ldacStateBt), "LDAC transmission bitrate (Kbps)", "$kbps")

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
     * AK-T009-24 at the level where it is expensive: a window in which only the
     * encoder underflow counter moved writes nothing at all.
     *
     * The 39-minute ABR run produced 23 such windows over music the owner heard
     * as flawless (`docs/perf/T-011-messung.md`). Each one used to become a
     * `LossDetected` — a red line in the panel, a row in the timeline and a row
     * in the database, none of which described anything that happened.
     */
    @Test
    fun `a window in which only underflow moved produces no loss event`() = runTest {
        val clock = TestClock(1_000L)
        val first = LiveLinkSource(shellOf(connected()), clock::now).readOnce()

        clock.advance(2_000L)
        val quiet = setCounter(connected(), "Counts (underflow)", "789")
        val source = LiveLinkSource(shellOf(quiet), clock::now)
        val second = source.readOnce(first)

        val delta = requireNotNull(second.txDelta)
        assertEquals(1L, delta.underflows)
        assertEquals(0L, delta.dropped)
        assertEquals(0L, delta.dropouts)
        assertFalse("underflow alone is not audible loss", delta.hasLoss)
        assertFalse("nor anywhere else on the path", second.hasLossThisWindow)
        assertTrue(
            "an underflow-only window wrote a loss event",
            source.eventsBetween(first, second).filterIsInstance<LinkEvent.LossDetected>().isEmpty(),
        )
    }

    /**
     * The channel AK-T009-24 names in so many words — "stack dropouts" — pinned
     * on the words rather than on the boolean, and pinned alone.
     *
     * The dropouts move here without any dropped packets, so the assertion
     * cannot be satisfied by the other tx counter, and the underflow counter
     * moves at the same time to show that it is carried on the event without
     * being named in the sentence.
     */
    @Test
    fun `a window of stack dropouts names that channel and not the underflows`() = runTest {
        val clock = TestClock(1_000L)
        val first = LiveLinkSource(shellOf(connected()), clock::now).readOnce()

        clock.advance(97_000L)
        var worse = setCounter(connected(), "Counts (flushed/dropped/dropouts)", "1 / 0 / 21")
        worse = setCounter(worse, "Counts (underflow)", "800")
        val source = LiveLinkSource(shellOf(worse), clock::now)
        val second = source.readOnce(first)

        val delta = requireNotNull(second.txDelta)
        assertEquals(0L, delta.dropped)
        assertEquals(21L, delta.dropouts)
        assertTrue("21 stack dropouts are loss on their own", delta.hasLoss)

        val loss = source.eventsBetween(first, second)
            .filterIsInstance<LinkEvent.LossDetected>()
            .single()
        assertEquals(21L, loss.txDropouts)
        assertEquals("the counter is still carried", 12L, loss.txUnderflows)
        assertTrue(loss.detail, loss.detail.contains("21 stack dropout"))
        assertFalse(
            "a counter that cannot say loss must not be named in the loss sentence",
            loss.detail.contains("underflow"),
        )
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

        // The panel must say which of the two worlds it is in, not merely leave
        // the numbers blank - a blank throughput row reads as "no traffic",
        // which is the opposite of "this codec is invisible from here".
        assertEquals(LinkObservability.OFFLOADED, snapshot.observability)
        assertEquals(InferenceConfidence.UNKNOWN, snapshot.modeInference.confidence)
        assertTrue(snapshot.modeInference.reason.contains("cannot observe"))
    }

    /**
     * The transition the KDoc on [LinkObservability] warns about, played out
     * over two polls instead of asserted on one (QA-009).
     *
     * A link that was host-encoded a moment ago leaves `btif_a2dp_source`'s
     * counters standing at whatever they reached. Read across the switch they
     * are not merely stale, they are a *difference* against a live session — 525
     * dropped packets and 21 dropouts that belong to a stream this codec no
     * longer goes through. The single-poll test above covers a link that was
     * offloaded all along and cannot see this at all.
     */
    @Test
    fun `counters that stay warm across a switch to offload are not differenced`() = runTest {
        val clock = TestClock(1_000L)
        val first = LiveLinkSource(shellOf(connected()), clock::now).readOnce()
        assertEquals(LinkObservability.HOST_ENCODED, first.observability)

        clock.advance(2_000L)
        val warm = setCounter(connected(), "Counts (flushed/dropped/dropouts)", "1 / 525 / 21")
        val second = LiveLinkSource(shellOf(withLdacOffloaded(warm)), clock::now).readOnce(first)

        assertEquals(LinkObservability.OFFLOADED, second.observability)
        assertNull("warm counters must not survive the switch", second.tx)
        assertNull("and nothing may be differenced out of them", second.txDelta)
        assertFalse("nor may the path report loss from them", second.hasLossThisWindow)

        // The same reading without the switch does produce the delta, so what
        // the three assertions above pin is the offload and not an unreadable
        // dump or a clock that never moved.
        val hostEncoded = LiveLinkSource(shellOf(warm), clock::now).readOnce(first)
        assertEquals(525L, hostEncoded.txDelta?.dropped)
        assertEquals(21L, hostEncoded.txDelta?.dropouts)
    }

    @Test
    fun `a host-encoded codec is marked observable`() = runTest {
        val snapshot = LiveLinkSource(shellOf(connected())).readOnce()
        assertEquals(LinkObservability.HOST_ENCODED, snapshot.observability)
    }

    /**
     * The link's rate, read rather than reconstructed, on the very first poll.
     *
     * That is the shape change worth pinning: a printed bitrate is a reading, so
     * unlike the counter arithmetic it replaced there is nothing to wait for and
     * no delta to fail.
     */
    @Test
    fun `the measured LDAC bitrate is available from the first poll`() = runTest {
        val snapshot = LiveLinkSource(shellOf(connected(ldacStateBt)), TestClock(0L)::now).readOnce()

        assertEquals(396, snapshot.ldac?.measuredKbps)
        assertEquals("ABR", snapshot.ldac?.stack?.qualityMode)
        assertEquals(InferenceConfidence.MEASURED, snapshot.modeInference.confidence)
        assertEquals(396, snapshot.modeInference.measuredKbps)
        assertNull("nothing has been differenced yet", snapshot.txDelta)
    }

    /**
     * The ABR event, end to end through the source: a level that holds becomes
     * one line with both measured figures on it.
     *
     * Four polls, because the first one produces no events at all and the
     * settling rule then wants three readings of the new level. The filter
     * itself is tested in `MeasuredBitrateTrackerTest`; what is being checked
     * here is that the source feeds it the measured field and renders the
     * result.
     */
    @Test
    fun `a settled change in the measured bitrate becomes one timeline event`() = runTest {
        val clock = TestClock(0L)
        val source = LiveLinkSource(shellOf(connected(ldacStateBt)), clock::now)

        var snapshot: LinkLiveSnapshot =
            LiveLinkSource(shellOf(atBitrate(330)), clock::now).readOnce()
        val events = mutableListOf<LinkEvent>()
        listOf(330, 330, 330, 660, 660, 660).forEach { kbps ->
            clock.advance(2_000L)
            val next = LiveLinkSource(shellOf(atBitrate(kbps)), clock::now).readOnce(snapshot)
            events += source.eventsBetween(snapshot, next)
            snapshot = next
        }

        val steps = events.filterIsInstance<LinkEvent.MeasuredBitrateChanged>()
        assertEquals("one announcement of 330, then one move to 660", 2, steps.size)
        assertEquals(330, steps.first().toKbps)

        val move = steps.last()
        assertEquals(330, move.fromKbps)
        assertEquals(660, move.toKbps)
        assertFalse(move.fell)
        assertEquals("ABR", move.qualityModeLabel)
        assertTrue(move.detail.contains("330 to 660 kbps"))
        // The timeline's bitrate column carries the measurement, not a spec figure.
        assertEquals(660, move.toMonitorEvent(null, null).bitrateKbps)
    }

    /**
     * The measured pendulum, through the real source. ABR was observed swinging
     * between 492 and 660 for a whole session; each swing is a bigger jump than
     * a genuine ladder step, so only the settling rule keeps the timeline clean.
     */
    @Test
    fun `an ABR link that keeps changing its mind writes nothing`() = runTest {
        val clock = TestClock(0L)
        val source = LiveLinkSource(shellOf(connected(ldacStateBt)), clock::now)

        var snapshot = LiveLinkSource(shellOf(atBitrate(492)), clock::now).readOnce()
        val events = mutableListOf<LinkEvent>()
        // Settle first, so the events below cannot be the opening announcement.
        val series = listOf(492, 492, 492) + (1..12).map { if (it % 2 == 0) 492 else 660 }
        series.forEach { kbps ->
            clock.advance(2_000L)
            val next = LiveLinkSource(shellOf(atBitrate(kbps)), clock::now).readOnce(snapshot)
            events += source.eventsBetween(snapshot, next)
            snapshot = next
        }

        val steps = events.filterIsInstance<LinkEvent.MeasuredBitrateChanged>()
        assertEquals("only the opening level should be announced", 1, steps.size)
        assertEquals(492, steps.single().toKbps)
    }

    /**
     * A build with no LDAC state section keeps the old honest refusal. The
     * fallback wording is not dead code — it is what every other build says.
     */
    @Test
    fun `a build without the LDAC section reports no rate and no bitrate events`() = runTest {
        val clock = TestClock(0L)
        val source = LiveLinkSource(shellOf(connected()), clock::now)
        var snapshot = source.readOnce()
        val events = mutableListOf<LinkEvent>()
        repeat(6) {
            clock.advance(2_000L)
            val next = source.readOnce(snapshot)
            events += source.eventsBetween(snapshot, next)
            snapshot = next
        }

        assertNull(snapshot.ldac?.measuredKbps)
        assertEquals(InferenceConfidence.UNKNOWN, snapshot.modeInference.confidence)
        assertTrue(events.filterIsInstance<LinkEvent.MeasuredBitrateChanged>().isEmpty())
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
