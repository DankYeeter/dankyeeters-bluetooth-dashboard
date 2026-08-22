package dev.dankyeeter.btdashboard.monitor.shell

/** Result of a shell command. Non-zero exit is data, not an exception. */
data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String = "") {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Runs a shell command under a shell-uid identity. Every dumpsys-based
 * fallback goes through this one seam, so tests inject canned output.
 *
 * The only real implementation is the app's own privileged helper
 * (`PrivilegedShellRunner` in `:app`), installed via
 * `MonitorGraph.installShellRunner`. Shizuku used to be the second one and is
 * gone on purpose: one access route, owned by this project, and nothing else.
 */
interface ShellRunner {
    suspend fun run(command: List<String>): ShellResult
    val isAvailable: Boolean
}

/** Used whenever no shell identity is up — everything degrades, nothing throws. */
object UnavailableShellRunner : ShellRunner {
    override suspend fun run(command: List<String>) =
        ShellResult(exitCode = -1, stdout = "", stderr = "shell identity unavailable")
    override val isAvailable: Boolean get() = false
}
