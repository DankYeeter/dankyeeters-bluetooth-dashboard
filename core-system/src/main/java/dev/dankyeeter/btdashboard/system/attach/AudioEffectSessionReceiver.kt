package dev.dankyeeter.btdashboard.system.attach

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log

/**
 * Receives the audio-session broadcasts that well-behaved players send, and
 * forwards them to the currently installed [SessionAttachmentStrategy].
 *
 * The strategy is set by the app's service/Application layer through
 * [Companion.strategy]; if nothing is installed the broadcast is dropped.
 */
class AudioEffectSessionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val target = strategy ?: return
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (sessionId <= 0) return
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                Log.d(TAG, "Session opened: $sessionId by ${intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)}")
                target.onSessionOpened(sessionId)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                target.onSessionClosed(sessionId)
            }
        }
    }

    companion object {
        private const val TAG = "AudioSessionRx"

        /** Set by the app layer while the EQ service is alive. */
        @Volatile
        @JvmStatic
        var strategy: SessionAttachmentStrategy? = null

        /**
         * Enables or disables the manifest component to match [strategy].
         *
         * These broadcasts fire on every track change of every well-behaved
         * player on the phone, and a manifest receiver means each one wakes
         * this process. In global mode — the common case on this device — the
         * first line of [onReceive] then drops the delivery, so the process
         * was started for nothing, all day long. Session mode is the only
         * consumer, so the component is switched on exactly then.
         *
         * DONT_KILL_APP: this runs from inside the running app; the default
         * behaviour of killing the package on a component change would end
         * the EQ service that just made the call.
         */
        fun setComponentEnabled(context: Context, enabled: Boolean) {
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    android.content.ComponentName(context, AudioEffectSessionReceiver::class.java),
                    if (enabled) {
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    },
                    android.content.pm.PackageManager.DONT_KILL_APP,
                )
            }.onFailure { Log.w(TAG, "could not toggle the session receiver", it) }
        }
    }
}
