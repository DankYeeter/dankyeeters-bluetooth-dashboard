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
 * What a poll costs when there is no device to poll about.
 *
 * [SamplingPolicy] decides whether the *conditions* are worth sampling in;
 * this is the case it cannot see. Conditions can be perfectly good — the
 * monitor screen open, or a deep capture explicitly requested — while the
 * A2DP profile has nothing connected to describe. The policy said "poll", the
 * collector returned an empty list, and the loop slept and asked again on the
 * interval, forever, for an answer that could not change without a broadcast.
 *
 * The rule the engine now follows: **a poll that produced no rows at all waits
 * for a signal instead of for the interval.** Every way a device can appear —
 * ACL connect, A2DP connection state, active-device change — already calls
 * `signalWake()`, so nothing is lost by waiting.
 *
 * Kept out of [IdleWakeupTest] deliberately: that class is being edited in
 * parallel for the `uiVisible` gate, which is the same bill paid one level up.
 * Merge the two once that has landed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SamplerParkingTest {

    private val bathys = BtAudioDevice(
        address = "A4:D9:31:C8:35:6A",
        name = "Focal Bathys",
        isActive = true,
    )

    private val events = FakeEventSource()

    private fun engine(
        codecSource: FakeCodecStatusSource,
        sleeps: MutableList<Long>? = null,
    ) = MonitorEngine(
        repository = InMemoryMonitorRepository(),
        eventSource = events,
        collector = LinkSampleCollector(
            codecSource = codecSource,
            dumpsysSource = FakeDumpsysLinkSource(isAvailable = false),
            qualityReportSource = UnavailableQualityReportSource("no BQR in tests"),
        ),
        screenOn = MutableStateFlow(true),
        sleep = { ms ->
            sleeps?.add(ms)
            kotlinx.coroutines.delay(ms)
        },
    )

    /**
     * Deep capture is the strongest "yes, poll" the policy can produce — it
     * overrides the screen being off and everything else. Even that must not
     * spin on a device list that is empty.
     */
    @Test
    fun `a poll that finds nothing does not schedule the next one`() = runTest {
        val sleeps = mutableListOf<Long>()
        val engine = engine(FakeCodecStatusSource(devices = emptyList()), sleeps)
        engine.startDeepCapture(10 * 60_000L)
        engine.start(TestScope(StandardTestDispatcher(testScheduler)))

        advanceTimeBy(10 * 60_000L)

        // One poll is allowed and necessary — that is how the loop finds out.
        // What must not happen is the interval after it.
        assertEquals("a fruitless poll must not schedule the next one", emptyList<Long>(), sleeps)
        assertEquals(SamplingMode.STOPPED, engine.status.value.mode)
        engine.stop()
    }

    /** And the loop comes back the moment a device actually shows up. */
    @Test
    fun `a parked sampler resumes when a device appears`() = runTest {
        val codecSource = FakeCodecStatusSource(devices = emptyList())
        val engine = engine(codecSource)
        engine.startDeepCapture(10 * 60_000L)
        engine.start(TestScope(StandardTestDispatcher(testScheduler)))
        advanceTimeBy(60_000L)
        assertEquals(SamplingMode.STOPPED, engine.status.value.mode)

        codecSource.devices = listOf(bathys)
        events.flow.emit(
            MonitorEvent(
                timestampMs = 1_000L,
                deviceAddress = bathys.address,
                deviceName = bathys.name,
                type = MonitorEventType.ACL_CONNECTED,
                detail = "connected",
            ),
        )
        advanceTimeBy(1_000L)

        assertTrue(
            "a connect broadcast must end the park",
            engine.status.value.mode != SamplingMode.STOPPED,
        )
        engine.stop()
    }
}
