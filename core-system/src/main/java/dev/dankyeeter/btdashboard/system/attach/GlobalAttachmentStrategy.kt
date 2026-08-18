package dev.dankyeeter.btdashboard.system.attach

import android.util.Log
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.audio.eq.SystemEqualizer
import dev.dankyeeter.btdashboard.audio.eq.SystemEqualizerFactory
import dev.dankyeeter.btdashboard.system.shizuku.ShizukuManager
import dev.dankyeeter.btdashboard.system.shizuku.ShizukuState

/**
 * Global attachment: one [SystemEqualizer] on audio session 0, the output mix.
 * This is the only path that reaches every app, Tidal included.
 *
 * ### Status (Stage A)
 * The direct attempt is implemented: with `MODIFY_AUDIO_SETTINGS` plus the
 * elevated context Shizuku provides, `DynamicsProcessing(0, 0, config)` is
 * attached to `AUDIO_SESSION_OUTPUT_MIX`. On stock Pixel builds this is
 * accepted once the app holds the privileged identity; on some builds
 * AudioFlinger additionally requires `MODIFY_AUDIO_ROUTING`, which is a
 * signature permission and can only be granted through Shizuku's
 * `IPackageManager.grantRuntimePermission` shim.
 *
 * ### What remains (documented deliberately, not hidden)
 * 1. Binding `IAudioPolicyService`/`IPackageManager` through
 *    `ShizukuBinderWrapper` to grant `MODIFY_AUDIO_ROUTING` to ourselves —
 *    the hidden-API surface differs per Android version and needs on-device
 *    iteration on the target Pixel.
 * 2. Verifying that the global effect survives an audio-device switch
 *    (BT connect/disconnect re-creates the output mix on some builds); the
 *    watchdog re-attaches via [reattachIfNeeded].
 * 3. Shizuku itself does not survive a reboot (wireless debugging is off after
 *    boot). The boot receiver therefore posts an "EQ inactive — start Shizuku"
 *    notification instead of pretending the EQ is running.
 *
 * Everything above is behind this interface, so the rest of the app is already
 * final regardless of how the remaining plumbing lands.
 */
class GlobalAttachmentStrategy(
    private val factory: SystemEqualizerFactory,
    private val shizuku: ShizukuManager,
) : EqAttachmentStrategy {

    override val kind = AttachmentKind.GLOBAL_SHIZUKU

    private var equalizer: SystemEqualizer? = null
    private var current: EqSettings = EqSettings.FLAT
    private var lastStatus: AttachmentStatus = AttachmentStatus.Inactive

    override val status: AttachmentStatus get() = lastStatus

    override fun activate(settings: EqSettings): AttachmentStatus {
        current = settings.sanitized()

        val shizukuState = shizuku.state.value
        if (shizukuState !is ShizukuState.Ready) {
            lastStatus = AttachmentStatus.Unavailable(
                "Shizuku is not ready. Without it the EQ can only attach to apps " +
                    "that announce their audio session."
            )
            return lastStatus
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
