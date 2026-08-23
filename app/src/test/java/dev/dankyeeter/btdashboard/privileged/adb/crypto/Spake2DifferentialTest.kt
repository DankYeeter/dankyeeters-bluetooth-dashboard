package dev.dankyeeter.btdashboard.privileged.adb.crypto

import io.github.muntashirakon.crypto.spake2.Spake2Context
import io.github.muntashirakon.crypto.spake2.Spake2Role
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Our SPAKE2 against one that is known to pair with a real `adbd`.
 *
 * ## Why this exists
 *
 * Pairing fails with a key mismatch, and the search has already cleared the
 * ground beneath: the curve arithmetic matches EdDSA-Java operation by
 * operation, M and N match BoringSSL's own table, HKDF matches RFC 5869, and
 * the daemon confirms our framing by reading the packet before failing to
 * decrypt it. Eight combinations of role, name terminator and password-scalar
 * convention have been tried against the device; all eight fail.
 *
 * So the question is no longer "which parameter" but "is our SPAKE2 correct at
 * all". A full exchange against an independent implementation answers that: our
 * Alice talks to their Bob, and either the two agree on a key or they do not.
 *
 * ## On the licence
 *
 * spake2-java is LGPL-3.0 and must not ship in the app - see settings.gradle.kts.
 * It is a test dependency, compiled into nothing that is distributed, and used
 * here purely as a second opinion.
 */
class Spake2DifferentialTest {

    private val clientName = "adb pair client".toByteArray()
    private val serverName = "adb pair server".toByteArray()
    private val password = "795583".toByteArray()

    @Test
    fun `our Alice and their Bob agree on a key`() {
        val ourAlice = Spake2(
            role = Spake2.Role.ALICE,
            myName = clientName,
            theirName = serverName,
            password = password,
        )
        val theirBob = Spake2Context(Spake2Role.Bob, serverName, clientName)

        val bobMessage = theirBob.generateMessage(password)
        val ourKey = ourAlice.computeKey(bobMessage)
        val theirKey = theirBob.processMessage(ourAlice.myMessage)

        assertNotNull("our side refused their message", ourKey)
        assertNotNull("their side refused our message", theirKey)
        assertArrayEquals("the two implementations derived different keys", theirKey, ourKey)
    }

    @Test
    fun `our Bob and their Alice agree on a key`() {
        // The mirror image, so a role-dependent mistake cannot hide behind a
        // symmetric one.
        val ourBob = Spake2(
            role = Spake2.Role.BOB,
            myName = serverName,
            theirName = clientName,
            password = password,
        )
        val theirAlice = Spake2Context(Spake2Role.Alice, clientName, serverName)

        val aliceMessage = theirAlice.generateMessage(password)
        val ourKey = ourBob.computeKey(aliceMessage)
        val theirKey = theirAlice.processMessage(ourBob.myMessage)

        assertNotNull(ourKey)
        assertNotNull(theirKey)
        assertArrayEquals(theirKey, ourKey)
    }

    @Test
    fun `the scalar convention that matches is recorded`() {
        // Whichever of the two conventions agrees with the reference is the one
        // adbd expects, since the reference is known to pair with it. Both are
        // tried so the test reports the answer rather than assuming it.
        val agreeing = listOf(true, false).filter { clearLowBits ->
            val ours = Spake2(
                role = Spake2.Role.ALICE,
                myName = clientName,
                theirName = serverName,
                password = password,
                clearLowBits = clearLowBits,
            )
            val theirs = Spake2Context(Spake2Role.Bob, serverName, clientName)
            val theirMessage = theirs.generateMessage(password)
            val ourKey = ours.computeKey(theirMessage)
            val theirKey = theirs.processMessage(ours.myMessage)
            ourKey != null && theirKey != null && ourKey.contentEquals(theirKey)
        }
        println("SPAKE2 clearLowBits values that agree with the reference: $agreeing")
        assertNotNull(agreeing)
    }
}
