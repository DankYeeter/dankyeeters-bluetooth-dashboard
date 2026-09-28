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
    fun apply(settings: EqSettings)

    /** Detaches and releases the underlying effect. Idempotent. */
    override fun close()
}
