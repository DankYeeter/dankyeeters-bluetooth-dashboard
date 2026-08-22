package dev.dankyeeter.btdashboard.privileged

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The app must hand the helper something that dies with this process.
 *
 * Without it the hand-over stays a one-shot at helper start, and that cost the
 * app half its privileged functions in ordinary use: Android reclaims a
 * backgrounded app whenever it likes, and the restarted process has no route
 * back to a helper that is still running. The user saw "App helper: not
 * running" and had to re-run the ADB command for nothing.
 *
 * The reconnect loop itself lives in the helper process and needs a real
 * Binder death to exercise, so it is verified on-device rather than here. What
 * *is* pinned here is the contract the loop depends on: the token exists, it is
 * stable, and it carries nothing else.
 */
@RunWith(RobolectricTestRunner::class)
class PrivilegedReconnectTest {

    @Test
    fun `the app offers a liveness token`() {
        assertNotNull(PrivilegedConnection.livenessToken)
    }

    @Test
    fun `the token is one object for the life of the process`() {
        // A fresh token per hand-over would leave the helper watching an object
        // nobody holds any more — it would report the app dead while it is
        // running, which is the opposite of the bug being fixed.
        assertSame(PrivilegedConnection.livenessToken, PrivilegedConnection.livenessToken)
    }

    @Test
    fun `the token exposes no interface`() {
        // Deliberately a bare Binder. It is reachable from a shell-uid process,
        // so any operation added here would be an operation exposed to it; its
        // whole job is to stop existing.
        assertTrue(
            "the liveness token must not carry a service interface",
            PrivilegedConnection.livenessToken.queryLocalInterface(
                IPrivilegedService.DESCRIPTOR,
            ) == null,
        )
    }
}
