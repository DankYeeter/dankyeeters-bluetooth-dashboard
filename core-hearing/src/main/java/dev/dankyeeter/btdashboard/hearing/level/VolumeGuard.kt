package dev.dankyeeter.btdashboard.hearing.level

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the media volume fixed for the duration of a hearing-test run.
 *
 * All 5 dB protocol steps happen digitally inside the tone generator; the
 * system volume is only a constant scaling factor. If it moves mid-run every
 * threshold measured before the change refers to a different absolute level,
 * so the run must be thrown away — hence the polling watchdog. Bluetooth
 * absolute volume is far too coarse (usually ~16 steps) to carry the protocol
 * itself.
 *
 * Volume *key* events are swallowed by the test screen (see the app module);
 * this class catches everything the app cannot intercept — headphone buttons,
 * assistants, other apps.
 */
class VolumeGuard(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var referenceVolume: Int = -1
    private var userVolume: Int = -1
    private var watchdog: Job? = null

    val maxVolume: Int get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val currentVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    /** Fraction of the maximum below which the test is not worth running. */
    val isVolumeTooLow: Boolean get() = currentVolume < (maxVolume * MIN_VOLUME_FRACTION)

    /**
     * Puts the media stream at a defined level for the run and latches it.
     *
     * The protocol's absolute levels only mean anything against a known system
     * volume, and leaving that to the user made the test silent whenever the
     * A2DP stream happened to sit at 3/25. The previous value is remembered and
     * put back by [restoreUserVolume] once the run ends.
     *
     * @return false only if the device refused the change *and* the volume that
     *   is actually set is too low to run a meaningful test.
     */
    fun applyTestVolume(fraction: Double = TEST_VOLUME_FRACTION): Boolean {
        if (userVolume < 0) userVolume = currentVolume
        val wanted = (maxVolume * fraction).toInt().coerceIn(1, maxVolume)
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, wanted, 0)
        }.onFailure {
            // Fixed-volume devices, DND policy or an absolute-volume A2DP sink
            // can all refuse this. Fall back to whatever is set.
            Log.w(TAG, "could not set the test volume", it)
        }
        latchReference()
        return !isVolumeTooLow
    }

    /** Puts back the volume the user had before [applyTestVolume]. */
    fun restoreUserVolume() {
        if (userVolume < 0) return
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, userVolume, 0)
        }.onFailure { Log.w(TAG, "could not restore the user's media volume", it) }
        userVolume = -1
    }

    /** Latches the current media volume as this run's reference. */
    fun latchReference(): Int {
        referenceVolume = currentVolume
        return referenceVolume
    }

    /** True once [latchReference] ran and the volume still matches it. */
    val isIntact: Boolean get() = referenceVolume >= 0 && currentVolume == referenceVolume

    /**
     * Polls the media volume and invokes [onChanged] once if it moved away from
     * the latched reference. Polling (rather than the hidden
     * `VOLUME_CHANGED_ACTION` broadcast) is used deliberately: it is a public
     * API and survives OEM differences.
     */
    fun startWatchdog(scope: CoroutineScope, onChanged: (from: Int, to: Int) -> Unit) {
        check(referenceVolume >= 0) { "latchReference() must run before the watchdog" }
        stopWatchdog()
        watchdog = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val now = currentVolume
                if (now != referenceVolume) {
                    Log.w(TAG, "media volume changed during a run: $referenceVolume -> $now")
                    onChanged(referenceVolume, now)
                    return@launch
                }
            }
        }
    }

    fun stopWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }

    /** Restores the latched volume, e.g. after a run that changed it by mistake. */
    fun restoreReference() {
        if (referenceVolume < 0) return
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, referenceVolume, 0)
        }.onFailure { Log.w(TAG, "could not restore media volume", it) }
    }

    fun release() {
        stopWatchdog()
        restoreUserVolume()
        referenceVolume = -1
    }

    private companion object {
        const val TAG = "VolumeGuard"
        const val POLL_INTERVAL_MS = 250L
        const val MIN_VOLUME_FRACTION = 0.3

        /** Where the run puts the media stream: loud enough for the -85 dBFS floor,
         *  well short of anything uncomfortable at the -6 dBFS ceiling. */
        const val TEST_VOLUME_FRACTION = 0.7
    }
}
