package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.data.InMemoryMonitorRepository
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.link.UnavailableQualityReportSource
import dev.dankyeeter.btdashboard.monitor.sampling.LinkSampleCollector
import dev.dankyeeter.btdashboard.monitor.sampling.MonitorEngine
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the sampler costs while there is nothing to sample.
 *
 * Screen off with nothing playing is the state a phone spends most of its day
 * in. A 30-second idle tick there was 2,880 wake-ups a day, every one of them
 * concluding that there was still nothing to do — so the idle path must wait
 * for a signal, not for a timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleWakeupTest {

    private val screenOn = MutableStateFlow(false)
    private val uiVisible = MutableStateFlow(false)
    private val events = FakeEventSource()

    private val bathys = BtAudioDevice(
        address = "A4:D9:31:C8:35:6A",
        name = "Focal Bathys",
        isActive = true,
    )

    /**
     * A connected headphone by default. That is the state these tests are
     * about — whether the sampler *waits for a signal* rather than ticking —
     * and it has to be stated explicitly now that a poll finding nothing at all
     * parks the loop on its own (see `a poll that finds nothing parks the
     * sampler`). Without a device the two conditions would be
     * indistinguishable, and a passing test would prove neither.
     */
    private fun engine(
        scope: TestScope,
        devices: List<BtAudioDevice> = listOf(bathys),
    ): Pair<MonitorEngine, MutableList<Long>> {
        val sleeps = mutableListOf<Long>()
        val engine = MonitorEngine(
            repository = InMemoryMonitorRepository(),
            eventSource = events,
            collector = LinkSampleCollector(
                codecSource = FakeCodecStatusSource(devices = devices),
                dumpsysSource = FakeDumpsysLinkSource(isAvailable = false),
                qualityReportSource = UnavailableQualityReportSource("no BQR in tests"),
            ),
            screenOn = screenOn,
            uiVisible = uiVisible,
            sleep = { ms ->
                sleeps += ms
                kotlinx.coroutines.delay(ms)
            },
        )
        return engine to sleeps
    }

    @Test
    fun `an idle sampler does not tick`() = runTest {
        val (engine, sleeps) = engine(this)
        engine.start(TestScope(StandardTestDispatcher(testScheduler)))

        // Ten minutes of screen-off, nothing playing.
        advanceTimeBy(10 * 60_000L)

        assertEquals("idle must not call sleep at all", emptyList<Long>(), sleeps)
        assertEquals(SamplingMode.STOPPED, engine.status.value.mode)
        engine.stop()
    }

    /**
     * Adjusted for H1: the screen coming on still *ends the wait*, but on its
     * own it no longer starts polling. The display being on is true for every
     * waking minute of the phone's day, and none of those minutes have a reader
     * for the numbers — so the loop re-evaluates and stops again.
     */
    @Test
    fun `the screen coming on alone does not start polling`() = runTest {
        val (engine, sleeps) = engine(this)
        engine.start(TestScope(StandardTestDispatcher(testScheduler)))
        advanceTimeBy(60_000L)
        assertEquals(SamplingMode.STOPPED, engine.status.value.mode)

        screenOn.value = true
        advanceTimeBy(5 * 60_000L)

        assertEquals(SamplingMode.STOPPED, engine.status.value.mode)
        assertEquals("a lit screen alone must not cost a poll", emptyList<Long>(), sleeps)
        engine.stop()
    }

    @Test
    fun `an open monitor screen with the display on ends the idle wait`() = runTest {
        val (engine, _) = engine(this)
        engine.start(TestScope(StandardTestDispatcher(testScheduler)))
        advanceTimeBy(60_000L)
        assertEquals(SamplingMode.STOPPED, engine.status.value.mode)

        screenOn.value = true
        uiVisible.value = true
        advanceTimeBy(1_000L)

        assertEquals(SamplingMode.BACKGROUND, engine.status.value.mode)
        engine.stop()
    }

    /** Closing the screen puts the sampler back to sleep at the next decision. */
    @Test
    fun `leaving the monitor screen stops the polling again`() = runTest {
        val (engine, _) = engine(this)
        screenOn.value = true
        uiVisible.value = true
        engine.start(TestScope(StandardTestDispatcher(testScheduler)))
        advanceTimeBy(1_000L)
        assertEquals(SamplingMode.BACKGROUND, engine.status.value.mode)

        uiVisible.value = false
        advanceTimeBy(120_000L)

        assertEquals(SamplingMode.STOPPED, engine.status.value.mode)
        engine.stop()
    }

    @Test
    fun `deep capture ends the idle wait immediately`() = runTest {
        val (engine, _) = engine(this)
        engine.start(TestScope(StandardTestDispatcher(testScheduler)))
        advanceTimeBy(60_000L)

        engine.startDeepCapture(30_000L)
        advanceTimeBy(1_000L)

        assertEquals(SamplingMode.DEEP, engine.status.value.mode)
        engine.stop()
    }

    /**
     * Changed from CODEC_CHANGED to PLAYING_STARTED under H1. The point of the
     * test is that a broadcast ends the idle wait rather than a timer doing it,
     * and that is unchanged — but a codec change with nothing playing and no
     * screen open is now correctly *not* a reason to poll, so it can no longer
     * carry the assertion.
     */
    @Test
    fun `a bluetooth event ends the idle wait`() = runTest {
        val (engine, _) = engine(this)
        engine.start(TestScope(StandardTestDispatcher(testScheduler)))
        advanceTimeBy(60_000L)

        screenOn.value = true
        events.flow.emit(
            MonitorEvent(
                timestampMs = 1_000L,
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                deviceName = "Focal Bathys",
                type = MonitorEventType.PLAYING_STARTED,
                detail = "playback started",
            ),
        )
        advanceTimeBy(1_000L)

        assertTrue(engine.status.value.mode != SamplingMode.STOPPED)
        engine.stop()
    }
}
