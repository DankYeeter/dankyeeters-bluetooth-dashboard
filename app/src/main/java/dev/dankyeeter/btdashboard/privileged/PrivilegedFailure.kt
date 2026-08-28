package dev.dankyeeter.btdashboard.privileged

import android.os.DeadObjectException
import android.os.TransactionTooLargeException
import android.util.Log
import java.util.concurrent.TimeoutException

/**
 * What went wrong with a call into the helper, as a decision rather than a
 * stack trace.
 *
 * ## The bug this exists to end
 *
 * Every privileged call used to be wrapped in `runCatching { … }.getOrElse {
 * PrivilegedConnection.forget(); … }`. That rule reads as "a throwing call means
 * a dead helper", and it is wrong: a Binder transaction can fail while both
 * processes are perfectly healthy. It did, on the owner's device, whenever two
 * of the monitor's readers pulled dumps at once —
 *
 *     W ActivityManager: pid <app> sent binder code 2 with flags 2
 *       and got error -2147483646
 *
 * — and `forget()` then cleared a connection to a helper that was still serving.
 * The app decided it had no helper, and the activation gate came up asking the
 * user to plug in a cable and re-run an ADB command for a helper that was
 * answering the whole time.
 *
 * So the two are separated. Only [HELPER_DIED] is death.
 */
internal enum class PrivilegedFailure {

    /**
     * The binder is gone. This is the one case where forgetting the connection
     * is the correct, honest thing to do — the proxy will never answer again,
     * and the death recipient in [PrivilegedConnection] is about to say so too.
     */
    HELPER_DIED,

    /**
     * The transport could not carry this reply *now*.
     *
     * `TransactionTooLargeException`, or the platform's own
     * "Transaction failed on small parcel; remote process probably died, but
     * will retry" — note that the platform itself says *retry*, and it is right:
     * the buffer is shared per process pair, so the same call succeeds once the
     * other replies have drained.
     */
    TRANSPORT_OVERLOADED,

    /** The helper did not answer in time. Nothing about that says it is gone. */
    TIMED_OUT,

    /**
     * Something else. Deliberately treated as transient.
     *
     * Failing towards "keep the connection" is the safe direction here and the
     * unsafe direction is the one that was in place: a helper that really has
     * died throws [DeadObjectException] *and* fires the death recipient, so a
     * stale connection cannot survive an actual death — while a live helper
     * misclassified as dead sends the user looking for a cable.
     */
    UNKNOWN,
    ;

    /** Only true death forgets the connection. */
    val isDeath: Boolean get() = this == HELPER_DIED

    companion object {

        /**
         * Classifies a throwable from a call into the helper.
         *
         * The whole cause chain is walked: these come back through AIDL proxies
         * and, on the codec path, through reflection, so the interesting
         * exception is routinely wrapped in an `InvocationTargetException`.
         */
        fun of(error: Throwable): PrivilegedFailure {
            var current: Throwable? = error
            val seen = mutableSetOf<Throwable>()
            while (current != null && seen.add(current)) {
                when {
                    current is DeadObjectException -> return HELPER_DIED
                    current is TransactionTooLargeException -> return TRANSPORT_OVERLOADED
                    current is TimeoutException -> return TIMED_OUT
                    current.looksLikeFailedTransaction() -> return TRANSPORT_OVERLOADED
                }
                current = current.cause
            }
            return UNKNOWN
        }

        /**
         * A failed transaction that the platform reported as a plain
         * `RuntimeException`.
         *
         * `Binder`'s native side turns FAILED_TRANSACTION into
         * `TransactionTooLargeException` only when it can see a large parcel;
         * otherwise it throws a `RuntimeException` carrying the text below, and
         * on some builds the raw status code. Matching on the message is
         * unlovely and is still the only thing available — the alternative is
         * classifying the exact failure this class exists for as UNKNOWN.
         */
        private fun Throwable.looksLikeFailedTransaction(): Boolean {
            val message = message ?: return false
            return message.contains("Transaction failed", ignoreCase = true) ||
                message.contains("FAILED_TRANSACTION") ||
                message.contains(FAILED_TRANSACTION_CODE)
        }

        /** The status the ActivityManager warning carried, as it is printed. */
        private const val FAILED_TRANSACTION_CODE = "-2147483646"
    }
}

/**
 * Decides, per failed call, whether the app still has a helper.
 *
 * One instance is shared by every caller ([SHARED]) because they all talk to the
 * same binder: the shell runner, the codec controller, the HD-audio controller
 * and the Bluetooth restart. A per-caller counter would need five failures on
 * each path before any of them checked, which is five times as long to notice a
 * helper that has genuinely stopped answering without dying.
 *
 * [forget] and the ping are injected so the rule can be tested without a device.
 */
internal class PrivilegedCallGuard(
    private val threshold: Int = FAILURES_BEFORE_PING,
    private val forget: () -> Unit = PrivilegedConnection::forget,
) {

    /** What the caller should do and what it should say. */
    data class Verdict(val forgotten: Boolean, val reason: String)

    @Volatile
    private var consecutiveFailures: Int = 0

    /** Called after any call that came back, successful or not. */
    fun succeeded() {
        consecutiveFailures = 0
    }

    /**
     * Classifies [error], forgets the connection only if it should, and words
     * the failure for the caller.
     *
     * @param what the operation, for the sentence the user may end up reading.
     * @param ping a tiny call into the helper — `version()`, which is unguarded,
     *   returns a compiled-in integer and carries no payload worth mentioning.
     *   Invoked only when [threshold] consecutive calls have failed without any
     *   of them proving death; it throws if the helper is really gone.
     */
    fun failed(what: String, error: Throwable, ping: () -> Unit): Verdict {
        val failure = PrivilegedFailure.of(error)
        Log.w(TAG, "$what failed, classified $failure", error)

        if (failure.isDeath) {
            consecutiveFailures = 0
            forget()
            return Verdict(forgotten = true, reason = "the privileged helper is no longer running")
        }

        consecutiveFailures += 1
        val transient = when (failure) {
            PrivilegedFailure.TRANSPORT_OVERLOADED ->
                "the reply was too large for the link to the privileged helper — retrying shortly"

            PrivilegedFailure.TIMED_OUT ->
                "the privileged helper did not answer in time — retrying shortly"

            else -> "$what did not go through (${error.message ?: error.javaClass.simpleName}) — " +
                "retrying shortly"
        }

        if (consecutiveFailures < threshold) {
            return Verdict(forgotten = false, reason = transient)
        }

        // WHY a ping rather than simply giving up here: a run of transient
        // failures is what a busy transport looks like *and* what a helper wedged
        // short of death looks like, and the two want opposite reactions. The
        // cheapest call on the interface settles it with certainty instead of a
        // heuristic.
        val run = consecutiveFailures
        val alive = runCatching { ping() }.isSuccess
        consecutiveFailures = 0
        if (alive) {
            Log.i(TAG, "$run failures in a row, but the helper answered a ping")
            return Verdict(forgotten = false, reason = transient)
        }
        forget()
        return Verdict(
            forgotten = true,
            reason = "the privileged helper stopped answering and did not respond to a check",
        )
    }

    companion object {

        /**
         * How many consecutive transient failures before the helper is asked
         * whether it is still there.
         *
         * The monitor polls, so five failures is a few seconds — short enough
         * that a genuinely wedged helper is caught quickly, long enough that a
         * burst of contention on the Binder buffer (which is what this whole
         * change is about) never reaches the check.
         */
        const val FAILURES_BEFORE_PING: Int = 5

        private const val TAG = "PrivilegedCallGuard"

        /** The one every caller in the app uses; see the class documentation. */
        val SHARED: PrivilegedCallGuard = PrivilegedCallGuard()
    }
}
