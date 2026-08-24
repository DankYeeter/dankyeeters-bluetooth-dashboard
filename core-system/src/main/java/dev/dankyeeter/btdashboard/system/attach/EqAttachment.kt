package dev.dankyeeter.btdashboard.system.attach

import dev.dankyeeter.btdashboard.audio.eq.EqSettings

/**
 * How the EQ effect gets attached to audio it does not own.
 *
 * Two strategies, tried in order of reach:
 *  1. [GlobalAttachmentStrategy] — attach to session 0 (the global output mix)
 *     with elevated privileges obtained through the helper. This is the only path
 *     that reaches Tidal reliably, and it is what Wavelet-style apps do.
 *  2. [SessionAttachmentStrategy] — no-privilege fallback: attach to the
 *     sessions that apps announce via
 *     `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`. Works for well-behaved
 *     players only; Tidal does not broadcast reliably.
 *
 * Everything the UI sees goes through this interface, so the global path can be
 * iterated on (or fail on a given ROM) without any UI change.
 */
interface EqAttachmentStrategy : AutoCloseable {
    val kind: AttachmentKind

    /** Current status; the UI is honest about which reach is actually active. */
    val status: AttachmentStatus

    /**
     * Attempts to become active with the given settings.
     * Must never throw — returns the resulting status instead.
     */
    fun activate(settings: EqSettings): AttachmentStatus

    /** Pushes new settings to whatever is currently attached. */
    fun update(settings: EqSettings)

    /** Detaches everything; safe to call repeatedly. */
    fun deactivate()

    override fun close() = deactivate()
}

enum class AttachmentKind { GLOBAL, SESSION_BROADCAST }

sealed interface AttachmentStatus {
    data object Inactive : AttachmentStatus

    /** Attached to the global mix — reaches every app. */
    data class ActiveGlobal(val sessionId: Int) : AttachmentStatus

    /** Attached to one or more announced sessions only. */
    data class ActiveSessions(val sessionIds: Set<Int>) : AttachmentStatus

    /** Could not attach; [reason] is user-facing English text. */
    data class Unavailable(val reason: String) : AttachmentStatus
}
