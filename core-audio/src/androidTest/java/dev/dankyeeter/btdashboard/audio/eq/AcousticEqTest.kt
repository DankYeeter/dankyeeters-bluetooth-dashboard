package dev.dankyeeter.btdashboard.audio.eq

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Does the equaliser actually change the sound?
 *
 * Every other test in this project checks that a number reached an object. This
 * one plays a tone out of the speaker, records it back through the microphone
 * and measures whether boosting that tone's band made it louder in the air. It
 * is the only test here that can fail because of something the audio HAL did.
 *
 * Method per band:
 *  1. play a sine at the band centre, EQ attached and flat, record ~0.5 s
 *  2. raise that one band by +12 dB, record again
 *  3. measure both recordings at exactly that frequency with a Goertzel filter
 *     and compare
 *
 * A Goertzel rather than an FFT because the frequency is known: it is a handful
 * of multiplies per sample and immune to bin-boundary smearing.
 *
 * Run with the phone somewhere quiet and the speaker unobstructed:
 *   ./gradlew :core-audio:connectedDebugAndroidTest
 */
@RunWith(JUnit4::class)
class AcousticEqTest {

    private val sampleRate = 48_000
    private val recordMs = 500
    private val settleMs = 250

    /**
     * Bands the phone speaker can actually reproduce and the mic can hear.
     * A phone speaker rolls off hard below a few hundred Hz, so testing 31.5 Hz
     * would measure the speaker's limits, not the equaliser's effect.
     */
    private val testableCentres = listOf(500f, 1000f, 2000f, 4000f, 8000f)

    @Test
    fun boosting_a_band_raises_that_band_in_the_air() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        assumeTrue("needs an audio service", audio != null)

        // Put the stream somewhere audible but not painful.
        val max = audio!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.8f).toInt(), 0)

        val results = mutableListOf<String>()
        try {
            testableCentres.forEach { centre ->
                val layout = EqBandLayout.OCTAVE_10
                val band = layout.centersHz.indexOfFirst { abs(it - centre) < 1f }
                assumeTrue("band $centre not in layout", band >= 0)

                val flat = measure(centre, layout, band, gainDb = 0f)
                val boosted = measure(centre, layout, band, gainDb = 12f)
                assumeTrue("effect unavailable on this device", flat != null && boosted != null)

                val deltaDb = 20 * log10(boosted!! / flat!!.coerceAtLeast(1e-9))
                results += "%.0f Hz: %+.1f dB".format(centre, deltaDb)

                assertTrue(
                    "boosting ${centre.toInt()} Hz by 12 dB changed the recorded level by " +
                        "only %.1f dB — the equaliser is not in the signal path".format(deltaDb),
                    deltaDb > 3.0,
                )
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
        }
        println("Acoustic EQ result: " + results.joinToString(", "))
    }

    /** @return the recorded magnitude at [centre], or null if the effect refused to attach. */
    private fun measure(
        centre: Float,
        layout: EqBandLayout,
        band: Int,
        gainDb: Float,
    ): Double? {
        val track = buildTrack()
        val equalizer = DynamicsProcessingEqualizer.create(track.audioSessionId, layout)
            ?: run { track.release(); return null }

        val gains = MutableList(layout.bandCount) { 0f }
        gains[band] = gainDb
        equalizer.apply(
            EqSettings(
                enabled = true,
                layout = layout,
                leftGainsDb = gains.toList(),
                rightGainsDb = gains.toList(),
                // No headroom trim: it would scale both runs and hide the effect.
                preGainDb = 0f,
                limiterEnabled = false,
            ),
        )

        val record = buildRecord()
        return try {
            track.write(sineFrames(centre), 0, sineFrames(centre).size)
            track.play()
            record.startRecording()
            Thread.sleep(settleMs.toLong())

            val samples = ShortArray(sampleRate * recordMs / 1000)
            var read = 0
            while (read < samples.size) {
                val n = record.read(samples, read, samples.size - read)
                if (n <= 0) break
                read += n
            }
            goertzel(samples, read, centre)
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { track.stop() }
            runCatching { track.release() }
            equalizer.close()
        }
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
        @Suppress("MissingPermission") // RECORD_AUDIO is granted for the test run
        return AudioRecord(
            // UNPROCESSED avoids the AGC and noise suppression that would fight
            // the very level difference this test is trying to measure.
            MediaRecorder.AudioSource.UNPROCESSED,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bytes * 2,
        )
    }

    /** One second of a stereo sine, faded in and out so the speaker does not click. */
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
            // -12 dBFS: loud enough to clear the room, with headroom for +12 dB.
            val v = (sin(2.0 * Math.PI * hz * i / sampleRate) * envelope * 0.25 * Short.MAX_VALUE)
            out[i * 2] = v.toInt().toShort()
            out[i * 2 + 1] = v.toInt().toShort()
        }
        return out
    }

    /** Magnitude at one known frequency. */
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
