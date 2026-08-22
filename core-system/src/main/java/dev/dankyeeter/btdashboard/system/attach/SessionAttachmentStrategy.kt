package dev.dankyeeter.btdashboard.system.attach

import android.util.Log
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.audio.eq.SystemEqualizer
import dev.dankyeeter.btdashboard.audio.eq.SystemEqualizerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * No-privilege fallback. Attaches one [SystemEqualizer] per audio session that
 * an app announced through `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`
 * (see [AudioEffectSessionReceiver], which feeds [onSessionOpened]).
 *
 * Limitation to document in the UI: many players — Tidal included — do not send
 * that broadcast, so this strategy silently reaches nothing for them.
 */
class SessionAttachmentStrategy(
    private val factory: SystemEqualizerFactory,
) : EqAttachmentStrategy {

    override val kind = AttachmentKind.SESSION_BROADCAST

    private val equalizers = ConcurrentHashMap<Int, SystemEqualizer>()
    private var current: EqSettings = EqSettings.FLAT
    private var active = false

    override val status: AttachmentStatus
        get() = when {
            !active -> AttachmentStatus.Inactive
            equalizers.isEmpty() -> AttachmentStatus.Unavailable(
                "No app has announced an audio session yet. Players that do not " +
                    "broadcast their session (such as Tidal) require the global " +
                    "attachment through the privileged helper."
            )
            else -> AttachmentStatus.ActiveSessions(equalizers.keys.toSet())
        }

    override fun activate(settings: EqSettings): AttachmentStatus {
        active = true
        current = settings.sanitized()
        Log.i(TAG, "activate enabled=${current.enabled} attached=${equalizers.keys}")
        equalizers.values.forEach { it.apply(current) }
        return status
    }

    override fun update(settings: EqSettings) {
        current = settings.sanitized()
        Log.i(TAG, "update enabled=${current.enabled} active=$active attached=${equalizers.keys}")
        if (!active) return
        equalizers.entries.removeIf { (_, eq) -> !eq.isAlive }
        equalizers.values.forEach { it.apply(current) }
    }

    /**
     * Called when a player opens a session, or when one is harvested.
     *
     * @return whether an equaliser is now attached to [sessionId]. Creating the
     *   effect can fail for reasons that have nothing to do with this app and
     *   pass on their own - AudioFlinger refuses with `Error: -3` while it still
     *   holds an internal effect on that session, which was observed on the
     *   device right after a previous process was killed. The caller needs to
     *   know, so it can try again rather than record the session as handled and
     *   leave the EQ inert for the rest of the track.
     */
    fun onSessionOpened(sessionId: Int): Boolean {
        if (sessionId == 0) return false
        if (equalizers.containsKey(sessionId)) return true
        val eq = factory.create(sessionId)
        if (eq == null) {
            Log.w(TAG, "Could not attach to session $sessionId; will retry on the next event")
            return false
        }
        equalizers[sessionId] = eq
        Log.i(TAG, "opened $sessionId active=$active enabled=${current.enabled}")
        if (active) eq.apply(current)
        return true
    }

    /**
     * Throws away every attached effect and builds it again.
     *
     * Blunt on purpose, because the gentler repair does not work. An effect
     * attached to a player that started while the phone was still booting comes
     * up **switched off** in AudioFlinger, and the app cannot tell: `setEnabled`
     * succeeds, `getEnabled` reads back true, `hasControl` is true. Re-applying
     * the settings to that same effect object was measured and changed nothing -
     * three cold starts, still off.
     *
     * What does work is a *fresh* effect on the same session. That was hiding in
     * the workaround all along: pausing and resuming fixed it, and what pausing
     * really does is close the effect and create a new one on the way back.
     *
     * The cost is a gap of a few milliseconds in the correction, once, right
     * after attaching - against the alternative of an equaliser that is silently
     * inert until the user happens to press pause.
     */
    fun reattachAll() {
        if (!active) return
        val sessions = equalizers.keys.toList()
        if (sessions.isEmpty()) return
        Log.i(TAG, "re-attaching $sessions to clear a stuck-disabled effect")
        sessions.forEach { sessionId ->
            equalizers.remove(sessionId)?.close()
            val eq = factory.create(sessionId)
            if (eq == null) {
                Log.w(TAG, "re-attach to $sessionId failed; will retry on the next event")
                return@forEach
            }
            equalizers[sessionId] = eq
            eq.apply(current)
        }
    }

    /** Called when the player closes its session. */
    fun onSessionClosed(sessionId: Int) {
        equalizers.remove(sessionId)?.close()
    }

    override fun deactivate() {
        Log.i(TAG, "deactivate; dropping ${equalizers.keys}")
        active = false
        equalizers.values.forEach { it.close() }
        equalizers.clear()
    }

    private companion object {
        const val TAG = "SessionAttach"
    }
}
