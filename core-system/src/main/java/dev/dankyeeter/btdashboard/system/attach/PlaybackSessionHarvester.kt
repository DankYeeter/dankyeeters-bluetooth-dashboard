package dev.dankyeeter.btdashboard.system.attach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Learns the audio sessions of players that never announce themselves.
 *
 * ## The problem it solves
 *
 * [AudioEffectSessionReceiver] only hears from well-behaved players. Tidal is
 * not one of them, and over Bluetooth the output-mix attach is measurably
 * silent, so without this the app has nothing to offer Tidal at all. Attaching
 * needs only the session id, though — verified on the device: handed the id from
 * outside, the effect attached and the music audibly dropped. The privileged
 * helper can read those ids; an ordinary app cannot, because
 * `getActivePlaybackConfigurations` anonymises them to zero.
 *
 * ## Why it does not drain the battery
 *
 * Three layers, in order of how much they save:
 *
 *  1. **No polling.** [AudioManager.registerAudioPlaybackCallback] is a public
 *     callback that fires when a player starts, stops or pauses. Nothing runs
 *     between those events — no timer, no loop, no wake-ups.
 *  2. **No privileged call when nothing is playing.** The callback already says
 *     *whether* media is active, just not which session. That is enough to skip
 *     the helper round-trip entirely in the common case of silence, which is
 *     most of the day.
 *  3. **No work when nothing changed.** Playback callbacks arrive in bursts —
 *     a track change can fire several in a second — so they are debounced, and
 *     an unchanged result never reaches the strategy.
 *
 * The expensive step, one `dumpsys audio` through the helper, therefore happens
 * roughly once per "music started" event and not otherwise.
 *
 * ## Privacy
 *
 * Only session ids leave the parse; the player's identity, its metadata and
 * what it is playing are all discarded. Nothing is stored and nothing is sent —
 * the app holds no INTERNET permission.
 */
class PlaybackSessionHarvester(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Runs a command as the helper; null when the helper is not connected. */
    private val runPrivileged: suspend (List<String>) -> String?,
    /** Returns whether every session was actually attached; false means retry. */
    private val onSessionsChanged: (Set<Int>) -> Boolean,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var pending: Job? = null
    private var lastReported: Set<Int> = emptySet()

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            scheduleHarvest(anyMediaPlaying(configs))
        }
    }

    fun start() {
        if (registered) return
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        runCatching { audio.registerAudioPlaybackCallback(callback, handler) }
            .onSuccess {
                registered = true
                Log.i(TAG, "watching playback for foreign sessions")
                // Whatever is already playing predates the first callback.
                scheduleHarvest(anyMediaPlaying(audio.activePlaybackConfigurations))
            }
            .onFailure { Log.w(TAG, "could not watch playback", it) }
    }

    fun stop() {
        pending?.cancel()
        pending = null
        if (!registered) return
        val audio = context.getSystemService(AudioManager::class.java)
        runCatching { audio?.unregisterAudioPlaybackCallback(callback) }
        registered = false
        lastReported = emptySet()
    }

    /**
     * Anonymised, but enough: a normal app may not see *which* session another
     * app uses, yet it may see that a media player is active. That is precisely
     * the cheap half of the question, and it decides whether the expensive half
     * is worth asking.
     */
    private fun anyMediaPlaying(configs: List<AudioPlaybackConfiguration>): Boolean =
        configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }

    private fun scheduleHarvest(mediaPlaying: Boolean) {
        pending?.cancel()
        if (!mediaPlaying) {
            // Silence is knowable for free; report it without touching the helper.
            report(emptySet())
            return
        }
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            harvestOnce()
        }
    }

    /**
     * Harvests now, if the helper can answer.
     *
     * Also called when the helper *arrives*, and that is not a nicety. The app
     * starts, enters session mode and harvests within a second or two, while the
     * helper is still connecting - so the first attempt returns nothing. If the
     * music was already playing, no further playback event is coming, and the EQ
     * would sit idle until the user happened to press pause. Observed exactly
     * once on the device, which is once more than it should be.
     */
    suspend fun harvestOnce() {
        val dump = runCatching { runPrivileged(listOf("dumpsys", "audio")) }
            .onFailure { Log.w(TAG, "helper could not read playback state", it) }
            .getOrNull()
        if (dump == null) {
            // Not an error worth a stack trace - the helper is simply absent,
            // which is the normal state for anyone who has not set it up. Logged
            // because the difference between "no helper" and "helper said
            // nothing is playing" is otherwise invisible from outside.
            Log.i(TAG, "no helper answer; nothing harvested")
            return
        }
        val sessions = PlaybackSessionParser.activeMediaSessions(dump)
        Log.i(TAG, "harvested $sessions from ${dump.length} chars")
        report(sessions)
    }

    /** Re-harvests after the helper connects; no-op when not watching. */
    fun onPrivilegedHelperConnected() {
        Log.i(TAG, "helper connected; watching=$registered")
        if (!registered) return
        pending?.cancel()
        pending = scope.launch { harvestOnce() }
    }

    private fun report(sessions: Set<Int>) {
        if (sessions == lastReported) return
        // Remembered only once it worked. Attaching can fail transiently -
        // AudioFlinger refuses while it still holds an internal effect on that
        // session - and treating a failed attempt as done would leave the EQ
        // inert until the set of players happened to change again. Leaving
        // lastReported alone turns the next playback event into a free retry.
        if (onSessionsChanged(sessions)) lastReported = sessions
    }

    private companion object {
        const val TAG = "SessionHarvest"

        /**
         * A track change fires several callbacks in quick succession; waiting
         * out the burst turns them into one helper call instead of four.
         */
        const val DEBOUNCE_MS = 400L
    }
}
