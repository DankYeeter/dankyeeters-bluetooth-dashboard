package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.sameDevice
import dev.dankyeeter.btdashboard.privileged.PrivilegedCodec
import dev.dankyeeter.btdashboard.monitor.diagnostic.DeviceDiagnosticRunner
import dev.dankyeeter.btdashboard.monitor.diagnostic.DiagnosticReport
import dev.dankyeeter.btdashboard.monitor.diagnostic.DiagnosticStepResult
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.QualityReportAvailability
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxProbe
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveUpdate
import dev.dankyeeter.btdashboard.monitor.link.live.LiveLinkSource
import dev.dankyeeter.btdashboard.monitor.link.live.toMonitorEvent
import dev.dankyeeter.btdashboard.monitor.sampling.LinkSampleCollector
import dev.dankyeeter.btdashboard.monitor.sampling.MonitorStatus
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingPolicy
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import dev.dankyeeter.btdashboard.system.devices.CodecPreferenceController

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
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

/**
 * What the live LDAC quality control has to say about its last attempt.
 *
 * A separate state rather than a boolean because setting a codec preference has
 * three outcomes, not two: applied and read back, accepted but not observed, and
 * "could not even ask". The panel prints whichever one happened; see
 * [CodecApplyOutcome] for why the middle one is not called a failure.
 */
data class LdacTuningState(
    /** True while the request is in flight, so the chips cannot be double-tapped. */
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/**
 * The raw Bluetooth address for a device the live panel is showing, or null.
 *
 * ## Why this exists at all
 *
 * `LinkLiveSnapshot.device.address` comes out of `dumpsys`, and a **user build
 * redacts it** to `XX:XX:XX:XX:37:8F`. That string is the right one to show and
 * a broken one to act on: `setCodecPreference` takes a `BluetoothDevice`, so
 * handing the redacted form to the helper produced exactly the rejection this
 * function was written for — *"XX:XX:XX:XX:37:8F is not a Bluetooth address"* —
 * a control that looked live and could never work on a stock phone.
 *
 * The A2DP profile is the side that holds the real address, so the two are
 * joined on the last two octets the dump prints verbatim. That is the same join
 * `LinkSampleCollector` makes between the same two sources, through the same
 * [sameDevice] helper, rather than a second rule that could drift from it.
 *
 * Null when the profile lists nothing matching. Deliberately **not** a fallback
 * to [shownAddress]: falling back would reinstate the bug and dress it as an
 * unexplainable failure from the helper.
 */
internal fun rawAddressFor(shownAddress: String?, connected: List<BtAudioDevice>): String? {
    if (shownAddress.isNullOrBlank()) return null
    return connected.firstOrNull { sameDevice(it.address, shownAddress) }?.address
}

/**
 * A raw address in the form the platform's own dumps use on a user build.
 *
 * Display-only, and the last two octets stay verbatim on purpose: they are what
 * lets somebody tell two connected headphones apart, and they are what the
 * platform itself considers safe to print. Applying it twice changes nothing.
 */
internal fun maskAddress(address: String): String {
    val tail = address.takeLast(5)
    return if (tail.length == 5 && tail[2] == ':') "XX:XX:XX:XX:$tail" else address
}

/**
 * Masks every raw address inside a sentence that came from below the UI.
 *
 * The layers under this one work in real addresses and quote them back in their
 * own error text. One redaction on the boundary is more reliable than trusting
 * each of them never to include one, and it costs a regex on a string that is
 * only built when a message is actually shown.
 */
internal fun redactAddresses(text: String): String =
    RAW_ADDRESS.replace(text) { maskAddress(it.value) }

/** Six hex octets. Already-redacted addresses contain `X` and never match. */
private val RAW_ADDRESS = Regex("""\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""")

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

    // ---- live link -----------------------------------------------------------

    private val _liveIntervalMs = MutableStateFlow(LiveLinkSource.DEFAULT_INTERVAL_MS)

    /** How often the live panel polls. The cost of each rate is in the explainer. */
    val liveIntervalMs: StateFlow<Long> = _liveIntervalMs.asStateFlow()

    /**
     * The poll a live event was last taken from.
     *
     * `MonitorGraph.liveLinkUpdates` replays its last reading to a new
     * collector, so a rotation or a tab switch hands us an update we have
     * already written to the timeline. Without this guard one dropout would
     * appear twice in the event log for no reason the user could see.
     */
    private var lastRecordedPollMs = 0L

    /**
     * One poll loop, at whichever rate the panel asked for.
     *
     * The default rate goes through the graph's *shared* loop so that a second
     * screen watching the same link costs nothing extra. Any other rate needs a
     * loop of its own — the shared one is built at the default interval and
     * cannot be re-timed — and `flatMapLatest` guarantees only one of the two is
     * ever collected, which matters: a pass is three `dumpsys` execs and two
     * loops would double that for one panel.
     *
     * Events are taken from this same collection rather than from
     * `MonitorGraph.liveLinkEvents` for the same reason. That property is a
     * second view on the shared default loop, so collecting it beside a
     * non-default rate here would quietly start the polling twice over.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveUpdates: SharedFlow<LinkLiveUpdate> = _liveIntervalMs
        .flatMapLatest { interval ->
            if (interval == LiveLinkSource.DEFAULT_INTERVAL_MS) {
                MonitorGraph.liveLinkUpdates
            } else {
                MonitorGraph.liveLink.updates(interval)
            }
        }
        .onEach { update -> recordLiveEvents(update) }
        // Shared, not merely cold: the panel now reads this twice — once for the
        // numbers and once for the 60-second graph — and a second collector of
        // the cold branch above would start a second poll loop, i.e. double the
        // dumpsys cost for one screen. It also keeps [recordLiveEvents] running
        // exactly once per pass.
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(LIVE_STOP_TIMEOUT_MS), replay = 1)

    /**
     * The newest reading, or null before the first poll returns.
     *
     * `WhileSubscribed` is what makes the polling lifecycle-bound: the screen
     * collects this with `collectAsStateWithLifecycle`, so a backgrounded
     * Monitor tab stops the loop within one interval and an empty panel costs
     * nothing. Null is a real state and is worded as "waiting", never drawn as
     * zeroes.
     */
    val liveLink: StateFlow<LinkLiveSnapshot?> = liveUpdates
        .map { it.snapshot }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(LIVE_STOP_TIMEOUT_MS), null)

    private val _ldacTuning = MutableStateFlow(LdacTuningState())
    val ldacTuning: StateFlow<LdacTuningState> = _ldacTuning.asStateFlow()

    // ---- the two graphs ------------------------------------------------------
    //
    // Both are ring buffers of the same shape over two different channels, and
    // the split is the whole point:
    //
    //   overview  60 s, on the panel's own 1–5 s cadence, off the full pass —
    //             so it counts app, mixer *and* stack loss. "I had a problem in
    //             the last minute, show me where."
    //   close-up  10 s at two readings a second, off [A2dpTxProbe], which runs
    //             one dump instead of three — the only way a 500 ms cadence is
    //             possible at all (a full pass is ~550 ms of work). It sees the
    //             stack's loss only, and says so.
    //
    // Both windows are rebuilt rather than persisted when the screen comes back
    // after more than a few seconds: the poller stops with the screen, so the
    // held points would be a line about a period nothing was measuring. Stale
    // counters look exactly like current ones, which is the same reason the
    // graph's shared source expires its replay.

    /** The close-up costs a dump twice a second, so it is off until asked for. */
    private val _closeUpEnabled = MutableStateFlow(false)
    val closeUpEnabled: StateFlow<Boolean> = _closeUpEnabled.asStateFlow()

    /** Built once: it holds no state, only the shell delegate it resolves per call. */
    private val txProbe by lazy { A2dpTxProbe(MonitorGraph.shell) }

    val overviewTrace: StateFlow<LiveTrace> = liveUpdates
        .scan(LiveTrace.overview(LiveLinkSource.DEFAULT_INTERVAL_MS)) { trace, update ->
            trace.append(update.snapshot, _liveIntervalMs.value)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(LIVE_STOP_TIMEOUT_MS),
            LiveTrace.overview(LiveLinkSource.DEFAULT_INTERVAL_MS),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val closeUpTrace: StateFlow<LiveTrace> = _closeUpEnabled
        .flatMapLatest { enabled ->
            if (!enabled) {
                flowOf(EMPTY_CLOSE_UP)
            } else {
                txProbe.samples(A2dpTxProbe.DEFAULT_INTERVAL_MS)
                    .scan(EMPTY_CLOSE_UP) { trace, sample ->
                        trace.plus(sample.toTracePoint())
                            .withReason(sample.unavailable, sample.observability)
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(LIVE_STOP_TIMEOUT_MS), EMPTY_CLOSE_UP)

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

    /** Changes the live poll rate. Takes effect on the next pass, not this one. */
    fun setLiveIntervalMs(intervalMs: Long) {
        _liveIntervalMs.value = intervalMs
    }

    /**
     * Turns the two-per-second close-up on or off.
     *
     * Switching it off stops the probe within one pass — the flow it drives is
     * `flatMapLatest`, so the loop is cancelled rather than left running behind
     * a hidden graph. The window is not kept: ten seconds of a link nobody is
     * watching is not worth a dump every 500 ms.
     */
    fun setCloseUpEnabled(enabled: Boolean) {
        _closeUpEnabled.value = enabled
    }

    /**
     * Writes this poll's changes to the same store the timeline reads.
     *
     * The live view and the timeline are two renderings of one link, and the
     * events that only the live poller can see — a dropout inside a two-second
     * window, an LDAC mode change — used to exist for as long as the panel was
     * open and then vanish. Recording them here puts them in the event log and
     * on the timeline beside the connects and takeovers, which is where somebody
     * correlating "it stuttered at 14:31" will look.
     */
    private suspend fun recordLiveEvents(update: LinkLiveUpdate) {
        if (update.events.isEmpty()) return
        if (update.snapshot.timestampMs <= lastRecordedPollMs) return
        lastRecordedPollMs = update.snapshot.timestampMs
        update.events.forEach { event ->
            MonitorGraph.repository.recordEvent(
                event.toMonitorEvent(
                    deviceAddress = update.snapshot.device?.address,
                    deviceName = update.snapshot.device?.name,
                ),
            )
        }
    }

    /**
     * Pins LDAC to one playback quality — or to adaptive — on the live device.
     *
     * The same mechanism the per-device profile editor uses: a [CodecPreference]
     * carrying AOSP's `codecSpecific1`, handed to the privileged helper's
     * [CodecPreferenceController], which reads the codec back and reports what it
     * *saw*. The difference is only in when it runs — the editor stores the wish
     * and replays it on every connect, this sets it for the link that is playing
     * right now.
     *
     * The controller is resolved by asking the installed codec controller
     * whether it can also take preferences. `:app` installs one object in both
     * `PrivilegedCodec` and `SystemGraph` precisely so there is a single answer
     * about whether the helper is there; when it is not, the stand-in cannot
     * take preferences and the user is told that rather than shown a control
     * that silently does nothing.
     */
    fun setLdacQuality(quality: Long) {
        if (_ldacTuning.value.busy) return
        val shownAddress = liveLink.value?.device?.address
        if (shownAddress == null) {
            _ldacTuning.value = LdacTuningState(
                message = "No live link to change — connect the headphone first.",
                messageIsError = true,
            )
            return
        }
        viewModelScope.launch {
            _ldacTuning.value = LdacTuningState(busy = true)
            // The address the panel shows cannot be the one the call is made
            // with; see [rawAddressFor]. Failing to build one is an outcome like
            // any other and is worded, never silently swallowed.
            val connected = runCatching { MonitorGraph.codecSource.connectedDevices() }
                .getOrDefault(emptyList())
            val address = rawAddressFor(shownAddress, connected)
            val controller = PrivilegedCodec.controller() as? CodecPreferenceController
            val outcome = when {
                address == null -> CodecApplyOutcome.Unavailable(
                    "Android is not listing this headphone on the A2DP profile, and the " +
                        "address in the dump is redacted — there is no device to set a " +
                        "codec on",
                )

                controller == null -> CodecApplyOutcome.Unavailable(
                    "the privileged helper is not running, so LDAC quality cannot be set",
                )

                else -> runCatching {
                    controller.apply(address, CodecPreference("LDAC", ldacQuality = quality))
                }.getOrElse { CodecApplyOutcome.Unavailable(it.message ?: "the request threw") }
            }
            _ldacTuning.value = LdacTuningState(
                busy = false,
                // Everything below the UI works in raw addresses and some of it
                // quotes them back — the helper's own rejection sentence names
                // the address it was handed. Redacting on the way out keeps that
                // useful without putting a real MAC on screen.
                message = redactAddresses(
                    when (outcome) {
                        is CodecApplyOutcome.Applied ->
                            "LDAC is now ${outcome.observed} — read back, not just requested."

                        // Not worded as a failure: nothing an app can reach tells
                        // a refusal apart from a renegotiation still in flight.
                        is CodecApplyOutcome.NotObserved ->
                            "The link still reads ${outcome.observed}: ${outcome.detail}."

                        is CodecApplyOutcome.Unavailable ->
                            "LDAC quality was not changed — ${outcome.reason}."
                    },
                ),
                messageIsError = outcome is CodecApplyOutcome.Unavailable,
            )
        }
    }

    fun dismissLdacMessage() {
        _ldacTuning.value = _ldacTuning.value.copy(message = null)
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

    private companion object {
        /**
         * Long enough that a rotation does not restart the poll loop, short
         * enough that leaving the screen stops it within one interval. Mirrors
         * the stop timeout the graph's shared loop uses.
         */
        const val LIVE_STOP_TIMEOUT_MS = 3_000L

        /** The close-up's resting state, and what it falls back to when switched off. */
        val EMPTY_CLOSE_UP = LiveTrace.closeUp(A2dpTxProbe.DEFAULT_INTERVAL_MS)
    }
}
