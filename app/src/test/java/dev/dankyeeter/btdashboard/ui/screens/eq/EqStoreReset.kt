package dev.dankyeeter.btdashboard.ui.screens.eq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.system.SystemGraph
import kotlinx.coroutines.runBlocking

/**
 * Puts the stored EQ back to a fresh install.
 *
 * The DataStore behind [SystemGraph.settingsStore] is a process singleton, and
 * Robolectric shares the process across test classes. Without this a curve or
 * a switched-on tilt saved by one test is what the next test's EqViewModel
 * loads, and the result depends on the order the runner picked.
 */
internal fun resetEqStore() {
    SystemGraph.init(ApplicationProvider.getApplicationContext<Context>())
    runBlocking {
        SystemGraph.settingsStore.save(EqSettings())
        SystemGraph.settingsStore.setActiveProfileId(null)
    }
}
