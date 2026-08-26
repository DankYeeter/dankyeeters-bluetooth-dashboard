package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.privileged.PrivilegedCodec
import dev.dankyeeter.btdashboard.monitor.diagnostic.DeviceDiagnosticRunner
import dev.dankyeeter.btdashboard.monitor.diagnostic.DiagnosticReport
import dev.dankyeeter.btdashboard.monitor.diagnostic.DiagnosticStepResult
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.QualityReportAvailability
import dev.dankyeeter.btdashboard.monitor.sampling.LinkSampleCollector
import dev.dankyeeter.btdashboard.monitor.sampling.MonitorStatus
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingPolicy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DiagnosticUiState(
    val running: Boolean = false,
    val steps: List<DiagnosticStepResult> = emptyList(),
    val report: DiagnosticReport? = null,
    /** Why the run did not start, or why it ended early. Null while healthy. */
    val message: String? = null,
    /**
     * Whether [message] reports a fault. Stopping the run yourself is not one,
     * and painting "Test stopped." in the error colour told the user something
     * had gone wrong when they had simply pressed the button.
     */
    val messageIsError: Boolean = false,
)

class MonitorViewModel : ViewModel() {

    /** Timeline window: the last two hours is what correlating a dropout needs. */
    private val windowMs = 2 * 60 * 60 * 1000L
    private val since get() = System.currentTimeMillis() - windowMs

    val events: StateFlow<List<MonitorEvent>> = MonitorGraph.repository.events(since)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val samples: StateFlow<List<LinkQualitySample>> = MonitorGraph.repository.samples(since)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val status: StateFlow<MonitorStatus> = MonitorGraph.engine.status

    private val _bqr = MutableStateFlow<QualityReportAvailability>(
        QualityReportAvailability.Unavailable("not checked"),
    )
    val bqrAvailability: StateFlow<QualityReportAvailability> = _bqr.asStateFlow()

    private val _diagnostic = MutableStateFlow(DiagnosticUiState())
    val diagnostic: StateFlow<DiagnosticUiState> = _diagnostic.asStateFlow()

    private var diagnosticJob: Job? = null

    init {
        // The sampler polls only while something is playing or a screen that
        // shows the numbers is up. This ViewModel exists exactly as long as the
        // Monitor screen's nav entry does, so its lifetime is that signal.
        MonitorGraph.setUiVisible(true)
        MonitorGraph.ensureRunning()
        viewModelScope.launch { _bqr.value = MonitorGraph.qualityReportSource.start() }
    }

    /** Which source is actually feeding the timeline right now. */
    /**
     * Reported from the samples that were actually written, not from what the
     * sources claim they could do — the codec source reports "available" as
     * soon as *either* the system API or the shell fallback works, so asking it
     * would credit the API for rows the shell produced.
     */
    fun activeSource(): LinkDataSource = when {
        _bqr.value.isActive -> LinkDataSource.QUALITY_REPORT
        // Fall back to what the collector *can* reach when no sample has been
        // written yet: the samples flow is WhileSubscribed, so it is legitimately
        // empty for the first seconds on this screen, and reporting "no source"
        // there reads as a failure rather than as a cold start.
        else -> samples.value.lastOrNull()?.source
            ?: MonitorGraph.collectorSource()
    }

    fun startDeepCapture() {
        MonitorGraph.engine.startDeepCapture(SamplingPolicy.DEEP_CAPTURE_WINDOW_MS)
        MonitorGraph.ensureRunning()
    }

    fun stopDeepCapture() = MonitorGraph.engine.stopDeepCapture()

    /**
     * Runs the guided "test device" routine against the first connected device.
     * The soak is deliberately short here; the runner itself takes any length.
     */
    fun runDiagnostic(soakMinutes: Int = 3) {
        if (_diagnostic.value.running) return
        diagnosticJob = viewModelScope.launch {
            // The previous report deliberately survives the start of a new run.
            // Clearing it emptied the only thing on the panel worth reading the
            // moment the user asked for a fresh look, and left three minutes of
            // nothing to compare the new run against.
            _diagnostic.value = _diagnostic.value.copy(
                running = true,
                steps = emptyList(),
                message = null,
            )
            val device = MonitorGraph.codecSource.connectedDevices().firstOrNull()
            if (device == null) {
                // Used to reset silently, which looked exactly like a frozen
                // button: the run needs a connected A2DP sink to test against.
                _diagnostic.value = _diagnostic.value.copy(
                    running = false,
                    message = "Connect a headphone first — the test needs a live link.",
                    messageIsError = true,
                )
                return@launch
            }
            val runner = DeviceDiagnosticRunner(
                codecSource = MonitorGraph.codecSource,
                collector = LinkSampleCollector(
                    codecSource = MonitorGraph.codecSource,
                    // The shared, TTL-cached reader — never a fresh
                    // ShellDumpsysLinkSource. A diagnostic soak samples on the
                    // deep-capture interval, and each of those runs would
                    // otherwise pay for its own exec-and-parse of the dump.
                    dumpsysSource = MonitorGraph.dumpsysSource,
                    qualityReportSource = MonitorGraph.qualityReportSource,
                ),
                // Real codec control when the privileged helper is answering,
                // NoOpCodecController when it is not. The helper runs as
                // com.android.shell, which holds BLUETOOTH_PRIVILEGED
                // (granted=true, verified on the device), so
                // setCodecConfigPreference is reachable from inside it.
                //
                // Resolved here rather than in init: the helper can connect or
                // die between opening this screen and pressing the button.
                codecController = PrivilegedCodec.controller(),
            )
            MonitorGraph.engine.startDeepCapture(soakMinutes * 60_000L)
            val report = runner.run(
                address = device.address,
                soakDurationMs = soakMinutes * 60_000L,
            ) { step ->
                _diagnostic.value = _diagnostic.value.copy(
                    steps = _diagnostic.value.steps + step,
                )
            }
            _diagnostic.value = _diagnostic.value.copy(running = false, report = report)
        }
    }

    /**
     * Aborts a diagnostic in flight. The soak runs for minutes, so "wait it
     * out" is not an acceptable only option — and the deep capture it started
     * lives on the app-wide monitor scope, so it has to be stopped explicitly
     * rather than dying with the job.
     *
     * The steps that already finished stay on screen: they were really measured
     * and are still true after the stop, so throwing them away would discard the
     * only result the aborted run produced.
     */
    fun cancelDiagnostic() {
        diagnosticJob?.cancel()
        diagnosticJob = null
        MonitorGraph.engine.stopDeepCapture()
        _diagnostic.value = _diagnostic.value.copy(
            running = false,
            message = "Test stopped.",
            messageIsError = false,
        )
    }

    fun dismissDiagnosticMessage() {
        _diagnostic.value = _diagnostic.value.copy(message = null)
    }

    override fun onCleared() {
        // Leaving the screen must not leave deep capture burning battery —
        // nor idle polling for a screen that is no longer there.
        MonitorGraph.setUiVisible(false)
        if (_diagnostic.value.running) MonitorGraph.engine.stopDeepCapture()
        super.onCleared()
    }
}
