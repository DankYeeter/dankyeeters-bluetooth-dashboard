package dev.dankyeeter.btdashboard.audio.eq

/**
 * Handle on one attached EQ instance.
 *
 * Architecture note (important, and a correction of the original spec):
 * other apps' audio never passes through our process, so a C++/Oboe DSP can
 * never equalize Tidal. The system EQ is therefore an Android `AudioEffect` —
 * `DynamicsProcessing` (API 28+) — attached to an audio session in the system
 * mixer. Oboe/NDK is used only for the hearing-test tone generator.
 *
 * An instance is bound to exactly one audio session id. Session `0` means
 * "global output mix" and requires the elevated attach path (the helper), see
 * :core-system.
 */
interface SystemEqualizer : AutoCloseable {
    /** The audio session this instance is attached to (0 = global mix). */
    val sessionId: Int

    /** False once the effect died (session gone, device switched, OEM quirk). */
    val isAlive: Boolean

    /** Applies a full settings snapshot. Cheap enough for slider drags. */
    /** Band layout the live effect is currently built for. */
    val activeLayout: EqBandLayout get() = EqBandLayout.DEFAULT

    fun apply(settings: EqSettings)

    /** Updates a single band gain without rewriting the whole config. */
    fun setBandGain(ear: Ear, bandIndex: Int, gainDb: Float)

    /** Input gain in dB, applied before the bands (negative = headroom). */
    fun setPreGain(db: Float)

    /** Enables/disables the whole effect without detaching it. */
    fun setEnabled(enabled: Boolean)

    /** Detaches and releases the underlying effect. Idempotent. */
    override fun close()
}

/**
 * Creates [SystemEqualizer] instances. :core-system owns the decision *which*
 * session to attach to (session mode vs. global via the helper); this factory only
 * knows how to build an effect for a given session id.
 */
interface SystemEqualizerFactory {
    /**
     * @return a live equalizer, or null if the effect could not be created
     *   (no permission for this session, OEM without DynamicsProcessing,
     *   session already gone). Callers must degrade gracefully, never crash.
     */
    fun create(sessionId: Int): SystemEqualizer?
}
