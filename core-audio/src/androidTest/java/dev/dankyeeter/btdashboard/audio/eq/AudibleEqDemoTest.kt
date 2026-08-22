package dev.dankyeeter.btdashboard.audio.eq

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.random.Random

/**
 * A demo to listen to, not a test to pass.
 *
 * ## Why it is built this way
 *
 * Over the speaker the equaliser is proven: an 18 dB cut measures as a 14 dB
 * drop through the microphone. Over Bluetooth nothing can be measured - the
 * sound is in the headphones, not the room - and every structural check says
 * the effect is live there too: it sits on the A2DP output thread, reports
 * ACTIVE, enabled, not suspended, and its gains read back correctly. Yet the
 * first listening attempt came back "sounded identical".
 *
 * One of those two things is wrong, and a timbre change is too subtle a probe
 * to decide which. So the first contrast here is not tone colour but **level**:
 * every band down 15 dB. A 15 dB drop in loudness is impossible to miss on any
 * working chain. If that one is inaudible, the problem is the route or the
 * volume, not the equaliser - and that is worth knowing before touching more
 * code.
 *
 * Segment order is deliberate: loud/quiet first (is anything working at all),
 * then the two timbre contrasts (does it work per band).
 *
 * Follow along with:
 *   adb logcat -s System.out
 */
@RunWith(JUnit4::class)
class AudibleEqDemoTest {

    private val sampleRate = 48_000
    private val segmentSeconds = 4
    private val layout = EqBandLayout.OCTAVE_10

    /** Output mix - the attach point the app itself uses. */
    private val globalSession = 0

    @Test
    fun play_a_contrast_the_ear_cannot_miss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java) ?: return

        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.55f).toInt().coerceAtLeast(1), 0)

        // Split at 1 kHz. With cutoffFrequency meaning the band's upper edge,
        // band 5 ends at 1414 Hz, so "highs" is everything above that.
        val split = layout.centersHz.indexOfFirst { it >= 1000f }
        val flat = List(layout.bandCount) { 0f }
        val quiet = List(layout.bandCount) { -15f }
        val dark = List(layout.bandCount) { if (it > split) -15f else 0f }
        val thin = List(layout.bandCount) { if (it <= split) -15f else 0f }

        // One effect for the whole demo, gains changed between segments. That is
        // how the app behaves in practice, and it avoids re-attaching six times.
        val equalizer = DynamicsProcessingEqualizer.create(globalSession, layout)
        if (equalizer == null) {
            say("Effekt konnte nicht auf Session 0 angehaengt werden - Abbruch.")
            return
        }

        try {
            say("=== EQ-Hoertest. Rauschen, 4s pro Abschnitt. ===")
            say("Abschnitt 2 muss LEISER sein. Das ist der wichtigste.")
            play(equalizer, "1/6  NORMAL", flat)
            play(equalizer, "2/6  LEISE  - alles 15 dB abgesenkt", quiet)
            play(equalizer, "3/6  NORMAL", flat)
            play(equalizer, "4/6  DUMPF  - nur Hoehen abgesenkt", dark)
            play(equalizer, "5/6  NORMAL", flat)
            play(equalizer, "6/6  DUENN  - nur Tiefen abgesenkt", thin)
            say("=== Ende. Erwartet: 2 leiser, 4 dumpfer, 6 duenner. ===")
        } finally {
            equalizer.close()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
        }
    }

    private fun play(equalizer: DynamicsProcessingEqualizer, label: String, gains: List<Float>) {
        say(label)
        val settings = EqSettings(
            enabled = true,
            layout = layout,
            leftGainsDb = gains,
            rightGainsDb = gains,
            preGainDb = 0f,
            // The limiter would fight the level contrast this demo depends on.
            limiterEnabled = false,
        )
        equalizer.apply(settings)

        val track = buildTrack()
        try {
            track.play()
            // Re-apply once the track is running: starting playback can move the
            // effect chain to another output thread, and the point of this demo
            // is to hear the gains, not to race the router.
            equalizer.apply(settings)
            say("     band8 liest zurueck: " + equalizer.readBandGain(Ear.LEFT, 8) + " dB")

            val chunk = ShortArray(sampleRate / 4 * 2)
            val rng = Random(1234) // identical noise every segment: only the EQ differs
            var written = 0
            val total = sampleRate * segmentSeconds
            while (written < total) {
                fillNoise(chunk, rng)
                track.write(chunk, 0, chunk.size)
                written += chunk.size / 2
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        Thread.sleep(600)
    }

    private var pinkState = 0.0

    /**
     * Broadband noise with a gentle tilt.
     *
     * Deliberately not heavily pinked: a bass-dominated noise would make the
     * "highs cut" segment nearly indistinguishable, because there would be
     * little up there to remove. Both halves of the spectrum need real energy
     * for the two timbre contrasts to mean anything.
     */
    private fun fillNoise(out: ShortArray, rng: Random) {
        var i = 0
        while (i < out.size) {
            val white = rng.nextDouble() * 2.0 - 1.0
            pinkState = 0.75 * pinkState + 0.25 * white
            val v = (pinkState * 1.1 + white * 0.55) * 0.42
            val s = (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
            out[i] = s
            out[i + 1] = s
            i += 2
        }
    }

    private fun buildTrack(): AudioTrack {
        val bytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bytes * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun say(line: String) = println("EQDEMO " + line)
}
