package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxDelta
import dev.dankyeeter.btdashboard.monitor.link.live.EncoderStarvationForensics
import dev.dankyeeter.btdashboard.monitor.link.live.EncoderStarvationReport
import dev.dankyeeter.btdashboard.monitor.link.live.EncoderStarvationTripwire
import dev.dankyeeter.btdashboard.monitor.link.live.LinkEvent
import dev.dankyeeter.btdashboard.monitor.link.live.LiveLinkSource
import dev.dankyeeter.btdashboard.monitor.link.live.toMonitorEvent
import dev.dankyeeter.btdashboard.monitor.shell.ShellResult
import dev.dankyeeter.btdashboard.monitor.shell.ShellRunner
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tripwire that has to fire correctly exactly once — the next time the
 * encoder starves.
 *
 * ## What is being defended
 *
 * On 2026-08-28 the owner measured ~49 encoder underflows per second on a live
 * LDAC 96 kHz link with the system EQ attached, and switching the EQ off stopped
 * it dead. The toggle that proved the correlation also destroyed the state that
 * caused it, so the mechanism is unproven and the only way to prove it is to be
 * ready for the next occurrence. That makes the failure modes of this class
 * asymmetric, and the tests are shaped by which one costs more:
 *
 *  - **Missing a real episode** loses the one chance to diagnose it. Expensive.
 *  - **Firing on a hiccup** writes a wrong line on the timeline and a stray row.
 *    Cheap, but it is also how a diagnostic becomes noise nobody reads, which
 *    ends up costing exactly as much as missing the episode.
 *
 * So the rules are stated from both sides here: the properties below say what
 * may never be captured, and the examples say what must be.
 */
class EncoderStarvationTripwireTest {

    /** The poll spacing the live view actually runs at. */
    private val windowMs = 2_000L

    /** Exactly [EncoderStarvationTripwire.TRIP_RATE_PER_SECOND] over one window. */
    private val atThreshold =
        (EncoderStarvationTripwire.TRIP_RATE_PER_SECOND * windowMs / 1_000L).toLong()

    private fun delta(underflows: Long, window: Long = windowMs) = A2dpTxDelta(
        windowMs = window,
        enqueued = 100,
        underflows = underflows,
    )

    /** Feeds a series of deltas in and records which index produced a capture. */
    private fun drive(
        series: List<A2dpTxDelta?>,
        tripwire: EncoderStarvationTripwire = EncoderStarvationTripwire(),
        stepMs: Long = windowMs,
    ): List<Pair<Int, Int>> = series.mapIndexedNotNull { index, delta ->
        tripwire.onPass(stepMs * index, delta)?.let { index to it }
    }

    /**
     * The three gates, re-derived from the input rather than from the state.
     *
     * A capture at index `i` is only legitimate if the deltas at
     * `i - SUSTAINED_PASSES + 1 .. i` were all measurable and all strictly over
     * the rate, and if the previous capture was at least a cooldown earlier.
     * That is the weakest statement that still forbids both failure modes.
     */
    private fun assertContract(what: String, series: List<A2dpTxDelta?>, captures: List<Pair<Int, Int>>) {
        val sustained = EncoderStarvationTripwire.SUSTAINED_PASSES
        val rate = EncoderStarvationTripwire.TRIP_RATE_PER_SECOND
        var previousCaptureMs: Long? = null
        captures.forEach { (index, passes) ->
            val at = "$what: capture at pass $index"
            assertTrue(
                "$at fired before $sustained passes could exist",
                index >= sustained - 1,
            )
            val window = (index - sustained + 1..index).map { series[it] }
            assertTrue(
                "$at rests on a window containing an unmeasurable poll: $window",
                window.all { it != null },
            )
            assertTrue(
                "$at rests on a window that was not over $rate/s: " +
                    window.map { it?.underflowsPerSecond },
                window.all { (it?.underflowsPerSecond ?: 0.0) > rate },
            )
            assertTrue("$at reported fewer than $sustained sustained passes", passes >= sustained)
            val nowMs = windowMs * index
            previousCaptureMs?.let { previous ->
                assertTrue(
                    "$at is only ${nowMs - previous} ms after the previous one, " +
                        "under the ${EncoderStarvationTripwire.CAPTURE_COOLDOWN_MS} ms cooldown",
                    nowMs - previous >= EncoderStarvationTripwire.CAPTURE_COOLDOWN_MS,
                )
            }
            previousCaptureMs = nowMs
        }
    }

    // ---- the threshold -------------------------------------------------------

    @Test
    fun `a link with no underflows never captures however long it is watched`() {
        val series = List(500) { delta(0) }
        val captures = drive(series)
        assertContract("silent link", series, captures)
        assertEquals("a clean link produced a capture", emptyList<Any>(), captures)
    }

    /**
     * The threshold is `>`, not `>=`. A link sitting exactly on the line is not
     * over it, and the boundary is worth pinning because it is the one value a
     * future change to the constant is most likely to get wrong.
     */
    @Test
    fun `a rate exactly at the threshold never captures`() {
        val series = List(200) { delta(atThreshold) }
        val captures = drive(series)
        assertEquals(
            EncoderStarvationTripwire.TRIP_RATE_PER_SECOND,
            series.first().underflowsPerSecond!!,
            0.0001,
        )
        assertContract("at the line", series, captures)
        assertEquals("a rate on the threshold captured", emptyList<Any>(), captures)
    }

    @Test
    fun `one pass over the threshold captures the moment the third one lands`() {
        // The incident's own rate: ~49/s, i.e. ~98 underflows per 2 s window.
        val series = List(6) { delta(98) }
        val captures = drive(series)
        assertContract("incident rate", series, captures)
        assertEquals("exactly one capture per episode", 1, captures.size)
        assertEquals(
            "the capture must land on the third sustained pass, not the first",
            EncoderStarvationTripwire.SUSTAINED_PASSES - 1,
            captures.single().first,
        )
        assertEquals(EncoderStarvationTripwire.SUSTAINED_PASSES, captures.single().second)
    }

    // ---- the sustained requirement -------------------------------------------

    @Test
    fun `a single bad window is a hiccup and captures nothing`() {
        // A track change, a seek, a Wi-Fi scan: one window over, then quiet.
        val series = listOf(delta(0), delta(200), delta(0), delta(0), delta(300), delta(0))
        val captures = drive(series)
        assertContract("isolated spikes", series, captures)
        assertEquals("an isolated spike captured", emptyList<Any>(), captures)
    }

    @Test
    fun `alternating good and bad windows never reach the sustained count`() {
        val series = (0 until 200).map { delta(if (it % 2 == 0) 200L else 0L) }
        val captures = drive(series)
        assertContract("alternating", series, captures)
        assertEquals("an alternating link captured", emptyList<Any>(), captures)
    }

    @Test
    fun `two bad windows in a row are still one short`() {
        val series = listOf(delta(0), delta(200), delta(200), delta(0), delta(200), delta(200))
        val captures = drive(series)
        assertContract("two in a row", series, captures)
        assertEquals("two sustained passes captured", emptyList<Any>(), captures)
    }

    /**
     * An unmeasurable window breaks the run rather than extending it.
     *
     * Same rule the rest of this module lives by: a counter that went backwards
     * means the stack restarted, and "this window cannot be measured" is not
     * evidence that the condition held through it. Counting it either way would
     * be inventing a reading.
     */
    @Test
    fun `an unmeasurable poll interrupts a run instead of completing one`() {
        val series = listOf(delta(200), delta(200), null, delta(200), delta(200), null, delta(200))
        val captures = drive(series)
        assertContract("gapped run", series, captures)
        assertEquals("a gapped run captured", emptyList<Any>(), captures)
    }

    @Test
    fun `a degenerate window has no rate and cannot trip the wire`() {
        // windowMs of zero divides by nothing; the delta reports null rather
        // than an infinite rate, and the tripwire has to treat that as a gap.
        val series = List(10) { delta(200, window = 0L) }
        assertNull(series.first().underflowsPerSecond)
        assertEquals(emptyList<Any>(), drive(series))
    }

    /**
     * A long deterministic walk over the shapes the device actually produces.
     *
     * Fixed strides rather than a seeded generator, so a failure is one named
     * sequence that reproduces on every machine.
     */
    @Test
    fun `the contract survives long adversarial walks`() {
        val shapes = listOf<Long?>(0, 0, 200, 98, 0, 20, 300, 0, 98, 98, 98, 0, 1)
        listOf(1, 2, 5, 7).forEach { stride ->
            val series = (0 until 400).map { i ->
                val underflows = shapes[(i * stride) % shapes.size]
                // Every 17th poll is unmeasurable, as a stack restart would be.
                if (i % 17 == 16 || underflows == null) null else delta(underflows)
            }
            assertContract("walk stride $stride", series, drive(series))
        }
    }

    // ---- the rate limit ------------------------------------------------------

    /**
     * The incident held for minutes. Without the cooldown that is one capture
     * every two seconds — two large dump parses and a database write each — for
     * the whole episode, which buries the timeline the capture is meant to
     * inform.
     */
    @Test
    fun `a starvation that lasts for hours is captured once per cooldown`() {
        // One hour of continuous starvation at the live view's poll interval.
        val passes = (60 * 60 * 1_000L / windowMs).toInt()
        val series = List(passes) { delta(98) }
        val captures = drive(series)
        assertContract("one hour", series, captures)

        // The first capture lands as soon as the run is long enough; every one
        // after it lands on the first poll the cooldown allows. Spelled out
        // rather than folded into one formula, because the off-by-one between
        // "when the run completes" and "when the cooldown expires" is exactly
        // what this test is for.
        val firstAt = EncoderStarvationTripwire.SUSTAINED_PASSES - 1
        val step = (EncoderStarvationTripwire.CAPTURE_COOLDOWN_MS / windowMs).toInt()
        assertEquals(
            "an hour of starvation should capture once per cooldown and no more",
            (passes - 1 - firstAt) / step + 1,
            captures.size,
        )
        assertEquals(firstAt, captures.first().first)
        captures.zipWithNext { (a, _), (b, _) ->
            assertEquals(
                "captures after the first should land on the first eligible poll",
                step,
                b - a,
            )
        }
    }

    /**
     * The cooldown gates captures, it does not gate detection.
     *
     * Once the cooldown expires the condition is *already* sustained, so the
     * next poll captures. Making it wait for three fresh passes after every
     * expiry would silently stretch a ten-minute rate limit into a longer one.
     */
    @Test
    fun `an episode that outlives the cooldown captures on the first eligible poll`() {
        val passesPerCooldown = (EncoderStarvationTripwire.CAPTURE_COOLDOWN_MS / windowMs).toInt()
        val firstAt = EncoderStarvationTripwire.SUSTAINED_PASSES - 1
        // Exactly long enough to reach the second capture and not one poll more,
        // so a wire that fired even one pass late would fail this rather than
        // pass it on a longer series.
        val series = List(firstAt + passesPerCooldown + 1) { delta(98) }
        val captures = drive(series)
        assertContract("cooldown expiry", series, captures)
        assertEquals(2, captures.size)
        assertEquals(firstAt, captures[0].first)
        assertEquals(firstAt + passesPerCooldown, captures[1].first)
    }

    @Test
    fun `a separate episode after a quiet stretch still waits for the cooldown`() {
        val quiet = List(60) { delta(0) }
        val loud = List(4) { delta(98) }
        val series = loud + quiet + loud
        val captures = drive(series)
        assertContract("two episodes", series, captures)
        assertEquals(
            "a second episode inside the cooldown must not produce a second capture",
            1,
            captures.size,
        )
    }

    @Test
    fun `reset forgets both the run and the cooldown`() {
        val tripwire = EncoderStarvationTripwire()
        val series = List(4) { delta(98) }
        assertEquals(1, drive(series, tripwire).size)

        tripwire.reset()
        assertEquals(0, tripwire.consecutiveOverThreshold)
        // A fresh link: the run starts again, and the cooldown of the previous
        // link's capture does not carry over onto it.
        assertEquals(1, drive(series, tripwire).size)
    }

    // ---- the forensic capture ------------------------------------------------

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("dumps/$name"),
    ) { "fixture $name missing" }.bufferedReader().readText()

    @Test
    fun `the capture counts effect instances and sessions out of the real dumps`() {
        val forensics = EncoderStarvationForensics.capture(
            flingerDump = fixture("audio_flinger_wavelet.txt"),
            audioDump = fixture("audio_players_tidal.txt"),
        )

        // The capture has one job: say how many effect instances were attached
        // and to how many sessions. Everything else in it is context.
        assertEquals(2, forensics.effectInstances)
        assertEquals(2, forensics.sessionsWithEffects)
        assertEquals(setOf(0, 145), forensics.effectsPerSession.map { it.sessionId }.toSet())
        assertTrue(
            "the effect names should reach the record verbatim: ${forensics.effectNames}",
            forensics.effectNames.isNotEmpty(),
        )
        // Only `state:started` media counts. The fixture also holds idle
        // SoundPool entries and a paused player, none of which are playing.
        assertEquals(listOf(8009), forensics.playbackSessionIds)
        assertNull("both sections parsed, so there is nothing to explain", forensics.note)
    }

    @Test
    fun `a dump that could not be read says so instead of reporting zero effects`() {
        val forensics = EncoderStarvationForensics.capture("", "")
        assertTrue(forensics.isEmpty)
        assertNotNull("an unreadable capture must carry its reason", forensics.note)

        val report = report(forensics)
        assertTrue(
            "the line must not claim there were no effects: ${report.detail}",
            report.detail.contains("could not be counted"),
        )
    }

    @Test
    fun `the one-liner names the rate and both counts`() {
        val detail = report(
            EncoderStarvationForensics.capture(
                fixture("audio_flinger_wavelet.txt"),
                fixture("audio_players_tidal.txt"),
            ),
        ).detail

        assertTrue(detail, detail.startsWith("Encoder starving —"))
        assertTrue(detail, detail.contains("49 encoder underflows/s"))
        assertTrue(detail, detail.contains("3 consecutive polls"))
        assertTrue(detail, detail.contains("2 effect instances on 2 sessions at the time"))
        assertTrue(detail, detail.contains("1 session playing"))
    }

    private fun report(forensics: dev.dankyeeter.btdashboard.monitor.link.live.EffectChainForensics) =
        EncoderStarvationReport(
            timestampMs = 1_000L,
            deviceAddress = "xx:xx:xx:xx:ab:cd",
            deviceName = "Headphones",
            underflowsPerSecond = 49.0,
            windowMs = 2_000L,
            sustainedPasses = 3,
            forensics = forensics,
        )

    @Test
    fun `a starvation capture lands on its own row of the timeline`() {
        val mapped = LinkEvent.EncoderStarvation(
            timestampMs = 5L,
            report = report(EncoderStarvationForensics.capture("", "")),
            detail = "Encoder starving — …",
        ).toMonitorEvent("xx:xx:xx:xx:ab:cd", "Headphones")

        // Not DROPOUT: a dropout says the user heard something, this says the
        // source could not feed the radio, and they have different suspects.
        assertEquals(MonitorEventType.ENCODER_STARVATION, mapped.type)
        assertNull("no bitrate was established by this event", mapped.bitrateKbps)
    }

    // ---- end to end through the poll loop ------------------------------------

    /**
     * A shell whose `bluetooth_manager` answer advances one poll at a time.
     *
     * The underflow counter climbs by 98 per pass, which at the live view's 2 s
     * interval is the ~49/s the incident measured. Everything else in the dump
     * stays exactly as the device printed it.
     */
    private inner class StarvingShell(
        private val baseBt: String,
        private val flinger: String,
        private val audio: String,
        private val underflowsPerPass: Long,
    ) : ShellRunner {

        var btReads = 0
            private set

        override val isAvailable = true

        override suspend fun run(command: List<String>): ShellResult {
            val key = command.joinToString(" ")
            return when {
                key.startsWith("dumpsys bluetooth_manager") -> {
                    val pass = btReads++
                    ShellResult(0, atCounters(baseBt, pass))
                }

                key.startsWith("dumpsys media.audio_flinger") -> ShellResult(0, flinger)
                key.startsWith("dumpsys audio") -> ShellResult(0, audio)
                else -> ShellResult(1, "", "no fake response for $key")
            }
        }

        private fun atCounters(dump: String, pass: Int): String {
            val enqueued = 389_197L + pass * 100L
            return setCounter(
                setCounter(dump, "Counts (underflow)", "${pass * underflowsPerPass}"),
                "Counts (enqueue/dequeue/readbuf)",
                "$enqueued / $enqueued / $enqueued",
            )
        }
    }

    private fun connected(dump: String): String = dump
        .replace("mConnectionState: STATE_DISCONNECTED", "mConnectionState: STATE_CONNECTED")
        .replace("mIsPlaying: false", "mIsPlaying: true")
        .replace("active_a2dp_devices: []", "active_a2dp_devices: [xx:xx:xx:xx:ab:cd]")

    private fun setCounter(dump: String, label: String, values: String): String =
        dump.lineSequence().joinToString("\n") { line ->
            if (line.trim().startsWith(label)) line.substringBefore(':') + ": " + values else line
        }

    /**
     * The whole path, on real dumps: a starving link polled through
     * [LiveLinkSource.updates] produces exactly one capture, it reaches the sink
     * that persists it, and it carries the counts.
     *
     * This is the test that would have to be green for the next occurrence to
     * diagnose itself, so it is deliberately end to end rather than a unit of it.
     */
    @Test
    fun `a starving link polled live captures once and hands the record to the sink`() = runTest {
        val shell = StarvingShell(
            baseBt = connected(fixture("bt_manager_pixel11_ldac_txqueue.txt")),
            flinger = fixture("audio_flinger_wavelet.txt"),
            audio = fixture("audio_players_tidal.txt"),
            underflowsPerPass = 98,
        )
        val persisted = mutableListOf<EncoderStarvationReport>()
        val source = LiveLinkSource(
            shell = shell,
            // One tick per served bluetooth dump, so every window is exactly the
            // 2 s the live view polls at and the rate arithmetic is the device's.
            clock = { shell.btReads * windowMs },
            onStarvationCaptured = { persisted += it },
        )

        val updates = source.updates(intervalMs = windowMs).take(6).toList()
        val captures = updates.flatMap { it.events }.filterIsInstance<LinkEvent.EncoderStarvation>()

        assertEquals("one capture per episode, not one per poll", 1, captures.size)
        assertEquals("the sink must receive exactly what the timeline got", 1, persisted.size)
        assertEquals(persisted.single(), captures.single().report)

        val report = captures.single().report
        assertEquals(49.0, report.underflowsPerSecond, 0.001)
        assertEquals(2_000L, report.windowMs)
        assertEquals(EncoderStarvationTripwire.SUSTAINED_PASSES, report.sustainedPasses)
        assertEquals("xx:xx:xx:xx:ab:cd", report.deviceAddress)
        assertEquals(2, report.forensics.effectInstances)
        assertEquals(2, report.forensics.sessionsWithEffects)
        assertEquals(listOf(8009), report.forensics.playbackSessionIds)
    }

    /**
     * The other half: a clean link polled for as long as anyone would leave the
     * panel open writes nothing at all.
     *
     * A diagnostic that fires on healthy hardware is a diagnostic that gets
     * switched off before it can ever be useful.
     */
    @Test
    fun `a clean link polled live captures nothing`() = runTest {
        val shell = StarvingShell(
            baseBt = connected(fixture("bt_manager_pixel11_ldac_txqueue.txt")),
            flinger = fixture("audio_flinger_wavelet.txt"),
            audio = fixture("audio_players_tidal.txt"),
            underflowsPerPass = 0,
        )
        val persisted = mutableListOf<EncoderStarvationReport>()
        val source = LiveLinkSource(
            shell = shell,
            clock = { shell.btReads * windowMs },
            onStarvationCaptured = { persisted += it },
        )

        val events = source.updates(intervalMs = windowMs).take(30).toList().flatMap { it.events }
        assertTrue(
            "a clean link produced ${events.filterIsInstance<LinkEvent.EncoderStarvation>().size} captures",
            events.filterIsInstance<LinkEvent.EncoderStarvation>().isEmpty(),
        )
        assertEquals(emptyList<EncoderStarvationReport>(), persisted)
    }

    /**
     * A sink that throws must cost one record, not the live view — and it must
     * not do so silently, because a capture nobody can read later is worth
     * exactly as much as no capture.
     */
    @Test
    fun `a failing sink is reported on the line rather than ending the poll loop`() = runTest {
        val shell = StarvingShell(
            baseBt = connected(fixture("bt_manager_pixel11_ldac_txqueue.txt")),
            flinger = fixture("audio_flinger_wavelet.txt"),
            audio = fixture("audio_players_tidal.txt"),
            underflowsPerPass = 98,
        )
        val source = LiveLinkSource(
            shell = shell,
            clock = { shell.btReads * windowMs },
            onStarvationCaptured = { throw IllegalStateException("database is gone") },
        )

        val updates = source.updates(intervalMs = windowMs).take(6).toList()
        val capture = updates.flatMap { it.events }
            .filterIsInstance<LinkEvent.EncoderStarvation>()
            .single()

        assertEquals("the loop must survive a broken sink", 6, updates.size)
        assertTrue(
            capture.detail,
            capture.detail.contains("could not be saved: IllegalStateException"),
        )
    }
}
