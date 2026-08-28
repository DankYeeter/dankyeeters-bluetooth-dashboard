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

    // ---- the wire format ----------------------------------------------------

    @Test
    fun `auth survives a round trip`() {
        val token = "6f5c1e8a-0b3d-4a71-9e2f-1c4d5b6a7f80"
        assertEquals(token, PrivilegedProtocol.decodeAuth(PrivilegedProtocol.encodeAuth(token)))
    }

    @Test
    fun `a command survives a round trip`() {
        val command = listOf("ps", "-A", "-o", "PID,NAME")
        assertEquals(command, PrivilegedProtocol.decodeRun(PrivilegedProtocol.encodeRun(command)))
    }

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
        // The helper reads from a socket anything on the device may connect to.
        // Malformed input has to be a refusable value, not a crash of a
        // privileged process.
        listOf("", "RUN", "RUN !!!not base64!!!", "OK", "OK x y z", "NONSENSE").forEach {
            assertNull("decodeRun($it)", PrivilegedProtocol.decodeRun(it))
        }
        assertNull(PrivilegedProtocol.decodeAuth("AUTH"))
        assertNull(PrivilegedProtocol.decodeResult("OK notanumber a b"))
        assertNull(PrivilegedProtocol.decodeError("OK 0 a b"))
    }

    @Test
    fun `a result line is not mistaken for a command line`() {
        val result = PrivilegedProtocol.encodeResult(0, "x", "")
        assertNull(PrivilegedProtocol.decodeRun(result))
        assertNull(PrivilegedProtocol.decodeAuth(result))
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
        assertNull(PrivilegedProtocol.decodeRun(codec))
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

    // ---- large exec replies -------------------------------------------------
    //
    // The frame that exists because a 222 KB dumpsys reply, doubled by UTF-16,
    // does not fit in a 1 MB Binder buffer shared with two other readers. See
    // PrivilegedProtocol.INLINE_LIMIT_BYTES.

    @Test
    fun `a staged reply survives a round trip`() {
        val encoded = PrivilegedProtocol.encodeFileResult(
            exitCode = 0,
            path = "/data/local/tmp/btdash_exec_a1b2c3.out",
            byteCount = 227_413,
            stderr = "one warning line",
        )
        assertEquals(1, encoded.lines().size)

        val handoff = PrivilegedProtocol.decodeFileResult(encoded)!!
        assertEquals(0, handoff.exitCode)
        assertEquals("/data/local/tmp/btdash_exec_a1b2c3.out", handoff.path)
        assertEquals(227_413, handoff.byteCount)
        assertEquals("one warning line", handoff.stderr)
    }

    @Test
    fun `a staged reply is not mistaken for anything else, and vice versa`() {
        // The two result shapes are the pair most worth keeping apart: a client
        // that read a staged reply as an inline one would hand the parsers a
        // file path where a dump should be, and a path parses as an empty dump
        // rather than as an error.
        val staged = PrivilegedProtocol.encodeFileResult(0, "/data/local/tmp/btdash_exec_x.out", 1, "")
        assertNull(PrivilegedProtocol.decodeResult(staged))
        assertNull(PrivilegedProtocol.decodeError(staged))
        assertNull(PrivilegedProtocol.decodeCodec(staged))
        assertNull(PrivilegedProtocol.decodeRun(staged))

        assertNull(PrivilegedProtocol.decodeFileResult(PrivilegedProtocol.encodeResult(0, "a", "b")))
        assertNull(PrivilegedProtocol.decodeFileResult(PrivilegedProtocol.encodeError("nope")))
    }

    @Test
    fun `garbage in a staged reply decodes to null instead of throwing`() {
        listOf(
            "",
            "FILE",
            "FILE 0 ${b64("/x")} 1",
            "FILE 0 ${b64("/x")} 1 ${b64("")} extra",
            "FILE zero ${b64("/x")} 1 ${b64("")}",
            "FILE 0 !!!not base64!!! 1 ${b64("")}",
            "FILE 0 ${b64("/x")} notanumber ${b64("")}",
            // A negative length is not a short read, it is a malformed line —
            // and the reader compares it against a real file size.
            "FILE 0 ${b64("/x")} -1 ${b64("")}",
        ).forEach { assertNull("decodeFileResult($it)", PrivilegedProtocol.decodeFileResult(it)) }
    }

    @Test
    fun `a reply of exactly the inline limit still travels inline`() {
        // The boundary is inclusive on the inline side, so the limit itself is
        // a size that never touches the filesystem.
        val stdout = "x".repeat(PrivilegedProtocol.INLINE_LIMIT_BYTES)
        val encoded = PrivilegedProtocol.encodeExecResult(0, stdout, "") {
            error("a reply of exactly the limit must not be staged")
        }
        assertEquals(stdout, PrivilegedProtocol.decodeResult(encoded)!!.second)
        assertNull(PrivilegedProtocol.decodeFileResult(encoded))
    }

    @Test
    fun `one byte over the limit is staged instead`() {
        val stdout = "x".repeat(PrivilegedProtocol.INLINE_LIMIT_BYTES + 1)
        var staged: String? = null
        val encoded = PrivilegedProtocol.encodeExecResult(7, stdout, "warned") {
            staged = it
            "/data/local/tmp/btdash_exec_over.out"
        }
        assertEquals(stdout, staged)
        assertNull(PrivilegedProtocol.decodeResult(encoded))

        val handoff = PrivilegedProtocol.decodeFileResult(encoded)!!
        assertEquals(7, handoff.exitCode)
        assertEquals("/data/local/tmp/btdash_exec_over.out", handoff.path)
        assertEquals(PrivilegedProtocol.INLINE_LIMIT_BYTES + 1, handoff.byteCount)
        assertEquals("warned", handoff.stderr)
    }

    @Test
    fun `the limit counts bytes, not characters`() {
        // The wire carries UTF-16 and a dump can hold a non-ASCII device name.
        // Counting characters would let a reply of twice the intended size
        // through as "inline" — which is the transaction that fails.
        val halfLimit = PrivilegedProtocol.INLINE_LIMIT_BYTES / 2
        val exactly = "ä".repeat(halfLimit)
        assertEquals(PrivilegedProtocol.INLINE_LIMIT_BYTES, exactly.toByteArray(Charsets.UTF_8).size)
        assertNull(
            PrivilegedProtocol.decodeFileResult(
                PrivilegedProtocol.encodeExecResult(0, exactly, "") { error("must not stage") },
            ),
        )

        val over = "ä".repeat(halfLimit + 1)
        assertTrue(over.length < PrivilegedProtocol.INLINE_LIMIT_BYTES)
        val encoded = PrivilegedProtocol.encodeExecResult(0, over, "") { "/data/local/tmp/btdash_exec_u.out" }
        assertEquals(
            over.toByteArray(Charsets.UTF_8).size,
            PrivilegedProtocol.decodeFileResult(encoded)!!.byteCount,
        )
    }

    @Test
    fun `a reply that cannot be staged is an error, never an inline reply`() {
        // Falling back to inline here would send exactly the transaction that
        // fails, and the user would see the helper "stop responding" instead of
        // a sentence naming the real problem.
        val stdout = "x".repeat(PrivilegedProtocol.INLINE_LIMIT_BYTES + 1)
        val encoded = PrivilegedProtocol.encodeExecResult(0, stdout, "") { null }
        assertNull(PrivilegedProtocol.decodeResult(encoded))
        assertNull(PrivilegedProtocol.decodeFileResult(encoded))
        assertTrue(PrivilegedProtocol.decodeError(encoded)!!.contains("could not stage"))
    }

    @Test
    fun `an enormous stderr is bounded rather than staged`() {
        // Only stdout is ever staged, because stdout is the payload. stderr is
        // the one field left that could still put a megabyte into a
        // transaction, so it is truncated - and says that it was.
        val stderr = "e".repeat(PrivilegedProtocol.INLINE_LIMIT_BYTES * 2)
        val encoded = PrivilegedProtocol.encodeExecResult(0, "short", stderr) { error("must not stage") }
        val carried = PrivilegedProtocol.decodeResult(encoded)!!.third
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
            PrivilegedProtocol.WRITE_OPERATIONS.map { it.aidlName }.sorted(),
        )
        assertEquals(
            listOf("codecStatus", "exec", "optionalCodecs", "version"),
            PrivilegedProtocol.READ_OPERATIONS.map { it.aidlName }.sorted(),
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
        // Same discipline as the codec decoder: the helper answers a socket
        // anything on the device may reach, so garbage has to be refusable
        // rather than silently readable as "off".
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

    @Test
    fun `operations can be found by their AIDL name`() {
        assertEquals(
            PrivilegedProtocol.PrivilegedOperation.SET_CODEC_PREFERENCE,
            PrivilegedProtocol.PrivilegedOperation.byAidlName("setCodecPreference"),
        )
        assertNull(PrivilegedProtocol.PrivilegedOperation.byAidlName("nothingLikeThat"))
    }
}
