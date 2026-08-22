package dev.dankyeeter.btdashboard.audio.eq

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Can the EQ reach a player that never announces its session?
 *
 * Tidal does not broadcast `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` —
 * measured on this device, not just quoted from a code comment. Over Bluetooth
 * the output-mix attach is silent too, so for Tidal the app currently has
 * nothing.
 *
 * But the broadcast is only the *polite* way to learn a session id. The
 * privileged helper (shell) can read every live player's session id out of
 * `dumpsys audio`, and attaching a session-bound effect to a foreign session
 * needs no special permission - only the id. If that attach audibly works, the
 * helper can harvest ids and hand them to the session strategy, and Tidal is
 * back on the table.
 *
 * This probe is that experiment: given a session id (from instrumentation
 * args), attach a hard -15 dB full-band cut to it while the player is playing
 * over Bluetooth, and measure broadband RMS through the earcup leakage before
 * and during. Music is not a test tone, so the numbers are rough - but 15 dB is
 * not subtle, and the listener wearing the headphones is the second instrument.
 *
 *   adb shell am instrument -w -r -e targetSession <id> -e class \
 *     dev.dankyeeter.btdashboard.audio.eq.ForeignSessionAttachProbeTest \
 *     dev.dankyeeter.btdashboard.audio.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(JUnit4::class)
class ForeignSessionAttachProbeTest {

    private val sampleRate = 48_000

    @Test
    fun attach_to_a_foreign_session_and_measure_the_drop() {
        val args = InstrumentationRegistry.getArguments()
        val sessionId = args.getString("targetSession")?.toIntOrNull()
        if (sessionId == null) {
            println("FOREIGN no targetSession argument given - nothing to probe")
            return
        }

        val layout = EqBandLayout.OCTAVE_10
        println("FOREIGN probing session $sessionId")

        val before = rms(1500)
        println("FOREIGN rms before attach: %.6f".format(before))

        val equalizer = DynamicsProcessingEqualizer.create(sessionId, layout)
        if (equalizer == null) {
            println("FOREIGN attach REFUSED for session $sessionId")
            return
        }
        println("FOREIGN attach succeeded")

        try {
            equalizer.apply(
                EqSettings(
                    enabled = true,
                    layout = layout,
                    leftGainsDb = List(layout.bandCount) { -15f },
                    rightGainsDb = List(layout.bandCount) { -15f },
                    preGainDb = 0f,
                    limiterEnabled = false,
                ),
            )
            Thread.sleep(800) // let the cut settle into the stream
            val during = rms(1500)
            val secondOpinion = rms(1500)
            println("FOREIGN rms during cut: %.6f / %.6f".format(during, secondOpinion))
            println(
                "FOREIGN drop: %.1f dB / %.1f dB (music, so rough - 15 dB was requested)"
                    .format(
                        20 * log10(before / during.coerceAtLeast(1e-9)),
                        20 * log10(before / secondOpinion.coerceAtLeast(1e-9)),
                    ),
            )
            // Hold a moment longer so the listener can confirm by ear.
            Thread.sleep(3000)
        } finally {
            equalizer.close()
            println("FOREIGN released - playback should be back to normal")
        }
    }

    /** Broadband RMS from the microphone over [ms] milliseconds. */
    private fun rms(ms: Int): Double {
        val bytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate * 2)
        @Suppress("MissingPermission")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bytes * 2,
        )
        return try {
            recorder.startRecording()
            val samples = ShortArray(sampleRate * ms / 1000)
            var read = 0
            while (read < samples.size) {
                val n = recorder.read(samples, read, samples.size - read)
                if (n <= 0) break
                read += n
            }
            var sum = 0.0
            for (i in 0 until read) {
                val v = samples[i] / Short.MAX_VALUE.toDouble()
                sum += v * v
            }
            if (read == 0) 0.0 else sqrt(sum / read)
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }
}
