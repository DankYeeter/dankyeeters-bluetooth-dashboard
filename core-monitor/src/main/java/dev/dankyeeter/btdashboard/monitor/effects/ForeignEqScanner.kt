package dev.dankyeeter.btdashboard.monitor.effects

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import dev.dankyeeter.btdashboard.monitor.shell.ShellRunner

/** Result of one scan, including why it could not run. */
data class ForeignEqScanResult(
    val warnings: List<ForeignEqWarning> = emptyList(),
    val available: Boolean = true,
    val unavailableReason: String? = null,
) {
    val hasForeignEq: Boolean get() = warnings.isNotEmpty()
}

/**
 * Runs `dumpsys media.audio_flinger`, parses the effect chains and attributes
 * them to apps. Needs Shizuku's shell identity: a normal app may not dump
 * audio_flinger. Without it the dashboard shows "cannot check" rather than a
 * false all-clear.
 */
class ForeignEqScanner(
    private val shell: ShellRunner,
    private val processResolver: ProcessResolver,
    private val ownPid: Int = Process.myPid(),
) {

    suspend fun scan(): ForeignEqScanResult {
        if (!shell.isAvailable) {
            return ForeignEqScanResult(
                available = false,
                unavailableReason = "Shizuku is not ready — other equalizers cannot be detected",
            )
        }
        val result = shell.run(listOf("dumpsys", "media.audio_flinger"))
        if (result.stdout.isBlank()) {
            return ForeignEqScanResult(
                available = false,
                unavailableReason = "audio_flinger dump was empty " +
                    "(${result.stderr.ifBlank { "exit ${result.exitCode}" }})",
            )
        }
        val snapshot = AudioFlingerEffectParser.parse(result.stdout)
        val pidMap = runCatching { processResolver.pidToPackage() }.getOrDefault(emptyMap())
        return ForeignEqScanResult(
            warnings = ForeignEqDetector.detect(snapshot, ownPid, pidMap),
        )
    }
}

/**
 * PID → package. `ActivityManager.getRunningAppProcesses()` only returns our
 * own process since Android 5, so the shell `ps` is the real source; the
 * ActivityManager call is kept as a zero-cost fallback for our own PID.
 */
class ShellProcessResolver(
    private val context: Context,
    private val shell: ShellRunner,
) : ProcessResolver {

    override suspend fun pidToPackage(): Map<Int, String> {
        val fromShell = if (shell.isAvailable) {
            val result = shell.run(listOf("ps", "-A", "-o", "PID,NAME"))
            PsOutputParser.parse(result.stdout)
        } else {
            emptyMap()
        }
        if (fromShell.isNotEmpty()) return fromShell

        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses.orEmpty().associate { it.pid to it.processName }
        }.getOrDefault(emptyMap())
    }
}
