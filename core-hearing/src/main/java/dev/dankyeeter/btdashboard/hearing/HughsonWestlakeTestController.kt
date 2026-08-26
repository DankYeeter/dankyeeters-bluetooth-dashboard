package dev.dankyeeter.btdashboard.hearing

import android.util.Log
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.tone.ToneEar
import dev.dankyeeter.btdashboard.audio.tone.ToneGenerator
import dev.dankyeeter.btdashboard.hearing.level.VolumeGuard
import dev.dankyeeter.btdashboard.hearing.noise.MicAmbientNoiseCheck
import dev.dankyeeter.btdashboard.hearing.protocol.EngineResult
import dev.dankyeeter.btdashboard.hearing.protocol.HughsonWestlakeEngine
import dev.dankyeeter.btdashboard.hearing.protocol.ProtocolConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Drives [HughsonWestlakeEngine] against a real [ToneGenerator].
 *
 * Everything protocol-shaped lives in the engine; this class only owns audio,
 * timing and the abort conditions:
 *  - pulsed tones with a randomised inter-stimulus interval so the listener
 *    cannot fall into a rhythm
 *  - silent catch trials look and last exactly like real ones
 *  - the media volume is latched at [prepare] and watched for the whole run;
 *    an external change (headphone buttons) aborts with
 *    [AbortReason.VOLUME_CHANGED] because every threshold measured so far
 *    refers to the old absolute level
 */
class HughsonWestlakeTestController(
    private val toneGenerator: ToneGenerator,
    private val watchdogScope: CoroutineScope,
    private val volumeGuard: VolumeGuard? = null,
    private val ambientNoiseCheck: AmbientNoiseCheck? = null,
    private val protocol: ProtocolConfig = ProtocolConfig(),
    private val timing: PresentationTiming = PresentationTiming(),
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : HearingTestController {

    private val _state = MutableStateFlow<HearingTestState>(HearingTestState.Idle)
    override val state: Flow<HearingTestState> = _state.asStateFlow()

    private val responded = AtomicBoolean(false)
    @Volatile private var abortReason: AbortReason? = null
    @Volatile private var config: HearingTestConfig? = null
    @Volatile private var ambientDbA: Double? = null

    /** False-positive bookkeeping of the last finished run, for the result UI. */
    @Volatile var reliability: RunReliability = RunReliability()
        private set

    override suspend fun prepare(config: HearingTestConfig): PrepareResult {
        this.config = config
        this.abortReason = null
        this.ambientDbA = null
        _state.value = HearingTestState.Idle

        if (!toneGenerator.start()) {
            return PrepareResult.Failed("No audio output could be opened. Connect your headphones and try again.")
        }
        toneGenerator.setToneActive(false)
        toneGenerator.setRampMs(timing.rampMs)
        toneGenerator.setLevelDbFs(protocol.minLevelDb)

        val guard = volumeGuard
        if (guard != null && !guard.applyTestVolume(config.testVolumeFraction)) {
            // Only reachable when the device refused the change (fixed-volume
            // output, DND policy) and what is actually set is unusable.
            return PrepareResult.Failed(
                "This device would not let the app set the media volume, and it is currently too low " +
                    "for the test. Turn it up to about two thirds, then start again.",
            )
        }

        if (config.runAmbientNoiseCheck) {
            val measured = ambientNoiseCheck?.measureDbA()
            ambientDbA = measured
            if (measured != null && measured >= MicAmbientNoiseCheck.WARN_THRESHOLD_DB) {
                return PrepareResult.Warning(
                    "Background noise is ${MicAmbientNoiseCheck.describe(measured)} " +
                        "(about ${measured.toInt()} dB). Quiet tones may be masked — a quieter room gives a better result.",
                )
            }
        }
        return PrepareResult.Ready
    }

    override suspend fun start() {
        val config = requireNotNull(config) { "prepare() must run before start()" }
        val ears = when (config.ear) {
            null -> listOf(Ear.LEFT, Ear.RIGHT)
            else -> listOf(config.ear)
        }

        volumeGuard?.startWatchdog(watchdogScope) { _, _ -> abortReason = AbortReason.VOLUME_CHANGED }

        val results = mutableMapOf<Ear, EngineResult>()
        try {
            for (ear in ears) {
                val result = runEar(ear, config) ?: return finishAborted()
                results[ear] = result
            }
        } finally {
            silence()
            volumeGuard?.stopWatchdog()
        }

        reliability = RunReliability(
            catchTrials = results.values.sumOf { it.catchTrials },
            falsePositives = results.values.sumOf { it.falsePositives },
            unreliable = results.values.any { it.isUnreliable(protocol) },
        )

        _state.value = HearingTestState.Completed(
            AudiogramRun(
                id = idFactory(),
                timestampMillis = clock(),
                deviceAddressHash = null,
                calibrationPresetId = config.calibrationPresetId,
                ancMode = config.ancMode,
                ambientNoiseDbA = ambientDbA,
                left = results[Ear.LEFT]?.points.orEmpty(),
                right = results[Ear.RIGHT]?.points.orEmpty(),
                volumeFraction = config.testVolumeFraction,
            ),
        )
    }

    override fun onUserResponse() {
        responded.set(true)
    }

    override suspend fun abort(reason: AbortReason) {
        abortReason = reason
        silence()
        volumeGuard?.stopWatchdog()
        _state.value = HearingTestState.Aborted(reason)
    }

    /** Releases the audio stream. Call when the test screen goes away. */
    fun release() {
        silence()
        volumeGuard?.release()
        toneGenerator.stop()
    }

    private suspend fun runEar(ear: Ear, config: HearingTestConfig): EngineResult? {
        val engine = HughsonWestlakeEngine(config.frequenciesHz, protocol, random)
        toneGenerator.setEar(ear.toToneEar())

        while (true) {
            if (abortReason != null || !currentCoroutineContext().isActive) return null
            when (val step = engine.next()) {
                is HughsonWestlakeEngine.Step.Finished -> return step.result
                is HughsonWestlakeEngine.Step.Present -> {
                    _state.value = HearingTestState.Presenting(
                        ear = ear,
                        frequencyHz = step.frequencyHz,
                        levelDb = step.levelDb,
                        frequencyIndex = step.frequencyIndex,
                        frequencyCount = config.frequenciesHz.size,
                    )
                    // Randomised silence before the stimulus: the listener must
                    // not be able to predict when a tone is due.
                    delay(timing.randomInterStimulusMs(random))
                    if (abortReason != null) return null
                    val heard = present(step)
                    engine.record(heard)
                }
            }
        }
    }

    /** Plays (or, for a catch trial, does not play) one stimulus and waits for the answer. */
    private suspend fun present(step: HughsonWestlakeEngine.Step.Present): Boolean {
        responded.set(false)
        if (!step.catchTrial) {
            toneGenerator.setFrequency(step.frequencyHz.toDouble())
            toneGenerator.setLevelDbFs(step.levelDb)
        }

        repeat(timing.pulseCount) { index ->
            if (!step.catchTrial) toneGenerator.setToneActive(true)
            if (waitForResponse(timing.pulseMs)) {
                silence()
                return true
            }
            silence()
            if (index < timing.pulseCount - 1 && waitForResponse(timing.pulseGapMs)) return true
        }
        // A late press still counts: reaction times of 1 s are normal.
        return waitForResponse(timing.responseWindowMs)
    }

    /** @return true as soon as the user answered inside [durationMs]. */
    private suspend fun waitForResponse(durationMs: Long): Boolean {
        var waited = 0L
        while (waited < durationMs) {
            if (responded.get()) return true
            if (abortReason != null) return false
            delay(POLL_MS)
            waited += POLL_MS
        }
        return responded.get()
    }

    private fun silence() {
        runCatching { toneGenerator.setToneActive(false) }
            .onFailure { Log.w(TAG, "could not gate the tone off", it) }
    }

    private fun finishAborted() {
        val reason = abortReason ?: AbortReason.USER_CANCELLED
        _state.value = HearingTestState.Aborted(reason)
    }

    private fun Ear.toToneEar(): ToneEar = when (this) {
        Ear.LEFT -> ToneEar.LEFT
        Ear.RIGHT -> ToneEar.RIGHT
    }

    private companion object {
        const val TAG = "HwTestController"
        const val POLL_MS = 25L
    }
}

/** Stimulus timing. Pulsed tones are standard practice — they are easier to detect than steady ones. */
data class PresentationTiming(
    val pulseMs: Long = 220,
    val pulseGapMs: Long = 200,
    val pulseCount: Int = 3,
    /** Extra time after the last pulse in which a press still counts. */
    val responseWindowMs: Long = 900,
    val minInterStimulusMs: Long = 900,
    val maxInterStimulusMs: Long = 2_400,
    val rampMs: Double = 30.0,
) {
    fun randomInterStimulusMs(random: Random): Long =
        random.nextLong(minInterStimulusMs, maxInterStimulusMs + 1)
}

/** Catch-trial statistics of a finished run. */
data class RunReliability(
    val catchTrials: Int = 0,
    val falsePositives: Int = 0,
    val unreliable: Boolean = false,
) {
    val summary: String
        get() = when {
            catchTrials == 0 -> "No catch trials were presented."
            falsePositives == 0 -> "$catchTrials silent catch trials, no false presses."
            else -> "$falsePositives false press(es) in $catchTrials silent catch trials."
        }
}
