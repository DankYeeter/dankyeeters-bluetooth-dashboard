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
        equalizers.values.forEach { it.apply(current) }
        return status
    }

    override fun update(settings: EqSettings) {
        current = settings.sanitized()
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
        if (active) eq.apply(current)
        return true
    }

    /** Called when the player closes its session. */
    fun onSessionClosed(sessionId: Int) {
        equalizers.remove(sessionId)?.close()
    }

    override fun deactivate() {
        active = false
        equalizers.values.forEach { it.close() }
        equalizers.clear()
    }

    private companion object {
        const val TAG = "SessionAttach"
    }
}
