package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.monitor.codec.NoOpCodecController
import dev.dankyeeter.btdashboard.monitor.diagnostic.DeviceDiagnosticRunner
import dev.dankyeeter.btdashboard.monitor.diagnostic.DiagnosticReport
import dev.dankyeeter.btdashboard.monitor.diagnostic.DiagnosticStepResult
import dev.dankyeeter.btdashboard.monitor.dumpsys.ShellDumpsysLinkSource
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.QualityReportAvailability
import dev.dankyeeter.btdashboard.monitor.sampling.LinkSampleCollector
import dev.dankyeeter.btdashboard.monitor.sampling.MonitorStatus
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingPolicy
import dev.dankyeeter.btdashboard.monitor.shell.ShizukuShellRunner
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
        else -> samples.value.lastOrNull()?.source ?: LinkDataSource.NONE
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
            _diagnostic.value = DiagnosticUiState(running = true)
            val device = MonitorGraph.codecSource.connectedDevices().firstOrNull()
            if (device == null) {
                // Used to reset silently, which looked exactly like a frozen
                // button: the run needs a connected A2DP sink to test against.
                _diagnostic.value = DiagnosticUiState(
                    running = false,
                    message = "No Bluetooth audio device is connected. Connect your headphones " +
                        "and start the diagnostic again.",
                )
                return@launch
            }
            val shell = ShizukuShellRunner()
            val runner = DeviceDiagnosticRunner(
                codecSource = MonitorGraph.codecSource,
                collector = LinkSampleCollector(
                    codecSource = MonitorGraph.codecSource,
                    dumpsysSource = ShellDumpsysLinkSource(shell),
                    qualityReportSource = MonitorGraph.qualityReportSource,
                ),
                // Forcing codecs needs privileges we have not verified on-device.
                codecController = NoOpCodecController,
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
     */
    fun cancelDiagnostic() {
        diagnosticJob?.cancel()
        diagnosticJob = null
        MonitorGraph.engine.stopDeepCapture()
        _diagnostic.value = _diagnostic.value.copy(
            running = false,
            message = "Diagnostic cancelled.",
        )
    }

    fun dismissDiagnosticMessage() {
        _diagnostic.value = _diagnostic.value.copy(message = null)
    }

    override fun onCleared() {
        // Leaving the screen must not leave deep capture burning battery.
        if (_diagnostic.value.running) MonitorGraph.engine.stopDeepCapture()
        super.onCleared()
    }
}
