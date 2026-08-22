package dev.dankyeeter.btdashboard.privileged

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Names shared by the helper and the app. Both sides load this same class. */
object PrivilegedContract {
    const val AUTHORITY: String = "dev.dankyeeter.btdashboard.privileged"
    const val METHOD_SEND_BINDER: String = "sendBinder"
    const val EXTRA_BINDER: String = "binder"
    const val EXTRA_TOKEN: String = "token"
    const val EXTRA_VERSION: String = "version"
    const val EXTRA_ACCEPTED: String = "accepted"

    /**
     * A binder the *app* owns, returned in the reply so the helper can watch
     * the app process for death.
     *
     * The hand-over is one-directional and happens once, at helper start. That
     * left a hole big enough to walk through: Android reclaims a backgrounded
     * app routinely, and a restarted app has no way to reach a helper it has
     * no binder for — so a perfectly healthy helper became unusable until the
     * user re-ran the ADB command. This is how the helper learns the app is
     * gone and can offer itself again when it comes back.
     */
    const val EXTRA_APP_TOKEN: String = "appToken"

    /** Android's shell user. The only uid allowed to hand this app a binder. */
    const val SHELL_UID: Int = 2000

    /**
     * The helper's `--nice-name`. One constant because two parties depend on
     * the exact string: the ADB command that starts a helper, and the reaper
     * that a starting helper runs to kill its predecessors. If those two ever
     * disagreed, stale helpers would silently survive again.
     */
    const val HELPER_PROCESS_NAME: String = "btdash_privileged"

    /**
     * The app this helper serves.
     *
     * Only a fallback: the helper normally learns the uid to trust by resolving
     * [AUTHORITY], which cannot drift out of step with the manifest the way a
     * second hardcoded string can. `PrivilegedTokenTest` asserts the two stay
     * consistent so a package rename cannot leave this one behind.
     */
    const val APP_PACKAGE: String = "dev.dankyeeter.btdashboard"
}

/**
 * Holds the live connection to the privileged helper.
 *
 * Process-wide and observable, because "is the helper there" is a question the
 * UI asks and the answer changes without anyone asking: the helper dies on
 * reboot, and a death notification is the only warning.
 */
object PrivilegedConnection {

    private val _service = MutableStateFlow<IPrivilegedService?>(null)

    /** The helper, or null when none has connected or the last one died. */
    val service: StateFlow<IPrivilegedService?> = _service.asStateFlow()

    /**
     * A binder whose lifetime is this app process, handed to the helper so it
     * can watch for the process dying.
     *
     * Deliberately a bare [Binder] with no interface: it carries no operations
     * and must never grow any. Its whole job is to stop existing.
     */
    val livenessToken: IBinder = Binder()

    val isConnected: Boolean get() = _service.value != null

    /**
     * The binder currently being watched, and the recipient watching it.
     *
     * Both are kept because a replacement has to undo the previous watch. Every
     * [accept] used to link a fresh recipient and never unlink any, which cost
     * more than a slot in a table: retiring the outgoing helper kills that
     * process, its binder dies, and *its* recipient then ran and set the
     * connection to null — wiping out the newer helper that had just been
     * accepted a few lines earlier. The app showed "not running" with a healthy
     * helper on the other end of a live binder.
     */
    private var watched: IBinder? = null
    private var watcher: IBinder.DeathRecipient? = null

    /**
     * Takes a binder handed over by the helper and watches it for death.
     *
     * The death recipient matters more than it looks: without it the app would
     * keep a stale proxy after a reboot and every command would fail with a
     * transaction error rather than an honest "the helper is not running".
     *
     * @return the helper this one replaces, if any, so the caller can retire it.
     */
    @Synchronized
    fun accept(binder: IBinder): IPrivilegedService? {
        val previous = _service.value

        // Stop watching the helper being replaced before anything else. It is
        // about to be shut down on purpose, and its death is not news.
        // runCatching: unlinkToDeath throws NoSuchElementException when the
        // binder has already died and the link is gone with it.
        watched?.let { old ->
            watcher?.let { runCatching { old.unlinkToDeath(it, 0) } }
        }
        watched = null
        watcher = null

        val proxy = IPrivilegedService.Stub.asInterface(binder)
        val recipient = IBinder.DeathRecipient {
            // Guarded by identity as well as unlinked above: unlinkToDeath can
            // fail for a binder that is already dead, and a recipient that
            // fires late must never clear a connection it is not part of.
            synchronized(this) {
                if (watched !== binder) return@DeathRecipient
                Log.i(TAG, "privileged helper died")
                watched = null
                watcher = null
                _service.value = null
            }
        }
        runCatching {
            binder.linkToDeath(recipient, 0)
            watched = binder
            watcher = recipient
        }.onFailure { Log.w(TAG, "cannot watch the helper for death", it) }
        _service.value = proxy
        Log.i(TAG, "privileged helper connected")
        return previous
    }

    @Synchronized
    fun forget() {
        watched?.let { old -> watcher?.let { runCatching { old.unlinkToDeath(it, 0) } } }
        watched = null
        watcher = null
        _service.value = null
    }

    private const val TAG = "PrivilegedConnection"
}

/**
 * How the helper reaches the app.
 *
 * A ContentProvider rather than a socket, because a socket cannot work: see
 * [PrivilegedServer] for the SELinux denial that was measured on the device. A
 * provider call goes through `system_server`, which is allowed.
 *
 * Exported, because the caller is a different uid. Two independent checks stand
 * in for the missing filesystem permission:
 *
 *  1. the caller must be the shell uid — no ordinary app can forge that, the
 *     kernel supplies it; and
 *  2. it must present a token the app generated, which only reached the helper
 *     because the user pasted it into an ADB command.
 *
 * Either alone would be weak. The uid check alone would trust anything running
 * as shell, and the token alone would trust anyone who guessed it.
 *
 * ## What an exported component must never do
 *
 * Crash. Everything below `call` is inert rather than throwing, and the extras
 * are read inside a `runCatching`: a `Bundle` from another process unparcels
 * lazily, so the first `getString` on one carrying a class this process cannot
 * load throws `BadParcelableException` — from a caller that has not been
 * authenticated yet. That was reachable before and is now a refusal.
 */
class PrivilegedProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != PrivilegedContract.METHOD_SEND_BINDER) return null
        val context = context ?: return refuse("no context")

        val callingUid = Binder.getCallingUid()
        if (callingUid != PrivilegedContract.SHELL_UID) {
            return refuse("hand-over from uid $callingUid, expected the shell uid")
        }

        val handOver = readHandOver(extras)
            ?: return refuse("the hand-over payload could not be read")

        val bootstrap = PrivilegedBootstrap(context)
        val match = bootstrap.match(handOver.token)
            ?: return refuse("hand-over with a token this app did not issue")

        if (handOver.version != PrivilegedServer.VERSION) {
            // A helper from an older APK is still running. Better to refuse it
            // than to answer commands through an interface that has moved —
            // and refusing *before* promoting means a stale helper can never
            // rotate the token out from under a working one.
            return refuse(
                "helper is version ${handOver.version}, this app expects ${PrivilegedServer.VERSION}",
            )
        }

        val binder = handOver.binder ?: return refuse("hand-over without a binder")

        // Ordering matters, and this is the order:
        //
        //  1. persist the promotion, so that if anything below fails the app
        //     still knows which token its next call must carry;
        //  2. accept the binder, which is what makes the helper reachable;
        //  3. only then retire the helper this one replaces.
        //
        // Doing (3) first would kill a working helper on the strength of a
        // hand-over that might still be refused, and doing (1) last would leave
        // the app holding a token no live helper answers to.
        val outgoingToken = when (match) {
            // A fresh command: the token in use changes, and the helper being
            // replaced is still answering to the one it displaces.
            is PrivilegedBootstrap.TokenMatch.Pending -> bootstrap.promote(match.token)

            // A re-run of the command already in use. Nothing rotates, but a
            // second helper has just started, and the one it replaces answers
            // to that same token - so it can still be told to stop.
            is PrivilegedBootstrap.TokenMatch.Active -> match.token
        }
        val previous = PrivilegedConnection.accept(binder)
        retire(previous, outgoingToken)

        return Bundle().apply {
            putBoolean(PrivilegedContract.EXTRA_ACCEPTED, true)
            // Lives exactly as long as this process. The helper links to death
            // on it, which is the only way it can notice that the app it is
            // serving no longer exists.
            putBinder(PrivilegedContract.EXTRA_APP_TOKEN, PrivilegedConnection.livenessToken)
        }
    }

    /**
     * Stops the helper that has just been replaced.
     *
     * Best effort by design: the old helper is a process the app cannot see and
     * has just stopped being able to authenticate to, so a failure here means
     * one idle process lingers until the next reboot — not a broken app. It is
     * attempted at all because the alternative is leaving a process running
     * with shell privileges that nothing will ever talk to again.
     */
    private fun retire(previous: IPrivilegedService?, tokenItAnswersTo: String?) {
        if (previous == null || tokenItAnswersTo == null) return
        runCatching { previous.shutdown(tokenItAnswersTo) }
            .onFailure { Log.i(TAG, "the replaced helper did not acknowledge shutdown") }
    }

    private data class HandOver(val token: String?, val version: Int, val binder: IBinder?)

    /**
     * Reads the payload, or null if it cannot be read at all.
     *
     * The class loader is set first so that a Bundle written by the helper —
     * which loads these same classes out of this same APK — unparcels against
     * the classes this process actually has, rather than against whatever the
     * system's default loader happens to offer.
     */
    private fun readHandOver(extras: Bundle?): HandOver? = runCatching {
        if (extras == null) return null
        extras.classLoader = PrivilegedProvider::class.java.classLoader
        HandOver(
            token = extras.getString(PrivilegedContract.EXTRA_TOKEN),
            version = extras.getInt(PrivilegedContract.EXTRA_VERSION, -1),
            binder = extras.getBinder(PrivilegedContract.EXTRA_BINDER),
        )
    }.onFailure { Log.w(TAG, "unreadable hand-over payload", it) }.getOrNull()

    private fun refuse(reason: String): Bundle {
        // Logged, never silent: a refused hand-over is indistinguishable from a
        // helper that failed to start unless one of them says something.
        Log.w(TAG, "refused: $reason")
        return Bundle().apply { putBoolean(PrivilegedContract.EXTRA_ACCEPTED, false) }
    }

    // The provider exists only for `call`. Everything else is deliberately
    // inert rather than throwing: an exported component should not be a way to
    // crash the app.

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "PrivilegedProvider"
    }
}
