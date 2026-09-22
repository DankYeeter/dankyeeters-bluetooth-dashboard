package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.ObservationRun
import dev.dankyeeter.btdashboard.system.setup.SetupStore
import dev.dankyeeter.btdashboard.ui.tuning.LdacQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the observation-run section shows. */
data class ObservationRunUi(
    /** The run, counting or ended; null before the first start on this screen. */
    val run: ObservationRun? = null,
    /** The pinnable quality whose rate is the share's threshold — the user's choice. */
    val thresholdQuality: Long = LdacQuality.STANDARD,
    /** Whether the one-time start notice is up. */
    val startNoticeShown: Boolean = false,
)

/**
 * Starts, stops and feeds the observation run for one Monitor screen.
 *
 * Lives in the screen's ViewModel and dies with it, which is the whole of the
 * run's lifetime: leaving the screen discards the run (`UI_SPEC.md` T-039). It
 * adds no work of its own — [onReading] is handed the readings the panel polls
 * anyway, and nothing else feeds it.
 *
 * The one thing kept past the screen is whether the start notice was confirmed,
 * in [SetupStore], exactly like the local-connection disclosure: set by
 * "Continue" only, so "Not now" asks again next time (decision 5).
 */
internal class ObservationRunController(
    private val scope: CoroutineScope,
    private val store: SetupStore,
) {
    private val _ui = MutableStateFlow(ObservationRunUi())
    val ui: StateFlow<ObservationRunUi> = _ui.asStateFlow()

    /** The newest reading seen, so a run begins strictly after the tap. */
    private var newestReadingMs: Long? = null

    fun onReading(snapshot: LinkLiveSnapshot, expectedIntervalMs: Long) {
        newestReadingMs = maxOf(newestReadingMs ?: snapshot.timestampMs, snapshot.timestampMs)
        _ui.update { it.copy(run = it.run?.plus(snapshot, expectedIntervalMs)) }
    }

    /** The Start chip: the notice first, once ever, then the run. */
    fun onStartTapped() {
        scope.launch {
            if (store.isObservationRunNoticeAccepted()) {
                start()
            } else {
                _ui.update { it.copy(startNoticeShown = true) }
            }
        }
    }

    fun onNoticeContinue() {
        _ui.update { it.copy(startNoticeShown = false) }
        scope.launch {
            store.setObservationRunNoticeAccepted(true)
            start()
        }
    }

    fun onNoticeDismiss() {
        _ui.update { it.copy(startNoticeShown = false) }
    }

    fun onStop() {
        _ui.update { it.copy(run = it.run?.stopped()) }
    }

    fun onThreshold(quality: Long) {
        _ui.update { it.copy(thresholdQuality = quality) }
    }

    private fun start() {
        _ui.update { it.copy(run = ObservationRun.startedAfter(newestReadingMs)) }
    }
}
