package dev.dankyeeter.btdashboard.privileged.adb.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

/**
 * Both halves of the exchange, run against each other.
 *
 * There is no published SPAKE2 test vector for BoringSSL's variant, so the
 * check that carries the most weight is the protocol's own promise: two parties
 * with the same code arrive at the same key, and two parties with different
 * codes do not. That catches the mistakes that actually happen here - a mask
 * applied with the wrong point, a transcript assembled in the wrong order, a
 * scalar reduced where it should not be.
 *
 * What it cannot catch is a shared misunderstanding of BoringSSL: if our Alice
 * and our Bob are wrong in the same way, they still agree. That last step is
 * settled against the real daemon, which is why the pieces this test does cover
 * are worth nailing down first.
 */
class Spake2Test {

    private val code = "491000".toByteArray()
    private val alicePeer = "adb pair client".toByteArray()
    private val bobPeer = "adb pair server".toByteArray()

    @Test
    fun `both sides derive the same key`() {
        val alice = Spake2(Spake2.Role.ALICE, alicePeer, bobPeer, code, fixedRandom(1))
        val bob = Spake2(Spake2.Role.BOB, bobPeer, alicePeer, code, fixedRandom(2))

        val aliceKey = alice.computeKey(bob.myMessage)
        val bobKey = bob.computeKey(alice.myMessage)

        assertNotNull(aliceKey)
        assertNotNull(bobKey)
        assertArrayEquals(aliceKey, bobKey)
        assertEquals("SHA-512 output", 64, aliceKey!!.size)
    }

    @Test
    fun `a wrong code produces a different key`() {
        val alice = Spake2(Spake2.Role.ALICE, alicePeer, bobPeer, code, fixedRandom(1))
        val bob = Spake2(Spake2.Role.BOB, bobPeer, alicePeer, "000000".toByteArray(), fixedRandom(2))

        val aliceKey = alice.computeKey(bob.myMessage)!!
        val bobKey = bob.computeKey(alice.myMessage)!!

        // The whole point of a PAKE: a wrong guess yields an unrelated key and
        // no way to tell how wrong it was.
        assertFalse(aliceKey.contentEquals(bobKey))
    }

    @Test
    fun `two runs with the same code still differ`() {
        // Fresh private keys each time, so the messages and the derived key
        // must differ. If they did not, the exchange would be replayable.
        val first = Spake2(Spake2.Role.ALICE, alicePeer, bobPeer, code, SecureRandom())
        val second = Spake2(Spake2.Role.ALICE, alicePeer, bobPeer, code, SecureRandom())
        assertFalse(first.myMessage.contentEquals(second.myMessage))
    }

    @Test
    fun `both roles must be used`() {
        // Two Alices blind with the same point and unblind with the wrong one,
        // so they cannot agree. Worth pinning: mixing the roles up is the
        // easiest way to build something that looks symmetric and is broken.
        val one = Spake2(Spake2.Role.ALICE, alicePeer, bobPeer, code, fixedRandom(1))
        val two = Spake2(Spake2.Role.ALICE, alicePeer, bobPeer, code, fixedRandom(2))
        assertFalse(one.computeKey(two.myMessage)!!.contentEquals(two.computeKey(one.myMessage)!!))
    }

    @Test
    fun `refuses a peer message that is not a point`() {
        val alice = Spake2(Spake2.Role.ALICE, alicePeer, bobPeer, code, fixedRandom(1))
        assertNull("wrong length", alice.computeKey(ByteArray(31)))
        assertNull("not on the curve", alice.computeKey(ByteArray(32).also { it[0] = 2 }))
    }

    @Test
    fun `the outgoing message is a valid point`() {
        val alice = Spake2(Spake2.Role.ALICE, alicePeer, bobPeer, code, fixedRandom(1))
        val decoded = Ed25519.decode(alice.myMessage)
        assertNotNull(decoded)
        assertEquals(32, alice.myMessage.size)
    }

    /** Deterministic, so a failure can be reproduced rather than re-rolled. */
    private fun fixedRandom(seed: Long) = SecureRandom.getInstance("SHA1PRNG").apply {
        setSeed(seed)
    }
}
