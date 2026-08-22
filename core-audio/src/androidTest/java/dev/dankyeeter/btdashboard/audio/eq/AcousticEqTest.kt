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

    /** Output mix, i.e. GlobalAttachmentStrategy.GLOBAL_SESSION_ID. */
    private val GLOBAL_MIX_SESSION = 0

    /**
     * Bands the phone speaker can actually reproduce and the mic can hear.
     * A phone speaker rolls off hard below a few hundred Hz, so testing 31.5 Hz
     * would measure the speaker's limits, not the equaliser's effect.
     */
    private val testableCentres = listOf(500f, 1000f, 2000f, 4000f, 8000f)

    /**
     * Bands measurable through **headphone leakage**, with the phone lying
     * against the earcups.
     *
     * 500 Hz is missing on purpose, and not as a convenience: a signal-to-noise
     * probe measured the tone sitting +0,7 dB above the microphone's own noise
     * floor there, against +47 dB at 1 kHz and +58 dB at 8 kHz. Closed-back
     * headphones simply do not radiate bass into the room, so at 500 Hz there is
     * nothing to measure and any number produced would be noise wearing a
     * decimal point.
     */
    private val leakageCentres = listOf(1000f, 2000f, 4000f, 8000f)

    /**
     * Does a single band land on the sound the user hears?
     *
     * A **cut**, not a boost. Boosts are unmeasurable here: the phone's speaker
     * protection refuses to let a tone near full volume get louder, so a +12 dB
     * request came back as 0,0 dB on every band and looked exactly like a dead
     * equaliser. Verified by the control test, which sees a level *drop*
     * perfectly well. A cut has no such ceiling, so it answers the real
     * question — is the effect in the signal path — without the speaker
     * overruling the answer.
     */
    @Test
    fun cutting_a_band_lowers_that_band_in_the_air() {
        runBandCut(globalMix = false, label = "Session EQ")
    }

    /**
     * The same cut on the **output mix** (session 0), which is the attach point
     * the app actually uses. The session-bound test can pass while this one
     * fails: they exercise different paths, and only this one covers audio from
     * other apps.
     *
     * Run with the app's own Eq service stopped, otherwise its session-0 effect
     * is a second variable:
     *   adb shell am force-stop dev.dankyeeter.btdashboard
     */
    @Test
    fun cutting_a_band_on_the_global_mix_lowers_it_in_the_air() {
        runBandCut(globalMix = true, label = "Global EQ")
    }

    private fun runBandCut(
        globalMix: Boolean,
        label: String,
        volumeFraction: Float = 0.4f,
        amplitude: Double = 0.25,
        centres: List<Float> = testableCentres,
        priority: Int = 0,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        assumeTrue("needs an audio service", audio != null)

        // 40 %: loud enough to measure, quiet enough that the speaker's
        // protection has no reason to intervene.
        val max = audio!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (max * volumeFraction).toInt().coerceAtLeast(1),
            0,
        )

        val drops = mutableListOf<Pair<Float, Double>>()
        try {
            centres.forEach { centre ->
                val layout = EqBandLayout.OCTAVE_10
                val band = layout.centersHz.indexOfFirst { abs(it - centre) < 1f }
                assumeTrue("band $centre not in layout", band >= 0)

                val flat = measureMedian(centre, layout, band, 0f, globalMix, amplitude, priority = priority)
                val cut = measureMedian(centre, layout, band, -18f, globalMix, amplitude, priority = priority)
                assumeTrue("effect unavailable on this device", flat != null && cut != null)

                drops += centre to 20 * log10(flat!! / cut!!.coerceAtLeast(1e-9))
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
        }

        val summary = drops.joinToString(", ") { "%.0f Hz: -%.1f dB".format(it.first, it.second) }
        println("$label cut result: $summary")

        // Judged across bands, not on the first one: a phone speaker has its own
        // response, and the lowest band is always the weakest evidence.
        val passing = drops.count { it.second > 3.0 }
        assertTrue(
            "an 18 dB cut was not audible in the air on enough bands " +
                "($passing of ${drops.size} dropped more than 3 dB) — $summary",
            passing > drops.size / 2,
        )
    }

    /**
     * The same question over **Bluetooth**, measured instead of guessed.
     *
     * This is the path that matters and the one nothing could verify for a long
     * time: over A2DP the microphone hears the room, not the headphones, so the
     * speaker tests say nothing about it. Every structural check said the effect
     * was live there — on the A2DP output thread, ACTIVE, enabled, not
     * suspended, gains reading back correctly — while the first listening test
     * came back "sounded identical". Structure cannot settle that; a number can.
     *
     * It works by putting the phone against the earcups and measuring the
     * leakage. That needs two changes from the speaker tests, both established
     * by a signal-to-noise probe rather than guessed:
     *  - **full volume** instead of 40 %, and a louder tone (−3 dBFS): at 80 %
     *    with a −12 dBFS tone the control test read −39 dB for a real 6 dB step,
     *    i.e. pure noise.
     *  - **no 500 Hz** — see [leakageCentres].
     *
     * Setup: phone lying against the earcups, headphones connected and playing
     * to, quiet room, and the app stopped so its own session-0 effect is not a
     * second variable:
     *   adb shell am force-stop dev.dankyeeter.btdashboard
     */
    @Test
    fun cutting_a_band_on_the_global_mix_lowers_it_through_headphone_leakage() {
        runBandCut(
            globalMix = true,
            label = "Global EQ via Bluetooth leakage",
            volumeFraction = 1.0f,
            amplitude = 0.7,
            centres = leakageCentres,
        )
    }

    /** The session-bound path over the same leakage measurement. */
    @Test
    fun cutting_a_band_on_a_session_lowers_it_through_headphone_leakage() {
        runBandCut(
            globalMix = false,
            label = "Session EQ via Bluetooth leakage",
            volumeFraction = 1.0f,
            amplitude = 0.7,
            centres = leakageCentres,
        )
    }

    /**
     * Does the output-mix effect reach Bluetooth if it asks for **priority**?
     *
     * The global attach is created with priority 0. On a route served by a
     * SPATIALIZER output thread that may simply lose to whatever else sits in
     * the chain - and losing is silent, which is the whole problem. If a higher
     * priority wins control, the app needs no session ids at all over
     * Bluetooth, and a Play Store build without the privileged helper would be
     * just as capable as the sideload one.
     *
     * That makes this the most valuable experiment left: it is the difference
     * between "full function needs ADB" and "full function for everyone".
     */
    @Test
    fun cutting_the_global_mix_with_high_priority_lowers_it_through_headphone_leakage() {
        runBandCut(
            globalMix = true,
            label = "Global EQ (priority 100) via Bluetooth leakage",
            volumeFraction = 1.0f,
            amplitude = 0.7,
            centres = leakageCentres,
            priority = 100,
        )
    }

    /**
     * The ruler for the leakage measurement, at the levels the leakage tests
     * actually use.
     *
     * [the_microphone_can_hear_a_known_level_difference] validates the *speaker*
     * chain and fails over Bluetooth for a reason that has nothing to do with
     * the equaliser: it plays too quietly to clear the noise floor through
     * closed earcups. This is the same known 6 dB step at full volume on the
     * bands that carry signal, so a Bluetooth EQ result can be trusted — or
     * dismissed — on evidence.
     */
    @Test
    fun the_microphone_can_hear_a_known_level_difference_through_headphone_leakage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        assumeTrue("needs an audio service", audio != null)

        val max = audio!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)

        val deltas = mutableListOf<Pair<Float, Double>>()
        try {
            leakageCentres.forEach { centre ->
                val loud = measureRaw(centre, amplitude = 0.7)  // -3 dBFS
                val quiet = measureRaw(centre, amplitude = 0.35) // -9 dBFS
                deltas += centre to 20 * log10(loud / quiet.coerceAtLeast(1e-9))
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
        }

        val summary = deltas.joinToString(", ") { "%.0f Hz: %+.1f dB".format(it.first, it.second) }
        println("Leakage control (-6 dB step) result: " + summary)

        val passing = deltas.count { it.second > 3.0 }
        assertTrue(
            "the mic could not hear a known 6 dB drop through the earcups " +
                "($passing of ${deltas.size} above +3 dB) — move the phone against " +
                "the earcups, or the leakage results mean nothing — " + summary,
            passing > deltas.size / 2,
        )
    }

    /**
     * Two identical measurements. Any difference is the measurement lying.
     *
     * Daniel noticed by ear that the first tone of each pair sounds louder than
     * the second - and every EQ test here measures **flat first, cut second**.
     * A systematic "second one is quieter" bias is therefore indistinguishable
     * from the very thing those tests claim to detect: it would manufacture a
     * drop out of nothing and the assertion would happily pass.
     *
     * So this measures flat against flat. The honest expectation is 0 dB. It is
     * the one test here that makes the others trustworthy, and if it fails,
     * every cut result above is void until the cause is found - a headphone that
     * ramps its own volume, ANC settling, or an A2DP link that starts hot.
     */
    @Test
    fun measuring_the_same_thing_twice_gives_the_same_answer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        assumeTrue("needs an audio service", audio != null)

        val max = audio!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)

        val deltas = mutableListOf<Pair<Float, Double>>()
        try {
            leakageCentres.forEach { centre ->
                val layout = EqBandLayout.OCTAVE_10
                val band = layout.centersHz.indexOfFirst { abs(it - centre) < 1f }
                assumeTrue("band $centre not in layout", band >= 0)

                // Deliberately the same call twice, in the same order and with
                // the same gain as the real tests use for their first leg.
                val first = measureMedian(centre, layout, band, 0f, false, 0.7)
                val second = measureMedian(centre, layout, band, 0f, false, 0.7)
                assumeTrue("effect unavailable", first != null && second != null)

                deltas += centre to 20 * log10(first!! / second!!.coerceAtLeast(1e-9))
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
        }

        val summary = deltas.joinToString(", ") { "%.0f Hz: %+.1f dB".format(it.first, it.second) }
        println("A/A (no change at all) result: " + summary)

        val worst = deltas.maxOfOrNull { abs(it.second) } ?: 0.0
        assertTrue(
            "measuring the same thing twice differed by up to %.1f dB - the first ".format(worst) +
                "tone is systematically louder than the second, so every cut result " +
                "is an artefact of ordering, not of the equalizer - " + summary,
            worst < 3.0,
        )
    }

    /**
     * The measurement itself, before any equaliser is involved.
     *
     * A 0 dB EQ result is only meaningful if the chain can see level changes at
     * all. This plays the same tone twice with no EQ — once at −12 dBFS, once at
     * −18 dBFS (a known 6 dB drop) — and requires the mic to read it back as a
     * clear drop. If this fails, the speaker→mic→Goertzel path is blind and no
     * verdict on the EQ (0 dB or otherwise) can be trusted; if it passes, a 0 dB
     * EQ reading means the EQ really did nothing, not that the ruler was broken.
     */
    @Test
    fun the_microphone_can_hear_a_known_level_difference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        assumeTrue("needs an audio service", audio != null)

        val max = audio!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.8f).toInt(), 0)

        val deltas = mutableListOf<Pair<Float, Double>>()
        try {
            testableCentres.forEach { centre ->
                val loud = measureRaw(centre, amplitude = 0.25)   // −12 dBFS
                val quiet = measureRaw(centre, amplitude = 0.125) // −18 dBFS
                deltas += centre to 20 * log10(loud / quiet.coerceAtLeast(1e-9))
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
        }

        val summary = deltas.joinToString(", ") { "%.0f Hz: %+.1f dB".format(it.first, it.second) }
        println("Control (−6 dB step) result: $summary")

        // The true difference is 6 dB. The room and the speaker curve add noise,
        // so accept anything clearly above zero on a majority of bands — the same
        // +3 dB bar the EQ tests use, so a passing control validates their scale.
        val passing = deltas.count { it.second > 3.0 }
        assertTrue(
            "the mic could not hear a known 6 dB drop on enough bands " +
                "($passing of ${deltas.size} above +3 dB) — the measurement chain " +
                "is unreliable, so EQ results cannot be trusted — $summary",
            passing > deltas.size / 2,
        )
    }


    /** Like [measure], but raises every band instead of one. */
    private fun measureAllBands(centre: Float, gainDb: Float): Double? {
        val layout = EqBandLayout.OCTAVE_10
        val track = buildTrack()
        val equalizer = DynamicsProcessingEqualizer.create(track.audioSessionId, layout)
            ?: run { track.release(); return null }

        val gains = List(layout.bandCount) { gainDb }
        equalizer.apply(
            EqSettings(
                enabled = true,
                layout = layout,
                leftGainsDb = gains,
                rightGainsDb = gains,
                preGainDb = 0f,
                limiterEnabled = false,
            ),
        )

        val record = buildRecord()
        return try {
            val frames = sineFrames(centre)
            track.write(frames, 0, frames.size)
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

    /**
     * @param globalMix attach the effect to the output mix (session 0), the way
     *   the app's GlobalAttachmentStrategy does, instead of to this track's own
     *   session. This is the path the user actually hears — the session-bound
     *   variant only proves the effect works on a track we own.
     * @return the recorded magnitude at [centre], or null if the effect refused to attach.
     */
    private fun measure(
        centre: Float,
        layout: EqBandLayout,
        band: Int,
        gainDb: Float,
        globalMix: Boolean = false,
        amplitude: Double = 0.25,
        priority: Int = 0,
    ): Double? {
        val track = buildTrack()
        val effectSession = if (globalMix) GLOBAL_MIX_SESSION else track.audioSessionId
        val equalizer = DynamicsProcessingEqualizer.create(effectSession, layout, priority)
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
            val frames = sineFrames(centre, amplitude)
            track.write(frames, 0, frames.size)
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

    /**
     * [measure] repeated, reduced to its median.
     *
     * A single leakage measurement is not trustworthy: measuring the same thing
     * twice came back up to 5 dB apart, and 1 kHz threw 45 dB outliers. Those
     * outliers are what make one reading dangerous - one lands on a band and the
     * verdict flips.
     *
     * The median rather than the mean is the point: it ignores a wild reading
     * completely instead of letting it drag the average, which is exactly the
     * failure mode here.
     */
    private fun measureMedian(
        centre: Float,
        layout: EqBandLayout,
        band: Int,
        gainDb: Float,
        globalMix: Boolean,
        amplitude: Double,
        repeats: Int = 5,
        priority: Int = 0,
    ): Double? {
        val values = (0 until repeats).mapNotNull {
            measure(centre, layout, band, gainDb, globalMix, amplitude, priority)
        }
        if (values.isEmpty()) return null
        return values.sorted()[values.size / 2]
    }
    private fun buildTrack(sessionId: Int? = null): AudioTrack {
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
            .apply { sessionId?.let(::setSessionId) }
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
    private fun sineFrames(hz: Float, amplitude: Double = 0.25): ShortArray {
        val frames = sampleRate
        val out = ShortArray(frames * 2)
        val fade = sampleRate / 50
        for (i in 0 until frames) {
            val envelope = when {
                i < fade -> i.toDouble() / fade
                i > frames - fade -> (frames - i).toDouble() / fade
                else -> 1.0
            }
            // 0.25 = -12 dBFS: loud enough to clear the room, with headroom for
            // +12 dB. [amplitude] lets the control test play a known-quieter tone.
            val v = (sin(2.0 * Math.PI * hz * i / sampleRate) * envelope * amplitude * Short.MAX_VALUE)
            out[i * 2] = v.toInt().toShort()
            out[i * 2 + 1] = v.toInt().toShort()
        }
        return out
    }

    /** Plays a plain tone at [amplitude] with no equaliser at all, and measures it. */
    private fun measureRaw(centre: Float, amplitude: Double): Double {
        val track = buildTrack()
        val record = buildRecord()
        return try {
            val frames = sineFrames(centre, amplitude)
            track.write(frames, 0, frames.size)
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
        }
    }



    /**
     * The same effect, asked to make the sound *quieter*.
     *
     * Every boost measured so far came back as 0 dB while the control test —
     * which lowers the level — was clearly visible. That asymmetry is the
     * signature of a speaker-protection limiter: the phone will not let a tone
     * played near full volume get louder, so a +12 dB request is swallowed
     * downstream of the equaliser and the measurement cannot tell that apart
     * from an equaliser that does nothing.
     *
     * A cut has no such ceiling. If −12 dB on every band shows up in the air,
     * the equaliser **is** in the signal path and the earlier flat results were
     * the speaker's limiter, not a broken EQ. Volume is also dropped to 40 % so
     * the protection has less reason to engage at all.
     */
    @Test
    fun cutting_every_band_lowers_the_whole_signal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        assumeTrue("needs an audio service", audio != null)

        val max = audio!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.4f).toInt().coerceAtLeast(1), 0)

        val deltas = mutableListOf<Pair<Float, Double>>()
        try {
            testableCentres.forEach { centre ->
                val flat = measureAllBands(centre, gainDb = 0f)
                val cut = measureAllBands(centre, gainDb = -12f)
                assumeTrue("effect unavailable", flat != null && cut != null)
                // Positive number = the cut made it quieter, which is the point.
                deltas += centre to 20 * log10(flat!! / cut!!.coerceAtLeast(1e-9))
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
        }

        val summary = deltas.joinToString(", ") { "%.0f Hz: -%.1f dB".format(it.first, it.second) }
        println("All-bands CUT result (drop achieved): $summary")

        val passing = deltas.count { it.second > 3.0 }
        assertTrue(
            "a -12 dB cut on every band did not make the tone quieter " +
                "($passing of ${deltas.size} dropped by more than 3 dB) — the " +
                "equaliser really is outside the signal path — $summary",
            passing > deltas.size / 2,
        )
    }

    /**
     * Which frequencies does one band actually control?
     *
     * `DynamicsProcessing.EqBand.cutoffFrequency` is the band's **upper edge**,
     * but the app fills those fields with octave *centre* frequencies. If that
     * mismatch is real, a slider labelled "1000 Hz" moves the 500–1000 Hz range
     * instead — which for hearing-loss compensation means correcting the wrong
     * part of the spectrum, quietly and plausibly.
     *
     * This cuts exactly one band (the one holding 1000 Hz) and measures the drop
     * at probe tones above and below it. Not pass/fail: it prints the shape so
     * the labelling can be judged against what the effect really does.
     */
    @Test
    fun report_which_frequencies_one_band_actually_moves() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        assumeTrue("needs an audio service", audio != null)

        val max = audio!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.4f).toInt().coerceAtLeast(1), 0)

        val layout = EqBandLayout.OCTAVE_10
        val band = layout.centersHz.indexOfFirst { abs(it - 1000f) < 1f }
        assumeTrue("no 1000 Hz band", band >= 0)

        val probes = listOf(400f, 600f, 800f, 1000f, 1300f, 1800f, 2500f)
        val drops = mutableListOf<Pair<Float, Double>>()
        try {
            probes.forEach { hz ->
                // A cut, not a boost: the speaker's protection limiter swallows
                // boosts near full level, which is what made every earlier
                // measurement read 0 dB.
                val flat = measureOneBand(hz, layout, band, gainDb = 0f)
                val cut = measureOneBand(hz, layout, band, gainDb = -18f)
                if (flat != null && cut != null) {
                    drops += hz to 20 * log10(flat / cut.coerceAtLeast(1e-9))
                }
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
        }

        val summary = drops.joinToString(", ") { "%.0f Hz: -%.1f dB".format(it.first, it.second) }
        println("Band-mapping probe (cut the '1000 Hz' band by 18 dB): $summary")
        println("Band-mapping probe: layout centres = ${layout.centersHz.joinToString()}")
    }

    /** One band moved, the rest flat, effect on a session created before the track. */
    private fun measureOneBand(
        hz: Float,
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
                preGainDb = 0f,
                limiterEnabled = false,
            ),
        )
        val record = buildRecord()
        return try {
            val frames = sineFrames(hz)
            track.write(frames, 0, frames.size)
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
            goertzel(samples, read, hz)
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { track.stop() }
            runCatching { track.release() }
            equalizer.close()
        }
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
