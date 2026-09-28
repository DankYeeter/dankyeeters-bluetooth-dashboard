package dev.dankyeeter.btdashboard.ui.screens.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source scans for rules a unit test cannot see: who constructs and who calls.
 * They read literal code only, so an indirect call (a reference, reflection)
 * is out of their reach. The working directory is `app/`.
 */
class LedgerSourceGrepTest {

    private val mainSources: List<File> =
        File("..").listFiles().orEmpty()
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }

    private fun callsOf(name: String): List<Pair<File, Int>> = mainSources.flatMap { file ->
        val text = file.readText()
        Regex("""(?<!fun |class )\b$name\(""").findAll(text).map { file to it.range.last }.toList()
    }

    /** The argument list that opens at [openParen], parentheses balanced. */
    private fun argumentsAt(text: String, openParen: Int): String {
        var depth = 0
        for (i in openParen until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(openParen, i + 1)
            }
        }
        error("unbalanced call in source")
    }

    @Test
    fun `exactly one production applier, and it is handed the real ledger (V5)`() {
        val constructions = callsOf("DeviceProfileApplier")

        assertEquals(listOf("SystemGraph.kt"), constructions.map { it.first.name })
        val (file, paren) = constructions.single()
        assertTrue(Regex("""\bledger\s*=""").containsMatchIn(argumentsAt(file.readText(), paren)))
    }

    @Test
    fun `only the confirmed forget in the ViewModel discards the ledger (V11)`() {
        assertEquals(listOf("DeviceProfilesViewModel.kt"), callsOf("discardUnreadable").map { it.first.name })
        assertTrue(mainSources.none { "discardUnreadableLedger" in it.readText() && it.name !in UI_CALLERS })
    }

    /**
     * AK-T054-11 — no string literal a user can see in these two files names
     * root. KDoc and line comments are exempt by the criterion itself.
     */
    @Test
    fun `no visible text names root (AK-T054-11)`() {
        val files = listOf(
            File("src/main/java/dev/dankyeeter/btdashboard/ui/screens/devices/DeviceProfilesScreen.kt"),
            File("../core-system/src/main/java/dev/dankyeeter/btdashboard/system/devices/BluetoothSystemControls.kt"),
        )
        val literal = Regex(""""(?:[^"\\]|\\.)*"""")
        val root = Regex("""\broot(ed)?\b""", RegexOption.IGNORE_CASE)

        val offending = files.flatMap { file ->
            file.readLines()
                .filterNot { it.trimStart().let { line -> line.startsWith("*") || line.startsWith("/*") || line.startsWith("//") } }
                .flatMap { line -> literal.findAll(line).map { it.value }.toList() }
                .filter { root.containsMatchIn(it) }
        }

        assertTrue(offending.toString(), offending.isEmpty())
    }

    private companion object {
        val UI_CALLERS = setOf("DeviceProfilesViewModel.kt", "DeviceProfilesScreen.kt")
    }
}
