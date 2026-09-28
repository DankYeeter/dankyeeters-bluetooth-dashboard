package dev.dankyeeter.btdashboard.privileged.adb

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * SR-039: the SPAKE2 exchange and the key fingerprint must never reach the
 * log in a release build. [PairingTrace.loggingEnabled] defaults to
 * `BuildConfig.DEBUG`, which is always true for a Robolectric unit test (only
 * the debug variant runs unit tests, see `app/build.gradle.kts`) - so the
 * release path is exercised here by flipping the flag directly, the
 * "injectable flag" option named in T-060a.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingTraceTest {

    @After
    fun restoreDefault() {
        // Never leave the shared flag flipped for the next test in the class run.
        PairingTrace.loggingEnabled = true
    }

    @Test
    fun `nothing is logged once loggingEnabled is off, as on a release build`() {
        PairingTrace.loggingEnabled = false
        ShadowLog.clear()

        PairingTrace.sent(AdbPairingPacket(TYPE_SPAKE2_MSG, ByteArray(32)))
        PairingTrace.received(AdbPairingPacket(TYPE_SPAKE2_MSG, ByteArray(32)))
        PairingTrace.keyDerived(ByteArray(32))

        assertTrue(ShadowLog.getLogs().toString(), ShadowLog.getLogs().isEmpty())
    }

    @Test
    fun `logging runs normally once loggingEnabled is on, as on a debug build`() {
        PairingTrace.loggingEnabled = true
        ShadowLog.clear()

        PairingTrace.sent(AdbPairingPacket(TYPE_SPAKE2_MSG, ByteArray(32)))

        assertTrue(ShadowLog.getLogs().toString(), ShadowLog.getLogs().isNotEmpty())
    }

    private companion object {
        const val TYPE_SPAKE2_MSG = 0
    }
}
