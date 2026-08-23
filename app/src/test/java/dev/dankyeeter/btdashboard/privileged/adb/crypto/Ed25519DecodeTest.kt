package dev.dankyeeter.btdashboard.privileged.adb.crypto

import net.i2p.crypto.eddsa.math.GroupElement
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Reading points that someone else wrote.
 *
 * Decoding is where a peer's bytes first meet our arithmetic, so it has to
 * agree with other implementations exactly - accept what they accept, refuse
 * what they refuse. A decoder that is merely *stricter* looks fine in isolation
 * and drops one exchange in however-many in the field.
 *
 * The comparison is against EdDSA-Java. An attempt to use spake2-java for this
 * was abandoned: its pure-Java module emitted byte strings that are not curve
 * points at all - x² came out a non-residue - and EdDSA-Java rejected them for
 * the same reason. Two implementations agreeing against a third is the answer,
 * and it is not the two.
 */
class Ed25519DecodeTest {

    private val spec = EdDSANamedCurveTable.getByName("Ed25519")
    private val random = SecureRandom()

    @Test
    fun `points we generate are read identically by EdDSA-Java`() {
        repeat(ROUNDS) {
            val scalar = BigInteger(200, random)
            val ours = Ed25519.scalarMultiplyBase(scalar).encode()
            assertArrayEquals(GroupElement(spec.curve, ours).toByteArray(), ours)
        }
    }

    @Test
    fun `points EdDSA-Java generates are read identically by us`() {
        repeat(ROUNDS) {
            val scalar = BigInteger(200, random).toLittleEndian(32)
            val theirs = spec.b.scalarMultiply(scalar).toByteArray()
            val decoded = Ed25519.decode(theirs)
            assertNotNull("we refused a point EdDSA-Java produced", decoded)
            assertArrayEquals(theirs, decoded!!.encode())
        }
    }

    @Test
    fun `both refuse bytes that are not a point`() {
        // A y whose x² is a non-residue: on paper it looks like an encoding,
        // and it is not on the curve.
        val notAPoint = ByteArray(32).also { it[0] = 2 }
        assertNull(Ed25519.decode(notAPoint))
        assertTrue(
            "EdDSA-Java should refuse it too",
            runCatching { GroupElement(spec.curve, notAPoint) }.isFailure ||
                runCatching { GroupElement(spec.curve, notAPoint).toByteArray() }.isFailure,
        )
    }

    @Test
    fun `y at or above the field prime is refused`() {
        // Deliberately stricter than the classic reference code, so that a
        // point cannot have two encodings.
        assertNull(Ed25519.decode(Ed25519.P.toLittleEndian(32)))
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)

    private companion object {
        const val ROUNDS = 25
    }
}
