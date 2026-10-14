package dev.dankyeeter.btdashboard.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whitelist and the wire format.
 *
 * Worth testing independently of the transport, and worth keeping after the
 * socket transport was measured to be blocked by SELinux (see
 * [PrivilegedServer]): whatever carries the messages, *these* are the rules
 * about what a process running with shell privileges will agree to do.
 */
class PrivilegedProtocolTest {

    // ---- the whitelist ------------------------------------------------------

    @Test
    fun `exactly the four commands this app issues are allowed`() {
        assertTrue(PrivilegedProtocol.isAllowed(listOf("dumpsys", "bluetooth_manager")))
        assertTrue(PrivilegedProtocol.isAllowed(listOf("dumpsys", "media.audio_flinger")))
        assertTrue(PrivilegedProtocol.isAllowed(listOf("dumpsys", "audio")))
        assertTrue(PrivilegedProtocol.isAllowed(listOf("ps", "-A", "-o", "PID,NAME")))
        // Pinned on purpose. This list is the entire privileged surface, and it
        // should never grow by accident - each addition is a deliberate decision
        // with a reason written next to it.
        assertEquals(4, PrivilegedProtocol.ALLOWED.size)
    }

    @Test
    fun `every allowed command only reads`() {
        // The helper runs as shell. Nothing on this list may change device
        // state: `dumpsys <service>` without further arguments prints a report,
        // and `ps` lists processes. Anything that writes belongs behind a named
        // AIDL method where it can be classified as mutating - see
        // PrivilegedOperation - not behind a generic exec.
        assertTrue(
            PrivilegedProtocol.ALLOWED.none { command ->
                command.any { it in setOf("set", "put", "start", "stop", "kill") }
            },
        )
    }

    @Test
    fun `a different service on an allowed executable is refused`() {
        // Matching is the whole argument vector, not the program name. Allowing
        // `dumpsys <anything>` would hand out most of the system's state.
        assertFalse(PrivilegedProtocol.isAllowed(listOf("dumpsys", "package")))
        assertFalse(PrivilegedProtocol.isAllowed(listOf("dumpsys")))
    }

    @Test
    fun `extra arguments are refused, not ignored`() {
        // Nothing this app runs varies at runtime, so a "close enough" match
        // would widen the surface for no benefit at all.
        assertFalse(
            PrivilegedProtocol.isAllowed(listOf("dumpsys", "bluetooth_manager", "--proto")),
        )
        assertFalse(PrivilegedProtocol.isAllowed(listOf("ps", "-A")))
    }

    @Test
    fun `nothing else gets through`() {
        listOf(
            listOf("sh", "-c", "rm -rf /"),
            listOf("pm", "uninstall", "com.something"),
            listOf("settings", "put", "global", "anything", "1"),
            emptyList(),
        ).forEach {
            assertFalse("$it must be refused", PrivilegedProtocol.isAllowed(it))
        }
    }

    // ---- the token compare --------------------------------------------------

    @Test
    fun `a blank token never matches, not even an identical blank one`() {
        // MessageDigest.isEqual alone accepts two equal whitespace strings. The
        // check in front of it has to refuse blank, not merely empty.
        assertFalse(PrivilegedProtocol.tokensMatch(" ", " "))
    }

    // ---- the wire format ----------------------------------------------------

    @Test
    fun `output containing newlines survives, which is the point of the encoding`() {
        // Every dumpsys reply is full of newlines. A plain-text line protocol
        // would have the first one end the message and the rest be parsed as
        // further commands.
        val dump = "Bluetooth Status\n  state: ON\n\n  device: XX:XX\n"
        val encoded = PrivilegedProtocol.encodeResult(0, dump, "")
        assertEquals(1, encoded.lines().size)

        val (exit, stdout, stderr) = PrivilegedProtocol.decodeResult(encoded)!!
        assertEquals(0, exit)
        assertEquals(dump, stdout)
        assertEquals("", stderr)
    }

    @Test
    fun `a non-zero exit is carried as data, not as an error`() {
        val (exit, _, stderr) = PrivilegedProtocol.decodeResult(
            PrivilegedProtocol.encodeResult(1, "", "not found"),
        )!!
        assertEquals(1, exit)
        assertEquals("not found", stderr)
    }

    @Test
    fun `errors survive a round trip`() {
        val message = "not on the whitelist: sh"
        assertEquals(message, PrivilegedProtocol.decodeError(PrivilegedProtocol.encodeError(message)))
    }

    @Test
    fun `garbage decodes to null instead of throwing`() {
        // The decoders run in the app on replies from a shell-uid process that
        // was only accepted after its uid and token were checked. That is still
        // a process boundary, so malformed input has to be a refusable value,
        // not a crash.
        listOf("", "OK", "OK x y z", "OK 0 !!! !!!", "ERR", "NONSENSE").forEach {
            assertNull("decodeResult($it)", PrivilegedProtocol.decodeResult(it))
            assertNull("decodeError($it)", PrivilegedProtocol.decodeError(it))
        }
        assertNull(PrivilegedProtocol.decodeResult("OK notanumber a b"))
        assertNull(PrivilegedProtocol.decodeError("OK 0 a b"))
    }

    // ---- codec replies ------------------------------------------------------

    @Test
    fun `a codec observation survives a round trip`() {
        val observation = CodecObservation(
            family = "LDAC",
            sampleRateHz = 96_000,
            bitsPerSample = 24,
            channelMode = ChannelModes.STEREO,
            ldacQuality = 1000L,
            matched = true,
            selectable = listOf("LDAC", "AAC", "SBC"),
            note = "read back after 750 ms",
        )
        assertEquals(observation, PrivilegedProtocol.decodeCodec(PrivilegedProtocol.encodeCodec(observation)))
    }

    @Test
    fun `all three states of matched survive`() {
        // Three-valued on purpose: "agreed", "did not agree (yet)", and "we
        // only read, nothing was requested". Collapsing them to a boolean is
        // how "not observed" turns into "failed".
        listOf(true, false, null).forEach { matched ->
            val observation = CodecObservation(family = "AAC", matched = matched)
            val decoded = PrivilegedProtocol.decodeCodec(PrivilegedProtocol.encodeCodec(observation))
            assertEquals(matched, decoded?.matched)
        }
    }

    @Test
    fun `a note with spaces does not split the line`() {
        // The free text is the part that carries a sentence for the user, and a
        // separator-delimited line with unescaped text in it is the oldest bug
        // in this file.
        val observation = CodecObservation(
            family = "APTX_HD",
            matched = false,
            note = "after 2500 ms the codec still reads aptX - the stack either refused " +
                "the request or has not finished renegotiating",
        )
        val encoded = PrivilegedProtocol.encodeCodec(observation)
        assertEquals(1, encoded.lines().size)
        assertEquals(observation.note, PrivilegedProtocol.decodeCodec(encoded)?.note)
    }

    @Test
    fun `an empty selectable list stays empty rather than becoming one blank entry`() {
        val encoded = PrivilegedProtocol.encodeCodec(CodecObservation(family = "SBC"))
        assertEquals(emptyList<String>(), PrivilegedProtocol.decodeCodec(encoded)?.selectable)
    }

    @Test
    fun `a codec line is not mistaken for anything else, and vice versa`() {
        val codec = PrivilegedProtocol.encodeCodec(CodecObservation(family = "SBC"))
        assertNull(PrivilegedProtocol.decodeResult(codec))
        assertNull(PrivilegedProtocol.decodeError(codec))

        assertNull(PrivilegedProtocol.decodeCodec(PrivilegedProtocol.encodeError("nope")))
        assertNull(PrivilegedProtocol.decodeCodec(PrivilegedProtocol.encodeResult(0, "a", "b")))
    }

    @Test
    fun `garbage in a codec line decodes to null instead of throwing`() {
        listOf(
            "",
            "CODEC",
            "CODEC a b c d e f g",
            "CODEC !!! 1 1 1 1 1 !!! !!!",
            "CODEC ${b64("SBC")} x 1 1 1 1 ${b64("")} ${b64("")}",
            "CODEC ${b64("SBC")} 1 1 1 1 7 ${b64("")} ${b64("")}",
        ).forEach { assertNull("decodeCodec($it)", PrivilegedProtocol.decodeCodec(it)) }
    }

    private fun b64(value: String): String =
        java.util.Base64.getEncoder().encodeToString(value.toByteArray())

    // ---- exec receipts ------------------------------------------------------
    //
    // Since helper version 6 the payload goes into a descriptor the app owns;
    // the reply only says what was written. See ARCHITECTURE.md AD-010.

    @Test
    fun `a receipt survives a round trip`() {
        val encoded = PrivilegedProtocol.encodeExecReceipt(7, 227_413, "one warning line")
        assertEquals(1, encoded.lines().size)

        val receipt = PrivilegedProtocol.decodeExecReceipt(encoded)!!
        assertEquals(7, receipt.exitCode)
        assertEquals(227_413, receipt.byteCount)
        assertEquals("one warning line", receipt.stderr)
    }

    @Test
    fun `a receipt carries no payload and no path`() {
        // SR-001: a reply with a path field is a reply the client can be
        // pointed somewhere with. Four fields, none of them a location.
        val encoded = PrivilegedProtocol.encodeExecReceipt(0, 12, "")
        assertEquals(4, encoded.split(' ').size)
        assertFalse(encoded.contains("/"))
    }

    @Test
    fun `a receipt is not mistaken for anything else, and vice versa`() {
        val receipt = PrivilegedProtocol.encodeExecReceipt(0, 1, "")
        assertNull(PrivilegedProtocol.decodeResult(receipt))
        assertNull(PrivilegedProtocol.decodeError(receipt))
        assertNull(PrivilegedProtocol.decodeCodec(receipt))

        assertNull(PrivilegedProtocol.decodeExecReceipt(PrivilegedProtocol.encodeResult(0, "a", "b")))
        assertNull(PrivilegedProtocol.decodeExecReceipt(PrivilegedProtocol.encodeError("nope")))
        // The version-5 file hand-over must not decode as a receipt either.
        assertNull(PrivilegedProtocol.decodeExecReceipt("FILE 0 ${b64("/x")} 1 ${b64("")}"))
    }

    @Test
    fun `garbage in a receipt decodes to null instead of throwing`() {
        listOf(
            "",
            "FD",
            "FD 0 1",
            "FD 0 1 ${b64("")} extra",
            "FD zero 1 ${b64("")}",
            "FD 0 notanumber ${b64("")}",
            "FD 0 1 !!!not base64!!!",
            // A negative length is not a short read, it is a malformed line.
            "FD 0 -1 ${b64("")}",
        ).forEach { assertNull("decodeExecReceipt($it)", PrivilegedProtocol.decodeExecReceipt(it)) }
    }

    @Test
    fun `an enormous stderr is bounded and says so`() {
        // stderr is the one field still on the wire, so it is the one that
        // could put a megabyte into a transaction.
        val stderr = "e".repeat(PrivilegedProtocol.INLINE_LIMIT_BYTES * 2)
        val carried = PrivilegedProtocol.decodeExecReceipt(
            PrivilegedProtocol.encodeExecReceipt(0, 5, stderr),
        )!!.stderr
        assertTrue(carried.length < stderr.length)
        assertTrue(carried.contains("truncated"))
    }

    // ---- the operation surface ----------------------------------------------

    @Test
    fun `every method on the Binder is classified as reading or writing`() {
        // The guard the AIDL documentation promises. A method added to
        // IPrivilegedService and forgotten in PrivilegedOperation would be an
        // entry point on a shell-uid process that nobody decided was safe.
        val onTheInterface = IPrivilegedService::class.java.declaredMethods
            .map { it.name }
            .toSortedSet()
        val classified = PrivilegedProtocol.PrivilegedOperation.entries
            .map { it.aidlName }
            .toSortedSet()

        assertEquals(classified, onTheInterface)
    }

    @Test
    fun `there is exactly one exec, and it takes the reply descriptor`() {
        // A6: the classification above compares names, so an overload of exec
        // would pass it. The version-5 exec without a descriptor is exactly the
        // inline-or-file reply SR-001 is about and must not survive beside the
        // new one.
        val execs = IPrivilegedService::class.java.declaredMethods.filter { it.name == "exec" }
        assertEquals(1, execs.size)
        assertEquals(
            listOf(String::class.java, List::class.java, android.os.ParcelFileDescriptor::class.java),
            execs.single().parameterTypes.toList(),
        )
    }

    @Test
    fun `mutating operations are a short, named list`() {
        // The security note that came with codec control: until it, every
        // operation was read-only. If this list ever grows, it should be
        // because somebody meant it to.
        //
        // `grantSecureSettings` was meant. It is the widest thing in here - it
        // permanently raises what the *app* may do, rather than changing a
        // setting - and it is deliberately a named operation instead of a
        // `pm grant` entry on the exec whitelist, which would have smuggled a
        // privilege change through the door marked read-only.
        // `setOptionalCodecs` and `restartBluetooth` were meant too, and both
        // are here rather than on the exec whitelist for the same reason
        // `grantSecureSettings` is: `cmd bluetooth_manager` also offers
        // `factoryReset`, which un-pairs every device the user owns, so a
        // caller able to vary those arguments would be a much larger thing than
        // a button that cycles the radio.
        assertEquals(
            listOf(
                "grantSecureSettings",
                "restartBluetooth",
                "setCodecPreference",
                "setOptionalCodecs",
                "shutdown",
            ),
            PrivilegedProtocol.PrivilegedOperation.entries.filter { it.mutates }.map { it.aidlName }.sorted(),
        )
        assertEquals(
            listOf("codecStatus", "exec", "optionalCodecs", "version"),
            PrivilegedProtocol.PrivilegedOperation.entries.filterNot { it.mutates }.map { it.aidlName }.sorted(),
        )
    }

    @Test
    fun `an HD-audio observation survives the round trip with all three states`() {
        // The tri-state is the whole point: "unknown" must not decay into
        // "false" anywhere between the helper and the screen, because the UI
        // words the two completely differently - "Android decides" versus "this
        // device is held to SBC".
        listOf(
            HdAudioObservation(supported = true, enabled = true, note = "on"),
            HdAudioObservation(supported = true, enabled = false, note = "off"),
            HdAudioObservation(supported = true, enabled = null, note = "nobody chose"),
            HdAudioObservation(supported = false, enabled = null, note = "SBC-only headphone"),
            HdAudioObservation(supported = null, enabled = null, note = ""),
        ).forEach { original ->
            val decoded = PrivilegedProtocol.decodeHdAudio(PrivilegedProtocol.encodeHdAudio(original))
            assertEquals(original, decoded)
        }
    }

    @Test
    fun `a malformed HD-audio line decodes to null rather than to a default`() {
        // Same discipline as the codec decoder: the reply crosses a process
        // boundary from a shell-uid process, accepted only after its uid and
        // token were checked, so garbage has to be refusable rather than
        // silently readable as "off".
        listOf(
            "HDAUDIO 1 1",
            "HDAUDIO 1 1 ${b64("")} extra",
            "HDAUDIO 2 1 ${b64("")}",
            "HDAUDIO 1 yes ${b64("")}",
            "CODEC 1 1 ${b64("")}",
        ).forEach { assertNull("decodeHdAudio($it)", PrivilegedProtocol.decodeHdAudio(it)) }
    }

    @Test
    fun `the three shell commands are all read-only`() {
        // The exec whitelist and the mutating list have to agree: exec is
        // classified as a read, which is only true while everything it can run
        // is a read.
        assertTrue(
            PrivilegedProtocol.ALLOWED.all { it.first() == "dumpsys" || it.first() == "ps" },
        )
        assertFalse(PrivilegedProtocol.PrivilegedOperation.EXEC.mutates)
    }
}
