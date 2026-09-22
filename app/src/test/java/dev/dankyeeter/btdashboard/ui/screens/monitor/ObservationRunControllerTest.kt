package dev.dankyeeter.btdashboard.ui.screens.monitor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.LdacStackState
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveCodecSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveDeviceSnapshot
import dev.dankyeeter.btdashboard.system.setup.SetupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who may start a run, against the real, persisted notice flag.
 *
 * `runBlocking` rather than `runTest`: the store answers on its own IO thread,
 * which virtual time would not wait for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ObservationRunControllerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun reading(timestampMs: Long) = LinkLiveSnapshot(
        timestampMs = timestampMs,
        device = LiveDeviceSnapshot(address = "AC:DE:48:00:37:8F", isConnected = true, isPlaying = true),
        codec = LiveCodecSnapshot(family = CodecFamily.LDAC, sampleRateHz = 96_000, codecSpecific1 = 0L),
        ldac = LdacState.from(0L, 96_000, LdacStackState(qualityMode = "ABR", transmissionKbps = 660)),
    )

    /** Waits until a tap on Start has either shown the notice or started the run. */
    private suspend fun ObservationRunController.settled() =
        withTimeout(TIMEOUT_MS) { ui.first { it.startNoticeShown || it.run != null } }

    private fun CoroutineScope.controller() = ObservationRunController(this, SetupStore(context))

    /** AK-T039-17: once ever, set by Continue only, remembered across a fresh ViewModel. */
    @Test
    fun `the start notice appears on the first tap ever and never again`() = runBlocking {
        SetupStore(context).setObservationRunNoticeAccepted(false)

        val first = controller()
        first.onStartTapped()
        first.settled()
        assertTrue("first tap: notice", first.ui.value.startNoticeShown)
        assertNull("first tap: no run", first.ui.value.run)

        first.onNoticeDismiss()
        assertNull("Not now: no run", first.ui.value.run)
        assertFalse("Not now: flag unset", SetupStore(context).isObservationRunNoticeAccepted())

        first.onStartTapped()
        first.settled()
        assertTrue("after Not now the notice comes back", first.ui.value.startNoticeShown)

        first.onNoticeContinue()
        withTimeout(TIMEOUT_MS) { first.ui.first { it.run != null } }
        assertFalse(first.ui.value.startNoticeShown)
        assertTrue("Continue: flag set", SetupStore(context).isObservationRunNoticeAccepted())

        // A fresh ViewModel over the same store — the app started again.
        val fresh = controller()
        fresh.onStartTapped()
        fresh.settled()
        assertFalse("later taps: no notice", fresh.ui.value.startNoticeShown)
        assertNotNull("later taps: the run starts directly", fresh.ui.value.run)
    }

    /** AK-T039-1: readings never start a run, and a run counts only what follows the tap. */
    @Test
    fun `only the tap starts a run, and it counts from the next reading`() = runBlocking {
        SetupStore(context).setObservationRunNoticeAccepted(true)
        val control = controller()

        (1..200).forEach { control.onReading(reading(it * 1_000L), 1_000L) }
        assertNull("two hundred readings started nothing", control.ui.value.run)

        control.onStartTapped()
        control.settled()
        control.onReading(reading(200_000L), 1_000L)
        assertEquals("the replayed reading at the tap is not counted", 0, control.ui.value.run?.readings)

        control.onReading(reading(201_000L), 1_000L)
        assertEquals(1, control.ui.value.run?.readings)

        control.onStop()
        control.onReading(reading(202_000L), 1_000L)
        assertEquals("a stopped run takes no more readings", 1, control.ui.value.run?.readings)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
