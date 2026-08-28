package dev.dankyeeter.btdashboard.privileged

import android.app.Application
import android.content.Context
import android.os.DeadObjectException
import android.os.TransactionTooLargeException
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How the app behaves when the link to the helper misbehaves.
 *
 * ## What was measured, and what the app concluded from it
 *
 * On the owner's device, while the monitor's "Watch live" and "Watch closely"
 * readers were both pulling dumps:
 *
 * ```
 * W ActivityManager: pid <app> sent binder code 2 with flags 2
 *   and got error -2147483646
 * ```
 *
 * FAILED_TRANSACTION. The dumps measured 115-222 KB each, they travel as one
 * Binder String (UTF-16, so twice that), and Binder gives a pair of processes a
 * single 1 MB asynchronous buffer between them — two or three at once exhaust
 * it. The helper process was demonstrably still alive throughout.
 *
 * The app's rule at the time was `runCatching { … }.getOrElse { forget() }`, so
 * it concluded the helper was gone and put the activation screen — plug in a
 * cable, run an ADB command — in front of a user whose helper was answering.
 *
 * Three things are pinned here, and the third is the owner-visible one:
 *
 *  1. exec calls are serialised, so replies never overlap in that buffer;
 *  2. a reply too large to send arrives as a file and is read back whole;
 *  3. a transient transport failure keeps the connection, and only real death
 *     clears it.
 *
 * Robolectric because the exceptions being classified are `android.os` types and
 * the fake helper is a real local Binder — `Stub.asInterface` hands back the
 * stub itself for one, so the calls are direct and no transaction is involved.
 */
@RunWith(RobolectricTestRunner::class)
// A stock Application rather than BtDashboardApplication. The real one collects
// PrivilegedConnection.service and asks for WRITE_SECURE_SETTINGS the moment a
// helper appears, which on a host JVM throws inside its own coroutine — an
// uncaught exception that outlives this class and gets blamed on whichever
// runTest happens to start next. Nothing here needs the app's own graph; what is
// under test is one runner and one binder.
@Config(application = Application::class)
class PrivilegedTransportTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val dump = listOf("dumpsys", "bluetooth_manager")

    @Before
    fun seedToken() {
        // What PrivilegedBootstrap.activeToken() reads. A runner without one
        // refuses before it ever reaches the binder.
        context.getSharedPreferences("privileged", Context.MODE_PRIVATE)
            .edit()
            .putString("token", "a-token")
            .commit()
    }

    @After
    fun disconnect() {
        PrivilegedConnection.forget()
    }

    private fun runner(
        helper: FakeHelper,
        guard: PrivilegedCallGuard = PrivilegedCallGuard(),
    ): PrivilegedShellRunner {
        PrivilegedConnection.accept(helper)
        return PrivilegedShellRunner(
            bootstrap = PrivilegedBootstrap(context),
            guard = guard,
            spill = ExecSpill(directory = temp.root),
        )
    }

    // ---- serialisation ------------------------------------------------------

    @Test
    fun `two concurrent execs never overlap in the binder buffer`() = runBlocking {
        // The whole point of the Mutex. Both readers poll independently, so the
        // overlap is the normal case rather than a corner one, and overlapping
        // is what exhausts the shared 1 MB buffer.
        val helper = FakeHelper(delayMs = 40) { PrivilegedProtocol.encodeResult(0, "state: ON", "") }
        val runner = runner(helper)

        val results = listOf(
            async(Dispatchers.Default) { runner.run(dump) },
            async(Dispatchers.Default) { runner.run(dump) },
        ).awaitAll()

        assertEquals(2, helper.execCalls.get())
        assertEquals(
            "two exec calls were in the helper at once",
            1,
            helper.peakConcurrentExecs.get(),
        )
        assertTrue(results.all { it.isSuccess })
    }

    // ---- the file hand-over -------------------------------------------------

    @Test
    fun `a reply too large to send arrives as a file and is read back whole`() = runBlocking {
        val payload = "Bluetooth Status\n" + "x".repeat(220_000)
        val staging = ExecSpill(directory = temp.root)
        val helper = FakeHelper {
            PrivilegedProtocol.encodeExecResult(0, payload, "") { staging.stage(it)?.path }
        }

        val result = runner(helper).run(dump)

        assertEquals(0, result.exitCode)
        assertEquals(payload, result.stdout)
        // Collected means consumed: nothing is left behind in the staging
        // directory for the helper's sweep to have to deal with.
        assertEquals(
            emptyList<String>(),
            temp.root.listFiles().orEmpty().map { it.name },
        )
    }

    @Test
    fun `repeated large replies reuse one staging file`() = runBlocking {
        // The follow-up defect: a name per call put ~470 files into
        // /data/local/tmp inside a two-minute watch-live session. Nothing
        // needed them distinct — this lock means one reply is staged, handed
        // over and read per turn — so they are one file, overwritten.
        val payload = "Bluetooth Status\n" + "x".repeat(120_000)
        val staging = ExecSpill(directory = temp.root)
        val staged = mutableListOf<String>()
        val helper = FakeHelper {
            PrivilegedProtocol.encodeExecResult(0, payload, "") { body ->
                staging.stage(body)?.path?.also { staged += it }
            }
        }
        val runner = runner(helper)

        repeat(5) { assertEquals(payload, runner.run(dump).stdout) }

        assertEquals(5, staged.size)
        assertEquals("every reply must have gone to the same file", 1, staged.toSet().size)
        assertEquals(
            emptyList<String>(),
            temp.root.listFiles().orEmpty().map { it.name },
        )
    }

    @Test
    fun `a small reply still travels inline`() = runBlocking {
        val helper = FakeHelper { PrivilegedProtocol.encodeExecResult(0, "state: ON", "") { null } }
        val result = runner(helper).run(dump)
        assertEquals("state: ON", result.stdout)
        assertEquals(emptyList<String>(), temp.root.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `a staged file the app cannot read fails the call and keeps the helper`() = runBlocking {
        val helper = FakeHelper {
            PrivilegedProtocol.encodeFileResult(
                exitCode = 0,
                path = temp.root.path + "/" + PrivilegedContract.SPILL_PREFIX + "gone" +
                    PrivilegedContract.SPILL_SUFFIX,
                byteCount = 10,
                stderr = "",
            )
        }
        val result = runner(helper).run(dump)

        assertFalse(result.isSuccess)
        assertTrue(result.stderr.isNotBlank())
        // A file that could not be read says nothing at all about whether the
        // helper is alive, and it plainly is - it just answered.
        assertTrue(PrivilegedConnection.isConnected)
    }

    // ---- what clears the connection, and what does not ----------------------

    @Test
    fun `a dead binder is forgotten`() = runBlocking {
        val helper = FakeHelper { throw DeadObjectException() }
        val result = runner(helper).run(dump)

        assertFalse(result.isSuccess)
        assertFalse("a dead helper must be forgotten", PrivilegedConnection.isConnected)
    }

    @Test
    fun `a transaction too large keeps the helper and the next poll succeeds`() = runBlocking {
        // The owner-visible symptom. This is the case that used to raise the
        // activation gate over a perfectly healthy helper.
        var attempt = 0
        val helper = FakeHelper {
            attempt += 1
            if (attempt == 1) throw TransactionTooLargeException()
            PrivilegedProtocol.encodeResult(0, "state: ON", "")
        }
        val runner = runner(helper)

        val failed = runner.run(dump)
        assertFalse(failed.isSuccess)
        assertTrue(
            "a transient transport failure must not raise the activation gate",
            PrivilegedConnection.isConnected,
        )

        val retried = runner.run(dump)
        assertTrue(retried.isSuccess)
        assertEquals("state: ON", retried.stdout)
    }

    @Test
    fun `the platform's own failed-transaction message keeps the helper too`() = runBlocking {
        // Binder throws TransactionTooLargeException only when it can see a
        // large parcel; otherwise this, which says "will retry" in as many
        // words and was still being read as death.
        val helper = FakeHelper {
            throw RuntimeException(
                "Transaction failed on small parcel; remote process probably died, but will retry",
            )
        }
        val result = runner(helper).run(dump)

        assertFalse(result.isSuccess)
        assertTrue(PrivilegedConnection.isConnected)
    }

    // ---- classification -----------------------------------------------------

    @Test
    fun `only a dead object is classified as death`() {
        assertEquals(PrivilegedFailure.HELPER_DIED, PrivilegedFailure.of(DeadObjectException()))
        assertEquals(
            PrivilegedFailure.TRANSPORT_OVERLOADED,
            PrivilegedFailure.of(TransactionTooLargeException()),
        )
        assertEquals(PrivilegedFailure.TIMED_OUT, PrivilegedFailure.of(TimeoutException()))
        assertEquals(
            PrivilegedFailure.TRANSPORT_OVERLOADED,
            PrivilegedFailure.of(RuntimeException("... and got error -2147483646")),
        )
        assertEquals(PrivilegedFailure.UNKNOWN, PrivilegedFailure.of(IllegalStateException("odd")))
        listOf(
            PrivilegedFailure.TRANSPORT_OVERLOADED,
            PrivilegedFailure.TIMED_OUT,
            PrivilegedFailure.UNKNOWN,
        ).forEach { assertFalse("$it must not count as death", it.isDeath) }
    }

    @Test
    fun `a wrapped cause is classified by what is inside it`() {
        // The codec path goes through reflection, so the log line that started
        // all this - "getCodecStatus unavailable: InvocationTargetException" -
        // is a wrapper. Classifying the wrapper would defeat the whole exercise.
        assertEquals(
            PrivilegedFailure.HELPER_DIED,
            PrivilegedFailure.of(InvocationTargetException(DeadObjectException())),
        )
        assertEquals(
            PrivilegedFailure.TRANSPORT_OVERLOADED,
            PrivilegedFailure.of(RuntimeException("wrapper", TransactionTooLargeException())),
        )
    }

    @Test
    fun `a cause chain that loops terminates`() {
        // Not a hypothetical shape: an exception rethrown around a retry can
        // end up pointing back at itself, and a classifier that walked such a
        // chain would hang a Binder thread instead of answering a question.
        val outer = RuntimeException("outer")
        val inner = RuntimeException("inner", outer)
        outer.initCause(inner)
        assertEquals(PrivilegedFailure.UNKNOWN, PrivilegedFailure.of(outer))
    }

    // ---- the liveness ping --------------------------------------------------

    @Test
    fun `a run of transient failures ends in a ping, not in giving up`() {
        var forgotten = false
        var pings = 0
        val guard = PrivilegedCallGuard(threshold = 3, forget = { forgotten = true })

        repeat(2) { guard.failed("exec", TransactionTooLargeException()) { pings += 1 } }
        assertEquals("the helper must not be pinged before the threshold", 0, pings)
        assertFalse(forgotten)

        val verdict = guard.failed("exec", TransactionTooLargeException()) { pings += 1 }
        assertEquals(1, pings)
        assertFalse("a helper that answers the ping is still there", verdict.forgotten)
        assertFalse(forgotten)
    }

    @Test
    fun `a helper that fails the ping is forgotten`() {
        var forgotten = false
        val guard = PrivilegedCallGuard(threshold = 2, forget = { forgotten = true })

        guard.failed("exec", TransactionTooLargeException()) { }
        val verdict = guard.failed("exec", TransactionTooLargeException()) {
            throw DeadObjectException()
        }

        assertTrue(verdict.forgotten)
        assertTrue(forgotten)
        assertTrue(verdict.reason.isNotBlank())
    }

    @Test
    fun `one success ends the run`() {
        var pings = 0
        val guard = PrivilegedCallGuard(threshold = 2, forget = { })

        guard.failed("exec", TransactionTooLargeException()) { pings += 1 }
        guard.succeeded()
        guard.failed("exec", TransactionTooLargeException()) { pings += 1 }

        assertEquals("the counter must start again after a call that worked", 0, pings)
    }

    @Test
    fun `death is not counted towards the threshold, it is acted on at once`() {
        var forgotten = false
        val guard = PrivilegedCallGuard(threshold = 5, forget = { forgotten = true })
        val verdict = guard.failed("exec", DeadObjectException()) {
            throw AssertionError("a dead binder must not be pinged")
        }
        assertTrue(verdict.forgotten)
        assertTrue(forgotten)
    }

    @Test
    fun `the threshold is a named constant, not a number in a branch`() {
        assertEquals(5, PrivilegedCallGuard.FAILURES_BEFORE_PING)
    }
}

/**
 * A helper on the far end of a local Binder.
 *
 * Records how many exec calls were inside it at once, which is the only way to
 * see the property the Mutex exists for: the failure it prevents happens in the
 * kernel's buffer, not in anything the app can observe afterwards.
 */
private class FakeHelper(
    private val delayMs: Long = 0,
    private val reply: () -> String,
) : IPrivilegedService.Stub() {

    /**
     * Everything that is not [exec].
     *
     * A constant rather than [reply], because accepting a binder wakes the
     * session harvester and the codec controllers, and those calls would
     * otherwise run the test's own reply function - staging extra files and
     * advancing counters the test believes only its own calls touch.
     */
    private val notUnderTest: String = PrivilegedProtocol.encodeError("not part of this test")

    val execCalls = AtomicInteger()
    val peakConcurrentExecs = AtomicInteger()
    private val inFlight = AtomicInteger()

    override fun version(): Int = PrivilegedServer.VERSION

    override fun exec(token: String?, command: MutableList<String>?): String {
        execCalls.incrementAndGet()
        val depth = inFlight.incrementAndGet()
        peakConcurrentExecs.updateAndGet { maxOf(it, depth) }
        try {
            if (delayMs > 0) Thread.sleep(delayMs)
            return reply()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override fun codecStatus(token: String?, address: String?): String = notUnderTest

    override fun setCodecPreference(
        token: String?,
        address: String?,
        codecType: Int,
        sampleRateHz: Int,
        bitsPerSample: Int,
        channelMode: Int,
        ldacQuality: Long,
    ): String = notUnderTest

    override fun optionalCodecs(token: String?, address: String?): String = notUnderTest

    override fun setOptionalCodecs(token: String?, address: String?, preference: Int): String =
        notUnderTest

    override fun restartBluetooth(token: String?): String = notUnderTest

    override fun grantSecureSettings(token: String?): String = notUnderTest

    override fun shutdown(token: String?) = Unit
}
