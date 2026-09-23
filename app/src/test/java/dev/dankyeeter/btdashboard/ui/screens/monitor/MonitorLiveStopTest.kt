package dev.dankyeeter.btdashboard.ui.screens.monitor

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveLinkSource
import dev.dankyeeter.btdashboard.monitor.link.live.ObservationRun
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.monitor.shell.ShellResult
import dev.dankyeeter.btdashboard.monitor.shell.ShellRunner
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.ui.tuning.readConditions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * F-012 / AK-4: once the screen stops collecting, the live poll ends within
 * [MonitorViewModel.LIVE_STOP_TIMEOUT_MS] — whatever the run or the comparison
 * is doing, because neither may hold the poll on its own.
 *
 * The real ViewModel against the real `MonitorGraph`, with a counting shell in
 * place of the helper. Real time rather than virtual: the graph's shared poll
 * runs on its own app-lifetime scope, which no test dispatcher reaches, and the
 * defect was exactly the sum of several stop timeouts along that path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MonitorLiveStopTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private var created: MonitorViewModel? = null

    /** Live passes seen by the helper. `media.audio_flinger` is run by the live pass and nothing else on this path. */
    private val passes = AtomicInteger()

    private val countingShell = object : ShellRunner {
        override val isAvailable = true
        override suspend fun run(command: List<String>): ShellResult {
            if (command == PASS_MARKER) passes.incrementAndGet()
            return ShellResult(exitCode = 0, stdout = "")
        }
    }

    @Before
    fun setUp() {
        // viewModelScope runs on Main; the Robolectric main looper only moves when the test idles it.
        Dispatchers.setMain(Dispatchers.Default)
        MonitorGraph.installShellRunner(countingShell)
    }

    @After
    fun tearDown() {
        val scope = created?.viewModelScope
        store.clear()
        // Resetting Main while the cancelled scope still dispatches on it throws.
        runBlocking { scope?.coroutineContext?.job?.join() }
        Dispatchers.resetMain()
    }

    @Test
    fun `leaving the screen stops the poll`() = runBlocking {
        val viewModel = viewModel()
        val screen = openScreen(viewModel)

        assertPollStopsAfterLeaving(screen)
    }

    @Test
    fun `leaving the screen stops the poll while a run counts`() = runBlocking {
        SystemGraph.setupStore.setObservationRunNoticeAccepted(true)
        val viewModel = viewModel()
        val screen = openScreen(viewModel)
        viewModel.observationRun.onStartTapped()
        withTimeout(WAIT_MS) { while (viewModel.observationRun.ui.value.run == null) delay(POLL_STEP_MS) }
        awaitNextPass()
        assertNull("the run is still counting", viewModel.observationRun.ui.value.run?.end)

        assertPollStopsAfterLeaving(screen)
    }

    @Test
    fun `leaving the screen stops the poll while arm A counts`() = runBlocking {
        val viewModel = viewModel()
        val screen = openScreen(viewModel)
        viewModel.comparison.placeArmA()
        awaitNextPass()
        val arm = viewModel.comparison.ui.value.running
        assertNotNull("arm A is running", arm)
        assertNull("arm A is still counting", arm?.run?.end)

        assertPollStopsAfterLeaving(screen)
    }

    private fun viewModel(): MonitorViewModel =
        ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(context))[MonitorViewModel::class.java]
            .also { created = it }

    /** The poll-bearing collections `MonitorScreen` makes, and the first pass they start. */
    private suspend fun openScreen(viewModel: MonitorViewModel): Job {
        val screen = CoroutineScope(Dispatchers.Default).launch {
            launch { viewModel.liveLink.collect {} }
            launch { viewModel.overviewTrace.collect {} }
            launch { viewModel.storedLdacQuality.collect {} }
        }
        awaitNextPass()
        return screen
    }

    private suspend fun awaitNextPass() {
        val seen = passes.get()
        withTimeout(WAIT_MS) { while (passes.get() == seen) delay(POLL_STEP_MS) }
    }

    private suspend fun assertPollStopsAfterLeaving(screen: Job) {
        screen.cancelAndJoin()
        delay(MonitorViewModel.LIVE_STOP_TIMEOUT_MS + TOLERANCE_MS)
        val settled = passes.get()
        delay(QUIET_WINDOW_MS)
        assertEquals("passes after the stop budget", settled, passes.get())
        assertTrue("the poll ran before leaving", settled > 0)
    }

    /**
     * Arm A, counting. A real pin needs the helper and a connected headphone,
     * so the arm is placed the way a successful pin leaves it (`onCompare`).
     */
    @Suppress("UNCHECKED_CAST")
    private fun ComparisonController.placeArmA() {
        val field = ComparisonController::class.java.getDeclaredField("_ui").apply { isAccessible = true }
        val state = field.get(this) as MutableStateFlow<ComparisonUi>
        val book = readConditions(context, LinkLiveSnapshot(timestampMs = System.currentTimeMillis()), null)
        state.value = ComparisonUi(
            phase = ComparisonPhase.ARM_A,
            measure = Measure.NO_DISCOVERY,
            running = RunningArm(ObservationRun.startedAfter(System.currentTimeMillis()), book, null),
        )
    }

    private companion object {
        val PASS_MARKER = listOf("dumpsys", "media.audio_flinger")

        /** One pass in flight when the stop lands; the counting shell answers at once. */
        const val TOLERANCE_MS = 1_000L

        /** Two intervals of the default poll: long enough for any pass still scheduled to show up. */
        const val QUIET_WINDOW_MS = 2 * LiveLinkSource.DEFAULT_INTERVAL_MS + 500L

        const val WAIT_MS = 10_000L
        const val POLL_STEP_MS = 20L
    }
}
