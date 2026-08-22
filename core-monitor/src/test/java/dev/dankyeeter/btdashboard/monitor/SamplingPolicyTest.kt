package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.sampling.AnomalyDetector
import dev.dankyeeter.btdashboard.monitor.sampling.MonitorConditions
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingDecision
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingMode
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingPolicyTest {

    private fun mode(
        playing: Boolean,
        screenOn: Boolean,
        uiVisible: Boolean = false,
        deepUntil: Long = 0,
        burstUntil: Long = 0,
        now: Long = 1_000,
    ) = SamplingPolicy.decide(
        MonitorConditions(
            nowMs = now,
            isPlaying = playing,
            isScreenOn = screenOn,
            uiVisible = uiVisible,
            deepCaptureUntilMs = deepUntil,
            burstUntilMs = burstUntil,
        ),
    )

    @Test
    fun `screen off without playback stops polling entirely`() {
        val decision = mode(playing = false, screenOn = false)
        assertTrue(decision is SamplingDecision.Stopped)
    }

    @Test
    fun `playing with the screen on polls every 30 seconds`() {
        val poll = mode(playing = true, screenOn = true) as SamplingDecision.Poll
        assertEquals(SamplingMode.ACTIVE, poll.mode)
        assertEquals(30_000L, poll.intervalMs)
    }

    @Test
    fun `playing in the pocket backs off to 60 seconds`() {
        val poll = mode(playing = true, screenOn = false) as SamplingDecision.Poll
        assertEquals(60_000L, poll.intervalMs)
    }

    @Test
    fun `deep capture wins over everything, even screen off`() {
        val poll = mode(playing = false, screenOn = false, deepUntil = 5_000) as SamplingDecision.Poll
        assertEquals(SamplingMode.DEEP, poll.mode)
        assertEquals(10_000L, poll.intervalMs)
    }

    @Test
    fun `an expired deep capture window releases the sampler`() {
        val decision = mode(playing = false, screenOn = false, deepUntil = 500, now = 1_000)
        assertTrue(decision is SamplingDecision.Stopped)
    }

    /**
     * The idle leg of this test now passes `uiVisible = true`. Its subject is
     * the burst window, not the idle rule: it asserts that an anomaly does not
     * tighten the interval while nothing is playing. Without the flag the idle
     * case would stop entirely and the assertion would be about the wrong
     * thing — see [`screen on with nothing playing and no monitor screen stops`].
     */
    @Test
    fun `an anomaly bursts to 5 seconds only while playing`() {
        val burst = mode(playing = true, screenOn = true, burstUntil = 9_000) as SamplingDecision.Poll
        assertEquals(SamplingMode.BURST, burst.mode)

        val idle = mode(playing = false, screenOn = true, uiVisible = true, burstUntil = 9_000)
            as SamplingDecision.Poll
        assertEquals(SamplingMode.BACKGROUND, idle.mode)
    }

    /**
     * The H1 rule. "Screen on" is true for every waking minute of the phone's
     * day; treating it as a reason to poll cost ~200 full sample runs a day —
     * a codec query plus a `dumpsys bluetooth_manager` each — with nothing
     * playing into them and no screen to draw them on.
     */
    @Test
    fun `screen on with nothing playing and no monitor screen stops`() {
        val decision = mode(playing = false, screenOn = true, uiVisible = false)
        assertTrue(decision is SamplingDecision.Stopped)
    }

    @Test
    fun `an open monitor screen is a reason to poll`() {
        val poll = mode(playing = false, screenOn = true, uiVisible = true) as SamplingDecision.Poll
        assertEquals(SamplingMode.BACKGROUND, poll.mode)
    }

    /** A screen that is on but locked or off is never a reason on its own. */
    @Test
    fun `a visible screen with the display off still stops`() {
        assertTrue(mode(playing = false, screenOn = false, uiVisible = true) is SamplingDecision.Stopped)
    }

    @Test
    fun `playback polls whether or not anyone is watching`() {
        assertEquals(
            SamplingMode.ACTIVE,
            (mode(playing = true, screenOn = true, uiVisible = false) as SamplingDecision.Poll).mode,
        )
    }
}

class AnomalyDetectorTest {

    private fun sample(
        at: Long,
        bitrate: Int? = null,
        rssi: Int? = null,
        codec: CodecFamily? = null,
        dropped: Int? = null,
    ) = LinkQualitySample(
        timestampMs = at,
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        source = LinkDataSource.CODEC_API,
        rssiDbm = rssi,
        codec = codec,
        bitrateKbps = bitrate,
        droppedPackets = dropped,
    )

    @Test
    fun `a large bitrate drop is flagged`() {
        val reasons = AnomalyDetector.detect(sample(0, bitrate = 909), sample(1, bitrate = 303))
        assertTrue(reasons.any { it.contains("Bitrate dropped") })
    }

    @Test
    fun `small bitrate wobble is not an anomaly`() {
        assertTrue(AnomalyDetector.detect(sample(0, bitrate = 909), sample(1, bitrate = 800)).isEmpty())
    }

    @Test
    fun `codec downgrade and rssi cliff are flagged`() {
        val reasons = AnomalyDetector.detect(
            sample(0, codec = CodecFamily.LDAC, rssi = -55),
            sample(1, codec = CodecFamily.SBC, rssi = -70),
        )
        assertTrue(reasons.any { it.contains("Codec changed") })
        assertTrue(reasons.any { it.contains("Signal dropped by 15 dB") })
    }

    @Test
    fun `weak absolute signal and packet loss are flagged without a predecessor`() {
        val reasons = AnomalyDetector.detect(null, sample(1, rssi = -85, dropped = 3))
        assertEquals(2, reasons.size)
    }

    @Test
    fun `a first sample with nothing unusual is quiet`() {
        assertTrue(AnomalyDetector.detect(null, sample(1, bitrate = 909, rssi = -50)).isEmpty())
    }
}
