package dev.dankyeeter.btdashboard.privileged

import android.app.Application
import android.content.Context
import android.os.TransactionTooLargeException
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import dev.dankyeeter.btdashboard.system.devices.HdAudioOutcome
import dev.dankyeeter.btdashboard.system.devices.HdAudioPreference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

private const val ADDRESS = "AC:DE:48:00:37:8F"

/** AOSP's LDAC `codecSpecific1` for 990 kbps. */
private val LDAC_990 = CodecPreference(codec = "LDAC", ldacQuality = 1000L)

/** Far above the test guard's time-out, far below "the suite hangs". */
private const val TEST_BOUND_MS = 5_000L

private fun context(): Context = ApplicationProvider.getApplicationContext()

private fun seedToken() {
    context().getSharedPreferences("privileged", Context.MODE_PRIVATE)
        .edit()
        .putString("token", "a-token")
        .commit()
}

/**
 * AD-048: a helper that never answers holds the ledger lock only until the
 * time-out, and nothing more reaches it while that call is unanswered.
 *
 * Every hanging call runs outside `runBlocking`'s children and is awaited through
 * `withTimeoutOrNull`: a Binder call cannot be cancelled, so without the fix a
 * direct call would hang the suite instead of failing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PrivilegedTimeoutTest {

    private val helper = UnansweringHelper()
    private val guard = PrivilegedCallGuard(callTimeoutMs = 200)
    private val ledgerLock = Mutex()
    private val outside = CoroutineScope(Dispatchers.IO)

    @Before
    fun connect() {
        seedToken()
        PrivilegedConnection.accept(helper)
    }

    @After
    fun releaseAndDisconnect() {
        helper.release.countDown()
        PrivilegedConnection.forget()
    }

    private fun <T> underLedgerLock(write: suspend () -> T): Deferred<T> =
        outside.async { ledgerLock.withLock { write() } }

    @Test
    fun `a codec write the helper never answers frees the ledger lock and blocks the next`() = runBlocking {
        val codec = PrivilegedCodecController(PrivilegedBootstrap(context()), guard)

        val first = withTimeoutOrNull(TEST_BOUND_MS) { underLedgerLock { codec.apply(ADDRESS, LDAC_990) }.await() }
        assertNotNull("the ledger lock was still held after $TEST_BOUND_MS ms", first)
        assertTrue("$first", (first as CodecApplyOutcome.Unavailable).reason.contains("did not answer"))
        assertFalse(ledgerLock.isLocked)

        val second = withTimeoutOrNull(TEST_BOUND_MS) { underLedgerLock { codec.apply(ADDRESS, LDAC_990) }.await() }
        assertTrue("$second", (second as CodecApplyOutcome.Unavailable).reason.contains("earlier request"))
        assertEquals("a second write reached a helper that has not answered the first", 1, helper.writes.get())

        helper.release.countDown()
        withTimeout(TEST_BOUND_MS) {
            while (codec.apply(ADDRESS, LDAC_990).isBusy()) delay(10)
        }
        assertEquals("once the first call ended, the next one must reach the helper", 2, helper.writes.get())
    }

    @Test
    fun `an HD-audio write the helper never answers frees the ledger lock`() = runBlocking {
        val hdAudio = PrivilegedHdAudioController(PrivilegedBootstrap(context()), guard)

        val outcome = withTimeoutOrNull(TEST_BOUND_MS) {
            underLedgerLock { hdAudio.apply(ADDRESS, HdAudioPreference.ENABLE) }.await()
        }
        assertNotNull("the ledger lock was still held after $TEST_BOUND_MS ms", outcome)
        assertTrue("$outcome", (outcome as HdAudioOutcome.Unavailable).reason.contains("did not answer"))
        assertFalse(ledgerLock.isLocked)
    }

    @Test
    fun `a liveness ping the helper never answers frees the ledger lock and forgets the helper`() = runBlocking {
        val wedged = UnansweringHelper(writeError = TransactionTooLargeException(), wedgedPing = true)
        PrivilegedConnection.accept(wedged)
        try {
            val codec = PrivilegedCodecController(PrivilegedBootstrap(context()), guard)
            repeat(PrivilegedCallGuard.FAILURES_BEFORE_PING - 1) {
                assertTrue(codec.apply(ADDRESS, LDAC_990) is CodecApplyOutcome.Unavailable)
            }

            val pinged = withTimeoutOrNull(TEST_BOUND_MS) { underLedgerLock { codec.apply(ADDRESS, LDAC_990) }.await() }
            assertNotNull("the ledger lock was still held after $TEST_BOUND_MS ms", pinged)
            assertFalse(ledgerLock.isLocked)
            assertFalse("a helper that does not answer the ping counts as gone", PrivilegedConnection.isConnected)
        } finally {
            wedged.release.countDown()
        }
    }

    private fun CodecApplyOutcome.isBusy(): Boolean =
        this is CodecApplyOutcome.Unavailable && reason.contains("earlier request")
}

/** B7/SR-030: a helper's error text can quote the raw address; the app's log must not. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PrivilegedCodecLogTest {

    @After
    fun disconnect() {
        PrivilegedConnection.forget()
    }

    @Test
    fun `an unreadable codec never puts the address in the log`() = runBlocking {
        seedToken()
        PrivilegedConnection.accept(
            UnansweringHelper(codecStatusReply = PrivilegedProtocol.encodeError("unknown device $ADDRESS")),
        )
        ShadowLog.clear()

        PrivilegedCodecController(context()).availableCodecs(ADDRESS)

        val logged = ShadowLog.getLogsForTag("PrivilegedCodec").joinToString("\n") { it.msg }
        assertTrue("the line ran: $logged", logged.contains("unknown device"))
        assertFalse(logged, logged.contains(ADDRESS))
    }
}

/**
 * Blocks every codec and HD-audio write until [release]; answers everything else at once.
 *
 * @param writeError thrown by every write instead of blocking.
 * @param wedgedPing blocks `version()` until [release] as well.
 */
private class UnansweringHelper(
    private val codecStatusReply: String = NOT_UNDER_TEST,
    private val writeError: Exception? = null,
    private val wedgedPing: Boolean = false,
) : IPrivilegedService.Stub() {

    val release = CountDownLatch(1)
    val writes = AtomicInteger()

    private fun hang(): String {
        writes.incrementAndGet()
        writeError?.let { throw it }
        release.await()
        return NOT_UNDER_TEST
    }

    override fun version(): Int {
        if (wedgedPing) release.await()
        return PrivilegedServer.VERSION
    }

    override fun exec(token: String?, command: MutableList<String>?): String = NOT_UNDER_TEST

    override fun codecStatus(token: String?, address: String?): String = codecStatusReply

    override fun setCodecPreference(
        token: String?,
        address: String?,
        codecType: Int,
        sampleRateHz: Int,
        bitsPerSample: Int,
        channelMode: Int,
        ldacQuality: Long,
    ): String = hang()

    override fun optionalCodecs(token: String?, address: String?): String = NOT_UNDER_TEST

    override fun setOptionalCodecs(token: String?, address: String?, preference: Int): String = hang()

    override fun restartBluetooth(token: String?): String = NOT_UNDER_TEST

    override fun grantSecureSettings(token: String?): String = NOT_UNDER_TEST

    override fun shutdown(token: String?) = Unit

    private companion object {
        val NOT_UNDER_TEST: String = PrivilegedProtocol.encodeError("not part of this test")
    }
}
