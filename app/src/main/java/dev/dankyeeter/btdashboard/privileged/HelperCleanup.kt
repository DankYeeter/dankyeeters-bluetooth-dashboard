package dev.dankyeeter.btdashboard.privileged

import android.content.Context
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Stop helper and clean up": the user-triggered fallback of AD-011.
 *
 * Not the main path — the helper removes its own leftovers once the package is
 * gone — and never run automatically. It asks a live helper to clean up and
 * end through the existing `shutdown(token)`, then reports only what the app
 * observed afterwards: the binder dying, and the known file names answering
 * ENOENT. The app cannot list `/data/local/tmp` (0771), only open exact names,
 * so per-call names an older build used stay "cannot check".
 */
class HelperCleanup internal constructor(
    private val bootstrap: PrivilegedBootstrap,
    private val knownPaths: List<String> = KNOWN_PATHS,
) {

    constructor(context: Context) : this(PrivilegedBootstrap(context))

    suspend fun run(): HelperCleanupReport = withContext(Dispatchers.IO) {
        val service = PrivilegedConnection.service.value
        val token = bootstrap.activeToken()
        val stopped = if (service == null || token == null) {
            null
        } else {
            // void and self-killing: the call usually ends in DeadObjectException,
            // which is the expected outcome, not a failure. The death recipient
            // in PrivilegedConnection is what proves it.
            runCatching { service.shutdown(token) }
            withTimeoutOrNull(DEATH_TIMEOUT_MS) {
                PrivilegedConnection.service.first { it !== service }
            } != null
        }
        HelperCleanupReport(stopped, knownPaths.associateWith(::presence))
    }

    internal companion object {
        const val DEATH_TIMEOUT_MS = 5_000L

        /** The names the app can check: the log, and version 5's one staged-reply file. */
        val KNOWN_PATHS = listOf(
            PrivilegedContract.HELPER_LOG_PATH,
            PrivilegedContract.HELPER_DIRECTORY + "/" +
                PrivilegedContract.STAGED_REPLY_PREFIX + "current" + PrivilegedContract.STAGED_REPLY_SUFFIX,
        )

        fun presence(path: String): FilePresence = try {
            Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            FilePresence.PRESENT
        } catch (_: NoSuchFileException) {
            FilePresence.GONE
        } catch (_: Exception) {
            FilePresence.UNKNOWN
        }
    }
}

enum class FilePresence { GONE, PRESENT, UNKNOWN }

/**
 * What [HelperCleanup] saw.
 *
 * @property stopped null when no helper was connected (nothing could be
 *   removed), otherwise whether its binder was seen to die.
 */
data class HelperCleanupReport(val stopped: Boolean?, val files: Map<String, FilePresence>) {

    fun describe(): String = buildString {
        append(
            when (stopped) {
                null -> "No helper was running, so nothing could be removed."
                true -> "The helper has stopped."
                false -> "The helper did not stop."
            },
        )
        val present = files.filterValues { it == FilePresence.PRESENT }.keys
        val unknown = files.filterValues { it == FilePresence.UNKNOWN }.keys
        when {
            present.isNotEmpty() -> append(" Still there: ${present.joinToString { it.substringAfterLast('/') }}.")
            unknown.isEmpty() -> append(" Its known files are gone.")
        }
        if (unknown.isNotEmpty()) {
            append(" Could not check: ${unknown.joinToString { it.substringAfterLast('/') }}.")
        }
        append(" Files older app versions named per call cannot be checked from the app.")
    }
}
