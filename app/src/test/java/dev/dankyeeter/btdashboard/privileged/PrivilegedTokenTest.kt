package dev.dankyeeter.btdashboard.privileged

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Token rotation, and the one property that makes it safe to do at all.
 *
 * The failure this guards against is specific: rotating the stored token while
 * a helper is connected makes every subsequent call fail with "bad token",
 * which on a phone is indistinguishable from the helper having crashed. So the
 * ordering — generate never rotates, only an accepted hand-over does — is
 * asserted rather than left to the comment that describes it.
 */
@RunWith(RobolectricTestRunner::class)
class PrivilegedTokenTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric shares a sandbox across the test classes in a run, so both
        // the preferences and the process-wide session token can arrive dirty.
        context.getSharedPreferences("privileged", Context.MODE_PRIVATE)
            .edit().clear().commit()
        PrivilegedBootstrap(context).newAdbCommand()
    }

    private fun bootstrap() = PrivilegedBootstrap(context)

    private fun tokenIn(command: String): String =
        command.substringAfter("PrivilegedServer ").substringBefore(" >")

    // ---- generating ---------------------------------------------------------

    @Test
    fun `the command carries the pending token`() {
        val bootstrap = bootstrap()
        assertEquals(bootstrap.pendingToken(), tokenIn(bootstrap.adbCommand()))
    }

    @Test
    fun `the command is stable within one session`() {
        // The setup screen and the wizard both render it, and a recomposition
        // must not invalidate a line the user has already copied.
        assertEquals(bootstrap().adbCommand(), bootstrap().adbCommand())
        assertEquals(bootstrap().adbCommand(), PrivilegedBootstrap(context).adbCommand())
    }

    @Test
    fun `asking for a new command retires the previous one`() {
        val old = tokenIn(bootstrap().adbCommand())
        val new = tokenIn(bootstrap().newAdbCommand())

        assertNotEquals(old, new)
        // The point of rotating: the old line is no longer accepted anywhere.
        assertNull(bootstrap().match(old))
        assertTrue(bootstrap().match(new) is PrivilegedBootstrap.TokenMatch.Pending)
    }

    // ---- the ordering rule --------------------------------------------------

    @Test
    fun `generating a command does not disturb a connected helper`() {
        val bootstrap = bootstrap()
        val connected = tokenIn(bootstrap.adbCommand())
        bootstrap.promote(connected)
        assertEquals(connected, bootstrap.activeToken())

        // The user opens the setup screen again and a fresh command is minted.
        val fresh = tokenIn(bootstrap.newAdbCommand())

        assertNotEquals(connected, fresh)
        // ...and the helper that is already running keeps working, because the
        // active token was not touched.
        assertEquals(connected, bootstrap.activeToken())
        assertTrue(bootstrap.match(connected) is PrivilegedBootstrap.TokenMatch.Active)
        assertTrue(bootstrap.match(fresh) is PrivilegedBootstrap.TokenMatch.Pending)
    }

    @Test
    fun `accepting a pending hand-over is the only thing that rotates`() {
        val bootstrap = bootstrap()
        val first = tokenIn(bootstrap.adbCommand())
        assertNull("nothing has connected yet", bootstrap.activeToken())

        bootstrap.promote(first)
        assertEquals(first, bootstrap.activeToken())
        assertNull("the pending slot is spent", bootstrap.pendingToken())

        val second = tokenIn(bootstrap.newAdbCommand())
        val retired = bootstrap.promote(second)

        assertEquals("the outgoing helper answers to the old token", first, retired)
        assertEquals(second, bootstrap.activeToken())
        assertNull(bootstrap.match(first))
    }

    @Test
    fun `promoting the token already in use retires nobody`() {
        val bootstrap = bootstrap()
        val token = tokenIn(bootstrap.adbCommand())
        bootstrap.promote(token)

        assertNull("there is no other helper to shut down", bootstrap.promote(token))
    }

    @Test
    fun `a token from neither slot is refused`() {
        val bootstrap = bootstrap()
        bootstrap.promote(tokenIn(bootstrap.adbCommand()))
        bootstrap.adbCommand()

        assertNull(bootstrap.match("6f5c1e8a-0b3d-4a71-9e2f-1c4d5b6a7f80"))
        assertNull(bootstrap.match(""))
        assertNull(bootstrap.match(null))
    }

    @Test
    fun `an empty store authenticates nobody`() {
        context.getSharedPreferences("privileged", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val fresh = PrivilegedBootstrap(context)
        // Both slots are empty; "nothing was ever set" must not match "nothing
        // was offered".
        assertNull(fresh.match(null))
        assertNull(fresh.match(""))
    }

    // ---- the compare itself -------------------------------------------------

    @Test
    fun `tokens match only when they are the same non-empty string`() {
        assertTrue(PrivilegedProtocol.tokensMatch("abc", "abc"))
        assertFalse(PrivilegedProtocol.tokensMatch("abc", "abd"))
        assertFalse(PrivilegedProtocol.tokensMatch("abc", "abcd"))
        assertFalse(PrivilegedProtocol.tokensMatch(null, null))
        assertFalse(PrivilegedProtocol.tokensMatch("", ""))
        assertFalse(PrivilegedProtocol.tokensMatch("abc", null))
    }

    // ---- identity -----------------------------------------------------------

    @Test
    fun `the fallback package name still matches the provider authority`() {
        // The helper normally learns which uid to trust by resolving the
        // authority; APP_PACKAGE is only the fallback. A rename that moved one
        // and not the other would leave a helper trusting the wrong uid, so the
        // two are pinned to each other here.
        assertTrue(
            PrivilegedContract.AUTHORITY.startsWith(PrivilegedContract.APP_PACKAGE + "."),
        )
        assertEquals(context.packageName, PrivilegedContract.APP_PACKAGE)
    }

    /**
     * The ADB command must start the helper under the exact name the helper's
     * own reaper looks for.
     *
     * These are two different files agreeing on one string. If they ever
     * drifted, nothing would fail loudly: helpers would simply stop reaping
     * each other and pile up as shell-uid processes again — the bug this
     * constant exists to prevent.
     */
    @Test
    fun `the start command names the process the reaper searches for`() {
        assertTrue(
            bootstrap().adbCommand()
                .contains("--nice-name=${PrivilegedContract.HELPER_PROCESS_NAME}"),
        )
    }
}
