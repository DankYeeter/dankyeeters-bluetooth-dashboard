package dev.dankyeeter.btdashboard.system.attach

import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single entry point the UI talks to. Picks the strategy with the widest reach
 * that actually works, and reports honestly which one is active.
 *
 * Order: global first, session broadcast as fallback. If the global path is
 * unavailable the session strategy stays installed so the EQ at least works
 * for well-behaved players.
 *
 * @param setSessionReceiverEnabled toggles the *manifest* session receiver to
 *   match the mode. In global mode those broadcasts would wake the process on
 *   every track change of every player, only to be dropped — so the component
 *   is off unless session mode is genuinely in use. Injected as a lambda
 *   because toggling needs a Context and this class deliberately has none.
 */
class EqController(
    private val global: GlobalAttachmentStrategy,
    private val session: SessionAttachmentStrategy,
    private val setSessionReceiverEnabled: (Boolean) -> Unit = {},
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
            setSessionReceiverEnabled(false)
            session.deactivate()
            active = global
            _status.value = globalStatus
            return
        }

        // Fallback: session mode. Only now is the manifest receiver worth its
        // wake-ups, so only now is it switched on.
        AudioEffectSessionReceiver.strategy = session
        setSessionReceiverEnabled(true)
        val sessionStatus = session.activate(settings)
        active = session
        _status.value = sessionStatus
    }

    /**
     * Re-attaches if the effect died since the last call.
     *
     * Switching Bluetooth device tears down the output mix on some builds and
     * takes the global effect with it. The strategy has always known how to
     * recover, but nothing ever asked it to outside of a slider drag — so an
     * EQ that died while the app sat idle stayed dead until the user happened
     * to touch a control. A connect is exactly the moment to check.
     */
    fun ensureAttached() {
        if (!settings.enabled) return
        val current = active
        if (current == null) {
            apply(settings)
            return
        }
        if (current.status is AttachmentStatus.Inactive) apply(settings)
        _status.value = current.status
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
        setSessionReceiverEnabled(false)
        active = null
        _status.value = AttachmentStatus.Inactive
    }

    override fun close() = deactivate()
}
