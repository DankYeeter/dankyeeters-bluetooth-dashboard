package dev.dankyeeter.btdashboard.privileged.adb.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cipher cannot be checked against a published vector - there is no
 * standard for "adb's particular use of AES-GCM" - so these pin the properties
 * that would otherwise break silently against the real daemon.
 *
 * Two ends of the same key material stand in for the two sides of the pairing:
 * one instance encrypts, a second decrypts. That is exactly the arrangement on
 * the wire, and it catches the mistakes that matter: a nonce that does not
 * advance, one that advances differently in each direction, or a key derivation
 * that depends on something local.
 */
class AdbPairingCipherTest {

    private val keyMaterial = ByteArray(64) { it.toByte() }

    @Test
    fun `a message survives the round trip`() {
        val sender = AdbPairingCipher(keyMaterial)
        val receiver = AdbPairingCipher(keyMaterial)
        val message = "the peer info payload".toByteArray()

        val decrypted = receiver.decrypt(sender.encrypt(message))
        assertNotNull(decrypted)
        assertArrayEquals(message, decrypted)
    }

    @Test
    fun `the same key material always derives the same key`() {
        // Both sides build their cipher independently from the SPAKE2 output.
        // Anything local leaking into the derivation - a random salt, a
        // timestamp - would work perfectly in a single-process test and fail
        // on the wire every time.
        val a = AdbPairingCipher(keyMaterial)
        val b = AdbPairingCipher(keyMaterial)
        assertArrayEquals(a.encrypt("x".toByteArray()), b.encrypt("x".toByteArray()))
    }

    @Test
    fun `the nonce advances, so repeats do not repeat`() {
        val cipher = AdbPairingCipher(keyMaterial)
        val first = cipher.encrypt("same".toByteArray())
        val second = cipher.encrypt("same".toByteArray())
        assertFalse(
            "identical ciphertext means the counter never moved",
            first.contentEquals(second),
        )
    }

    @Test
    fun `each direction counts separately`() {
        // Sending does not advance the receive counter. A single shared counter
        // would decrypt the first message and nothing after it - the kind of
        // fault that looks like an intermittent network problem.
        val alice = AdbPairingCipher(keyMaterial)
        val bob = AdbPairingCipher(keyMaterial)

        val toBob = alice.encrypt("first".toByteArray())
        alice.encrypt("alice talks again".toByteArray())

        assertArrayEquals("first".toByteArray(), bob.decrypt(toBob))
    }

    @Test
    fun `a wrong key returns null instead of rubbish`() {
        val sender = AdbPairingCipher(keyMaterial)
        val wrongKey = AdbPairingCipher(ByteArray(64) { (it + 1).toByte() })
        assertNull(wrongKey.decrypt(sender.encrypt("secret".toByteArray())))
    }

    @Test
    fun `a tampered tag returns null`() {
        val sender = AdbPairingCipher(keyMaterial)
        val receiver = AdbPairingCipher(keyMaterial)
        val ciphertext = sender.encrypt("secret".toByteArray())
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        assertNull(receiver.decrypt(ciphertext))
    }

    @Test
    fun `ciphertext carries the authentication tag`() {
        val cipher = AdbPairingCipher(keyMaterial)
        val plaintext = ByteArray(20)
        // GCM appends a 16-byte tag and no nonce: the nonce is implied by the
        // counter and never travels.
        assertEquals(plaintext.size + 16, cipher.encrypt(plaintext).size)
    }
}
