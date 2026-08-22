package dev.dankyeeter.btdashboard.system.attach

import android.util.Log
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.audio.eq.SystemEqualizer
import dev.dankyeeter.btdashboard.audio.eq.SystemEqualizerFactory

/**
 * Global attachment: one [SystemEqualizer] on audio session 0, the output mix.
 * This is the only path that reaches every app, Tidal included.
 *
 * History worth keeping: this used to gate on Shizuku being ready, on the
 * theory that attaching to session 0 needs `MODIFY_AUDIO_ROUTING` granted
 * through an elevated identity. Measured on the Pixel 8 Pro / Android 16 that
 * is not the case — `DynamicsProcessing(0, 0, config)` attaches with plain
 * `MODIFY_AUDIO_SETTINGS`, and that permission appears in the manifest while
 * `MODIFY_AUDIO_ROUTING` appears nowhere in the project. The gate's only
 * effect was to *refuse to try* on phones where the try would have worked.
 * Now the factory's own result is the answer; a build that really does refuse
 * a global effect gets the honest fallback text below.
 *
 * The effect can still die when the output mix is torn down (BT device
 * switch); the watchdog re-attaches via [reattachIfNeeded].
 */
class GlobalAttachmentStrategy(
    private val factory: SystemEqualizerFactory,
) : EqAttachmentStrategy {

    override val kind = AttachmentKind.GLOBAL

    private var equalizer: SystemEqualizer? = null
    private var current: EqSettings = EqSettings.FLAT
    private var lastStatus: AttachmentStatus = AttachmentStatus.Inactive

    override val status: AttachmentStatus get() = lastStatus

    override fun activate(settings: EqSettings): AttachmentStatus {
        current = settings.sanitized()

        // No gate before the attempt. The old Shizuku-Ready check was a proxy
        // with no mechanical role: nothing Shizuku provides flows into the
        // attach, and MODIFY_AUDIO_ROUTING appears nowhere in this project —
        // measured on the Pixel 8 Pro / Android 16, DynamicsProcessing on
        // session 0 attaches with plain MODIFY_AUDIO_SETTINGS. The factory
        // result below is the real answer, so it is the only check.
        // A live attachment is reused, not replaced. activate() runs on every
        // service start and every boot-restore, and each call used to build a
        // fresh DynamicsProcessing while the field silently dropped the old
        // one: a native effect leak, and worse — the instances *stack* on the
        // output mix, so the correction curve was applied twice, three times…
        // For an app whose whole purpose is a precise curve, that is not a
        // performance bug but a wrong-audio bug.
        equalizer?.let { existing ->
            if (existing.isAlive) {
                existing.apply(current)
                lastStatus = AttachmentStatus.ActiveGlobal(GLOBAL_SESSION_ID)
                return lastStatus
            }
            // Dead effect (output mix was torn down): release before replacing.
            existing.close()
            equalizer = null
        }

        val eq = factory.create(GLOBAL_SESSION_ID)
        if (eq == null) {
            lastStatus = AttachmentStatus.Unavailable(
                "This build refused a global audio effect. The system EQ falls back " +
                    "to session mode."
            )
            return lastStatus
        }

        equalizer = eq
        eq.apply(current)
        lastStatus = AttachmentStatus.ActiveGlobal(GLOBAL_SESSION_ID)
        return lastStatus
    }

    override fun update(settings: EqSettings) {
        current = settings.sanitized()
        val eq = equalizer ?: return
        if (!eq.isAlive) {
            reattachIfNeeded()
            return
        }
        eq.apply(current)
    }

    /** Re-attaches after the output mix was torn down (e.g. BT device switch). */
    fun reattachIfNeeded() {
        val eq = equalizer
        if (eq != null && eq.isAlive) return
        Log.i(TAG, "Global effect died, re-attaching")
        eq?.close()
        equalizer = null
        activate(current)
    }

    override fun deactivate() {
        equalizer?.close()
        equalizer = null
        lastStatus = AttachmentStatus.Inactive
    }

    companion object {
        private const val TAG = "GlobalAttach"

        /** `AudioManager.AUDIO_SESSION_ID_GENERATE` is -1; 0 is the output mix. */
        const val GLOBAL_SESSION_ID = 0
    }
}
