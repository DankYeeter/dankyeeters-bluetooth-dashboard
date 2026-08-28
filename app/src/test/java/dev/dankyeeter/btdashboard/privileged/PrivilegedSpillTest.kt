package dev.dankyeeter.btdashboard.privileged

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The file half of a large exec reply.
 *
 * The reply that made this necessary was 222 KB of `dumpsys` travelling as one
 * Binder String while two other readers were doing the same thing, into a 1 MB
 * buffer the three of them share — see [ExecSpill]. What is exercised here is
 * the agreement the two processes have about that file: its name, when it is
 * swept, and what the reader does when what it finds is not what was announced.
 *
 * [ExecSpill] takes its directory and its clock as parameters for exactly this
 * reason. A temporary folder stands in for `/data/local/tmp`, so both ends of an
 * agreement between two processes can be run inside one JVM.
 */
class PrivilegedSpillTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private var now: Long = 1_700_000_000_000L

    private fun spill(directory: File = temp.root): ExecSpill =
        ExecSpill(directory = directory, clock = { now })

    // ---- the hand-over ------------------------------------------------------

    @Test
    fun `a staged reply comes back byte for byte and the file goes`() {
        val payload = "Bluetooth Status\n  state: ON\n" + "x".repeat(200_000)
        val spill = spill()

        val file = spill.stage(payload)!!
        assertTrue(file.exists())

        val handoff = PrivilegedProtocol.ExecHandoff(
            exitCode = 0,
            path = file.path,
            byteCount = payload.toByteArray(Charsets.UTF_8).size,
            stderr = "",
        )
        assertEquals(payload, spill.collect(handoff).getOrThrow())
        // The client unlinks what it has read. On the device this is best
        // effort - /data/local/tmp belongs to shell and the app usually cannot
        // unlink there - which is why the helper sweeps as well.
        assertFalse("the collected file should have been removed", file.exists())
    }

    @Test
    fun `a non-ASCII payload survives, which the byte count depends on`() {
        // The announced length is in bytes. A device named in anything but
        // ASCII is where a character count and a byte count part company.
        val payload = "device: Sennheiser Momentum – ÄÖÜ\n" + "ß".repeat(70_000)
        val spill = spill()
        val file = spill.stage(payload)!!
        val handoff = PrivilegedProtocol.ExecHandoff(
            0,
            file.path,
            payload.toByteArray(Charsets.UTF_8).size,
            "",
        )
        assertEquals(payload, spill.collect(handoff).getOrThrow())
    }

    @Test
    fun `staging reuses one file rather than making a new one each time`() {
        // Measured on the device: a name per call put ~470 files in
        // /data/local/tmp inside a two-minute session, about 3.5 a second. The
        // reuse is safe because PrivilegedShellRunner.EXEC_LOCK means one reply
        // is staged and collected at a time.
        val spill = spill()
        val first = spill.stage("one")!!
        val second = spill.stage("two")!!

        assertEquals(first.path, second.path)
        assertEquals("two", second.readText())
    }

    @Test
    fun `many staged replies leave exactly one file behind`() {
        val spill = spill()
        repeat(50) { spill.stage("dump number $it") }

        val left = temp.root.listFiles().orEmpty().map { it.name }
        assertEquals(listOf(PrivilegedContract.SPILL_NAME), left)
    }

    @Test
    fun `a staged file is named so that both ends can recognise it`() {
        val file = spill().stage("payload")!!
        assertEquals(PrivilegedContract.SPILL_NAME, file.name)
        assertTrue(file.name.startsWith(PrivilegedContract.SPILL_PREFIX))
        assertTrue(file.name.endsWith(PrivilegedContract.SPILL_SUFFIX))
        assertTrue(spill().isMine(file.path))
    }

    @Test
    fun `a per-call name from an older helper is still collectable`() {
        // The path travels in the reply, so the name is the helper's business.
        // A client running against a helper from the build that used a name per
        // call has to be able to read what that helper wrote.
        val legacy = File(
            temp.root,
            "${PrivilegedContract.SPILL_PREFIX}a1b2c3d4${PrivilegedContract.SPILL_SUFFIX}",
        )
        legacy.writeText("an older helper's dump")

        assertTrue(spill().isMine(legacy.path))
        assertEquals(
            "an older helper's dump",
            spill().collect(
                PrivilegedProtocol.ExecHandoff(
                    0,
                    legacy.path,
                    "an older helper's dump".toByteArray(Charsets.UTF_8).size,
                    "",
                ),
            ).getOrThrow(),
        )
    }

    @Test
    fun `staging into a directory that does not exist fails rather than throws`() {
        // The helper turns this into a worded error. Throwing here would become
        // "the helper stopped responding", which is the wrong sentence twice
        // over: it did respond, and it is still running.
        assertNull(spill(File(temp.root, "absent")).stage("payload"))
    }

    // ---- what the reader refuses -------------------------------------------

    @Test
    fun `a path outside the staging directory is refused`() {
        // The reply is authenticated, so this is a guard against a bug rather
        // than an attacker - but a client that opens whatever path a reply
        // names reaches further than the operation needs.
        val elsewhere = temp.newFile("btdash_exec_stray.out")
        elsewhere.writeText("payload")
        val other = temp.newFolder("other")

        val result = spill(other).collect(
            PrivilegedProtocol.ExecHandoff(0, elsewhere.path, 7, ""),
        )
        assertTrue(result.isFailure)
        assertTrue(elsewhere.exists())
    }

    @Test
    fun `a file with the wrong name is refused even in the right directory`() {
        val log = File(temp.root, "btdash_helper.log")
        log.writeText("helper output")
        assertFalse(spill().isMine(log.path))
        assertTrue(spill().collect(PrivilegedProtocol.ExecHandoff(0, log.path, 13, "")).isFailure)
        assertTrue(log.exists())
    }

    @Test
    fun `a short read is refused instead of parsed`() {
        // This is the failure the announced length exists for. A truncated dump
        // is not malformed - it is what a disconnected headphone produces - so
        // the parsers downstream would accept it and answer the wrong thing.
        val spill = spill()
        val file = spill.stage("the whole dump")!!
        val result = spill.collect(PrivilegedProtocol.ExecHandoff(0, file.path, 99_999, ""))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("partial dump"))
    }

    @Test
    fun `a file that has already gone is refused with a sentence`() {
        val spill = spill()
        val file = spill.stage("payload")!!
        file.delete()
        val result = spill.collect(
            PrivilegedProtocol.ExecHandoff(0, file.path, 7, ""),
        )
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()!!.message)
    }

    // ---- the sweep ----------------------------------------------------------

    @Test
    fun `stale staged files are swept and nothing else is`() {
        val stale = File(temp.root, "${PrivilegedContract.SPILL_PREFIX}old${PrivilegedContract.SPILL_SUFFIX}")
        val fresh = File(temp.root, "${PrivilegedContract.SPILL_PREFIX}new${PrivilegedContract.SPILL_SUFFIX}")
        val log = File(temp.root, "btdash_helper.log")
        val stranger = File(temp.root, "someone_elses_file.out")
        listOf(stale, fresh, log, stranger).forEach { it.writeText("x") }

        val ancient = now - PrivilegedContract.SPILL_MAX_AGE_MS - 1_000L
        stale.setLastModified(ancient)
        log.setLastModified(ancient)
        stranger.setLastModified(ancient)
        fresh.setLastModified(now)

        assertEquals(1, spill().sweepStale())
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        // A privileged process deleting files it knows nothing about would be a
        // much bigger thing than an untidy directory. Age alone is not enough;
        // the name has to match too.
        assertTrue("the helper's own log must survive the sweep", log.exists())
        assertTrue(stranger.exists())
    }

    @Test
    fun `a file exactly at the age limit is left alone`() {
        val edge = File(temp.root, "${PrivilegedContract.SPILL_PREFIX}edge${PrivilegedContract.SPILL_SUFFIX}")
        edge.writeText("x")
        edge.setLastModified(now - PrivilegedContract.SPILL_MAX_AGE_MS)
        assertEquals(0, spill().sweepStale())
        assertTrue(edge.exists())
    }

    @Test
    fun `staging sweeps too, because the helper is never restarted`() {
        // There is no idle shutdown by design, so a start-up-only sweep would
        // run once and then not again for days. Every write is the only moment
        // this process is reliably awake. The name here is a per-call one: the
        // files an earlier build left in /data/local/tmp are precisely what
        // still needs collecting, and nothing else will ever remove them.
        val abandoned = File(
            temp.root,
            "${PrivilegedContract.SPILL_PREFIX}abandoned${PrivilegedContract.SPILL_SUFFIX}",
        )
        abandoned.writeText("a reply nobody ever collected")
        abandoned.setLastModified(now - PrivilegedContract.SPILL_MAX_AGE_MS - 1L)

        val fresh = spill().stage("a new reply")!!
        assertFalse(abandoned.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `sweeping an empty or missing directory is not an error`() {
        assertEquals(0, spill().sweepStale())
        assertEquals(0, spill(File(temp.root, "absent")).sweepStale())
    }
}
