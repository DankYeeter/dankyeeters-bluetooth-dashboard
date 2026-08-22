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
 * Holds audio open long enough to inspect where the effects actually landed.
 *
 * Over Bluetooth the microphone cannot answer anything — the sound is in the
 * headphones, not the room. But AudioFlinger will say which output thread
 * carries which effect chain, and that is the whole question: on this Pixel the
 * A2DP route runs through a SPATIALIZER thread, and an output-mix (session 0)
 * effect attached to the plain mixer never touches it.
 *
 * This attaches **both** kinds at once — one on session 0, one on the track's
 * own session — and plays for 20 s so `dumpsys media.audio_flinger` can be read
 * while they are live. If the track-session chain shows up on the spatializer
 * thread and the session-0 chain does not, then session-bound attach is the way
 * to reach Bluetooth audio and global attach is not.
 */
@RunWith(JUnit4::class)
class SpatializerRoutingProbeTest {

    private val sampleRate = 48_000
    private val playSeconds = 20
    private val layout = EqBandLayout.OCTAVE_10

    @Test
    fun hold_both_attach_points_open_for_inspection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java) ?: return

        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.4f).toInt().coerceAtLeast(1), 0)

        val track = buildTrack()
        // A hard, unmistakable curve on both: if either one is in the path the
        // difference would be obvious to a listener too.
        val gains = List(layout.bandCount) { if (it > 5) -15f else 0f }

        val global = DynamicsProcessingEqualizer.create(0, layout)
        val session = DynamicsProcessingEqualizer.create(track.audioSessionId, layout)
        println("PROBE track session id = ${track.audioSessionId}")
        println("PROBE global attach   = ${if (global != null) "ok" else "FAILED"}")
        println("PROBE session attach  = ${if (session != null) "ok" else "FAILED"}")

        listOfNotNull(global, session).forEach {
            it.apply(
                EqSettings(
                    enabled = true,
                    layout = layout,
                    leftGainsDb = gains,
                    rightGainsDb = gains,
                    preGainDb = 0f,
                    limiterEnabled = false,
                ),
            )
        }

        try {
            track.play()

            // Read the gains back *after* playback has started. Attaching
            // happens while the speaker thread is still the active output;
            // starting the track moves the whole chain to the A2DP/spatializer
            // thread, and AudioFlinger re-creates the effect to do it. If that
            // rebuild drops the configuration, the effect is present, enabled,
            // and flat - which is exactly what "sounds identical" looks like.
            Thread.sleep(2500)
            fun report(when_: String) {
                val g = global?.readBandGain(Ear.LEFT, 8)
                val se = session?.readBandGain(Ear.LEFT, 8)
                println("PROBE $when_: band8 gain global=$g session=$se (expected -15.0)")
            }
            report("after playback started")

            // Re-apply now that the chain has settled on the real output.
            listOfNotNull(global, session).forEach {
                it.apply(
                    EqSettings(
                        enabled = true,
                        layout = layout,
                        leftGainsDb = gains,
                        rightGainsDb = gains,
                        preGainDb = 0f,
                        limiterEnabled = false,
                    ),
                )
            }
            report("after re-apply")

            val chunk = ShortArray(sampleRate / 4 * 2)
            val rng = Random(7)
            var written = 0
            val total = sampleRate * playSeconds
            while (written < total) {
                var i = 0
                while (i < chunk.size) {
                    val v = ((rng.nextDouble() * 2 - 1) * 0.25 * Short.MAX_VALUE).toInt().toShort()
                    chunk[i] = v
                    chunk[i + 1] = v
                    i += 2
                }
                track.write(chunk, 0, chunk.size)
                written += chunk.size / 2
            }
            println("PROBE done playing")
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            global?.close()
            session?.close()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
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
}
