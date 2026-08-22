package dev.dankyeeter.btdashboard.audio.eq

import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Can an ordinary app learn a foreign player's audio session id?
 *
 * Attaching to a session needs nothing but the id - proven on Tidal, which
 * never announces one. So the whole question of whether non-broadcasting
 * players can be corrected reduces to: where does the id come from?
 *
 * The privileged helper can always parse it out of `dumpsys audio`. This probe
 * checks whether that detour is necessary at all, or whether
 * `getActivePlaybackConfigurations` hands it over to a normal app.
 */
@RunWith(JUnit4::class)
class SessionIdVisibilityProbe {

    @Test
    fun what_can_a_normal_app_see_about_active_players() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java) ?: return

        val configs = audio.activePlaybackConfigurations
        println("VISIBILITY ${configs.size} active playback configuration(s)")
        configs.forEach { config ->
            // getSessionId() is the field that decides everything here. If it is
            // public and truthful, the helper is not needed for the EQ at all.
            val sessionId = runCatching {
                val method = config.javaClass.getMethod("getSessionId")
                method.invoke(config)
            }.getOrElse { "NOT ACCESSIBLE (${it.javaClass.simpleName})" }
            println("VISIBILITY usage=${config.audioAttributes.usage} sessionId=$sessionId")
        }
    }
}
