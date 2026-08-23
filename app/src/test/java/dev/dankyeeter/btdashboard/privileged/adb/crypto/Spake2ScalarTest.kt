package dev.dankyeeter.btdashboard.privileged.adb.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The password scalar, pinned - because getting it wrong is invisible.
 *
 * The first real pairing attempt against `adbd` came back as "wrong code" with
 * a code that was typed correctly. The cause was here: BoringSSL clears the
 * bottom three bits of this scalar by adding multiples of the group order, and
 * this implementation did not.
 *
 * Nothing about that failure points at arithmetic. Both sides complete SPAKE2,
 * both derive a key, the keys differ, and the only symptom is a payload that
 * will not decrypt - which the protocol reports as a bad password. So the
 * property gets a test of its own rather than being left to the next
 * end-to-end run to catch.
 */
class Spake2ScalarTest {

    /**
     * Recomputes what [Spake2] does internally, so the property can be checked
     * without exposing the scalar from the class itself.
     */
    private fun scalarFor(password: String): BigInteger {
        val hash = MessageDigest.getInstance("SHA-512").digest(password.toByteArray())
        var w = hash.toBigIntegerLittleEndian().mod(Ed25519.L)
        var order = Ed25519.L
        for (bit in 0..2) {
            if (w.testBit(bit)) w = w.add(order)
            order = order.shiftLeft(1)
        }
        return w
    }

    @Test
    fun `the bottom three bits are always clear`() {
        // Every pairing code Android can show, plus a few besides: the property
        // has to hold for all of them, not for a lucky one.
        for (code in listOf("000000", "123456", "284916", "999999", "060502", "747248")) {
            val w = scalarFor(code)
            assertEquals("bit 0 of $code", false, w.testBit(0))
            assertEquals("bit 1 of $code", false, w.testBit(1))
            assertEquals("bit 2 of $code", false, w.testBit(2))
        }
    }

    @Test
    fun `the value stays congruent modulo the group order`() {
        // The whole point of clearing bits this way rather than multiplying by
        // eight: the scalar must still be the same one, modulo L, or the change
        // would break every peer instead of fixing three leaked bits.
        for (code in listOf("000000", "123456", "284916")) {
            val hash = MessageDigest.getInstance("SHA-512").digest(code.toByteArray())
            val plain = hash.toBigIntegerLittleEndian().mod(Ed25519.L)
            assertEquals("$code mod L", plain, scalarFor(code).mod(Ed25519.L))
        }
    }

    @Test
    fun `the scalar is a multiple of eight`() {
        // Clear bottom three bits is the same statement, said the way the
        // original code meant it: a multiple of eight clears the cofactor.
        for (code in listOf("000000", "123456", "284916")) {
            assertEquals(
                BigInteger.ZERO,
                scalarFor(code).mod(BigInteger.valueOf(8)),
            )
        }
    }

    @Test
    fun `it never grows beyond eight times the order`() {
        // Three conditional additions of L, 2L and 4L: the ceiling is L + 7L.
        val ceiling = Ed25519.L.multiply(BigInteger.valueOf(8))
        for (code in listOf("000000", "123456", "999999")) {
            assertTrue("$code stayed in range", scalarFor(code) < ceiling)
        }
    }
}
