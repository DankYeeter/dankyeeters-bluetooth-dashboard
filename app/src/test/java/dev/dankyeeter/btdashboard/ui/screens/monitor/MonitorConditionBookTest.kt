package dev.dankyeeter.btdashboard.ui.screens.monitor

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.monitor.data.InMemoryMonitorRepository
import dev.dankyeeter.btdashboard.monitor.data.MonitorRepository
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.live.LinkEvent
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveUpdate
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AD-044 on the real ViewModel and the real event store: the book comes with
 * each reading on the open screen, off it there is neither book nor event row,
 * and the first reading back reads anew (AK-18, AK-19, AK-20 return).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MonitorConditionBookTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private lateinit var viewModel: MonitorViewModel
    private val eventStore = InMemoryMonitorRepository()

    private val t1 = System.currentTimeMillis()
    private val t2 = t1 + 2_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        installEventStore(eventStore)
        viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(context))[
            MonitorViewModel::class.java,
        ]
    }

    @After
    fun tearDown() {
        val scope = viewModel.viewModelScope
        store.clear()
        runBlocking { scope.coroutineContext.job.join() }
        Dispatchers.resetMain()
    }

    @Test
    fun `a reading on the open screen brings its book`() = runBlocking {
        setWifi("1")
        viewModel.onReading(reading(t1))

        val read = viewModel.conditionBook.value
        assertEquals(ConditionValue.Read("on"), read?.book?.get(Condition.WIFI_RADIO))
        assertEquals(t1, read?.readAtMs)
    }

    @Test
    fun `off the screen a loss writes no row and reads no book`() = runBlocking {
        hideScreen()
        viewModel.onReading(reading(t1, loss = true))

        assertNull(viewModel.conditionBook.value)
        assertEquals(emptyList<MonitorEvent>(), rowsAt(t1))
    }

    @Test
    fun `a loss row on the open screen carries the book of its episode's first reading`() = runBlocking {
        setWifi("1")
        viewModel.onReading(reading(t1, loss = true))
        setWifi("0")
        viewModel.onReading(reading(t2, loss = true))

        val first = rowsAt(t1).single().detail.lines()
        val second = rowsAt(t2).single().detail.lines()
        assertEquals(EPISODE_HEADING, first[1])
        assertTrue(first.toString(), "Wi-Fi power: on, read." in first)
        assertEquals(first.drop(1), second.drop(1))
    }

    @Test
    fun `leaving and coming back reads the book anew`() = runBlocking {
        setWifi("1")
        viewModel.onReading(reading(t1))
        hideScreen()
        MonitorGraph.setUiVisible(true)
        delay(SETTLE_MS)
        assertNull("no book until the first reading back", viewModel.conditionBook.value)

        setWifi("0")
        viewModel.onReading(reading(t2))
        val read = viewModel.conditionBook.value
        assertEquals(t2, read?.readAtMs)
        assertEquals(ConditionValue.Read("off"), read?.book?.get(Condition.WIFI_RADIO))
    }

    private fun setWifi(value: String) {
        Settings.Global.putString(context.contentResolver, "wifi_on", value)
    }

    private suspend fun hideScreen() {
        MonitorGraph.setUiVisible(false)
        withTimeout(WAIT_MS) { while (viewModel.conditionBook.value != null) delay(STEP_MS) }
        delay(SETTLE_MS)
    }

    private suspend fun rowsAt(at: Long): List<MonitorEvent> =
        eventStore.events(at).first().filter { it.timestampMs == at }

    /**
     * The graph's Room store stays bound to the first Robolectric environment of
     * this JVM and fails silently in every later one, so each test hands the
     * graph a store of its own through its lazy holder.
     */
    private fun installEventStore(store: MonitorRepository) {
        val holder = MonitorGraph::class.java.getDeclaredField("repository\$delegate")
            .apply { isAccessible = true }
            .get(null)
        holder.javaClass.getDeclaredField("_value").apply { isAccessible = true }.set(holder, store)
    }

    private fun reading(at: Long, loss: Boolean = false) = LinkLiveUpdate(
        snapshot = LinkLiveSnapshot(timestampMs = at),
        events = if (loss) listOf(LinkEvent.LossDetected(at, 2_000L, 1L, 0L, 0L, 0L, "Audio loss")) else emptyList(),
    )

    private companion object {
        const val WAIT_MS = 5_000L
        const val STEP_MS = 20L

        /** Lets the ViewModel's visibility collector run once the flag has moved. */
        const val SETTLE_MS = 200L
    }
}
