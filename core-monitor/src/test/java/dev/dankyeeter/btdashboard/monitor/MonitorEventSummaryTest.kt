package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.EventLayer
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventSummary
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a list line is allowed to be.
 *
 * The event log's whole redesign rests on one property: the line in the list is
 * short, plain and derived from the typed fields, and the machinery's own
 * sentence only ever appears one tap down. These pin that property from the
 * wording side — the rendering side is `MonitorEventLogTest` in `:app`.
 */
class MonitorEventSummaryTest {

    private fun event(
        type: MonitorEventType,
        detail: String = "detail",
        deviceName: String? = "Encore",
        codec: CodecFamily? = null,
        bitrateKbps: Int? = null,
        timestampMs: Long = 1_000L,
    ) = MonitorEvent(
        timestampMs = timestampMs,
        deviceAddress = "AC:DE:48:00:37:8F",
        deviceName = deviceName,
        type = type,
        detail = detail,
        codec = codec,
        bitrateKbps = bitrateKbps,
    )

    /**
     * The guard the redesign is worth nothing without.
     *
     * Every type, with the most hostile inputs a real device can produce: a
     * headphone whose Bluetooth name is a marketing paragraph, a four-digit
     * bitrate, and a detail string long enough to wrap three times. None of it
     * may reach a line.
     */
    @Test
    fun `every event type produces a line inside the bound`() {
        val hostileName = "Noble FoKus Prestige Encore Limited Edition Mk II"
        MonitorEventType.entries.forEach { type ->
            val summary = MonitorEventSummary.of(
                event(
                    type = type,
                    detail = "x".repeat(400),
                    deviceName = hostileName,
                    codec = CodecFamily.APTX_ADAPTIVE,
                    bitrateKbps = 1_234,
                ),
                previousBitrateKbps = 9_876,
            )
            assertTrue(
                "$type produced ${summary.length} chars: $summary",
                summary.length <= MonitorEventSummary.MAX_CHARS,
            )
            assertTrue("$type produced an empty line", summary.isNotBlank())
        }
    }

    /**
     * The rule that keeps the two layers apart. A line that quoted its event's
     * sentence would be the old log with a character limit on it.
     */
    @Test
    fun `no line is built out of the detail sentence`() {
        MonitorEventType.entries.forEach { type ->
            val summary = MonitorEventSummary.of(
                event(type, detail = "btif_a2dp_source: enqueue underflow count 12"),
            )
            assertFalse(
                "$type leaked the parser's own words: $summary",
                summary.contains("btif") || summary.contains("enqueue"),
            )
        }
    }

    @Test
    fun `a connect names the device and a nameless drop says what happened`() {
        assertEquals(
            "Encore connected",
            MonitorEventSummary.of(event(MonitorEventType.ACL_CONNECTED)),
        )
        assertEquals(
            "Connection lost",
            MonitorEventSummary.of(
                event(MonitorEventType.ACL_DISCONNECTED, deviceName = null),
            ),
        )
    }

    /** A blank name is not a name. It used to print " connected". */
    @Test
    fun `a blank device name is treated as no name`() {
        assertEquals(
            "Connected",
            MonitorEventSummary.of(event(MonitorEventType.ACL_CONNECTED, deviceName = "   ")),
        )
    }

    @Test
    fun `a codec change is the codec, not a sentence about one`() {
        assertEquals(
            "Codec: LDAC",
            MonitorEventSummary.of(
                event(MonitorEventType.CODEC_CHANGED, codec = CodecFamily.LDAC),
            ),
        )
    }

    /**
     * The line the owner asked for by name. The "from" side is not stored on the
     * row — it is reconstructed from the previous rate this same log reported.
     */
    @Test
    fun `a bitrate step reads as a step`() {
        val log = listOf(
            event(MonitorEventType.BITRATE_MODE_CHANGED, codec = CodecFamily.LDAC, bitrateKbps = 660),
            event(
                MonitorEventType.BITRATE_MODE_CHANGED,
                codec = CodecFamily.LDAC,
                bitrateKbps = 990,
                timestampMs = 2_000L,
            ),
        )

        val lines = MonitorEventSummary.lines(log).map { it.summary }

        // The first has nothing to step from and says so by not pretending to.
        assertEquals(listOf("LDAC 660 kbps", "LDAC 660 → 990 kbps"), lines)
    }

    /** An arrow from a number to itself would claim a move that did not happen. */
    @Test
    fun `an unchanged rate is not drawn as a step`() {
        val log = List(2) {
            event(
                MonitorEventType.BITRATE_MODE_CHANGED,
                codec = CodecFamily.LDAC,
                bitrateKbps = 990,
                timestampMs = it * 1_000L,
            )
        }

        assertEquals(
            listOf("LDAC 990 kbps", "LDAC 990 kbps"),
            MonitorEventSummary.lines(log).map { it.summary },
        )
    }

    /**
     * An adaptive mode change carries no figure on purpose — see
     * `LinkEvent.toMonitorEvent`. The line must not borrow the last rate it saw
     * and present it as this event's.
     */
    @Test
    fun `a rateless mode change stays rateless and does not clear the memory`() {
        val log = listOf(
            event(MonitorEventType.BITRATE_MODE_CHANGED, codec = CodecFamily.LDAC, bitrateKbps = 660),
            event(MonitorEventType.BITRATE_MODE_CHANGED, codec = CodecFamily.LDAC, timestampMs = 2_000L),
            event(
                MonitorEventType.BITRATE_MODE_CHANGED,
                codec = CodecFamily.LDAC,
                bitrateKbps = 990,
                timestampMs = 3_000L,
            ),
        )

        val lines = MonitorEventSummary.lines(log).map { it.summary }

        assertEquals("LDAC quality changed", lines[1])
        // 660 is still the last rate anybody was told about, so the step is from
        // there rather than from nothing.
        assertEquals("LDAC 660 → 990 kbps", lines[2])
    }

    // ---- the classification audit ------------------------------------------

    /**
     * The four types that must never reach the list, named one by one rather
     * than derived from the enum: this is the audit result itself, and a test
     * that recomputed it from the same property it is checking would pass no
     * matter which way somebody later flipped a flag.
     */
    @Test
    fun `only the diagnostic types are kept out of the list`() {
        val detailOnly = MonitorEventType.entries
            .filter { it.layer == EventLayer.DETAIL }
            .toSet()

        assertEquals(
            setOf(
                MonitorEventType.ACTIVE_DEVICE_CHANGED,
                MonitorEventType.QUALITY_REPORT,
                MonitorEventType.MONITOR_NOTE,
            ),
            detailOnly,
        )
    }

    /** The set both the log and the timeline colour from. */
    @Test
    fun `the loud set is the audible failures and nothing else`() {
        val loud = MonitorEventType.entries.filter { it.loud }.toSet()

        assertEquals(
            setOf(
                MonitorEventType.ACL_DISCONNECTED,
                MonitorEventType.TAKEOVER,
                MonitorEventType.INTERRUPTION,
                MonitorEventType.DROPOUT,
                MonitorEventType.ENCODER_STARVATION,
            ),
            loud,
        )
    }

    /** Every line keeps the event it came from, in the order it was given. */
    @Test
    fun `lines are returned oldest first beside their events`() {
        val log = listOf(
            event(MonitorEventType.ACL_CONNECTED, timestampMs = 1L),
            event(MonitorEventType.DROPOUT, timestampMs = 2L),
        )

        val lines = MonitorEventSummary.lines(log)

        assertEquals(listOf(1L, 2L), lines.map { it.event.timestampMs })
        assertEquals("Audio dropout", lines[1].summary)
    }
}
