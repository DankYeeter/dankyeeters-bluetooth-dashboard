package dev.dankyeeter.btdashboard.privileged

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the helper deletes in `/data/local/tmp`, and what it must leave alone
 * (ARCHITECTURE.md AD-011). A temporary folder stands in for the directory.
 */
class HelperLeftoversTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private fun leftovers() = HelperLeftovers(directory = temp.root)

    private fun staged(name: String) = File(
        temp.root,
        PrivilegedContract.STAGED_REPLY_PREFIX + name + PrivilegedContract.STAGED_REPLY_SUFFIX,
    ).apply { writeText("dump") }

    @Test
    fun `every staged reply of an older helper goes, whatever its age`() {
        // Since version 6 nothing stages, so a fresh file of this shape is as
        // much a leftover as an old one: there is no age limit any more.
        staged("current")
        staged("a1b2c3d4")

        assertEquals(2, leftovers().removeStagedReplies())
        assertEquals(emptyList<String>(), temp.root.list().orEmpty().toList())
    }

    @Test
    fun `nothing outside the name shape is touched`() {
        // A privileged process that empties a directory on its own judgement
        // is a bigger fault than the one it would fix. The log, the measuring
        // tools' files and a bare prefix all stay.
        val log = File(temp.root, "btdash_helper.log").apply { writeText("log") }
        val foreign = File(temp.root, "btperf_run.txt").apply { writeText("x") }
        val bare = File(
            temp.root,
            PrivilegedContract.STAGED_REPLY_PREFIX + PrivilegedContract.STAGED_REPLY_SUFFIX,
        ).apply { writeText("x") }

        assertEquals(0, leftovers().removeStagedReplies())
        assertTrue(log.exists())
        assertTrue(foreign.exists())
        assertTrue(bare.exists())
    }

    @Test
    fun `a directory of that shape is not entered`() {
        val dir = File(
            temp.root,
            PrivilegedContract.STAGED_REPLY_PREFIX + "dir" + PrivilegedContract.STAGED_REPLY_SUFFIX,
        ).apply { mkdirs() }
        File(dir, "inner").writeText("x")

        assertEquals(0, leftovers().removeStagedReplies())
        assertTrue(File(dir, "inner").exists())
    }

    @Test
    fun `a missing directory is not an error`() {
        assertEquals(0, HelperLeftovers(directory = File(temp.root, "absent")).removeStagedReplies())
    }

    @Test
    fun `no privileged code grants access to others`() {
        // A15: the helper inherits umask 077 and makes nothing it creates
        // readable or writable for other uids. One `ownerOnly = false` grant
        // undoes that silently; the version-5 spill did exactly this.
        val source = File(System.getProperty("user.dir"), "src/main/java/dev/dankyeeter/btdashboard/privileged")
        assertTrue("source not found at ${source.path}", source.isDirectory)
        val grant = Regex("""set(Readable|Writable|Executable)\(\s*true\s*,\s*false\s*\)""")
        val offenders = source.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { grant.containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `nothing writes into the shared directory any more`() {
        // AD-011: the class that tidies has no write path, and no exec reply is
        // staged under a name. A reintroduced ExecSpill would show up here.
        val source = File(System.getProperty("user.dir"), "src/main/java/dev/dankyeeter/btdashboard/privileged")
        assertFalse(File(source, "ExecSpill.kt").exists())
        val leftovers = File(source, "HelperLeftovers.kt").readText()
        listOf("writeBytes", "writeText", "FileOutputStream", "createNewFile").forEach {
            assertFalse("HelperLeftovers must not write ($it)", leftovers.contains(it))
        }
    }
}
