package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysBluetoothParser
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpLinkDumpParser
import dev.dankyeeter.btdashboard.monitor.link.live.AudioFlingerTrackParser
import dev.dankyeeter.btdashboard.monitor.link.live.PlayingStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * Where the checkout is, found at runtime instead of assumed.
 *
 * The sweeps below need the fixture *files*, not the classpath resources: they
 * enumerate everything in the directory so that a dump added tomorrow is swept
 * without anybody remembering to list it, and a classpath resource cannot be
 * listed portably. Gradle's working directory for an Android unit test is the
 * module directory, but that is a default and not a promise, so the root is
 * located by walking up until the build's own `settings.gradle.kts` shows up —
 * and failing that, from wherever these test classes were compiled to.
 */
internal object RepoTree {

    val root: File by lazy {
        val found = climb(File(System.getProperty("user.dir") ?: ".").absoluteFile)
            ?: climb(compiledClassesDir())
        requireNotNull(found) {
            "repository root not found from ${System.getProperty("user.dir")}"
        }
    }

    private fun compiledClassesDir(): File = runCatching {
        File(RepoTree::class.java.protectionDomain!!.codeSource.location.toURI())
    }.getOrElse { File(".").absoluteFile }

    /** Every fixture under `:core-monitor`'s `dumps/`, README included. */
    val dumpFixtures: List<File> by lazy {
        val dir = File(root, "core-monitor/src/test/resources/dumps")
        require(dir.isDirectory) { "fixture directory missing: $dir" }
        dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
            .also { require(it.isNotEmpty()) { "no fixtures in $dir" } }
    }

    fun mainSourceFiles(module: String): List<File> {
        val dir = File(root, "$module/src/main")
        require(dir.isDirectory) { "main sources missing: $dir" }
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
    }

    private fun climb(start: File): File? = generateSequence(start) { it.parentFile }
        .take(12)
        .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "core-monitor").isDirectory }
}

/**
 * The invariants every parser entry point has to hold for **any** input.
 *
 * Two families, and the second is the interesting one:
 *
 *  - *never throws*. Each parser already catches `Throwable` and turns it into a
 *    warning, so the assertion is on that warning rather than on an exception
 *    escaping. A pattern that blows up in a static initialiser still escapes,
 *    which is the point.
 *  - *absence is not zero*. A section the dump does not contain must come back
 *    as null or as an empty list, never as a struct full of zeros. This is the
 *    invariant the 850-line overrun broke in the other direction — a parser that
 *    kept reading past its section produced a confident wrong answer instead of
 *    an honest "not here" — and it is the one a fixture-by-fixture expectation
 *    test cannot state, because it can only state what each fixture happens to
 *    contain.
 *
 * The absence checks re-derive the answer from the raw text (does any line say
 * `A2DP State:`?) rather than from the parser's own warnings, so a parser and
 * its warning cannot agree with each other and both be wrong.
 */
internal object ParserInvariants {

    fun assertAll(label: String, text: String) {
        assertDumpsysBluetooth(label, text)
        assertA2dpLinkDump(label, text)
        assertAudioFlinger(label, text)
        assertPlayingStreams(label, text)
    }

    // ---- dumpsys bluetooth_manager, hierarchy-3 fallback parser --------------

    private fun assertDumpsysBluetooth(label: String, text: String) {
        val snapshot = DumpsysBluetoothParser.parse(text)
        assertNoParseFailure(label, "DumpsysBluetoothParser", snapshot.warnings)
        if (snapshot.devices.isEmpty()) {
            assertTrue(
                "$label: DumpsysBluetoothParser found nothing and said nothing about it",
                snapshot.warnings.isNotEmpty(),
            )
        }
        snapshot.devices.forEach { device ->
            val d = "$label: device ${device.address}"
            assertTrue("$d has a blank address", device.address.isNotBlank())
            // UNKNOWN is what the decoder returns when it could not identify a
            // codec, and the parser is supposed to drop it rather than carry it.
            assertTrue("$d carries CodecFamily.UNKNOWN", device.codec != CodecFamily.UNKNOWN)
            assertPositiveOrAbsent(d, "sampleRateHz", device.sampleRateHz)
            assertPositiveOrAbsent(d, "bitsPerSample", device.bitsPerSample)
            device.rssiDbm?.let {
                assertTrue("$d has an out-of-range rssi $it", it in -127..0)
            }
        }
    }

    // ---- the live A2DP link -------------------------------------------------

    private fun assertA2dpLinkDump(label: String, text: String) {
        val dump = A2dpLinkDumpParser.parse(text)
        assertNoParseFailure(label, "A2dpLinkDumpParser", dump.warnings)

        // readTxStats: the `A2DP State:` block, reached through parse().
        if (!hasTopLevelSection(text, "A2DP State:")) {
            assertNull(
                "$label: tx counters materialised out of a dump with no 'A2DP State:' section",
                dump.tx,
            )
        }
        dump.tx?.let { tx ->
            assertTrue(
                "$label: an empty tx-stats struct was returned instead of null",
                !tx.isEmpty,
            )
            listOf(
                "enqueueCount" to tx.enqueueCount,
                "dequeueCount" to tx.dequeueCount,
                "readBufCount" to tx.readBufCount,
                "flushedCount" to tx.flushedCount,
                "droppedCount" to tx.droppedCount,
                "dropoutCount" to tx.dropoutCount,
                "underflowCount" to tx.underflowCount,
                "underflowBytes" to tx.underflowBytes,
            ).forEach { (name, value) ->
                value?.let { assertTrue("$label: negative $name = $it", it >= 0) }
            }
        }
        // Absence has to be announced, not merely returned. A blank read is
        // short-circuited with its own "empty dump" warning before the section
        // readers run, so the requirement is a warning rather than that one
        // warning's exact wording.
        if (dump.tx == null) {
            assertTrue(
                "$label: no tx stats and no warning saying so",
                dump.warnings.any { it.contains("A2DP State:") || it.contains("empty dump") },
            )
        }

        // readLdacStackState: gated on the negotiated codec, which is the
        // cross-field property worth pinning — a bitrate can only be reported
        // for the codec that is actually running.
        if (!hasTopLevelSection(text, "A2DP LDAC State:")) {
            assertNull("$label: an LDAC block appeared from a dump without one", dump.ldacStack)
        }
        dump.ldacStack?.let { ldac ->
            assertEquals(
                "$label: an LDAC stack state was attached to a link that is not LDAC",
                CodecFamily.LDAC,
                dump.codec?.family,
            )
            assertTrue("$label: an empty LdacStackState was returned instead of null", !ldac.isEmpty)
            assertPositiveOrAbsent(label, "transmissionKbps", ldac.transmissionKbps)
            assertPositiveOrAbsent(label, "effectiveMtu", ldac.effectiveMtu)
            ldac.savedTxQueueLength?.let {
                assertTrue("$label: negative savedTxQueueLength $it", it >= 0)
            }
        }

        if (dump.device == null) {
            assertTrue(
                "$label: no device and no warning saying so",
                dump.warnings.any { it.contains("A2dpStateMachine") || it.contains("empty dump") },
            )
        }
        dump.codec?.let { codec ->
            assertTrue("$label: codec carries CodecFamily.UNKNOWN", codec.family != CodecFamily.UNKNOWN)
            assertPositiveOrAbsent(label, "codec.sampleRateHz", codec.sampleRateHz)
            assertPositiveOrAbsent(label, "codec.bitsPerSample", codec.bitsPerSample)
        }
        // A codec without a device is a codec attributed to nobody.
        if (dump.codec != null) {
            assertTrue("$label: a codec was reported with no device", dump.device != null)
        }
    }

    // ---- AudioFlinger output threads ----------------------------------------

    private fun assertAudioFlinger(label: String, text: String) {
        val dump = AudioFlingerTrackParser.parse(text)
        assertNoParseFailure(label, "AudioFlingerTrackParser", dump.warnings)

        if (!text.contains("Output thread")) {
            assertEquals(
                "$label: output threads appeared from a dump with no thread header",
                emptyList<Any>(),
                dump.threads,
            )
        }
        if (dump.threads.isEmpty()) {
            assertTrue(
                "$label: AudioFlingerTrackParser found nothing and said nothing about it",
                dump.warnings.isNotEmpty(),
            )
        }
        dump.bluetoothThread?.let {
            assertTrue(
                "$label: bluetoothThread returned a thread that is not a Bluetooth route",
                it.output.isBluetoothRoute,
            )
        }
        dump.threads.forEach { thread ->
            val t = "$label: thread ${thread.output.threadName}"
            assertTrue("$t has a blank name", thread.output.threadName.isNotBlank())
            assertPositiveOrAbsent(t, "sampleRateHz", thread.output.sampleRateHz)
            assertPositiveOrAbsent(t, "channelCount", thread.output.channelCount)
            listOf(
                "fastMixerUnderruns" to thread.output.fastMixerUnderruns,
                "normalMixerPartialUnderruns" to thread.output.normalMixerPartialUnderruns,
                "normalMixerEmptyUnderruns" to thread.output.normalMixerEmptyUnderruns,
            ).forEach { (name, value) ->
                value?.let { assertTrue("$t: negative $name = $it", it >= 0) }
            }
            thread.tracks.forEach { track ->
                val r = "$t track ${track.pid}/${track.uid}"
                assertTrue("$r has a negative pid", track.pid >= 0)
                assertTrue("$r has a negative uid", track.uid >= 0)
                assertPositiveOrAbsent(r, "sampleRateHz", track.sampleRateHz)
                track.underruns?.let { assertTrue("$r: negative underruns $it", it >= 0) }
                track.flushed?.let { assertTrue("$r: negative flushed $it", it >= 0) }
            }
        }
    }

    // ---- dumpsys audio, monitor side ----------------------------------------

    private fun assertPlayingStreams(label: String, text: String) {
        val streams = PlayingStreamParser.playingStreams(text)
        if (!text.contains("AudioPlaybackConfiguration")) {
            assertEquals(
                "$label: playing streams appeared from a dump with no playback configurations",
                emptyList<Any>(),
                streams,
            )
        }
        streams.forEach { stream ->
            val s = "$label: stream u/pid ${stream.uid}/${stream.pid}"
            assertTrue("$s has a negative uid", stream.uid >= 0)
            assertTrue("$s has a negative pid", stream.pid >= 0)
            // Zero is what SoundPool prints when the framework never had a rate;
            // it has to reach the caller as "unknown", not as 0 Hz.
            assertPositiveOrAbsent(s, "sampleRateHz", stream.sampleRateHz)
            assertPositiveOrAbsent(s, "channelCount", stream.channelCount)
            stream.sessionId?.let { assertTrue("$s has a negative session id $it", it >= 0) }
        }
    }

    // ---- shared -------------------------------------------------------------

    /**
     * A top-level section header, by the same rule the parsers use: column zero,
     * nothing but the label on the line. Deliberately re-derived here rather
     * than asked of the parser.
     */
    private fun hasTopLevelSection(text: String, header: String): Boolean =
        text.lineSequence().any { line ->
            line.isNotBlank() && line.first() != ' ' && line.first() != '\t' && line.trim() == header
        }

    private fun assertNoParseFailure(label: String, parser: String, warnings: List<String>) {
        assertTrue(
            "$label: $parser threw — ${warnings.filter { it.contains("parse failed") }}",
            warnings.none { it.contains("parse failed") },
        )
    }

    private fun assertPositiveOrAbsent(what: String, field: String, value: Int?) {
        value?.let {
            assertTrue("$what: $field came back as $it instead of absent", it > 0)
        }
    }
}
