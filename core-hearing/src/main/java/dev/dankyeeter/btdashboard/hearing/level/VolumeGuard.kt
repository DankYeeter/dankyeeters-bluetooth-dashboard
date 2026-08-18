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
    private var watchdog: Job? = null

    val maxVolume: Int get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val currentVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    /** Fraction of the maximum below which the test is not worth running. */
    val isVolumeTooLow: Boolean get() = currentVolume < (maxVolume * MIN_VOLUME_FRACTION)

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
        referenceVolume = -1
    }

    private companion object {
        const val TAG = "VolumeGuard"
        const val POLL_INTERVAL_MS = 250L
        const val MIN_VOLUME_FRACTION = 0.3
    }
}
