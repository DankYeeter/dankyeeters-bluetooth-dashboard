package dev.dankyeeter.btdashboard.system.attach

import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single entry point the UI talks to. Picks the strategy with the widest reach
 * that actually works, and reports honestly which one is active.
 *
 * Order: global (Shizuku) first, session broadcast as fallback. If the global
 * path is unavailable the session strategy stays installed so the EQ at least
 * works for well-behaved players.
 */
class EqController(
    private val global: GlobalAttachmentStrategy,
    private val session: SessionAttachmentStrategy,
) : AutoCloseable {

    private val _status = MutableStateFlow<AttachmentStatus>(AttachmentStatus.Inactive)
    val status: StateFlow<AttachmentStatus> = _status.asStateFlow()

    private var active: EqAttachmentStrategy? = null
    private var settings: EqSettings = EqSettings.FLAT

    /** Applies settings, attaching (or re-attaching) as needed. */
    fun apply(newSettings: EqSettings) {
        settings = newSettings.sanitized()
        if (!settings.enabled) {
            deactivate()
            return
        }

        val globalStatus = global.activate(settings)
        if (globalStatus is AttachmentStatus.ActiveGlobal) {
            AudioEffectSessionReceiver.strategy = null
            session.deactivate()
            active = global
            _status.value = globalStatus
            return
        }

        // Fallback: session mode.
        AudioEffectSessionReceiver.strategy = session
        val sessionStatus = session.activate(settings)
        active = session
        _status.value = sessionStatus
    }

    /** Pushes a live update (slider drag) without re-running attachment. */
    fun update(newSettings: EqSettings) {
        settings = newSettings.sanitized()
        active?.update(settings) ?: apply(settings)
        active?.let { _status.value = it.status }
    }

    fun deactivate() {
        global.deactivate()
        session.deactivate()
        AudioEffectSessionReceiver.strategy = null
        active = null
        _status.value = AttachmentStatus.Inactive
    }

    override fun close() = deactivate()
}
