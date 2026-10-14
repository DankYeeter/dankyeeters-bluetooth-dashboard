package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatus
import dev.dankyeeter.btdashboard.monitor.data.InMemoryMonitorRepository
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.sampling.LinkSampleCollector
import dev.dankyeeter.btdashboard.monitor.sampling.MonitorEngine
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingMode
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's own loop is driven by `runTest`'s virtual clock: [TestClock]
 * mirrors the scheduler's time, so a three-minute scenario runs in microseconds
 * and no test ever waits on wall-clock delays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorEngineTest {

    private val address = "AA:BB:CC:DD:EE:FF"

    private class Fixture(
        val engine: MonitorEngine,
        val repository: InMemoryMonitorRepository,
        val events: FakeEventSource,
    )

    private fun TestScope.fixture(clock: TestClock, screenOn: Boolean = true, uiVisible: Boolean = false): Fixture {
        val testScope = this
        val repository = InMemoryMonitorRepository()
        val eventSource = FakeEventSource()
        val codecSource = FakeCodecStatusSource(
            devices = listOf(BtAudioDevice(address, "Encore", isActive = true, isPlaying = true)),
        ).withStatus(address, CodecStatus(CodecFamily.LDAC, bitrateKbps = 909))
        val collector = LinkSampleCollector(
            codecSource = codecSource,
            dumpsysSource = FakeDumpsysLinkSource(isAvailable = false),
            qualityReportSource = FakeQualityReportSource("test"),
            clock = clock::now,
        )
        val engine = MonitorEngine(
            repository = repository,
            eventSource = eventSource,
            collector = collector,
            screenOn = MutableStateFlow(screenOn),
            uiVisible = MutableStateFlow(uiVisible),
            clock = clock::now,
            sleep = { ms ->
                kotlinx.coroutines.delay(ms)
                clock.nowMs = testScope.currentTime
            },
        )
        return Fixture(engine, repository, eventSource)
    }

    @Test
    fun `idle with the screen off records nothing`() = runTest {
        val clock = TestClock()
        val f = fixture(clock, screenOn = false)
        f.engine.start(backgroundScope)
        advanceTimeBy(90_000)
        runCurrent()

        assertEquals(SamplingMode.STOPPED, f.engine.status.value.mode)
        assertTrue(f.repository.samples(0).first().isEmpty())
        f.engine.stop()
    }

    @Test
    fun `playback events switch the sampler to the active interval`() = runTest {
        val clock = TestClock()
        val f = fixture(clock, uiVisible = true)
        f.engine.start(backgroundScope)
        runCurrent()

        f.events.flow.emit(
            MonitorEvent(clock.now(), address, "Encore", MonitorEventType.PLAYING_STARTED, "start"),
        )
        advanceTimeBy(61_000)
        runCurrent()

        assertTrue(f.repository.events(0).first().any { it.type == MonitorEventType.PLAYING_STARTED })
        assertTrue(f.repository.samples(0).first().isNotEmpty())
        assertEquals(SamplingMode.ACTIVE, f.engine.status.value.mode)
        f.engine.stop()
    }

    @Test
    fun `deep capture forces the fast interval even with the screen off`() = runTest {
        val clock = TestClock()
        val f = fixture(clock, screenOn = false)
        f.engine.startDeepCapture(durationMs = 60_000)
        f.engine.start(backgroundScope)
        advanceTimeBy(59_000)
        runCurrent()

        // 10-second resolution across the window.
        assertTrue(f.repository.samples(0).first().size >= 5)
        assertEquals(SamplingMode.DEEP, f.engine.status.value.mode)
        f.engine.stop()
    }

    /** T-055s V21: whatever a caller asks for, a capture is never longer than its window. */
    @Test
    fun `deep capture is bounded to its window`() = runTest {
        val clock = TestClock()
        val f = fixture(clock)
        f.engine.startDeepCapture(Long.MAX_VALUE)

        assertEquals(clock.now() + SamplingPolicy.DEEP_CAPTURE_WINDOW_MS, f.engine.status.value.deepCaptureUntilMs)
    }

    /** AD-039 point 4: the measurement service follows the status, so start and stop show in it at once. */
    @Test
    fun `starting and stopping deep capture shows in the status at once`() = runTest {
        val clock = TestClock()
        val f = fixture(clock)
        f.engine.startDeepCapture(60_000)
        assertEquals(clock.now() + 60_000, f.engine.status.value.deepCaptureUntilMs)
        assertEquals(clock.now(), f.engine.status.value.deepCaptureSinceMs)

        f.engine.stopDeepCapture()
        assertEquals(0L, f.engine.status.value.deepCaptureUntilMs)
    }
}
