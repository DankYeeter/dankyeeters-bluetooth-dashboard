package dev.dankyeeter.btdashboard.privileged.adb.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Checked against RFC 8032, not against itself.
 *
 * Hand-written curve arithmetic is the one place in this project where a bug
 * does not announce itself. A wrong carry produces points that are still on the
 * curve, still encode, still multiply - and simply disagree with the peer, so
 * pairing fails with "certificate unknown" and no hint that the mathematics was
 * the problem. Test vectors from the standard are the only way to know.
 *
 * The vectors are the Ed25519 key generation examples from RFC 8032 §7.1: a
 * known secret produces a known public key, and the public key is
 * `encode(clamp(SHA-512(secret)[0..31]) · B)`. Getting those right exercises
 * field arithmetic, point addition, doubling, scalar multiplication and the
 * compressed encoding in one go.
 */
class Ed25519Test {

    @Test
    fun `curve constants match the standard`() {
        assertEquals(
            "2^255 - 19",
            BigInteger("57896044618658097711785492504343953926634992332820282019728792003956564819949"),
            Ed25519.P,
        )
        assertEquals(
            BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989"),
            Ed25519.L,
        )
        // d, written out, from RFC 8032 section 5.1.
        assertEquals(
            BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555"),
            Ed25519.D,
        )
    }

    @Test
    fun `the base point is on the curve and has the documented order`() {
        assertTrue(Ed25519.isOnCurve(Ed25519.B))
        // L·B is the identity: the defining property of the group order, and a
        // strong check that addition and doubling agree with each other.
        val shouldBeIdentity = Ed25519.scalarMultiply(Ed25519.L, Ed25519.B)
        val (x, y) = shouldBeIdentity.toAffine()
        assertEquals(BigInteger.ZERO, x)
        assertEquals(BigInteger.ONE, y)
    }

    @Test
    fun `RFC 8032 test vector 1`() {
        assertPublicKey(
            secret = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            expectedPublic = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
        )
    }

    @Test
    fun `RFC 8032 test vector 2`() {
        assertPublicKey(
            secret = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            expectedPublic = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
        )
    }

    @Test
    fun `RFC 8032 test vector 3`() {
        assertPublicKey(
            secret = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
            expectedPublic = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
        )
    }

    @Test
    fun `encoding round-trips through decoding`() {
        // A handful of unrelated multiples, so a mistake in the sign bit cannot
        // hide behind one lucky point.
        for (k in listOf(1L, 2L, 3L, 7L, 1234567L)) {
            val point = Ed25519.scalarMultiplyBase(BigInteger.valueOf(k))
            val decoded = Ed25519.decode(point.encode())
            assertNotNull("k=$k did not decode", decoded)
            assertEquals("k=$k", point.toAffine(), decoded!!.toAffine())
        }
    }

    @Test
    fun `addition agrees with repeated addition`() {
        val five = Ed25519.scalarMultiplyBase(BigInteger.valueOf(5))
        var sum = Ed25519.IDENTITY
        repeat(5) { sum = Ed25519.add(sum, Ed25519.B) }
        assertEquals(five.toAffine(), sum.toAffine())
    }

    @Test
    fun `subtracting a point undoes adding it`() {
        val a = Ed25519.scalarMultiplyBase(BigInteger.valueOf(9))
        val b = Ed25519.scalarMultiplyBase(BigInteger.valueOf(4))
        assertEquals(a.toAffine(), Ed25519.subtract(Ed25519.add(a, b), b).toAffine())
    }

    @Test
    fun `refuses encodings that are not points`() {
        // y = p - 1 with the curve equation unsatisfiable, and a y at or above
        // the field prime. Both must be rejected rather than coerced: a peer
        // controls these bytes.
        val notOnCurve = ByteArray(32).also { it[0] = 2 }
        assertNull(Ed25519.decode(notOnCurve))

        val tooLarge = ByteArray(32) { 0xff.toByte() }.also { it[31] = 0x7f }
        assertNull(Ed25519.decode(tooLarge))
    }

    /** public = encode(clamp(SHA-512(secret)[0..31]) · B), per RFC 8032 §5.1.5. */
    private fun assertPublicKey(secret: String, expectedPublic: String) {
        val hash = MessageDigest.getInstance("SHA-512").digest(secret.hexToBytes())
        val clamped = hash.copyOfRange(0, 32).also {
            it[0] = (it[0].toInt() and 0xf8).toByte()
            it[31] = ((it[31].toInt() and 0x7f) or 0x40).toByte()
        }
        val scalar = clamped.toBigIntegerLittleEndian()
        val public = Ed25519.scalarMultiplyBase(scalar).encode()
        assertArrayEquals(expectedPublic.hexToBytes(), public)
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
