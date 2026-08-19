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
            }
    }

    private suspend fun sampleLoop() {
        while (currentScopeActive()) {
            val decision = SamplingPolicy.decide(
                MonitorConditions(
                    nowMs = clock(),
                    isPlaying = annotator.anyPlaying,
                    isScreenOn = screenOn.value,
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
                    // Idle tick, not a poll: nothing is measured, we only
                    // re-evaluate whether conditions changed.
                    sleep(idleTickMs)
                }

                is SamplingDecision.Poll -> {
                    _status.value = _status.value.copy(
                        mode = decision.mode,
                        reason = decision.reason,
                        deepCaptureUntilMs = deepCaptureUntilMs,
                    )
                    runCatching { sampleOnce() }
                    sleep(decision.intervalMs)
                }
            }
        }
    }

    private suspend fun sampleOnce() {
        collector.collect().forEach { sample ->
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
    }

    private suspend fun currentScopeActive(): Boolean =
        kotlin.coroutines.coroutineContext[Job]?.isActive ?: true

    private companion object {
        /** How much link history is kept on disk. */
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
    }

}
