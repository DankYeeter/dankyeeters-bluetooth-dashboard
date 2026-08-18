package dev.dankyeeter.btdashboard.monitor.dumpsys

import dev.dankyeeter.btdashboard.monitor.shell.ShellRunner

/** Level-3 source: whatever we can scrape out of `dumpsys bluetooth_manager`. */
interface DumpsysLinkSource {
    suspend fun snapshot(): DumpsysSnapshot
    val isAvailable: Boolean
}

class ShellDumpsysLinkSource(private val shell: ShellRunner) : DumpsysLinkSource {

    override val isAvailable: Boolean get() = shell.isAvailable

    override suspend fun snapshot(): DumpsysSnapshot {
        if (!shell.isAvailable) {
            return DumpsysSnapshot(warnings = listOf("no shell identity — Shizuku not ready"))
        }
        val result = shell.run(listOf("dumpsys", "bluetooth_manager"))
        if (!result.isSuccess && result.stdout.isBlank()) {
            return DumpsysSnapshot(
                warnings = listOf("dumpsys failed: ${result.stderr.ifBlank { "exit ${result.exitCode}" }}"),
            )
        }
        // A non-zero exit with partial output still parses — dumpsys often
        // times out on one section while the rest is usable.
        return DumpsysBluetoothParser.parse(result.stdout)
    }
}

object UnavailableDumpsysLinkSource : DumpsysLinkSource {
    override suspend fun snapshot() =
        DumpsysSnapshot(warnings = listOf("dumpsys fallback disabled"))
    override val isAvailable: Boolean get() = false
}
