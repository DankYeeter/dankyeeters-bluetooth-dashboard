package dev.dankyeeter.btdashboard.privileged

import java.io.File
import java.util.UUID

/**
 * The file side of a large exec reply — both halves of it.
 *
 * ## Why a file at all
 *
 * See `PrivilegedProtocol.INLINE_LIMIT_BYTES`. In short: Binder gives a pair of
 * processes one shared 1 MB asynchronous buffer, a `dumpsys` reply is 115-222 KB
 * of UTF-16 in it, and two or three at once returned FAILED_TRANSACTION from a
 * helper that was still running. A file does not use that buffer.
 *
 * ## Why one class serves both processes
 *
 * The helper writes and the app reads, which is two behaviours — but they are
 * two halves of one agreement about names, permissions and lifetimes, and the
 * surest way to let those drift apart is to write them in two files. Both ends
 * load this class out of the same APK, exactly as they both load
 * [PrivilegedProtocol].
 *
 * [directory] and [clock] are parameters only so the whole agreement can be
 * exercised against a temporary directory in a unit test; nothing in the app
 * passes anything but the defaults.
 */
internal class ExecSpill(
    private val directory: File = File(PrivilegedContract.SPILL_DIRECTORY),
    private val clock: () -> Long = System::currentTimeMillis,
    private val unique: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {

    /**
     * Writes [payload] to a fresh file and returns it, or null if it could not
     * be staged.
     *
     * Null rather than an exception, and null rather than a fallback to the
     * inline reply: an inline fallback for a payload this size is precisely the
     * transaction that was failing, so a caller that cannot stage has to say so
     * instead of trying the thing that does not work.
     *
     * ## Permissions
     *
     * 0644. The helper runs as shell and the app does not, so the default
     * owner-only mode would produce a file the app can see and cannot open.
     * Write stays owner-only: the app has no reason to modify a reply, and the
     * directory is world-writable to nobody either way.
     *
     * ## Why a sweep happens here as well as at start-up
     *
     * The helper is deliberately immortal — there is no idle shutdown, by the
     * owner's decision — so a start-up-only sweep would run once and then never
     * again for days. Sweeping before each write bounds the directory in the
     * only process that is awake often enough to do it, and costs one listing of
     * a directory that normally holds two files.
     */
    fun stage(payload: String): File? = runCatching {
        sweepStale()
        if (!directory.isDirectory) return null
        val file = File(directory, PrivilegedContract.SPILL_PREFIX + unique() + PrivilegedContract.SPILL_SUFFIX)
        file.writeBytes(payload.toByteArray(Charsets.UTF_8))
        // ownerOnly = false is the whole point; see the permissions note above.
        file.setReadable(true, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
        file
    }.getOrNull()

    /**
     * Deletes staged files older than [maxAgeMs] and returns how many went.
     *
     * Only files matching the name shape are considered. The helper's own log
     * lives in this same directory and so may anything else a shell has left
     * there; a sweep that took the directory at its word would be a privileged
     * process deleting files it knows nothing about.
     */
    fun sweepStale(maxAgeMs: Long = PrivilegedContract.SPILL_MAX_AGE_MS): Int = runCatching {
        val cutoff = clock() - maxAgeMs
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && isSpillName(it.name) && it.lastModified() < cutoff }
            .count { runCatching { it.delete() }.getOrDefault(false) }
    }.getOrDefault(0)

    /**
     * Reads a staged reply and removes it.
     *
     * Failure carries a sentence rather than an exception because every caller
     * turns it into the `stderr` of a [dev.dankyeeter.btdashboard.monitor.shell.ShellResult],
     * which is what the screen ends up wording.
     *
     * ## Why the size is checked
     *
     * A short read and a short dump are the same bytes. The parsers downstream
     * accept a short dump quite happily — that is what a disconnected headphone
     * produces — so a truncated file would not fail, it would silently answer a
     * question wrongly. The announced length is the only thing that can tell the
     * two apart.
     *
     * ## Why the delete is best effort
     *
     * `/data/local/tmp` is owned by shell. The app can traverse it and read a
     * world-readable file in it, and on most builds it cannot unlink there —
     * removing a file needs write permission on the *directory*, which the app
     * does not have. So the delete is attempted (it succeeds where the platform
     * allows it, and costs one syscall where it does not) and [sweepStale] in
     * the helper is what actually guarantees the directory stays bounded.
     */
    fun collect(handoff: PrivilegedProtocol.ExecHandoff): Result<String> {
        if (!isMine(handoff.path)) {
            // The reply came from a helper this app authenticated, so this is
            // not a defence against an attacker so much as against a bug: a
            // client that opens whatever path a reply names has a wider reach
            // than the operation it is performing needs, and narrowing it is
            // free.
            return Result.failure(
                IllegalArgumentException(
                    "the helper named a staged reply outside ${directory.path}",
                ),
            )
        }
        val file = File(handoff.path)
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return Result.failure(
                IllegalStateException(
                    "the helper staged its reply in ${handoff.path} but the app cannot read it " +
                        "(${it.message ?: it.javaClass.simpleName})",
                ),
            )
        }
        runCatching { file.delete() }
        if (bytes.size != handoff.byteCount) {
            return Result.failure(
                IllegalStateException(
                    "the staged reply is ${bytes.size} bytes, the helper announced " +
                        "${handoff.byteCount} — refusing to parse a partial dump",
                ),
            )
        }
        return Result.success(String(bytes, Charsets.UTF_8))
    }

    /** Whether [path] is a staged reply in the directory this instance owns. */
    fun isMine(path: String): Boolean {
        val file = File(path)
        return file.parentFile?.path == directory.path && isSpillName(file.name)
    }

    private fun isSpillName(name: String): Boolean =
        name.startsWith(PrivilegedContract.SPILL_PREFIX) &&
            name.endsWith(PrivilegedContract.SPILL_SUFFIX) &&
            name.length > PrivilegedContract.SPILL_PREFIX.length + PrivilegedContract.SPILL_SUFFIX.length
}
