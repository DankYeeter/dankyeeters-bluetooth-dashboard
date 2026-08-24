package dev.dankyeeter.btdashboard.ui.screens.wizard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.setup.SetupSignals
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
 * Drives the setup process.
 *
 * The step list is recomputed from a "look again" signal rather than being
 * pushed at: permissions and Settings toggles change outside the app, so every
 * resume and every "Re-check" simply bumps the signal and the whole list is
 * re-derived. No step can end up showing a cached answer.
 *
 * The signal is [SetupSignals], shared with the gate that decides whether this
 * process is shown at all. Two ticks would let the two disagree about the same
 * permission, and the one that would look wrong is the gate.
 */
class SetupWizardViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SystemGraph.setupStore
    private val environment = AndroidSetupEnvironment(application)

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    val steps: StateFlow<List<SetupStepState>> =
        combine(store.skippedStepIds, SetupSignals.changed) { skipped, _ ->
            SetupStatus.evaluate(environment, skipped)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SetupStatus.evaluate(environment, emptySet()),
        )

    val stepCount: Int = SetupStep.entries.size

    fun refresh() {
        SetupSignals.refresh()
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

    /**
     * "Done": hands back, and nothing else.
     *
     * It used to write a "wizard completed" flag, and that flag was the entry
     * condition of the whole app - which is why a fresh install could skip the
     * process entirely. What happens after this tap is now decided by the live
     * state, so there is nothing left to record.
     */
    fun finish(onDone: () -> Unit) = onDone()

    /** Re-running the process asks about previously skipped steps again. */
    fun restart() {
        viewModelScope.launch {
            store.clearSkips()
            _currentIndex.value = 0
            refresh()
        }
    }
}
