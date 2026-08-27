package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpLinkDumpParser
import dev.dankyeeter.btdashboard.monitor.link.live.BitrateStep
import dev.dankyeeter.btdashboard.monitor.link.live.BitrateStepReason
import dev.dankyeeter.btdashboard.monitor.link.live.MeasuredBitrateTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Properties that must hold for **every** input sequence, rather than for the
 * few sequences somebody thought to write down.
 *
 * ## The two properties, and why they are worth a file
 *
 * *The bitrate tracker never reports an unsettled level.* Its whole reason for
 * existing is that ABR pendulums between 492 and 660 kbps on a healthy link, so
 * a tracker that leaks one unsettled reading puts a line on the timeline every
 * few seconds and buries the connects and dropouts the timeline is for. An
 * example test proves it for the pendulum somebody typed; this proves it for
 * oscillation, drift ramps, codec flaps, observation gaps and a long
 * deterministic walk, by re-deriving the settling rule from the input rather
 * than from the implementation.
 *
 * *A measured bitrate belongs to the codec that is running.* `dumpsys` prints an
 * `A2DP <codec> State:` block for every codec the phone supports, whether or not
 * it is the negotiated one, so an LDAC block sits in the dump of an AAC link
 * too. Reporting its bitrate would be a live-looking number for an encoder that
 * is not encoding anything. The gate is stated here from both sides.
 */
class LiveLinkPropertyTest {

    // ---- the bitrate tracker -------------------------------------------------

    /** One reading, as the poll loop hands it over. */
    private data class Reading(val kbps: Int?, val sampleRateHz: Int?)

    private fun readings(vararg kbps: Int?, sampleRateHz: Int? = 48_000): List<Reading> =
        kbps.map { Reading(it, sampleRateHz) }

    /** A run: which reading index produced which step. */
    private fun drive(
        sequence: List<Reading>,
        tracker: MeasuredBitrateTracker = MeasuredBitrateTracker(),
    ): List<Pair<Int, BitrateStep>> = sequence.mapIndexedNotNull { index, reading ->
        tracker.onReading(
            timestampMs = 1_000L * index,
            kbps = reading.kbps,
            sampleRateHz = reading.sampleRateHz,
        )?.let { index to it }
    }

    /**
     * The settling rule, checked against the input instead of the state.
     *
     * A step at index `i` is only legitimate if the readings at `i`, `i-1` and
     * `i-2` were all real (non-null, positive) and all sat within
     * [MeasuredBitrateTracker.LEVEL_TOLERANCE_KBPS] of a common anchor — which
     * bounds the spread of the three at twice the tolerance. That is the weakest
     * statement that still forbids the failure mode: reporting a level the link
     * never held.
     */
    private fun assertSettled(what: String, sequence: List<Reading>, steps: List<Pair<Int, BitrateStep>>) {
        val tolerance = MeasuredBitrateTracker.LEVEL_TOLERANCE_KBPS
        var previousReported: Int? = null
        steps.forEachIndexed { ordinal, (index, step) ->
            val at = "$what: step #$ordinal at reading $index"
            assertTrue(
                "$at was reported before ${MeasuredBitrateTracker.SUSTAIN_READINGS} readings existed",
                index >= MeasuredBitrateTracker.SUSTAIN_READINGS - 1,
            )
            val window = (index - MeasuredBitrateTracker.SUSTAIN_READINGS + 1..index)
                .map { sequence[it].kbps }
            assertTrue(
                "$at settled on a window containing an unreadable poll: $window",
                window.all { it != null && it > 0 },
            )
            val values = window.filterNotNull()
            assertTrue(
                "$at settled on a window spanning ${values.max() - values.min()} kbps: $window",
                values.max() - values.min() <= 2 * tolerance,
            )
            assertEquals("$at did not report the newest reading", sequence[index].kbps, step.toKbps)
            assertEquals("$at has the wrong baseline", previousReported, step.fromKbps)
            assertEquals(
                "$at is${if (ordinal == 0) " not" else ""} the first step but its reason disagrees",
                ordinal == 0,
                step.reason == BitrateStepReason.FIRST_READING,
            )
            assertEquals("$at has the wrong timestamp", 1_000L * index, step.timestampMs)
            previousReported = step.toKbps
        }
    }

    @Test
    fun `a pendulum between two ABR levels never settles`() {
        // The measured 492-660 swing on a clean link. 168 kbps apart, which is
        // more than a genuine ladder step, and it must still produce nothing.
        val sequence = (0 until 20).map { Reading(if (it % 2 == 0) 492 else 660, 48_000) }
        val steps = drive(sequence)
        assertSettled("pendulum", sequence, steps)
        assertEquals("a pendulum produced timeline events", emptyList<Any>(), steps.map { it.second })
    }

    @Test
    fun `a pendulum that pauses on one level reports exactly that level`() {
        val sequence = readings(492, 660, 492, 660, 396, 396, 396, 660, 492, 660)
        val steps = drive(sequence)
        assertSettled("pendulum with a plateau", sequence, steps)
        assertEquals(1, steps.size)
        assertEquals(396, steps.single().second.toKbps)
        assertEquals(BitrateStepReason.FIRST_READING, steps.single().second.reason)
    }

    @Test
    fun `a drift ramp steeper than the tolerance never settles`() {
        // +5 kbps per poll: the second reading is inside the tolerance and the
        // third is not, so the run restarts forever and reaches three never.
        val sequence = (0 until 40).map { Reading(330 + it * 5, 48_000) }
        val steps = drive(sequence)
        assertSettled("steep drift", sequence, steps)
        assertEquals("a steep drift produced timeline events", emptyList<Any>(), steps.map { it.second })
    }

    @Test
    fun `a drift ramp inside the tolerance still obeys the settling rule`() {
        // +3 kbps per poll does settle, repeatedly. The point is not how many
        // steps come out but that no step ever ratchets the baseline: the
        // significance gate has to keep it where it is.
        val sequence = (0 until 60).map { Reading(330 + it * 3, 48_000) }
        val steps = drive(sequence)
        assertSettled("shallow drift", sequence, steps)
        steps.zipWithNext { (_, a), (_, b) ->
            assertTrue(
                "a drift moved the baseline by ${b.toKbps - (b.fromKbps ?: 0)} kbps, " +
                    "which is under the ${MeasuredBitrateTracker.MIN_STEP_KBPS} kbps floor",
                b.reason != BitrateStepReason.LARGE_STEP ||
                    kotlin.math.abs(b.toKbps - a.toKbps) >= MeasuredBitrateTracker.MIN_STEP_KBPS,
            )
        }
    }

    @Test
    fun `a sample-rate flap on its own is not a bitrate step`() {
        // 470 kbps lands in a different quality class on the 44.1 ladder than on
        // the 48 one. A codec renegotiation that changes only the sample rate
        // must not therefore manufacture a "the bitrate moved" event.
        val sequence = listOf(48_000, 48_000, 48_000, 44_100, 44_100, 44_100, 48_000, 48_000)
            .map { Reading(470, it) }
        val steps = drive(sequence)
        assertSettled("sample-rate flap", sequence, steps)
        assertEquals("a sample-rate flap produced extra steps", 1, steps.size)
        assertEquals(BitrateStepReason.FIRST_READING, steps.single().second.reason)
    }

    @Test
    fun `unreadable polls interrupt a run instead of completing one`() {
        val sequence = readings(400, null, 400, null, 400, null, 400)
        val steps = drive(sequence)
        assertSettled("observation gaps", sequence, steps)
        assertEquals("a gapped run reported a level", emptyList<Any>(), steps.map { it.second })
    }

    @Test
    fun `zero and negative readings never count towards a level`() {
        // A zero here is the stack saying "nothing is flowing", not a bitrate.
        val sequence = readings(400, 400, 0, 400, 400, -5, 400, 400, 0, 400)
        val steps = drive(sequence)
        assertSettled("zeroed polls", sequence, steps)
        assertEquals("a zeroed run reported a level", emptyList<Any>(), steps.map { it.second })
    }

    @Test
    fun `a steady level is reported once and only once`() {
        val sequence = readings(*Array<Int?>(12) { 400 })
        val steps = drive(sequence)
        assertSettled("steady", sequence, steps)
        assertEquals(1, steps.size)
        assertNull("the first step of a link has no baseline", steps.single().second.fromKbps)
        assertEquals(400, steps.single().second.toKbps)
    }

    @Test
    fun `a genuine move across the ladder is reported with its reason`() {
        val sequence = readings(330, 330, 330, 990, 990, 990)
        val steps = drive(sequence)
        assertSettled("ladder move", sequence, steps)
        assertEquals(2, steps.size)
        assertEquals(BitrateStepReason.QUALITY_CLASS, steps[1].second.reason)
        assertEquals(330, steps[1].second.fromKbps)
        assertEquals(990, steps[1].second.toKbps)
        assertTrue("a move up must not read as a fall", !steps[1].second.fell)
    }

    @Test
    fun `a settled but insignificant level does not ratchet the baseline`() {
        // 400 to 450: same class, 50 kbps, half the floor. Settled, and still
        // not worth a line — otherwise a slow drift walks the baseline across a
        // class boundary one meaningless step at a time.
        val sequence = readings(400, 400, 400, 450, 450, 450, 452, 452, 452)
        val steps = drive(sequence)
        assertSettled("insignificant move", sequence, steps)
        assertEquals(1, steps.size)
        assertEquals(400, steps.single().second.toKbps)
    }

    @Test
    fun `a large move inside one class is reported as a large step`() {
        val sequence = readings(350, 350, 350, 460, 460, 460)
        val steps = drive(sequence)
        assertSettled("large same-class move", sequence, steps)
        assertEquals(2, steps.size)
        assertEquals(BitrateStepReason.LARGE_STEP, steps[1].second.reason)
    }

    @Test
    fun `reset makes the next settled level a first reading again`() {
        val tracker = MeasuredBitrateTracker()
        val first = readings(*Array<Int?>(4) { 400 })
        val stepsBefore = drive(first, tracker)
        assertEquals(1, stepsBefore.size)
        assertEquals(400, tracker.lastReportedKbps)

        tracker.reset()
        assertNull("reset kept the baseline", tracker.lastReportedKbps)

        val stepsAfter = drive(first, tracker)
        assertEquals(1, stepsAfter.size)
        assertEquals(BitrateStepReason.FIRST_READING, stepsAfter.single().second.reason)
        assertNull(stepsAfter.single().second.fromKbps)
    }

    /**
     * A long walk over the levels the device actually produced, plus the gaps.
     *
     * Deterministic on purpose — a fixed cycle stepped at four different strides
     * rather than a seeded generator — so a failure is one named sequence that
     * reproduces on every machine and every run.
     */
    @Test
    fun `the settling rule survives long adversarial walks`() {
        val levels = listOf(330, 396, 396, 492, 660, 660, 990, 0, 492, 492, 492, 396)
        listOf(1, 2, 5, 7).forEach { stride ->
            val sequence = (0 until 200).map { i ->
                val level = levels[(i * stride) % levels.size]
                Reading(
                    kbps = if (i % 23 == 22) null else level,
                    sampleRateHz = if (i % 31 == 0) 44_100 else 48_000,
                )
            }
            assertSettled("walk stride $stride", sequence, drive(sequence))
        }
    }

    // ---- the LDAC block belongs to the LDAC link -----------------------------

    /**
     * A dump with one connected device and, optionally, the stack's own LDAC
     * block. Shaped after the Pixel 11 Pro capture in `dumps/`, cut to the lines
     * the parser reads.
     */
    private fun dumpWith(codecLine: String, ldacBlock: String = ""): String = """
        |Bluetooth Status
        |  enabled: true
        |
        |Profile: A2dpService
        |  active_a2dp_devices: [xx:xx:xx:xx:ab:cd]
        |  === A2dpStateMachine for xx:xx:xx:xx:ab:cd (Active) ===
        |    mConnectionState: STATE_CONNECTED, mLastConnectionState: STATE_CONNECTING
        |    mIsPlaying: true
        |    $codecLine
        |
        |A2DP State:
        |  Counts (enqueue/dequeue/readbuf)                        : 51423 / 51423 / 51423
        |  Counts (underflow)                                      : 2
        |$ldacBlock
    """.trimMargin()

    private val ldacBlock = """
        |A2DP LDAC State:
        |  Config: Rate=96000 Bits=32 Mode=STEREO
        |  LDAC quality mode                                       : ABR
        |  LDAC transmission bitrate (Kbps)                        : 396
        |  LDAC saved transmit queue length                        : 0
    """.trimMargin()

    private val ldacConfig =
        "mCodecConfig: {codecName:LDAC,mCodecType:4,mSampleRate:0x8(96000)," +
            "mBitsPerSample:0x4(32),mChannelMode:0x2(STEREO),mCodecSpecific1:0}"

    private val aacConfig =
        "mCodecConfig: {codecName:AAC,mCodecType:1,mSampleRate:0x1(44100)," +
            "mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0}"

    @Test
    fun `an LDAC bitrate is only reported for an LDAC link`() {
        val onLdac = A2dpLinkDumpParser.parse(dumpWith(ldacConfig, ldacBlock))
        assertEquals(CodecFamily.LDAC, onLdac.codec?.family)
        assertNotNull("the LDAC block was not read on an LDAC link", onLdac.ldacStack)
        assertEquals(396, onLdac.ldacStack?.transmissionKbps)

        // The same block, verbatim, beside an AAC link. Every codec keeps a
        // state block; only the negotiated one describes what is playing.
        val onAac = A2dpLinkDumpParser.parse(dumpWith(aacConfig, ldacBlock))
        assertEquals(CodecFamily.AAC, onAac.codec?.family)
        assertNull("an LDAC bitrate was reported for an AAC link", onAac.ldacStack)
    }

    @Test
    fun `an invalid or zeroed LDAC block reads as absent, not as zero`() {
        val invalid = ldacBlock.replace("Config: Rate=96000 Bits=32 Mode=STEREO", "Config: Invalid")
        assertNull(
            "a block the stack marked Invalid was carried as a bitrate",
            A2dpLinkDumpParser.parse(dumpWith(ldacConfig, invalid)).ldacStack,
        )

        val zeroed = ldacBlock.replace(": 396", ": 0")
        val parsed = A2dpLinkDumpParser.parse(dumpWith(ldacConfig, zeroed)).ldacStack
        assertNotNull("the block itself went missing", parsed)
        assertNull("a bitrate of zero was carried as a rate", parsed?.transmissionKbps)
    }

    /**
     * The boundary the 850-line overrun broke, stated directly.
     *
     * `Profile: HeadsetService` prints its own `mConnectionState: 2` — HFP's
     * numeric spelling, which contains no `STATE_CONNECTED` — and reading past
     * the A2DP block into it turned a live link into "last session".
     */
    @Test
    fun `a following profile cannot overwrite the A2DP link state`() {
        val withHeadset = dumpWith(ldacConfig, ldacBlock) + "\n" + """
            |Profile: HeadsetService
            |  === HeadsetStateMachine for xx:xx:xx:xx:ab:cd ===
            |    mConnectionState: 2
            |    mIsPlaying: false
        """.trimMargin()
        val parsed = A2dpLinkDumpParser.parse(withHeadset)
        assertEquals(true, parsed.device?.isConnected)
        assertEquals(true, parsed.device?.isPlaying)
        assertEquals(CodecFamily.LDAC, parsed.codec?.family)
    }
}
