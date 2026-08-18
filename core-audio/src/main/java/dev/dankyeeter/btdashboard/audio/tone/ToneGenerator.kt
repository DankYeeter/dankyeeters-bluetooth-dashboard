package dev.dankyeeter.btdashboard.audio.tone

/** Ear routing for the test tone. Channel isolation is strict — see the C++ side. */
enum class ToneEar(val nativeValue: Int) {
    LEFT(0),
    RIGHT(1),
    BOTH(2),
}

/**
 * Sample-accurate pure-tone source for the hearing test.
 *
 * Contract for Worker B (:core-hearing):
 *  - Call [start] once per test run, [stop] in `onStop`/when the run ends.
 *  - Level steps are digital, in dBFS attenuation (`<= 0.0`). System/media
 *    volume must stay fixed for the whole run; Bluetooth absolute volume is
 *    far too coarse to carry 5 dB audiometry steps.
 *  - Set [frequency] and [ear] only while the tone is gated off, then pulse
 *    with [setToneActive]. Gate changes are ramped, so no click cues the user.
 *  - Everything here is safe to call from the main thread; the audio thread
 *    only reads lock-free atomics.
 */
interface ToneGenerator {
    /** True while an output stream is open and running. */
    val isRunning: Boolean

    /** Stream sample rate in Hz, or 0 when not running. */
    val sampleRate: Int

    /** Opens the output stream. Returns false if the device refused it. */
    fun start(): Boolean

    /** Stops and releases the output stream. Idempotent. */
    fun stop()

    /** Sine frequency in Hz. Change only while the tone is gated off. */
    fun setFrequency(hz: Double)

    /** Digital attenuation in dBFS; values above 0.0 are clamped to 0.0. */
    fun setLevelDbFs(db: Double)

    /** Selects the active ear. */
    fun setEar(ear: ToneEar)

    /** Gates the tone on/off using the configured ramp. */
    fun setToneActive(active: Boolean)

    /** Ramp length for gate/level changes in ms (default 30 ms). */
    fun setRampMs(ms: Double)
}
