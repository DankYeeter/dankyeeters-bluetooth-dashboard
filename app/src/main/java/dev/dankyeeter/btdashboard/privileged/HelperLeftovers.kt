package dev.dankyeeter.btdashboard.privileged

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

/**
 * What the helper leaves in `/data/local/tmp`, and the only code that removes it.
 *
 * Deliberately has no way to write a file. Exec replies travel through a
 * descriptor the app owns (ARCHITECTURE.md AD-010), so nothing stages into this
 * directory any more; a class without a write path cannot start doing so again
 * by accident (AD-011).
 *
 * Only the helper calls this. The app cannot unlink here: removing a file needs
 * write permission on the directory, and that belongs to shell.
 *
 * [directory], [logPath] and [ownOutput] are parameters only so the rules can be
 * exercised against a temporary directory in a unit test.
 */
internal class HelperLeftovers(
    private val directory: File = File(PrivilegedContract.HELPER_DIRECTORY),
    private val logPath: String = PrivilegedContract.HELPER_LOG_PATH,
    private val ownOutput: FileDescriptor = FileDescriptor.out,
) {

    /**
     * Deletes every staged exec reply a helper of version 5 or earlier wrote,
     * and returns how many went.
     *
     * No age limit: since version 6 nothing stages, so every file of this shape
     * is a leftover. Only regular files of exactly this shape, never recursive —
     * a privileged process that empties a directory on its own judgement is a
     * bigger fault than the one it would be fixing.
     */
    fun removeStagedReplies(): Int = runCatching {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && isStagedReplyName(it.name) }
            .count { runCatching { it.delete() }.getOrDefault(false) }
    }.getOrDefault(0)

    /**
     * Makes this helper's own log owner-only, through the descriptor it is
     * writing to rather than through the path (AD-012).
     *
     * The start command already sets `umask 077` and removes an old log before
     * the redirect creates a fresh one. This covers the case it cannot: a start
     * line copied from an older build, where the redirect truncated a 0666 file
     * and kept its mode.
     */
    fun narrowOwnLog(): Boolean = runCatching {
        if (!isOwnLog()) return false
        Os.fchmod(ownOutput, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        true
    }.getOrDefault(false)

    /**
     * Deletes the log, but only if it is still the file this helper writes to.
     *
     * A helper never deletes a path a successor may already have opened: the
     * start command of the next helper creates the log by redirect before that
     * helper's code runs. Comparing device and inode of the own stdout with what
     * sits at [logPath] makes that rule a check instead of a hope (AD-011).
     */
    fun removeOwnLog(): Boolean = runCatching {
        isOwnLog() && File(logPath).delete()
    }.getOrDefault(false)

    private fun isOwnLog(): Boolean {
        val own = Os.fstat(ownOutput)
        val atPath = Os.stat(logPath)
        return own.st_dev == atPath.st_dev && own.st_ino == atPath.st_ino
    }

    private fun isStagedReplyName(name: String): Boolean =
        name.startsWith(PrivilegedContract.STAGED_REPLY_PREFIX) &&
            name.endsWith(PrivilegedContract.STAGED_REPLY_SUFFIX) &&
            name.length > PrivilegedContract.STAGED_REPLY_PREFIX.length +
            PrivilegedContract.STAGED_REPLY_SUFFIX.length
}
