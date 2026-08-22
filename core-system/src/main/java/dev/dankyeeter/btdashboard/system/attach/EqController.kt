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
 * @param globalAttachReachesOutput asked before every global attach. Attaching
 *   to the output mix succeeds over Bluetooth and then does nothing at all -
 *   measured, see [OutputMixReachGate]. Trusting the attach means shipping a
 *   silent EQ; asking first means falling back to session mode, which reaches
 *   fewer players but is audible. Defaults to "yes" so tests and callers that
 *   do not care are unaffected.
 * @param setSessionHarvestEnabled runs alongside the receiver, and for the same
 *   reason: players that never broadcast (Tidal) can only be reached by reading
 *   their session id through the privileged helper. Both are off in global mode
 *   - there the output mix already covers everything, so watching playback would
 *   be work with no product.
 * @param setSessionReceiverEnabled toggles the *manifest* session receiver to
 *   match the mode. In global mode those broadcasts would wake the process on
 *   every track change of every player, only to be dropped — so the component
 *   is off unless session mode is genuinely in use. Injected as a lambda
 *   because toggling needs a Context and this class deliberately has none.
 */
class EqController(
    // The interface, not the concrete class: the only thing this needs from the
    // global path is the strategy contract, and a seam here is what lets the
    // fallback be tested without an audio HAL. [session] stays concrete because
    // the broadcast receiver calls its session callbacks.
    private val global: EqAttachmentStrategy,
    private val session: SessionAttachmentStrategy,
    private val globalAttachReachesOutput: () -> Boolean = { true },
    private val setSessionReceiverEnabled: (Boolean) -> Unit = {},
    private val setSessionHarvestEnabled: (Boolean) -> Unit = {},
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

        // Ask before attaching, not after: a global attach that never reaches
        // the output still reports success, so its own status cannot be trusted
        // to notice.
        val globalIsInaudibleHere = !globalAttachReachesOutput()
        val globalStatus = if (!globalIsInaudibleHere) {
            global.activate(settings)
        } else {
            global.deactivate()
            AttachmentStatus.Unavailable(NO_GLOBAL_REACH_REASON)
        }
        if (globalStatus is AttachmentStatus.ActiveGlobal) {
            AudioEffectSessionReceiver.strategy = null
            setSessionReceiverEnabled(false)
            setSessionHarvestEnabled(false)
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
        // Only now. Harvesting can attach to a player within milliseconds, and
        // a strategy that has not been activated yet still holds EqSettings.FLAT
        // - so an effect created before this line lands on the track disabled
        // and stays that way, while the status cheerfully reports success.
        // Observed on the device: attached to Tidal with Enabled=n.
        setSessionHarvestEnabled(true)
        // Say why the wider mode is off. The session strategy's own "nothing has
        // announced itself" message points at the privileged helper as the fix,
        // which is true in general and wrong here: with Spatial Audio in the way
        // the helper changes nothing, and following that advice wastes the
        // user's time on the one screen that cannot help them.
        _status.value = if (globalIsInaudibleHere && sessionStatus is AttachmentStatus.Unavailable) {
            AttachmentStatus.Unavailable(NO_GLOBAL_REACH_REASON)
        } else {
            sessionStatus
        }
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
        if (current.status is AttachmentStatus.Inactive) {
            apply(settings)
            return
        }

        // A live attachment is not necessarily the *right* attachment. Connect
        // Bluetooth to a phone that was playing through its speaker and the
        // global effect that was audible a second ago goes silent, without
        // changing its status by one field. Checking only for a dead attachment
        // would sail straight past that: the effect is alive, it just stopped
        // reaching anyone. The reverse matters too - unplugging
        // should win the wider reach back instead of leaving the user in session
        // mode until they next touch a slider.
        val wrongModeForThisRoute = when (current.kind) {
            AttachmentKind.GLOBAL -> !globalAttachReachesOutput()
            AttachmentKind.SESSION_BROADCAST -> globalAttachReachesOutput()
        }
        if (wrongModeForThisRoute) {
            apply(settings)
            return
        }
        _status.value = current.status
    }

    /** Pushes a live update (slider drag) without re-running attachment. */
    fun update(newSettings: EqSettings) {
        settings = newSettings.sanitized()
        active?.update(settings) ?: apply(settings)
        active?.let { _status.value = it.status }
    }

    /**
     * Sessions found by watching playback, rather than announced by the player.
     *
     * Both directions matter. Opening reaches Tidal, which is the whole point.
     * Closing matters just as much: an effect on a session whose player has
     * stopped is a leaked native object, and holding a dozen of them because
     * the user skipped a dozen tracks is how a background service turns into a
     * battery complaint.
     */
    fun onHarvestedSessions(sessions: Set<Int>): Boolean {
        if (active !== session) return true
        val known = (session.status as? AttachmentStatus.ActiveSessions)?.sessionIds.orEmpty()
        // all() would short-circuit and skip the remaining sessions; every one
        // of them deserves its attempt regardless of what the first one did.
        val attached = (sessions - known).map(session::onSessionOpened).all { it }
        (known - sessions).forEach(session::onSessionClosed)
        _status.value = session.status
        return attached
    }

    /**
     * Writes the current settings to whatever is attached, changing nothing.
     *
     * Exists because an effect can be attached and silently switched off, and
     * the app has no way to find out - see [PlaybackSessionHarvester].
     * Re-applying is the only reliable repair.
     */
    fun reassertCurrentSettings() {
        if (!settings.enabled) return
        // Re-applying settings is not enough - the effect has to be built again.
        // See SessionAttachmentStrategy.reattachAll for why.
        if (active === session) session.reattachAll() else active?.update(settings)
        active?.let { _status.value = it.status }
    }

    fun deactivate() {
        global.deactivate()
        session.deactivate()
        AudioEffectSessionReceiver.strategy = null
        setSessionReceiverEnabled(false)
        setSessionHarvestEnabled(false)
        active = null
        _status.value = AttachmentStatus.Inactive
    }

    override fun close() = deactivate()

    private companion object {
        /**
         * Measured, not guessed: over Bluetooth an 18 dB cut on the output mix
         * moved the sound by 0,2 dB on every band, against 6-9 dB on a track's
         * own session - with a measured noise floor of about 3 dB.
         *
         * Naming Bluetooth is the honest part. An earlier version of this text
         * blamed Spatial Audio, which was the first suspect and turned out not
         * to be the cause: switching it off changed nothing. Sending the user to
         * a setting that cannot help would have been worse than saying little.
         */
        const val NO_GLOBAL_REACH_REASON =
            "Over Bluetooth this phone does not pass the system-wide equalizer " +
                "through, so only apps that announce their audio session can be " +
                "corrected - and none has yet. On the phone speaker the equalizer " +
                "reaches everything."
    }
}
