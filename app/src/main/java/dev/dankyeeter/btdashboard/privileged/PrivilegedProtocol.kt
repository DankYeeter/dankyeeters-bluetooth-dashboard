package dev.dankyeeter.btdashboard.privileged

import java.util.Base64

/**
 * The wire format between the app and its own privileged helper, and the
 * closed set of things that helper will do.
 *
 * Pure Kotlin on purpose: this is the part that decides what a process running
 * with shell privileges is willing to execute, so it is the part that must be
 * testable without a device.
 *
 * ## Why a whitelist and not a shell
 *
 * Shizuku hands an authorised app a general shell. That is the right trade for
 * a general-purpose tool; it is the wrong one here, because this app issues
 * exactly three commands and never will issue a fourth without someone editing
 * [ALLOWED]. A general shell behind a socket is a much larger thing to get
 * wrong than a list of three fixed argument vectors.
 *
 * Matching is by exact argument vector, not by prefix or by executable name.
 * `dumpsys` with a different service, or the same service with an extra flag,
 * is refused — there is no argument this app needs to vary at runtime, so
 * allowing variation would only widen the surface for nothing.
 *
 * ## The shift that happened when codec control arrived
 *
 * Until the codec work, **every** helper operation was read-only: three
 * dumpsys/ps commands that observe and change nothing. Setting a codec is the
 * first operation that changes the state of the device.
 *
 * That is a category change, not a bigger version of the same thing, so it is
 * modelled as one: [PrivilegedOperation] names every entry point the Binder
 * offers and declares for each whether it mutates. Mutating operations are a
 * separate, explicitly listed set — deliberately *not* a flag or an extra
 * argument on the read path, because a mutating call that travels through the
 * same door as a read is one forgotten branch away from being reachable by
 * accident. `PrivilegedProtocolTest` reflects over the AIDL interface and
 * fails if a method is added there and not classified here.
 */
object PrivilegedProtocol {

    /** Every shell command the app is allowed to ask for, in full. */
    val ALLOWED: List<List<String>> = listOf(
        listOf("dumpsys", "bluetooth_manager"),
        listOf("dumpsys", "media.audio_flinger"),
        // Read-only, and the narrowest source for one specific question: which
        // audio sessions are playing *media* right now. Players that never
        // broadcast their session (Tidal) can only be equalised by learning the
        // id this way - measured on the device, an effect attached to a
        // harvested id works exactly like an announced one.
        //
        // Chosen over the already-allowed media.audio_flinger because it is
        // more precise, not because it is more powerful: it distinguishes
        // state:started from paused and USAGE_MEDIA from notification blips, so
        // the app attaches to music and nothing else. Only integer session ids
        // are kept; package names, metadata and playback content are discarded
        // at the parser - see PlaybackSessionParser.
        listOf("dumpsys", "audio"),
        listOf("ps", "-A", "-o", "PID,NAME"),
    )

    fun isAllowed(command: List<String>): Boolean = ALLOWED.any { it == command }

    // ---- the operation surface ----------------------------------------------

    /**
     * Every method the helper's Binder offers, and whether it changes anything.
     *
     * [aidlName] must match `IPrivilegedService` exactly; the test asserts the
     * two agree in both directions, so neither a new method nor a renamed one
     * can slip through unclassified.
     */
    enum class PrivilegedOperation(val aidlName: String, val mutates: Boolean) {
        /** Build number of the running helper. Reveals nothing, changes nothing. */
        VERSION("version", mutates = false),

        /** Runs one of [ALLOWED]. All three only read. */
        EXEC("exec", mutates = false),

        /** Reads the negotiated A2DP codec through the privileged system API. */
        CODEC_STATUS("codecStatus", mutates = false),

        /**
         * Asks the Bluetooth stack to renegotiate a codec. The first — and so
         * far only — operation that changes the device.
         */
        SET_CODEC_PREFERENCE("setCodecPreference", mutates = true),

        /**
         * Stops the helper. Counted as mutating: it ends the privileged
         * process, which is the largest state change on offer here.
         */
        SHUTDOWN("shutdown", mutates = true),
        ;

        companion object {
            fun byAidlName(name: String): PrivilegedOperation? =
                entries.firstOrNull { it.aidlName == name }
        }
    }

    /** Operations that observe and change nothing. */
    val READ_OPERATIONS: List<PrivilegedOperation> =
        PrivilegedOperation.entries.filterNot { it.mutates }

    /**
     * Operations that change the device. Listed separately and by name so that
     * "how much can this helper actually do to the phone" is one short list to
     * read rather than a property to go looking for.
     */
    val WRITE_OPERATIONS: List<PrivilegedOperation> =
        PrivilegedOperation.entries.filter { it.mutates }

    // ---- wire format --------------------------------------------------------
    //
    // One request per line, one response per line, UTF-8. Arguments and output
    // are Base64 so that a newline in a dumpsys dump cannot end the message —
    // the dumps are full of them, and a length-prefixed binary framing would
    // buy nothing else here.

    private const val SEPARATOR = ' '

    /**
     * Strips the line terminator and nothing else.
     *
     * Deliberately not [String.trim]: an empty field encodes to an empty Base64
     * string, so a result with no stderr ends in a separator. trim() ate that
     * trailing empty field, the response came back with three parts instead of
     * four, and every successful command decoded as unreadable.
     */
    private fun String.stripLineEnding(): String = trimEnd('\r', '\n')

    fun encodeAuth(token: String): String = "AUTH ${encode(token)}"

    fun decodeAuth(line: String): String? {
        val parts = line.stripLineEnding().split(SEPARATOR)
        if (parts.size != 2 || parts[0] != "AUTH") return null
        return decodeOrNull(parts[1])
    }

    fun encodeRun(command: List<String>): String =
        (listOf("RUN") + command.map(::encode)).joinToString(SEPARATOR.toString())

    fun decodeRun(line: String): List<String>? {
        val parts = line.stripLineEnding().split(SEPARATOR)
        if (parts.isEmpty() || parts[0] != "RUN" || parts.size < 2) return null
        return parts.drop(1).map { decodeOrNull(it) ?: return null }
    }

    fun encodeResult(exitCode: Int, stdout: String, stderr: String): String =
        "OK $exitCode ${encode(stdout)} ${encode(stderr)}"

    /** Returns (exitCode, stdout, stderr), or null if the line is not a result. */
    fun decodeResult(line: String): Triple<Int, String, String>? {
        val parts = line.stripLineEnding().split(SEPARATOR)
        if (parts.size != 4 || parts[0] != "OK") return null
        val exit = parts[1].toIntOrNull() ?: return null
        val out = decodeOrNull(parts[2]) ?: return null
        val err = decodeOrNull(parts[3]) ?: return null
        return Triple(exit, out, err)
    }

    /**
     * Length-independent token compare, shared by the helper and the provider.
     *
     * One implementation rather than two identical ones, because the two ends
     * silently disagreeing about what counts as a matching token is a much
     * worse bug than the timing leak this closes. A timing attack across a
     * Binder transaction is not a realistic threat here; the compare is free,
     * so there is no reason to leave the asymmetry lying around.
     *
     * A null or blank token never matches, even against a null or blank
     * expectation — "nothing was ever set" must not authenticate anybody.
     */
    fun tokensMatch(offered: String?, expected: String?): Boolean {
        if (offered.isNullOrBlank() || expected.isNullOrBlank()) return false
        val x = offered.toByteArray(Charsets.UTF_8)
        val y = expected.toByteArray(Charsets.UTF_8)
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    fun encodeError(message: String): String = "ERR ${encode(message)}"

    fun decodeError(line: String): String? {
        val parts = line.stripLineEnding().split(SEPARATOR)
        if (parts.size != 2 || parts[0] != "ERR") return null
        return decodeOrNull(parts[1])
    }

    // ---- codec replies ------------------------------------------------------

    /**
     * A codec reply carries *what the helper observed*, never what it asked
     * for. See [CodecObservation]: the whole point of the read-back is that a
     * request and a result are different things, so they must not share an
     * encoding that lets one be mistaken for the other.
     *
     * Numeric fields go over the wire as plain decimal rather than Base64:
     * they cannot contain a separator, and a readable line is worth more in a
     * logcat than two saved characters.
     */
    fun encodeCodec(observation: CodecObservation): String = listOf(
        "CODEC",
        encode(observation.family),
        observation.sampleRateHz.toString(),
        observation.bitsPerSample.toString(),
        observation.channelMode.toString(),
        observation.ldacQuality.toString(),
        when (observation.matched) {
            null -> "-1"
            true -> "1"
            false -> "0"
        },
        encode(observation.selectable.joinToString(",")),
        encode(observation.note),
    ).joinToString(SEPARATOR.toString())

    fun decodeCodec(line: String): CodecObservation? {
        val parts = line.stripLineEnding().split(SEPARATOR)
        if (parts.size != 9 || parts[0] != "CODEC") return null
        return CodecObservation(
            family = decodeOrNull(parts[1]) ?: return null,
            sampleRateHz = parts[2].toIntOrNull() ?: return null,
            bitsPerSample = parts[3].toIntOrNull() ?: return null,
            channelMode = parts[4].toIntOrNull() ?: return null,
            ldacQuality = parts[5].toLongOrNull() ?: return null,
            matched = when (parts[6]) {
                "1" -> true
                "0" -> false
                "-1" -> null
                else -> return null
            },
            selectable = (decodeOrNull(parts[7]) ?: return null)
                .split(",")
                .filter { it.isNotBlank() },
            note = decodeOrNull(parts[8]) ?: return null,
        )
    }

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    /**
     * Malformed Base64 returns null rather than throwing. The server reads from
     * a socket anything on the device may connect to, so garbage in has to be a
     * normal, refusable input rather than a crash of a privileged process.
     */
    private fun decodeOrNull(value: String): String? = runCatching {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    }.getOrNull()
}
