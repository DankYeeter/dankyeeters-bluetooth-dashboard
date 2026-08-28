package dev.dankyeeter.btdashboard.audio.eq

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the volume-aware tilt gets its one input from.
 *
 * An interface rather than the class directly, because the only thing that
 * needs the platform here is *reading* the number: a caller that wants to
 * decide what happens at a given volume — the EQ screen's ViewModel, a test of
 * it — should not have to stand up an `AudioManager` and a content observer to
 * say "the volume is now a tenth".
 */
interface MediaVolumeSource {
    /** 0..1, the media stream's index over its maximum. */
    val fraction: StateFlow<Float>
}

/**
 * The media-volume fraction, as a flow that follows the hardware keys.
 *
 * Reading the value is [AudioManager]'s job and the app already does it that
 * way for the per-device default volume (`SystemMediaVolumeController`); the
 * convention is deliberately the same one — the stream's index over its
 * maximum, so a phone with 15 steps and one with 25 report the same number for
 * the same slider position.
 *
 * *Noticing* a change is the harder half. `android.media.VOLUME_CHANGED_ACTION`
 * is not public API, so this watches `Settings.System` instead, which is where
 * the volume index is actually written: every route that can move media volume
 * — the keys, the panel, another app, a device profile this app applied itself
 * — passes through it. The observer fires on unrelated system settings too,
 * which costs one `getStreamVolume` call and is filtered out by the
 * [MutableStateFlow] refusing to re-emit an unchanged value.
 *
 * The observer is registered for the life of the process and never
 * unregistered: this is a lazily built process singleton, there is exactly one
 * of it, and the alternative — reference counting subscribers so it can be torn
 * down — would buy nothing measurable.
 */
class MediaVolumeMonitor(context: Context) : MediaVolumeSource {

    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _fraction = MutableStateFlow(read())

    /** 0..1. Falls back to [VolumeAwareTilt.REFERENCE_FRACTION] when unreadable. */
    override val fraction: StateFlow<Float> = _fraction.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            _fraction.value = read()
        }
    }

    init {
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                /* notifyForDescendants = */ true,
                observer,
            )
        }.onFailure { Log.w(TAG, "cannot observe media volume; the tilt will not follow it", it) }
    }

    /** Re-reads the volume now. For callers that just changed it themselves. */
    fun refresh() {
        _fraction.value = read()
    }

    private fun read(): Float {
        val manager = audio ?: return VolumeAwareTilt.REFERENCE_FRACTION
        return runCatching {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            // A fixed-volume device (some docks, and absolute volume delegating
            // to the headphone) reports a degenerate range. Claiming the
            // reference fraction there means the tilt stays flat, which is the
            // right answer when the phone's slider does not describe the level.
            if (max <= 0) return VolumeAwareTilt.REFERENCE_FRACTION
            (manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max).coerceIn(0f, 1f)
        }.getOrDefault(VolumeAwareTilt.REFERENCE_FRACTION)
    }

    private companion object {
        const val TAG = "MediaVolumeMonitor"
    }
}
