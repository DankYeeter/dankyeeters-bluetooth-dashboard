package dev.dankyeeter.btdashboard.privileged

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What "Stop helper and clean up" may claim (ARCHITECTURE.md AD-011): only what
 * the app observed, and never that the directory holds nothing of ours.
 */
class HelperCleanupTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `a missing file reads as gone and an existing one as present`() {
        val log = File(temp.root, "btdash_helper.log").apply { writeText("x") }
        assertEquals(FilePresence.PRESENT, HelperCleanup.presence(log.path))
        log.delete()
        assertEquals(FilePresence.GONE, HelperCleanup.presence(log.path))
    }

    @Test
    fun `the known names are the log and version 5's one staged reply`() {
        assertEquals(
            listOf("/data/local/tmp/btdash_helper.log", "/data/local/tmp/btdash_exec_current.out"),
            HelperCleanup.KNOWN_PATHS,
        )
    }

    @Test
    fun `no helper means nothing was removed, and the check still runs`() {
        val text = HelperCleanupReport(null, mapOf("/d/btdash_helper.log" to FilePresence.PRESENT)).describe()
        assertTrue(text, text.startsWith("No helper was running"))
        assertTrue(text, "Still there: btdash_helper.log" in text)
    }

    @Test
    fun `gone is claimed only for the known files, never for the directory`() {
        val text = HelperCleanupReport(true, mapOf("/d/a" to FilePresence.GONE)).describe()
        assertTrue(text, "known files are gone" in text)
        assertTrue(text, "cannot be checked" in text)
        assertFalse(text, "nothing" in text.lowercase())
    }

    @Test
    fun `an unreadable name is reported as unchecked, not as gone`() {
        val text = HelperCleanupReport(true, mapOf("/d/a" to FilePresence.UNKNOWN)).describe()
        assertFalse(text, "are gone" in text)
        assertTrue(text, "Could not check: a" in text)
    }
}
