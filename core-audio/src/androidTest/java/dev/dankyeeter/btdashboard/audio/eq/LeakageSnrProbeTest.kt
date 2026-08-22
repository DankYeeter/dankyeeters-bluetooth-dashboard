package dev.dankyeeter.btdashboard.audio.eq

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Is there enough signal to measure at all?
 *
 * The plan was to lay the phone next to the headphones and let the existing
 * acoustic test measure the leakage instead of the room. The control test came
 * back with -39 dB at 500 Hz and +13 dB at 8 kHz for what is really a 6 dB step
 * - not a bad measurement, no measurement: closed-back ANC headphones radiate
 * almost nothing, so the microphone was reading its own noise floor.
 *
 * Before changing volumes and re-running hopefully, this establishes the one
 * number that decides whether the setup can work: **how far the tone sits above
 * the silence**. It measures the floor with nothing playing, then the tone, and
 * prints the difference per frequency.
 *
 * Rule of thumb: below ~10 dB of headroom nothing measured that way means
 * anything, and no amount of re-running will fix it - the headphones have to be
 * physically against the microphone, or the question has to be answered another
 * way.
 */
@RunWith(JUnit4::class)
class LeakageSnrProbeTest {

    private val sampleRate = 48_000
    private val recordMs = 500
    private val settleMs = 250
    private val centres = listOf(500f, 1000f, 2000f, 4000f, 8000f)

    @Test
    fun how_far_is_the_tone_above_the_noise_floor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java) ?: return

        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Full volume: the question is whether this can work at its best, not
        // whether it works at a polite level.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        println("SNR stream volume $max/$max")

        try {
            centres.forEach { hz ->
                val floor = record(hz, play = false)
                val tone = record(hz, play = true)
                val snr = 20 * log10(tone / floor.coerceAtLeast(1e-12))
                println(
                    "SNR %.0f Hz: floor=%.3e tone=%.3e  ->  %+.1f dB ueber dem Rauschen"
                        .format(hz, floor, tone, snr),
                )
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
        }
    }

    /** Records at [hz], optionally with the tone actually playing. */
    private fun record(hz: Float, play: Boolean): Double {
        val track = if (play) buildTrack() else null
        val recorder = buildRecord()
        return try {
            if (track != null) {
                val frames = sineFrames(hz)
                track.write(frames, 0, frames.size)
                track.play()
            }
            recorder.startRecording()
            Thread.sleep(settleMs.toLong())
            val samples = ShortArray(sampleRate * recordMs / 1000)
            var read = 0
            while (read < samples.size) {
                val n = recorder.read(samples, read, samples.size - read)
                if (n <= 0) break
                read += n
            }
            goertzel(samples, read, hz)
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            track?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
        }
    }

    private fun sineFrames(hz: Float): ShortArray {
        val frames = sampleRate
        val out = ShortArray(frames * 2)
        val fade = sampleRate / 50
        for (i in 0 until frames) {
            val envelope = when {
                i < fade -> i.toDouble() / fade
                i > frames - fade -> (frames - i).toDouble() / fade
                else -> 1.0
            }
            // Louder than the acoustic test: this probe is asking whether the
            // signal can clear the floor at all.
            val v = sin(2.0 * Math.PI * hz * i / sampleRate) * envelope * 0.7 * Short.MAX_VALUE
            out[i * 2] = v.toInt().toShort()
            out[i * 2 + 1] = v.toInt().toShort()
        }
        return out
    }

    private fun buildTrack(): AudioTrack {
        val bytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate * 4)
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
            .setBufferSizeInBytes(bytes * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
    }

    private fun buildRecord(): AudioRecord {
        val bytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate * 2)
        @Suppress("MissingPermission")
        return AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bytes * 2,
        )
    }

    private fun goertzel(samples: ShortArray, length: Int, hz: Float): Double {
        if (length <= 0) return 0.0
        val k = 2.0 * cos(2.0 * Math.PI * hz / sampleRate)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until length) {
            val s = samples[i] / Short.MAX_VALUE.toDouble() + k * s1 - s2
            s2 = s1
            s1 = s
        }
        return kotlin.math.sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / length
    }
}
