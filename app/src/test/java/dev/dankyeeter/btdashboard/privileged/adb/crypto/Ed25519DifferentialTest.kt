package dev.dankyeeter.btdashboard.privileged.adb.crypto

import net.i2p.crypto.eddsa.math.GroupElement
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Our curve arithmetic against a second implementation, operation by operation.
 *
 * ## Why this test exists
 *
 * Pairing against the real `adbd` fails with a key mismatch, and every part has
 * been checked in isolation and looks right: RFC 8032 vectors pass, M and N
 * match BoringSSL's own table byte for byte, HKDF matches RFC 5869, the wire
 * format is confirmed by the daemon itself (it reads our packet and only then
 * fails to decrypt). Something is still wrong, and inspection has not found it.
 *
 * The RFC 8032 vectors have a blind spot that fits the symptom exactly: they
 * only exercise multiples of the **base point**. SPAKE2 multiplies arbitrary
 * points - M, N, and whatever the peer sends - and nothing so far has tested
 * that. This does, against EdDSA-Java, an independent implementation under CC0.
 *
 * A disagreement here names the bug. Agreement everywhere rules the arithmetic
 * out and moves the search to the layer above, which is worth almost as much.
 */
class Ed25519DifferentialTest {

    private val spec = EdDSANamedCurveTable.getByName("Ed25519")
    private val curve = spec.curve

    /** EdDSA-Java speaks 32-byte little-endian scalars. */
    private fun BigInteger.toScalarBytes(): ByteArray = toLittleEndian(32)

    private fun reference(encoded: ByteArray): GroupElement = GroupElement(curve, encoded)

    /**
     * EdDSA-Java can only multiply a point that carries a precomputation table,
     * and the only public way to get one is this constructor flag - hence the
     * round trip through the encoding rather than reusing the element.
     */
    private fun referenceScalarMultiply(point: GroupElement, scalar: BigInteger): ByteArray {
        val p3 = GroupElement(curve, point.toByteArray(), true)
        return p3.scalarMultiply(scalar.toScalarBytes()).toByteArray()
    }

    @Test
    fun `base point multiples agree`() {
        val base = spec.b
        for (k in listOf(1L, 2L, 8L, 12345L, 987654321L)) {
            val scalar = BigInteger.valueOf(k)
            assertArrayEquals(
                "k=$k",
                base.scalarMultiply(scalar.toScalarBytes()).toByteArray(),
                Ed25519.scalarMultiplyBase(scalar).encode(),
            )
        }
    }

    @Test
    fun `M and N decode to the same point in both implementations`() {
        assertArrayEquals(
            "M",
            reference(Spake2Points.M.encode()).toByteArray(),
            Spake2Points.M.encode(),
        )
        assertArrayEquals(
            "N",
            reference(Spake2Points.N.encode()).toByteArray(),
            Spake2Points.N.encode(),
        )
    }

    @Test
    fun `multiplying an arbitrary point agrees`() {
        // The operation RFC 8032 never tests and SPAKE2 depends on entirely.
        val m = reference(Spake2Points.M.encode())
        for (k in listOf(1L, 2L, 8L, 99991L)) {
            val scalar = BigInteger.valueOf(k)
            assertArrayEquals(
                "M * $k",
                referenceScalarMultiply(m, scalar),
                Ed25519.scalarMultiply(scalar, Spake2Points.M).encode(),
            )
        }
    }

    @Test
    fun `multiplying by a realistic password scalar agrees`() {
        // The real thing: a full-width scalar derived from a pairing code, not
        // a small integer. Carry handling only shows up at this size.
        val hash = MessageDigest.getInstance("SHA-512").digest("795583".toByteArray())
        val w = hash.toBigIntegerLittleEndian().mod(Ed25519.L)
        val m = reference(Spake2Points.M.encode())
        assertArrayEquals(
            referenceScalarMultiply(m, w),
            Ed25519.scalarMultiply(w, Spake2Points.M).encode(),
        )
    }

    @Test
    fun `adding two unrelated points agrees`() {
        val a = Ed25519.scalarMultiplyBase(BigInteger.valueOf(7))
        val sum = Ed25519.add(a, Spake2Points.M).encode()

        val referenceSum = GroupElement(curve, a.encode()).toP3()
            .add(GroupElement(curve, Spake2Points.M.encode()).toCached())
            .toP3()
            .toByteArray()

        assertArrayEquals(referenceSum, sum)
    }

    @Test
    fun `subtracting agrees`() {
        val a = Ed25519.scalarMultiplyBase(BigInteger.valueOf(11))
        val difference = Ed25519.subtract(a, Spake2Points.N).encode()

        val referenceDifference = GroupElement(curve, a.encode()).toP3()
            .sub(GroupElement(curve, Spake2Points.N.encode()).toCached())
            .toP3()
            .toByteArray()

        assertArrayEquals(referenceDifference, difference)
    }

    @Test
    fun `a whole SPAKE2 message agrees`() {
        // End to end for the half that goes on the wire: x·B + w·M, exactly as
        // the daemon computes it. If this matches and pairing still fails, the
        // arithmetic is exonerated.
        val hash = MessageDigest.getInstance("SHA-512").digest("123456".toByteArray())
        val w = hash.toBigIntegerLittleEndian().mod(Ed25519.L)
        val x = BigInteger.valueOf(8).multiply(BigInteger("424242424242424242424242"))

        val ours = Ed25519.add(
            Ed25519.scalarMultiplyBase(x),
            Ed25519.scalarMultiply(w, Spake2Points.M),
        ).encode()

        val theirs = GroupElement(curve, spec.b.scalarMultiply(x.toScalarBytes()).toByteArray())
            .toP3()
            .add(
                GroupElement(
                    curve,
                    referenceScalarMultiply(reference(Spake2Points.M.encode()), w),
                ).toCached(),
            )
            .toP3()
            .toByteArray()

        assertArrayEquals(theirs, ours)
    }

    @Test
    fun `the field prime is the same number`() {
        // Reduced, not compared directly: EdDSA-Java's field element for q is
        // q itself, which any field encoding represents as zero. Asking whether
        // our prime vanishes in their field is the same statement without the
        // circularity.
        assertEquals(
            BigInteger.ZERO,
            spec.curve.field.q.toByteArray().toBigIntegerLittleEndian().mod(Ed25519.P),
        )
    }
}
