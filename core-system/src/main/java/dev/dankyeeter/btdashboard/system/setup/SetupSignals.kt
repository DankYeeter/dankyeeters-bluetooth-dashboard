package dev.dankyeeter.btdashboard.system.setup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Look again."
 *
 * Nothing about a permission is observable: Android hands out a yes or a no
 * when asked and offers no callback when the answer changes, so the only
 * honest model is to re-read on every occasion where it could have changed —
 * coming back from Settings, a permission dialog that just closed, a tap on
 * Re-check.
 *
 * Process-wide rather than per-screen because two screens need the same answer
 * at the same moment: the gate that decides which face of the app is shown, and
 * the setup process running inside it. A tick each would let them disagree, and
 * the one that would look wrong is the gate — the screen that cannot be argued
 * with.
 *
 * Deliberately not a poll. Battery work in this app happens on demand.
 */
object SetupSignals {

    private val _changed = MutableStateFlow(0)

    /** Bumped whenever something might have changed. The value carries nothing. */
    val changed: StateFlow<Int> = _changed.asStateFlow()

    fun refresh() {
        _changed.value += 1
    }
}
