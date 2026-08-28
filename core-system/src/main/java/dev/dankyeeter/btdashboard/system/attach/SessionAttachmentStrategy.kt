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
 *
 * ## Why every mutation is serialised
 *
 * The four callers of this class arrive on four different threads, and two of
 * them can be inside it at the same time:
 *
 *  - [AudioEffectSessionReceiver] delivers on the **main** thread;
 *  - [PlaybackSessionHarvester] reports harvests on its own **IO** scope;
 *  - the same harvester's settle check calls [reattachAll] from a *second*
 *    coroutine on that scope, which `Dispatchers.IO` is free to run on a
 *    different thread than the harvest;
 *  - the EQ screen and the foreground service push [update] from theirs.
 *
 * A `ConcurrentHashMap` makes each individual operation atomic and that is not
 * enough here, because the operations that matter are *sequences*: [reattachAll]
 * removes an effect, builds a replacement, then puts it back. An
 * [onSessionOpened] for the same session landing in the middle of that finds the
 * key absent, creates its own effect, and stores it — and the put at the end of
 * `reattachAll` then overwrites that entry without closing it. The overwritten
 * `DynamicsProcessing` is **still registered in AudioFlinger on that session**;
 * nothing holds a reference to it any more, so nothing will ever close it, and
 * it goes on processing audio next to its replacement.
 *
 * That is not a hypothetical. The window is real on the device — the settle
 * check fires 2.5 s after an attach, which is exactly when a track change is
 * likely to produce the next harvest — and a chain that accumulates a second
 * (and a third) processing instance is the leading explanation for the encoder
 * starvation measured on 2026-08-28, where a long-lived attached chain starved
 * the Bluetooth encoder at ~49 underflows/s and a freshly attached one did not.
 * `:core-monitor`'s `EncoderStarvationTripwire` now counts the instances at the
 * moment it next happens, which is what will confirm or kill that explanation.
 *
 * So: one lock, held across every read-modify-write of [equalizers]. It is held
 * while `factory.create` runs, which is a short binder call into audioserver
 * that cannot call back into this class, so it cannot deadlock. [status] reads
 * the map without it on purpose — a status is a report, and blocking the UI
 * thread behind an attach to produce one would be the wrong trade.
 */
class SessionAttachmentStrategy(
    private val factory: SystemEqualizerFactory,
) : EqAttachmentStrategy {

    override val kind = AttachmentKind.SESSION_BROADCAST

    private val equalizers = ConcurrentHashMap<Int, SystemEqualizer>()

    /** Guards every read-modify-write of [equalizers]. See the class KDoc. */
    private val lock = Any()

    @Volatile
    private var current: EqSettings = EqSettings.FLAT

    @Volatile
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
        synchronized(lock) {
            active = true
            current = settings.sanitized()
            Log.i(TAG, "activate enabled=${current.enabled} attached=${equalizers.keys}")
            equalizers.values.forEach { it.apply(current) }
        }
        return status
    }

    override fun update(settings: EqSettings) {
        synchronized(lock) {
            current = settings.sanitized()
            Log.i(TAG, "update enabled=${current.enabled} active=$active attached=${equalizers.keys}")
            if (!active) return
            // A dead effect is not an attachment. Dropping it here is also what
            // lets onSessionOpened build a replacement rather than reporting the
            // corpse as success.
            //
            // Closed, not merely dropped. `isAlive` goes false for reasons other
            // than close(): the underlying effect marks itself dead the first
            // time a framework call throws, and it is *not* released at that
            // point. Letting the last reference go without calling close() means
            // nothing will ever ask for the release again, so the native effect
            // stays registered in AudioFlinger on that session — one more
            // instance on a chain, which is exactly the accumulation under
            // suspicion for the encoder starvation. close() is documented
            // idempotent, so this costs nothing when it really was closed.
            equalizers.entries.toList()
                .filterNot { (_, eq) -> eq.isAlive }
                .forEach { (sessionId, eq) ->
                    equalizers.remove(sessionId)
                    eq.close()
                }
            equalizers.values.forEach { it.apply(current) }
        }
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
        synchronized(lock) {
            // Nothing may create an effect while this strategy is not the one in
            // use. A broadcast or an in-flight harvest can land after
            // deactivate() has already closed everything - or after the
            // controller has moved to the global mix - and an effect built then
            // is attached to a player, switched off (the settings are still
            // FLAT), and owned by nobody: deactivate() has been and gone, so
            // nothing will ever close it. It simply stays in AudioFlinger's
            // chain for that session. Refusing is also the honest answer, since
            // `false` means "not attached", which is exactly true.
            if (!active) {
                Log.i(TAG, "ignoring session $sessionId; the session strategy is not active")
                return false
            }

            val existing = equalizers[sessionId]
            if (existing != null) {
                if (existing.isAlive) return true
                // A dead effect held under the session's key used to make this
                // return success forever: the session looked handled while the
                // EQ reached nothing on it.
                Log.i(TAG, "replacing a dead effect on session $sessionId")
                equalizers.remove(sessionId)?.close()
            }

            val eq = factory.create(sessionId)
            if (eq == null) {
                Log.w(TAG, "Could not attach to session $sessionId; will retry on the next event")
                return false
            }
            equalizers[sessionId] = eq
            Log.i(TAG, "opened $sessionId active=$active enabled=${current.enabled}")
            eq.apply(current)
            return true
        }
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
     *
     * @return whether every session came back. A false means at least one
     *   session was dropped and *is no longer attached to anything*, which the
     *   caller has to be able to see: the harvester remembers what it reported,
     *   so without this it would never offer that session again and the EQ would
     *   stay off it until the set of players happened to change.
     */
    fun reattachAll(): Boolean {
        synchronized(lock) {
            if (!active) return true
            val sessions = equalizers.keys.toList()
            if (sessions.isEmpty()) return true
            Log.i(TAG, "re-attaching $sessions to clear a stuck-disabled effect")
            var allBack = true
            sessions.forEach { sessionId ->
                equalizers.remove(sessionId)?.close()
                val eq = factory.create(sessionId)
                if (eq == null) {
                    Log.w(TAG, "re-attach to $sessionId failed; will retry on the next event")
                    allBack = false
                    return@forEach
                }
                equalizers[sessionId] = eq
                eq.apply(current)
            }
            return allBack
        }
    }

    /** Called when the player closes its session. */
    fun onSessionClosed(sessionId: Int) {
        synchronized(lock) {
            equalizers.remove(sessionId)?.close()
        }
    }

    override fun deactivate() {
        synchronized(lock) {
            Log.i(TAG, "deactivate; dropping ${equalizers.keys}")
            // Cleared before the closes, so that anything blocked on the lock
            // sees a strategy that is already out of use rather than one in the
            // middle of shutting down.
            active = false
            equalizers.values.forEach { it.close() }
            equalizers.clear()
        }
    }

    private companion object {
        const val TAG = "SessionAttach"
    }
}
