package dev.dankyeeter.btdashboard.system.attach

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioEffect
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
    }
}
