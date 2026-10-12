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
 * Robolectric shares the process across test classes. Without this a curve or
 * a switched-on tilt saved by one test is what the next test's EqViewModel
 * loads, and the result depends on the order the runner picked.
 *
 * The write runs off the main thread while this thread keeps draining the main
 * looper: blocking the test thread outright hung the suite.
 */
internal fun resetEqStore() {
    SystemGraph.init(ApplicationProvider.getApplicationContext<Context>())
    val store = SystemGraph.settingsStore
    val reset = CoroutineScope(Dispatchers.IO).launch {
        store.save(EqSettings())
        store.setActiveProfileId(null)
    }
    val deadline = System.currentTimeMillis() + 10_000
    while (!reset.isCompleted) {
        check(System.currentTimeMillis() < deadline) { "the EQ store did not reset within 10 s" }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
}
