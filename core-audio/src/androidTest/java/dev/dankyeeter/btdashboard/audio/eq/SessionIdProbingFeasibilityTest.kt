package dev.dankyeeter.btdashboard.audio.eq

import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Could a Play Store build reach Tidal without the privileged helper?
 *
 * Attaching an equaliser to another app's audio needs one thing only: the
 * session id. Android hides foreign ids from ordinary apps on purpose, which is
 * why the sideload build reads them through the helper. But session ids are not
 * secret in the cryptographic sense - they are a small counter handed out by
 * AudioFlinger, and `generateAudioSessionId()` tells any app where that counter
 * currently stands.
 *
 * If a live player's id sits close below that number, an app could find it by
 * trying a bounded range instead of asking - entirely within public API, no ADB,
 * no shell. This measures whether that is true here, and how wide the range
 * would have to be. It attaches nothing and changes nothing: it only creates
 * effects to see which ids exist, and closes them immediately.
 *
 * Run with music playing:
 *   adb shell am instrument -w -e class \
 *     dev.dankyeeter.btdashboard.audio.eq.SessionIdProbingFeasibilityTest \
 *     dev.dankyeeter.btdashboard.audio.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(JUnit4::class)
class SessionIdProbingFeasibilityTest {

    @Test
    fun how_close_is_the_session_counter_to_a_live_player() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java) ?: return

        val next = audio.generateAudioSessionId()
        println("PROBE generateAudioSessionId() = $next")

        // Walk backwards from the counter. A session that exists accepts an
        // effect; one that never existed does not. Deliberately bounded - the
        // point is to find out how far back a real player sits, not to attach
        // to everything on the device.
        val window = 64
        val alive = mutableListOf<Int>()
        for (offset in 1..window) {
            val candidate = next - offset
            if (candidate <= 0) break
            val eq = DynamicsProcessingEqualizer.create(candidate, EqBandLayout.OCTAVE_10)
            if (eq != null) {
                alive += candidate
                eq.close()
            }
        }

        println("PROBE ids in [${next - window}, ${next - 1}] that accepted an effect: $alive")
        println("PROBE distance from counter to nearest: ${alive.maxOrNull()?.let { next - it }}")
    }
}
