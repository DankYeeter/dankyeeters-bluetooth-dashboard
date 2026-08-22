package dev.dankyeeter.btdashboard.monitor.sampling

import dev.dankyeeter.btdashboard.monitor.data.MonitorRepository
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventSource
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.link.TakeoverAnnotator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** What the Monitor screen needs to describe the monitor's own state. */
data class MonitorStatus(
    val running: Boolean = false,
    val mode: SamplingMode = SamplingMode.STOPPED,
    val reason: String = "not started",
    val deepCaptureUntilMs: Long = 0L,
)

/**
 * Ties the event stream and the adaptive sampler together.
 *
 * The scheduling itself is [SamplingPolicy] (pure, unit-tested); this class is
 * the plumbing around it. [sleep] is injectable so the loop can be driven by a
 * virtual clock in tests instead of real time.
 */
class MonitorEngine(
    private val repository: MonitorRepository,
    private val eventSource: MonitorEventSource,
    private val collector: LinkSampleCollector,
    private val screenOn: StateFlow<Boolean>,
    /**
     * Whether a screen that displays link data is in the foreground. Defaults
     * to false so that anything constructing an engine without wiring the UI
     * gets the cheap behaviour, never the expensive one.
     */
    private val uiVisible: StateFlow<Boolean> = MutableStateFlow(false),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val idleTickMs: Long = 30_000L,
) {

    private val annotator = TakeoverAnnotator()
    private val _status = MutableStateFlow(MonitorStatus())
    val status: StateFlow<MonitorStatus> = _status.asStateFlow()

    private var deepCaptureUntilMs = 0L
    private var burstUntilMs = 0L
    private val lastSamples = mutableMapOf<String, LinkQualitySample>()
    private var jobs = mutableListOf<Job>()
    private val wake = MutableStateFlow(0L)

    fun start(scope: CoroutineScope) {
        if (_status.value.running) return
        _status.value = _status.value.copy(running = true, reason = "starting")
        // The repository has always had a purge; nothing ever called it, so the
        // tables grew without bound. History is diagnostic data with a two-hour
        // display window — keeping a day of it is already generous.
        jobs += scope.launch {
            runCatching { repository.purgeOlderThan(clock() - RETENTION_MS) }
        }
        jobs += scope.launch { collectEvents() }
        jobs += scope.launch { sampleLoop() }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        _status.value = MonitorStatus(running = false, reason = "stopped")
    }

    /** "Watch live" quick action: 10-second resolution for a bounded window. */
    fun startDeepCapture(durationMs: Long = SamplingPolicy.DEEP_CAPTURE_WINDOW_MS) {
        deepCaptureUntilMs = clock() + durationMs
        signalWake()
    }

    fun stopDeepCapture() {
        deepCaptureUntilMs = 0L
    }

    private suspend fun collectEvents() {
        eventSource.events()
            .catch { /* a dead broadcast stream must not kill the monitor */ }
            .collect { event ->
                repository.recordEvent(event)
                annotator.onEvent(event).forEach { repository.recordEvent(it) }
                if (event.type == MonitorEventType.CODEC_CHANGED) {
                    burstUntilMs = clock() + SamplingPolicy.BURST_WINDOW_MS
                }
                // A connect, a disconnect or playback starting all mean the
                // idle wait should end now rather than at the next ceiling.
                signalWake()
            }
    }

    private suspend fun sampleLoop() {
        while (currentScopeActive()) {
            val decision = SamplingPolicy.decide(
                MonitorConditions(
                    nowMs = clock(),
                    isPlaying = annotator.anyPlaying,
                    isScreenOn = screenOn.value,
                    uiVisible = uiVisible.value,
                    deepCaptureUntilMs = deepCaptureUntilMs,
                    burstUntilMs = burstUntilMs,
                ),
            )
            annotator.flushPending(clock()).forEach { repository.recordEvent(it) }

            when (decision) {
                is SamplingDecision.Stopped -> {
                    _status.value = _status.value.copy(
                        mode = SamplingMode.STOPPED,
                        reason = decision.reason,
                        deepCaptureUntilMs = deepCaptureUntilMs,
                    )
                    // Nothing to measure. Every condition that could end this
                    // state is signal-driven — the screen turning on, a
                    // Bluetooth broadcast, an explicit deep-capture request —
                    // so the loop waits for one instead of re-evaluating on a
                    // timer. A 30-second idle tick was 2,880 wake-ups a day to
                    // discover, each time, that there was still nothing to do.
                    awaitWakeSignal()
                }

                is SamplingDecision.Poll -> {
                    _status.value = _status.value.copy(
                        mode = decision.mode,
                        reason = decision.reason,
                        deepCaptureUntilMs = deepCaptureUntilMs,
                    )
                    // A poll that produced no rows at all means there is no
                    // Bluetooth audio device to describe — not that this one
                    // reading was empty. Repeating it on the interval is the
                    // 30-second idle poll all over again, one level down:
                    // `ensureRunning()` now runs from Application.onCreate and
                    // the process is kept alive by the EQ service, so "idle,
                    // screen on" otherwise means a sample every 60 s for as
                    // long as the phone is awake, each one a codec query plus a
                    // `dumpsys` exec through the helper, with nothing connected
                    // to measure.
                    //
                    // Every way a device can appear — ACL connect, A2DP
                    // connection state, active-device change — is a broadcast
                    // that already calls signalWake(), so waiting for one loses
                    // nothing. A reading that *did* produce rows keeps the
                    // interval: that is the case the policy is about.
                    val rows = runCatching { sampleOnce() }.getOrNull()
                    if (rows == 0) {
                        _status.value = _status.value.copy(
                            mode = SamplingMode.STOPPED,
                            reason = "no Bluetooth audio device to sample",
                        )
                        awaitWakeSignal()
                    } else {
                        sleep(decision.intervalMs)
                    }
                }
            }
        }
    }

    /**
     * Suspends until something could plausibly have changed.
     *
     * [idleTickMs] survives as a ceiling rather than a period: if a signal is
     * ever missed the loop still re-checks eventually, but a healthy idle costs
     * nothing at all.
     */
    private suspend fun awaitWakeSignal() {
        val seen = wake.value
        // Each leg waits for a *change*, not for a value. A `StateFlow` replays
        // its current value to a new collector, so `screenOn.filter { it }`
        // would return instantly in any stopped state that has the screen on —
        // which, now that "screen on" no longer implies polling, is a normal
        // state and would spin the loop.
        val screenSeen = screenOn.value
        val uiSeen = uiVisible.value
        withTimeoutOrNull(idleTickMs * IDLE_CEILING_FACTOR) {
            merge(
                screenOn.filter { it != screenSeen },
                uiVisible.filter { it != uiSeen },
                wake.filter { it != seen },
            ).first()
        }
    }

    /**
     * Bumped whenever something happens that the sampler should react to.
     *
     * A counter, not a timestamp. Stamping [clock] here meant two signals
     * inside the same millisecond were one signal, and — worse — a signal at
     * time zero was no signal at all, because zero is also the flow's initial
     * value. An incrementing counter cannot collide with itself.
     */
    private fun signalWake() {
        wake.value = wake.value + 1
    }

    /** @return how many rows were written; zero means nothing was connected. */
    private suspend fun sampleOnce(): Int {
        val samples = collector.collect()
        samples.forEach { sample ->
            repository.recordSample(sample)
            val previous = lastSamples.put(sample.deviceAddress, sample)
            val anomalies = AnomalyDetector.detect(previous, sample)
            if (anomalies.isNotEmpty()) {
                burstUntilMs = clock() + SamplingPolicy.BURST_WINDOW_MS
                repository.recordEvent(
                    MonitorEvent(
                        timestampMs = sample.timestampMs,
                        deviceAddress = sample.deviceAddress,
                        deviceName = null,
                        type = MonitorEventType.QUALITY_REPORT,
                        detail = anomalies.joinToString("; "),
                        codec = sample.codec,
                        bitrateKbps = sample.bitrateKbps,
                    ),
                )
            }
        }
        return samples.size
    }

    private suspend fun currentScopeActive(): Boolean =
        kotlin.coroutines.coroutineContext[Job]?.isActive ?: true

    private companion object {
        /** How much link history is kept on disk. */
        const val RETENTION_MS = 24 * 60 * 60 * 1000L

        /** Idle re-check ceiling, as a multiple of [idleTickMs]. */
        const val IDLE_CEILING_FACTOR = 20
    }

}
