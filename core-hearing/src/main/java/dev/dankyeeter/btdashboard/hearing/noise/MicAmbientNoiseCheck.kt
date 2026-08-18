package dev.dankyeeter.btdashboard.hearing.noise

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dev.dankyeeter.btdashboard.hearing.AmbientNoiseCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Microphone ambient-noise pre-check. This is the **only** place in the app
 * that uses RECORD_AUDIO, nothing is stored and nothing leaves the process —
 * the mic is opened, an RMS is computed, the recorder is released.
 *
 * The returned value is an *uncalibrated* estimate: phone mics have no
 * absolute reference, so we map full-scale RMS onto a plausible dB(A)-ish
 * scale using a fixed offset. It is good enough to tell "quiet room" from
 * "kitchen with the extractor hood on", which is all the plan asks for — the
 * result is a warning, never a blocker.
 */
class MicAmbientNoiseCheck(context: Context) : AmbientNoiseCheck {

    private val appContext = context.applicationContext

    val hasPermission: Boolean
        get() = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun measureDbA(durationMillis: Long): Double? = withContext(Dispatchers.IO) {
        if (!hasPermission) return@withContext null

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return@withContext null
        val bufferSize = minBuffer * 2

        val recorder = try {
            @Suppress("MissingPermission")
            AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
        } catch (e: Exception) {
            Log.w(TAG, "could not open the microphone", e)
            return@withContext null
        }

        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return@withContext null
            recorder.startRecording()
            val buffer = ShortArray(bufferSize / 2)
            var sumSquares = 0.0
            var samples = 0L
            val deadline = System.currentTimeMillis() + durationMillis
            // Skip the first block: the mic AGC/preamp settles for a moment.
            recorder.read(buffer, 0, buffer.size)
            while (System.currentTimeMillis() < deadline) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) break
                for (i in 0 until read) {
                    val v = buffer[i] / 32768.0
                    sumSquares += v * v
                }
                samples += read
            }
            if (samples == 0L) return@withContext null
            val rms = sqrt(sumSquares / samples)
            if (rms <= 0.0) return@withContext FLOOR_DB
            (20.0 * log10(rms) + FULL_SCALE_DB_SPL).coerceAtLeast(FLOOR_DB)
        } catch (e: Exception) {
            Log.w(TAG, "ambient measurement failed", e)
            null
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    companion object {
        private const val TAG = "MicAmbientNoiseCheck"
        private const val SAMPLE_RATE = 48_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /**
         * Assumed SPL of a full-scale signal. Typical phone mics clip somewhere
         * around 120 dB SPL; this constant is what makes the scale plausible
         * rather than accurate, and the UI says so.
         */
        private const val FULL_SCALE_DB_SPL = 120.0
        private const val FLOOR_DB = 20.0

        /** Above this the UI warns that the room is too loud for quiet tones. */
        const val WARN_THRESHOLD_DB = 40.0

        fun describe(dbA: Double): String = when {
            dbA < 30 -> "very quiet"
            dbA < WARN_THRESHOLD_DB -> "quiet enough"
            dbA < 50 -> "noticeable background noise"
            else -> "loud"
        }
    }
}

/** Stub used in previews and on devices where RECORD_AUDIO was denied. */
class NoAmbientNoiseCheck : AmbientNoiseCheck {
    override suspend fun measureDbA(durationMillis: Long): Double? = null
}
