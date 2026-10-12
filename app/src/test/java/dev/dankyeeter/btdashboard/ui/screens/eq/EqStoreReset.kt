package dev.dankyeeter.btdashboard.ui.screens.eq

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.system.SystemGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.robolectric.Shadows.shadowOf

/**
 * Puts the stored EQ back to a fresh install.
 *
 * The DataStore behind [SystemGraph.settingsStore] is a process singleton, and
 * Robolectric shares the process across test classes: without this, a curve or
 * a switched-on tilt saved by one test is what the next test loads.
 */
internal fun resetEqStore() {
    SystemGraph.init(ApplicationProvider.getApplicationContext<Context>())
    val store = SystemGraph.settingsStore
    val reset = CoroutineScope(Dispatchers.IO).launch {
        store.save(EqSettings())
        store.setActiveProfileId(null)
    }
    drainUntil("the EQ store did not reset") { reset.isCompleted }
}

/**
 * Waits for the persisted EQ to reach the ViewModel.
 *
 * DataStore reads on its own thread, so one pass over the main looper can come
 * too early. A load that lands after the test has started editing replaces the
 * edit with the stored curve, and the test then fails on a value it never set.
 */
internal fun EqViewModel.awaitLoaded(): EqViewModel = apply {
    drainUntil("the EQ never loaded") { settingsLoaded }
}

private fun drainUntil(failure: String, done: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (done()) return
        check(System.currentTimeMillis() < deadline) { "$failure within 10 s" }
        Thread.sleep(5)
    }
}
