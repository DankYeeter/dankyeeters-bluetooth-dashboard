package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.hearing.level.VolumeGuard
import dev.dankyeeter.btdashboard.audio.eq.Ear

data class HearingTestConfig(
    val ear: Ear?,               // null = both ears sequentially
    val frequenciesHz: List<Int> = TEST_FREQUENCIES_HZ,
    val calibrationPresetId: String,
    val ancMode: AncMode,
    val runAmbientNoiseCheck: Boolean = true,
    /**
     * Fraction of the maximum media volume the run locks itself to.
     *
     * The default window bottoms out for someone who hears very well: every
     * point comes back at the floor, and the app cannot ask any quieter -- the
     * 16-bit link is out of digits. Lowering the *analogue* volume shifts the
     * whole measurable window down instead, without touching the digital
     * floor. The price is that thresholds only compare against runs taken at
     * the same setting, which the store enforces.
     */
    val testVolumeFraction: Double = VolumeGuard.TEST_VOLUME_FRACTION,
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
