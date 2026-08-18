package dev.dankyeeter.btdashboard.ui.screens.wizard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.setup.SetupStatus
import dev.dankyeeter.btdashboard.system.setup.SetupStep
import dev.dankyeeter.btdashboard.system.setup.SetupStepState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the first-run wizard.
 *
 * The step list is recomputed from a "tick" flow rather than being pushed at:
 * permissions and Settings toggles change outside the app, so every resume and
 * every "Re-check" simply bumps the tick and the whole list is re-derived. No
 * step can end up showing a cached answer.
 */
class SetupWizardViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SystemGraph.setupStore
    private val environment = AndroidSetupEnvironment(application)

    private val tick = MutableStateFlow(0)

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    val steps: StateFlow<List<SetupStepState>> =
        combine(store.skippedStepIds, tick) { skipped, _ ->
            SetupStatus.evaluate(environment, skipped)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SetupStatus.evaluate(environment, emptySet()),
        )

    val stepCount: Int = SetupStep.entries.size

    fun refresh() {
        SystemGraph.shizuku.refresh()
        tick.value += 1
    }

    fun goTo(index: Int) {
        _currentIndex.value = index.coerceIn(0, stepCount - 1)
    }

    fun next() = goTo(_currentIndex.value + 1)

    fun previous() = goTo(_currentIndex.value - 1)

    fun skip(step: SetupStep) {
        viewModelScope.launch {
            store.setSkipped(step.id, skipped = true)
            next()
        }
    }

    fun unskip(step: SetupStep) {
        viewModelScope.launch { store.setSkipped(step.id, skipped = false) }
    }

    /** "Finish": records that the wizard was seen. Not a claim that all is granted. */
    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            store.setWizardCompleted(true)
            onDone()
        }
    }

    /** Re-running the wizard asks about previously skipped steps again. */
    fun restart() {
        viewModelScope.launch {
            store.clearSkips()
            _currentIndex.value = 0
            refresh()
        }
    }
}
