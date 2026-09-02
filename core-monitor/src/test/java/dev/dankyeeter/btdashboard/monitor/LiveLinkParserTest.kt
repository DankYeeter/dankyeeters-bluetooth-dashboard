package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpLinkDumpParser
import dev.dankyeeter.btdashboard.monitor.link.live.AudioFlingerTrackParser
import dev.dankyeeter.btdashboard.monitor.link.live.Honesty
import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.MixerOutputSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.PcmFormat
import dev.dankyeeter.btdashboard.monitor.link.live.PlayingStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned to **verbatim** captures from the Pixel 11 Pro this feature was built
 * for, not to reconstructions.
 *
 * That distinction is the whole value of the file. Every number the live view
 * shows comes out of a `dumpsys` section with no compatibility promise, and the
 * two that matter most — the LDAC quality index and the tx-queue loss counters
 * — are precisely the ones that read as a plausible zero when the parser has
 * quietly stopped finding them. A capture from the real phone is what turns
 * that into a red test instead of a dashboard that says everything is fine.
 */
class LiveLinkParserTest {

    /** JUnit4's assertNotNull returns Unit, so this is the value-carrying form. */
    private fun <T : Any> present(value: T?, what: String): T =
        requireNotNull(value) { "$what missing from the fixture" }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("dumps/$name"),
    ) { "fixture $name missing" }.bufferedReader().readText()

    private val pixel11 by lazy { fixture("bt_manager_pixel11_ldac_txqueue.txt") }
    private val flinger by lazy { fixture("audio_flinger_pixel11_threads.txt") }

    /**
     * The capture that ended the guessing: a live LDAC link with the
     * `A2DP LDAC State:` block in it, taken while music was playing and ABR had
     * settled on 396 kbps. Verbatim apart from the MAC addresses.
     */
    private val ldacState by lazy { fixture("bt_manager_pixel11_ldac_state_abr.txt") }

    /**
     * Rewrites one `label : value` row, ignoring the device's own padding.
     *
     * Line-based rather than a substring replace so that a test does not depend
     * on the exact run of spaces the phone happened to print — that is
     * formatting, and pinning it here would make a harmless build change look
     * like a parser bug.
     */
    private fun rewriteLabel(dump: String, label: String, value: String): String =
        dump.lineSequence().joinToString("\n") { line ->
            if (line.trim().startsWith(label)) line.substringBefore(':') + ": " + value else line
        }

    /**
     * Drops whole rows, which is how a build that prints fewer of them looks.
     *
     * The rows this app reads are a debugging surface with no compatibility
     * promise; older stacks print a shorter block. Removing a row from the
     * capture is the only way to test that case without inventing a dump nobody
     * recorded — the remaining lines are still the device's own.
     */
    private fun withoutLabels(dump: String, vararg labels: String): String =
        dump.lineSequence()
            .filterNot { line -> labels.any { line.trim().startsWith(it) } }
            .joinToString("\n")

    // ---- the negotiated link -------------------------------------------------

    @Test
    fun `reads the negotiated LDAC config off a real Pixel 11 dump`() {
        val codec = present(A2dpLinkDumpParser.parse(pixel11).codec, "codec")
        assertEquals(CodecFamily.LDAC, codec.family)
        assertEquals(96_000, codec.sampleRateHz)
        assertEquals(32, codec.bitsPerSample)
        assertEquals(ChannelMode.STEREO, codec.channelMode)
        // Both raw values are kept: the name is what picks a mode-signature
        // provider, the type is what labels a link no name could identify.
        assertEquals("LDAC", codec.rawCodecName)
        assertEquals(4, codec.rawCodecType)
    }

    /**
     * The finding this whole module is shaped around.
     *
     * `mCodecSpecific1` is `0` on a live LDAC link because nobody has pinned an
     * LDAC quality in Developer options — so the stack runs adaptive bitrate,
     * and the rate it picks is never printed anywhere. If a future build starts
     * reporting a real index here, this test fails and the honest "no live
     * bitrate" wording can be dropped.
     */
    @Test
    fun `an untouched phone reports no pinned LDAC quality`() {
        val codec = present(A2dpLinkDumpParser.parse(pixel11).codec, "codec")
        assertEquals(0L, codec.codecSpecific1)

        val ldac = LdacState.from(codec.codecSpecific1, codec.sampleRateHz)
        assertEquals(LdacQualityMode.NOT_PINNED, ldac.mode)
        assertTrue(ldac.mode.isAdaptive)
        assertNull("an adaptive link has no single bitrate to name", ldac.nominalKbps)
        assertTrue(ldac.note.isNotBlank())
    }

    /**
     * LDAC is missing from `codecConfigOffloading`, which is what makes the
     * tx-queue counters in the same dump real. Were LDAC offloaded, those
     * counters would be a frozen leftover reading as a perfect link.
     */
    @Test
    fun `LDAC is encoded on the host on this controller`() {
        val codec = present(A2dpLinkDumpParser.parse(pixel11).codec, "codec")
        assertFalse(codec.isOffloaded)
        assertTrue(codec.isEncodedOnHost)
    }

    @Test
    fun `a bonded but disconnected device is not reported as connected`() {
        val device = present(A2dpLinkDumpParser.parse(pixel11).device, "device")
        // "mConnectionState: STATE_DISCONNECTED, mLastConnectionState:
        // STATE_DISCONNECTING" - reading the whole line for "STATE_CONNECT"
        // gets this backwards, which is how a headphone that is switched off
        // ends up showing a live codec badge.
        assertFalse(device.isConnected)
        assertFalse(device.isPlaying)
    }

    @Test
    fun `reads a pinned LDAC quality when one is set`() {
        val codec = present(A2dpLinkDumpParser.parse(fixture("bt_manager_pixel8_ldac.txt")).codec, "codec")
        assertEquals(1001L, codec.codecSpecific1)
        val ldac = LdacState.from(codec.codecSpecific1, codec.sampleRateHz)
        assertEquals(LdacQualityMode.STANDARD, ldac.mode)
        assertEquals(660, ldac.nominalKbps)
    }

    /**
     * LDAC's rate ladder follows the sample-rate family, so the same mode is
     * 990 kbps at 96 kHz and 909 kbps at 88.2 kHz. Printing one figure for both
     * is off by 8% — small enough to look right.
     */
    @Test
    fun `nominal LDAC rates follow the sample rate family`() {
        assertEquals(990, LdacState.nominalKbps(LdacQualityMode.HIGH_QUALITY, 48_000))
        assertEquals(990, LdacState.nominalKbps(LdacQualityMode.HIGH_QUALITY, 96_000))
        assertEquals(909, LdacState.nominalKbps(LdacQualityMode.HIGH_QUALITY, 44_100))
        assertEquals(909, LdacState.nominalKbps(LdacQualityMode.HIGH_QUALITY, 88_200))
        assertEquals(330, LdacState.nominalKbps(LdacQualityMode.CONNECTION_PRIORITY, 96_000))
        assertNull(LdacState.nominalKbps(LdacQualityMode.ADAPTIVE, 96_000))
        assertNull(LdacState.nominalKbps(LdacQualityMode.NOT_PINNED, 96_000))
    }

    /**
     * A live link must not be read as a finished one because another profile
     * printed the same field name later in the dump.
     *
     * `Profile: HeadsetService`'s state machine prints `mConnectionState: 2` —
     * HFP's numeric spelling, containing no `STATE_CONNECTED` — about 850 lines
     * after the A2DP block. While the A2DP block ran to the end of the dump,
     * that line was the last one read and a connected, playing LDAC link came
     * back as disconnected. The verbatim capture is the only reason this was
     * found at all.
     */
    @Test
    fun `a later profile's state machine does not disconnect the A2DP link`() {
        val device = present(A2dpLinkDumpParser.parse(ldacState).device, "device")
        assertTrue("the capture really does contain the decoy", ldacState.contains("mConnectionState: 2"))
        assertTrue(device.isConnected)
        assertTrue(device.isPlaying)
    }

    // ---- the LDAC encoder's own state ----------------------------------------

    /**
     * The section this whole rebuild rests on, read off the verbatim capture.
     *
     * Every value here is one the app used to claim was unknowable. If a build
     * ever stops printing them this test goes red, which is the point: the panel
     * must fall back to saying so rather than keep showing the last figure it
     * happened to have.
     */
    @Test
    fun `reads the live LDAC bitrate off a real Pixel 11 dump`() {
        val stack = present(A2dpLinkDumpParser.parse(ldacState).ldacStack, "LDAC state")
        assertEquals("ABR", stack.qualityMode)
        assertEquals(396, stack.transmissionKbps)
        assertEquals(883, stack.effectiveMtu)
        assertEquals(0, stack.savedTxQueueLength)
        assertEquals(true, stack.isAdaptive)
    }

    /**
     * The whole chain on the same capture: an unpinned link (`mCodecSpecific1`
     * is 0, so the *configuration* is adaptive) whose *rate* is nonetheless a
     * measurement. That combination is exactly what the old code could not say.
     */
    @Test
    fun `an unpinned link now reports a measured rate rather than a refusal`() {
        val parsed = A2dpLinkDumpParser.parse(ldacState)
        val codec = present(parsed.codec, "codec")
        assertEquals(0L, codec.codecSpecific1)

        val ldac = LdacState.from(codec.codecSpecific1, codec.sampleRateHz, parsed.ldacStack)
        assertEquals(LdacQualityMode.NOT_PINNED, ldac.mode)
        assertTrue(ldac.isAdaptive)
        assertEquals(396, ldac.measuredKbps)
        assertEquals(Honesty.MEASURED, ldac.liveBitrateHonesty)
        assertNull("adaptive still has no single spec figure to name", ldac.nominalKbps)
        assertFalse(
            "the note must not still claim the rate is unreadable",
            ldac.note.contains("cannot be read"),
        )
    }

    /**
     * D-11: the two `LDAC adaptive bit rate` rows, which this parser used to
     * walk past.
     *
     * They are the figures all three measurement runs were evaluated on
     * (`docs/perf/T-007-aufnahme.md`, `T-008-experimente.md`,
     * `T-011-messung.md`), and the adjustments counter is the only one that
     * reports a rung change that fell between two polls. Without it every change
     * rate computed downstream undercounts, silently and by an unknown amount.
     */
    @Test
    fun `reads the adaptive bitrate rung and its change count off a real Pixel 11 dump`() {
        val stack = present(A2dpLinkDumpParser.parse(ldacState).ldacStack, "LDAC state")
        assertEquals(4, stack.adaptiveBitrateIndex)
        assertEquals(3L, stack.adaptiveBitrateAdjustments)
        // The rung is the stack's own numbering and is never mapped to a rate:
        // this capture pairs index 4 with 396 kbps, while T-007 measured index 1
        // at 660 and index 3 at 492.
        assertEquals(396, stack.transmissionKbps)
    }

    /**
     * A build that does not print these rows must read as absent. Zero is a
     * count the encoder can genuinely have, so the two cases cannot share a
     * value.
     */
    @Test
    fun `a build without the adaptive bitrate rows reports absence and not zero`() {
        val older = withoutLabels(
            ldacState,
            "LDAC adaptive bit rate encode quality mode index",
            "LDAC adaptive bit rate adjustments",
        )
        val stack = present(A2dpLinkDumpParser.parse(older).ldacStack, "LDAC state")
        assertNull("a row that is not printed is not a rung of 0", stack.adaptiveBitrateIndex)
        assertNull("a row that is not printed is not a count of 0", stack.adaptiveBitrateAdjustments)
        // The rest of the block is unaffected: a shorter block is still a block.
        assertEquals(396, stack.transmissionKbps)
        assertEquals("ABR", stack.qualityMode)
    }

    /** The other half of the same distinction: a printed zero is a measurement. */
    @Test
    fun `a printed zero in the adaptive bitrate rows is a value and not absence`() {
        val untouched = rewriteLabel(ldacState, "LDAC adaptive bit rate adjustments", "0")
            .let { rewriteLabel(it, "LDAC adaptive bit rate encode quality mode index", "0") }
        val stack = present(A2dpLinkDumpParser.parse(untouched).ldacStack, "LDAC state")
        assertEquals(0, stack.adaptiveBitrateIndex)
        assertEquals(0L, stack.adaptiveBitrateAdjustments)
    }

    /**
     * The limit of that distinction, pinned so it is not read as wider than it
     * is: a printed value this cannot read comes back as null as well, and
     * nothing downstream can tell it from a row that was never printed.
     *
     * Pinned rather than closed. Separating the two needs a state that says
     * "printed, unreadable", and there is no reader that would do anything
     * different with it — inventing one ahead of its consumer would be a guess
     * about how it is meant to be used.
     */
    @Test
    fun `a value too large to read comes back as null just like an absent row`() {
        val huge = rewriteLabel(
            ldacState,
            "LDAC adaptive bit rate encode quality mode index",
            "99999999999",
        ).let { rewriteLabel(it, "LDAC adaptive bit rate adjustments", "99999999999999999999") }

        val stack = present(A2dpLinkDumpParser.parse(huge).ldacStack, "LDAC state")

        assertNull("a value out of Int range is not a rung", stack.adaptiveBitrateIndex)
        assertNull("a value out of Long range is not a count", stack.adaptiveBitrateAdjustments)
        // The rest of the block still reads, so this is the value's doing and
        // not a block that fell over.
        assertEquals(396, stack.transmissionKbps)
        assertEquals("ABR", stack.qualityMode)
    }

    /**
     * A reading of 990 kbps is a reading, not an event.
     *
     * The adaptive controller steers onto that rung by itself and leaves it
     * again inside a single sample — 31 times in one 39-minute run, 30 of them
     * for exactly one sample, none of them with any loss
     * (`docs/perf/T-011-messung.md`). Anything in this layer that treated the
     * number as special would have produced 31 false alarms in that run.
     *
     * What this can show is exactly that much: the parser does not branch on
     * the value, so the whole reading differs in the one rewritten field and in
     * no other. Where 990 does change an outcome is a layer up, in
     * `MeasuredBitrateTracker`, whose HIGH boundary sits between 660 and 990 —
     * that is not this test's subject, and the title no longer suggests it is.
     */
    @Test
    fun `the parser does not branch on a reading of 990 kbps`() {
        val base = present(A2dpLinkDumpParser.parse(ldacState).ldacStack, "LDAC state")
        val at990 = rewriteLabel(ldacState, "LDAC transmission bitrate (Kbps)", "990")

        val stack = present(A2dpLinkDumpParser.parse(at990).ldacStack, "LDAC state")

        assertEquals(base.copy(transmissionKbps = 990), stack)
    }

    /**
     * `Effective MTU:` is printed by **every** codec's state block, and the six
     * codecs that are not negotiated all print `0`. A whole-dump scan for the
     * label would therefore report whichever one it reached first.
     */
    @Test
    fun `the MTU comes from the LDAC block and not from an idle codec's`() {
        val stack = present(A2dpLinkDumpParser.parse(ldacState).ldacStack, "LDAC state")
        assertEquals(883, stack.effectiveMtu)
        // The same dump has AptX-HD, AptX, AAC and SBC blocks printing 0.
        assertTrue("the capture must still contain the decoy blocks", ldacState.contains("A2DP AptX-HD State:"))
    }

    /**
     * A build without the section must produce absence, not a zero. The older
     * captures are exactly that case, which is why they are kept.
     */
    @Test
    fun `a dump without the LDAC state section reports absence and says why`() {
        val parsed = A2dpLinkDumpParser.parse(pixel11)
        assertNull(parsed.ldacStack)
        assertTrue(parsed.warnings.any { it.contains("A2DP LDAC State") })

        val codec = present(parsed.codec, "codec")
        val ldac = LdacState.from(codec.codecSpecific1, codec.sampleRateHz, parsed.ldacStack)
        assertNull(ldac.measuredKbps)
        assertEquals(Honesty.UNAVAILABLE, ldac.liveBitrateHonesty)
    }

    /**
     * The mode token is data, not an enum. A stack that prints something this
     * app has never seen must pass it through, and must not be forced into
     * "adaptive" or "pinned" on the strength of not matching.
     */
    @Test
    fun `an unknown quality mode token is carried through rather than decided`() {
        val odd = rewriteLabel(ldacState, "LDAC quality mode", "TURBO")
        val stack = present(A2dpLinkDumpParser.parse(odd).ldacStack, "LDAC state")
        assertEquals("TURBO", stack.qualityMode)
        assertNull("an unrecognised token decides nothing", stack.isAdaptive)
        // The rate is still a measurement — the token being strange says nothing
        // about the number beside it.
        assertEquals(396, stack.transmissionKbps)
    }

    /**
     * `Config: Invalid` is the stack's own way of saying this codec is not the
     * one running. Its zeros are not a link at 0 kbps.
     */
    @Test
    fun `an invalid LDAC config is absence rather than a link at zero`() {
        val idle = ldacState
            .replace("Config: Rate=96000 Bits=32 Mode=STEREO", "Config: Invalid")
        assertNull(A2dpLinkDumpParser.parse(idle).ldacStack)
    }

    /** An LDAC block beside a non-LDAC link describes a codec that is not running. */
    @Test
    fun `the LDAC block is not attached to a link negotiated on another codec`() {
        val sbc = ldacState.lineSequence().joinToString("\n") { line ->
            if (line.trim().startsWith("mCodecConfig")) {
                line.replace("codecName:LDAC,mCodecType:4", "codecName:SBC,mCodecType:0")
            } else {
                line
            }
        }
        val parsed = A2dpLinkDumpParser.parse(sbc)
        assertEquals(CodecFamily.SBC, parsed.codec?.family)
        assertNull(parsed.ldacStack)
    }

    // ---- btif_a2dp_source media statistics -----------------------------------

    @Test
    fun `reads every tx queue counter off the real A2DP State block`() {
        val tx = present(A2dpLinkDumpParser.parse(pixel11).tx, "tx stats")
        assertEquals(389_197L, tx.enqueueCount)
        assertEquals(854_736L, tx.dequeueCount)
        assertEquals(1_240_579L, tx.readBufCount)
        assertEquals(4_693_895L, tx.framesPerPacketTotal)
        assertEquals(12, tx.framesPerPacketMax)
        assertEquals(12, tx.framesPerPacketAvg)
        assertEquals(1L, tx.flushedCount)
        assertEquals(0L, tx.droppedCount)
        assertEquals(0L, tx.dropoutCount)
        assertEquals(337_189L, tx.enqueueOverdue)
        assertEquals(51_842L, tx.enqueuePremature)
    }

    /**
     * Three subsystems print `Counts (underflow)` in one dump — A2DP, the
     * Hearing Aid audio HAL and the LE Audio HAL client — and on a phone with
     * neither hearing aid nor LE Audio connected the other two are zero. A
     * whole-dump search for the label therefore finds 788, 0 and 0 in an order
     * nothing guarantees, and the answer it settles on is a coin flip.
     */
    @Test
    fun `the A2DP underflow counter is not overwritten by the other HALs`() {
        val tx = present(A2dpLinkDumpParser.parse(pixel11).tx, "tx stats")
        assertEquals(788L, tx.underflowCount)
        assertEquals(806_912L, tx.underflowBytes)
    }

    @Test
    fun `a dump without an A2DP State block says so instead of inventing zeros`() {
        val parsed = A2dpLinkDumpParser.parse(fixture("bt_manager_pixel8_ldac.txt"))
        assertNull(parsed.tx)
        assertTrue(parsed.warnings.any { it.contains("A2DP State") })
    }

    @Test
    fun `unreadable input degrades rather than throwing`() {
        assertTrue(A2dpLinkDumpParser.parse("").warnings.isNotEmpty())
        assertNull(A2dpLinkDumpParser.parse("permission denied").tx)
        assertEquals(emptyList<Any>(), AudioFlingerTrackParser.parse("").threads)
        assertEquals(emptyList<Any>(), PlayingStreamParser.playingStreams(""))
    }

    // ---- AudioFlinger --------------------------------------------------------

    @Test
    fun `reads output threads with their route and format`() {
        val threads = AudioFlingerTrackParser.parse(flinger).threads
        assertEquals(listOf("AudioOut_D", "AudioOut_15"), threads.map { it.output.threadName })

        val primary = threads.first().output
        assertEquals(48_000, primary.sampleRateHz)
        assertEquals(2, primary.channelCount)
        assertEquals(PcmFormat.PCM_FLOAT, primary.halFormat)
        assertEquals(0x400000, primary.outputDeviceMask)
        assertTrue(primary.isInStandby)
        assertFalse("the speaker-safe route is not Bluetooth", primary.isBluetoothRoute)
    }

    /**
     * SCO is the call path — mono, 8 or 16 kHz, and nothing to do with the A2DP
     * link this screen is about. It prints `BLUETOOTH` in its device name and
     * sits two bits below A2DP in the mask, so a loose match latches the whole
     * live view onto the wrong thread as soon as a call starts.
     */
    @Test
    fun `the SCO call route is not mistaken for the media link`() {
        val sco = MixerOutputSnapshot(
            threadName = "AudioOut_SCO",
            outputDeviceMask = 0x10,
            outputDeviceNames = "AUDIO_DEVICE_OUT_BLUETOOTH_SCO",
            sampleRateHz = 16_000,
            channelCount = 1,
            halFormat = PcmFormat.PCM_16_BIT,
            isInStandby = false,
        )
        assertFalse(sco.isBluetoothRoute)
        assertTrue(
            sco.copy(
                outputDeviceMask = 0x80,
                outputDeviceNames = "AUDIO_DEVICE_OUT_BLUETOOTH_A2DP",
            ).isBluetoothRoute,
        )
    }

    /**
     * `Bluetooth latency modes are enabled` is printed unindented in the middle
     * of every playback thread, immediately before the underrun counters and
     * the track table. Treating column 0 as the end of a block therefore drops
     * exactly the two things this parser exists for.
     */
    @Test
    fun `an unindented stray line does not truncate the thread block`() {
        val primary = AudioFlingerTrackParser.parse(flinger).threads.first().output
        assertEquals(4L, primary.fastMixerUnderruns)
        assertEquals(0L, primary.normalMixerPartialUnderruns)
        assertEquals(0L, primary.normalMixerEmptyUnderruns)
    }

    /**
     * A thread's `Local log:` replays historic track rows in the identical
     * format after the live table has ended. Counting them would report tracks
     * on a thread that has been in standby for hours.
     */
    @Test
    fun `historic rows in the thread event log are not live tracks`() {
        val threads = AudioFlingerTrackParser.parse(flinger).threads
        assertTrue(threads.all { it.tracks.isEmpty() })
    }

    /**
     * The row parser, against rows AudioFlinger printed itself.
     *
     * Fixed-width slicing fails on these: `Client(pid/uid)` contains a space
     * (`9137/  10360`) and so does the trailing latency (`189.01 k`), so every
     * field after them shifts. The regexes anchor on content instead.
     */
    @Test
    fun `reads pid uid format and underruns out of a real track row`() {
        val row = "   08-26 11:48:24.584 removeTrack_l (0xb40000755a81af58)         591     " +
            "no    9137/  10360    2473    1240 T  0x600 00000001 00000003  48000  3   1  0 " +
            "  -33     0     0     0          -33      false 00009600  12000       0 f      " +
            "   0    12000      false        false  189.01 k"
        val track = present(AudioFlingerTrackParser.parseTrackRow(row), "track row")
        assertEquals(9137, track.pid)
        assertEquals(10360, track.uid)
        assertEquals(2473, track.sessionId)
        assertEquals(1240, track.portId)
        assertEquals(48_000, track.sampleRateHz)
        assertEquals(PcmFormat.PCM_16_BIT, track.format)
        assertEquals(0x3, track.channelMask)
        assertEquals(0L, track.underruns)
        assertEquals(12_000L, track.flushed)
    }

    @Test
    fun `a row whose port is muted still yields its counters`() {
        // The PortMuted column is the anchor for the tail, so `true` there must
        // parse exactly like `false` - it flipped on the very first real row.
        val row = "   08-26 09:41:47.274 removeTrack_l (0xb40000755a813698)         354     " +
            "no    4187/  10378    1177     757 T  0x600 00000001 00000001  44100  5   5  0 " +
            " -inf     0     0     0           -6       true 000006FA  22050       0 f      " +
            "   0        0      false        false  388.90 k"
        val track = present(AudioFlingerTrackParser.parseTrackRow(row), "track row")
        assertEquals(4187, track.pid)
        assertEquals(44_100, track.sampleRateHz)
        assertEquals(1, track.channelMask)
        assertEquals(0L, track.underruns)
    }

    @Test
    fun `a line that is not a track row is not mistaken for one`() {
        assertNull(
            AudioFlingerTrackParser.parseTrackRow(
                "   08-26 11:48:26.866 CFG_EVENT_CREATE_AUDIO_PATCH: old device 0x2 " +
                    "(AUDIO_DEVICE_OUT_SPEAKER) new device 0x2 (AUDIO_DEVICE_OUT_SPEAKER)",
            ),
        )
        assertNull(AudioFlingerTrackParser.parseTrackRow("      0    yes  920       0     0    full  2400    3881760"))
    }

    // ---- the input side ------------------------------------------------------

    @Test
    fun `reads what the playing app is feeding in`() {
        val streams = PlayingStreamParser.playingStreams(fixture("audio_players_tidal.txt"))
        assertEquals(1, streams.size)
        val tidal = streams.single()
        // "u/pid:10400/13838" is uid then pid - the opposite order to
        // AudioFlinger's "Client(pid/uid)", which is a good way to join the
        // wrong column silently.
        assertEquals(10400, tidal.uid)
        assertEquals(13838, tidal.pid)
        assertEquals(8009, tidal.sessionId)
        assertEquals(44_100, tidal.sampleRateHz)
        assertEquals(2, tidal.channelCount)
        assertEquals("USAGE_MEDIA", tidal.usage)
        assertEquals("CONTENT_TYPE_MUSIC", tidal.contentType)
        assertFalse(tidal.isSpatialized)
    }

    @Test
    fun `a paused player is not an input`() {
        val streams = PlayingStreamParser.playingStreams(fixture("audio_players_tidal.txt"))
        assertTrue("Spotify sat paused on session 8137", streams.none { it.sessionId == 8137 })
    }

    /**
     * SoundPool entries report `sampleRate=0`, which is the framework saying it
     * never had one rather than a track running at zero hertz. Showing "0 Hz"
     * in the input column would be a number where there is no measurement.
     */
    @Test
    fun `a missing sample rate reads as unknown and not as zero`() {
        val line = "  AudioPlaybackConfiguration piid:55 deviceIds:[] type:android.media.SoundPool " +
            "u/pid:1000/1449 state:started attr:AudioAttributes: usage=USAGE_NOTIFICATION " +
            "content=CONTENT_TYPE_SONIFICATION tags= bundle=null sessionId:0 mutedState:none " +
            "FormatInfo{isSpatialized=false, channelMask=0x0, sampleRate=0}"
        val stream = PlayingStreamParser.playingStreams(line).single()
        assertNull(stream.sampleRateHz)
        assertNull(stream.channelCount)
    }
}
