package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear
import kotlinx.coroutines.flow.Flow

/**
 * Contract for the modified Hughson-Westlake test driver (Worker B).
 *
 * Protocol recap the implementation must honour:
 *  - per frequency: ascend in 5 dB steps until a response, then down 10 dB and
 *    up 5 dB again; threshold = lowest level with >= 2 of 3 responses
 *  - strict per-ear presentation (one channel only, see ToneGenerator)
 *  - level steps happen digitally in the tone generator, never via system
 *    volume; the run is invalidated if the user changes media volume
 *  - pulsed tones with randomised inter-stimulus intervals so the listener
 *    cannot anticipate presentations
 */
interface HearingTestController {
    val state: Flow<HearingTestState>

    /** Prepares the run: opens the tone stream, latches the reference volume. */
    suspend fun prepare(config: HearingTestConfig): PrepareResult

    /** Starts the measurement. Emits progress through [state]. */
    suspend fun start()

    /** Called from the UI when the user reports hearing the tone. */
    fun onUserResponse()

    /** Aborts and releases audio resources. */
    suspend fun abort(reason: AbortReason)
}

data class HearingTestConfig(
    val ear: Ear?,               // null = both ears sequentially
    val frequenciesHz: List<Int> = TEST_FREQUENCIES_HZ,
    val calibrationPresetId: String,
    val ancMode: AncMode,
    val runAmbientNoiseCheck: Boolean = true,
)

sealed interface PrepareResult {
    data object Ready : PrepareResult
    data class Warning(val message: String) : PrepareResult   // e.g. ambient noise too high
    data class Failed(val message: String) : PrepareResult    // e.g. no audio output
}

enum class AbortReason { USER_CANCELLED, VOLUME_CHANGED, DEVICE_DISCONNECTED, AUDIO_ERROR }

sealed interface HearingTestState {
    data object Idle : HearingTestState
    data class Presenting(
        val ear: Ear,
        val frequencyHz: Int,
        val levelDb: Double,
        val frequencyIndex: Int,
        val frequencyCount: Int,
    ) : HearingTestState

    data class Aborted(val reason: AbortReason) : HearingTestState
    data class Completed(val run: AudiogramRun) : HearingTestState
}

/**
 * Ambient noise pre-check via microphone. Produces a warning only — this is a
 * living room, not a sound booth, and we say so.
 */
interface AmbientNoiseCheck {
    /** @return approximate dB(A), or null if RECORD_AUDIO was denied. */
    suspend fun measureDbA(durationMillis: Long = 3_000): Double?
}
