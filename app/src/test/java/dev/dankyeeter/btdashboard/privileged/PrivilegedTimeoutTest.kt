package dev.dankyeeter.btdashboard.privileged

import android.app.Application
import android.content.Context
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

    private fun CodecApplyOutcome.isBusy(): Boolean =
        this is CodecApplyOutcome.Unavailable && reason.contains("earlier request")
}

/** Blocks every codec and HD-audio write until [release]; answers everything else at once. */
private class UnansweringHelper(
    private val codecStatusReply: String = NOT_UNDER_TEST,
) : IPrivilegedService.Stub() {

    val release = CountDownLatch(1)
    val writes = AtomicInteger()

    private fun hang(): String {
        writes.incrementAndGet()
        release.await()
        return NOT_UNDER_TEST
    }

    override fun version(): Int = PrivilegedServer.VERSION

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
