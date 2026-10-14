package dev.dankyeeter.btdashboard.privileged

import android.os.DeadObjectException
import android.os.TransactionTooLargeException
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

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
    private val callTimeoutMs: Long = HELPER_CALL_TIMEOUT_MS,
    private val execTimeoutMs: Long = EXEC_CALL_TIMEOUT_MS,
) {

    /** What the caller should do and what it should say. */
    data class Verdict(val forgotten: Boolean, val reason: String)

    /** How a [bounded] call ended. */
    sealed interface Bounded<out T> {
        data class Done<T>(val value: T) : Bounded<T>

        enum class Unanswered : Bounded<Nothing> {
            /** An earlier call is still unanswered; this one never reached the helper. */
            BUSY,

            /** This call is unanswered and has been left running. */
            TIMED_OUT,
        }
    }

    @Volatile
    private var consecutiveFailures: Int = 0

    /** The one codec or HD-audio call left running after its caller stopped waiting, if any. */
    private val callSlot = AtomicReference<Deferred<*>?>(null)

    /** The one exec left running after its caller stopped waiting, if any (AD-053). */
    private val execSlot = AtomicReference<Deferred<*>?>(null)

    /**
     * Runs a blocking codec or HD-audio call, but waits for it at most [callTimeoutMs] (AD-048).
     *
     * WHY it is left running rather than cancelled: a Binder transaction cannot
     * be interrupted, so a cancelled coroutine would still wait for it — and the
     * caller may be holding the settings ledger's lock. WHY one slot: a call left
     * running may still write when the helper wakes up, so nothing else is sent
     * until it has ended; otherwise that late write could land after a way back.
     *
     * Exceptions thrown by [block] are rethrown to the caller unchanged. A time-out
     * deliberately does not count as a failure: the liveness ping would be one
     * more call into the same unanswering helper.
     */
    suspend fun <T> bounded(block: () -> T): Bounded<T> = bounded(callSlot, callTimeoutMs, block)

    /**
     * [bounded] for `exec`, with its own slot and [execTimeoutMs] (AD-053).
     *
     * WHY its own slot: a hanging exec must not lock out the codec calls, nor a
     * hanging codec call the monitor's reads. WHY one slot all the same: a
     * second dumpsys while an abandoned one still runs is extra load in the
     * Bluetooth process, which AK-1 forbids (AD-010). [block] should therefore
     * include opening, reading back and closing the reply descriptor, so a late
     * answer still lands in an open descriptor and releases it before the slot
     * frees.
     */
    suspend fun <T> boundedExec(block: () -> T): Bounded<T> = bounded(execSlot, execTimeoutMs, block)

    private suspend fun <T> bounded(slot: AtomicReference<Deferred<*>?>, timeoutMs: Long, block: () -> T): Bounded<T> {
        val call = CALLS.async(start = CoroutineStart.LAZY) { block() }
        // isCompleted, not isActive: a call claimed but not yet started is not
        // active either, and must still count as occupying the slot.
        val free = slot.get()?.takeIf { it.isCompleted }
        if (!slot.compareAndSet(free, call)) {
            call.cancel()
            return Bounded.Unanswered.BUSY
        }
        call.start()
        return withTimeoutOrNull(timeoutMs) { Bounded.Done(call.await()) }
            ?: Bounded.Unanswered.TIMED_OUT.also {
                Log.w(TAG, "a helper call did not answer within $timeoutMs ms and is left running")
            }
    }

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
        // The class only (V12): the message can quote the address the call was for.
        Log.w(TAG, "$what failed, classified $failure (${error.javaClass.simpleName})")

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
        // Waited for at most callTimeoutMs, off the caller's thread: the caller
        // may hold the settings ledger's lock, and a wedged helper would keep it
        // there. A ping that does not answer counts as a helper that is gone.
        // Outside bounded()'s slot, because `version()` writes nothing that
        // could land late.
        val alive = runCatching {
            CompletableFuture.runAsync(ping, Dispatchers.IO.asExecutor())
                .get(callTimeoutMs, TimeUnit.MILLISECONDS)
        }.isSuccess
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

        /**
         * How long a caller waits for a codec or HD-audio call (AD-048).
         *
         * Above the helper's own budget for such a call, read from the code and
         * not measured: `HelperBluetooth.BIND_TIMEOUT_SECONDS` (8 s) plus
         * `SETTLE_BUDGET_MS` (2.5 s). Equal to the helper's own exec time-out,
         * `PrivilegedServer.TIMEOUT_SECONDS`. Anything shorter would report a slow
         * but healthy helper as "unknown".
         */
        const val HELPER_CALL_TIMEOUT_MS: Long = 15_000

        /**
         * How long a caller waits for an exec (AD-053).
         *
         * The helper's own exec time-out, `PrivilegedServer.TIMEOUT_SECONDS`
         * (15 s), plus 5 s for writing a reply of up to 444 KB and the
         * transaction — derived, not measured. So a healthy helper with a slow
         * child always answers first, with its own and more precise sentence.
         */
        const val EXEC_CALL_TIMEOUT_MS: Long = 20_000

        private const val TAG = "PrivilegedCallGuard"

        /** Where calls run, so a call left running outlives the caller that gave up on it. */
        private val CALLS = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /** The one every caller in the app uses; see the class documentation. */
        val SHARED: PrivilegedCallGuard = PrivilegedCallGuard()
    }
}
