package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.BitrateStepReason
import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.monitor.link.live.LinkEvent
import dev.dankyeeter.btdashboard.monitor.link.live.toMonitorEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the live poller's events say once they reach the timeline.
 *
 * The poller writes for itself — "A2DP stream started", "Audio loss: 3 app
 * underrun(s)" — and that is fine where it lands, in its own log. The mapping
 * into a `MonitorEvent` is where those become something a listener reads, so
 * these pin the boundary: no profile acronyms, no `printf` plural markers, and
 * the honesty labels that separate a measurement from a rated figure survive the
 * translation rather than being smoothed away.
 */
class LinkEventWordingTest {

    @Test
    fun `a dropped link is worded for the person whose music stopped`() {
        val detail = LinkEvent.ConnectionChanged(
            timestampMs = 1L,
            isConnected = false,
            detail = "Device disconnected",
        ).toMonitorEvent(null, "Encore").detail

        assertEquals("The link to Encore dropped.", detail)
    }

    /** "A2DP" names a Bluetooth profile. Nobody listening to music has one. */
    @Test
    fun `playback wording does not name the profile`() {
        val detail = LinkEvent.PlaybackChanged(
            timestampMs = 1L,
            isPlaying = true,
            detail = "A2DP stream started",
        ).toMonitorEvent(null, "Encore").detail

        assertFalse(detail, detail.contains("A2DP"))
        assertEquals("Audio started flowing to Encore.", detail)
    }

    /** With no device name there is still a sentence, and it still parses. */
    @Test
    fun `an unnamed device gets a noun rather than a hole`() {
        val detail = LinkEvent.PlaybackChanged(
            timestampMs = 1L,
            isPlaying = false,
            detail = "A2DP stream stopped",
        ).toMonitorEvent(null, null).detail

        assertEquals("Audio stopped flowing to the headphone.", detail)
    }

    /**
     * The `(s)` was the tell that nobody had written this line for a reader.
     * The window is worth keeping — it is what makes "3 underruns" a rate.
     */
    @Test
    fun `loss counts are pluralised properly and keep their window`() {
        val detail = LinkEvent.LossDetected(
            timestampMs = 1L,
            windowMs = 2_000L,
            inputUnderruns = 3,
            mixerUnderruns = 0,
            txDropped = 1,
            txDropouts = 0,
            txUnderflows = 0,
            detail = "Audio loss: 3 app underrun(s), 1 dropped packet(s)",
        ).toMonitorEvent(null, null).detail

        assertFalse(detail, detail.contains("(s)"))
        assertEquals("Audio was lost in a 2 s window: 3 app underruns, 1 dropped packet.", detail)
    }

    /**
     * The channel AK-T009-24 names, in the sentence a copied log shows.
     *
     * "stack dropouts" is what the criterion asks for word for word, so the
     * words are asserted here and not merely the fact that some sentence came
     * out. The underflow counter is set high in the same window on purpose: it
     * rides along on the event as a record, and it must not turn up in a
     * sentence that says audio was lost — see `A2dpTxDelta.hasLoss`.
     */
    @Test
    fun `a dropout window names the stack dropouts and not the underflows`() {
        val detail = LinkEvent.LossDetected(
            timestampMs = 1L,
            windowMs = 97_000L,
            inputUnderruns = 0,
            mixerUnderruns = 0,
            txDropped = 0,
            txDropouts = 21,
            txUnderflows = 12,
            detail = "Audio loss: 21 stack dropout(s)",
        ).toMonitorEvent(null, null).detail

        assertTrue(detail, detail.contains("21 stack dropouts"))
        assertFalse(detail, detail.contains("underflow"))
    }

    /** An uneven poll is reported as uneven, not rounded into a tidy lie. */
    @Test
    fun `an uneven window keeps its tenth of a second`() {
        val detail = LinkEvent.LossDetected(
            timestampMs = 1L,
            windowMs = 1_800L,
            inputUnderruns = 1,
            mixerUnderruns = 0,
            txDropped = 0,
            txDropouts = 0,
            txUnderflows = 0,
            detail = "…",
        ).toMonitorEvent(null, null).detail

        assertTrue(detail, detail.contains("1.8 s window"))
    }

    /**
     * The honesty contract, in the layer it belongs to. A rated figure has to
     * say it is rated even here, because this sentence is what a copied log
     * shows somebody who never saw the panel it came from.
     */
    @Test
    fun `a pinned mode names its figure as a rating and a measured one as measured`() {
        val pinned = LinkEvent.LdacModeChanged(
            timestampMs = 1L,
            from = LdacQualityMode.NOT_PINNED,
            to = LdacQualityMode.HIGH_QUALITY,
            nominalKbps = 990,
            detail = "…",
        ).toMonitorEvent(null, null, CodecFamily.LDAC)

        assertTrue(pinned.detail, pinned.detail.contains("rated figure is 990 kbps"))

        val measured = LinkEvent.MeasuredBitrateChanged(
            timestampMs = 1L,
            fromKbps = 990,
            toKbps = 660,
            reason = BitrateStepReason.QUALITY_CLASS,
            qualityModeLabel = "ABR",
            detail = "…",
        ).toMonitorEvent(null, null, CodecFamily.LDAC)

        assertTrue(measured.detail, measured.detail.contains("fell from 990 to 660 kbps"))
        assertTrue(measured.detail, measured.detail.contains("not a rated figure"))
    }

    /**
     * The codec travels with the rate so the list line can name what moved.
     * Without it the log said "660 → 990 kbps" about nothing in particular.
     */
    @Test
    fun `a rate event carries the codec it was measured on`() {
        val mapped = LinkEvent.MeasuredBitrateChanged(
            timestampMs = 1L,
            fromKbps = 660,
            toKbps = 990,
            reason = BitrateStepReason.LARGE_STEP,
            qualityModeLabel = null,
            detail = "…",
        ).toMonitorEvent(null, null, CodecFamily.LDAC)

        assertEquals(CodecFamily.LDAC, mapped.codec)
        assertEquals(990, mapped.bitrateKbps)
    }

    /**
     * And only where it belongs: a loss is about the link, not about a codec,
     * so a codec name on that row would be a fact nobody established.
     */
    @Test
    fun `a loss event claims no codec even when one is known`() {
        val mapped = LinkEvent.LossDetected(
            timestampMs = 1L,
            windowMs = 2_000L,
            inputUnderruns = 1,
            mixerUnderruns = 0,
            txDropped = 0,
            txDropouts = 0,
            txUnderflows = 0,
            detail = "…",
        ).toMonitorEvent(null, null, CodecFamily.LDAC)

        assertEquals(null, mapped.codec)
    }
}
