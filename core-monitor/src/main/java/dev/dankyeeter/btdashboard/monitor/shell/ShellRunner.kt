package dev.dankyeeter.btdashboard.monitor.shell

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Result of a shell command. Non-zero exit is data, not an exception. */
data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String = "") {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Runs a shell command under Shizuku's shell identity. Every dumpsys-based
 * fallback goes through this one seam, so tests inject canned output.
 */
interface ShellRunner {
    suspend fun run(command: List<String>): ShellResult
    val isAvailable: Boolean
}

/** Used whenever Shizuku is not ready — everything degrades, nothing throws. */
object UnavailableShellRunner : ShellRunner {
    override suspend fun run(command: List<String>) =
        ShellResult(exitCode = -1, stdout = "", stderr = "shell identity unavailable")
    override val isAvailable: Boolean get() = false
}

/**
 * `Shizuku.newProcess` is hidden API inside the Shizuku library itself
 * (`@RestrictTo`) and its signature has moved between versions, so it is
 * called reflectively and every failure degrades to an unavailable shell.
 */
class ShizukuShellRunner : ShellRunner {

    override val isAvailable: Boolean
        get() = runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    override suspend fun run(command: List<String>): ShellResult = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext UnavailableShellRunner.run(command)
        runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }

            val process = method.invoke(null, command.toTypedArray(), null, null)
                ?: return@runCatching ShellResult(-1, "", "newProcess returned null")

            val stdout = readStream(process, "getInputStream")
            val stderr = readStream(process, "getErrorStream")
            val exit = runCatching {
                process.javaClass.getMethod("waitFor").invoke(process) as? Int
            }.getOrNull() ?: 0
            runCatching { process.javaClass.getMethod("destroy").invoke(process) }
            ShellResult(exit, stdout, stderr)
        }.getOrElse {
            Log.w(TAG, "shell command failed: ${command.firstOrNull()}", it)
            ShellResult(-1, "", it.message ?: it.javaClass.simpleName)
        }
    }

    private fun readStream(process: Any, getter: String): String = runCatching {
        val stream = process.javaClass.getMethod(getter).invoke(process) as? java.io.InputStream
        stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }.getOrDefault("")

    private companion object {
        const val TAG = "ShizukuShellRunner"
    }
}
