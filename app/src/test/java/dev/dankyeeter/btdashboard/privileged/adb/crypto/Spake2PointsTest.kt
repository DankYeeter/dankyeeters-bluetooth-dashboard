package dev.dankyeeter.btdashboard.privileged.adb.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does our derivation produce the same M and N that `adbd` uses?
 *
 * This is the test that decides whether pairing can ever work. SPAKE2 blinds
 * each side with a multiple of a fixed point; if our M is not byte-for-byte
 * BoringSSL's M, both sides derive different keys and the handshake fails with
 * nothing pointing at the cause.
 *
 * The expected values are the first entry of BoringSSL's own precomputation
 * tables `kSpakeMSmallPrecomp` and `kSpakeNSmallPrecomp`. Those tables store
 * multiples of the point, and the first entry is the multiple for k = 1 - the
 * point itself, as x then y, each 32 bytes little-endian. So this compares our
 * independently derived points against the library's, without trusting either
 * to check the other.
 */
class Spake2PointsTest {

    /** kSpakeMSmallPrecomp[0..63]: x then y, little-endian. */
    private val expectedMx = """
        c8 a6 63 c5 97 f1 ee 40 ab 62 42 ee 25 6f 32 6c
        75 2c a7 d3 bd 32 3b 1e 11 9c bd 04 a9 78 6f 45
    """.hexToBytes()

    private val expectedMy = """
        5a da 7e 4b f6 dd d9 ad b6 62 6d 32 13 1c 6b 5c
        51 a1 e3 47 a3 47 8f 53 cf cf 44 1b 88 ee d1 2e
    """.hexToBytes()

    /** kSpakeNSmallPrecomp[0..63]. */
    private val expectedNx = """
        20 1b c5 b3 43 17 71 10 44 1e 73 b3 ae 3f bf 9f
        f5 44 c8 13 8f d1 01 c2 8a 1a 6d ea 4d 00 5d 6e
    """.hexToBytes()

    private val expectedNy = """
        10 e3 df 0a e3 7d 8e 7a 99 b5 fe 74 b4 46 72 10
        3d bd dc bd 06 af 68 0d 71 32 9a 11 69 3b c7 78
    """.hexToBytes()

    @Test
    fun `M matches BoringSSL`() = assertPoint(Spake2Points.M, expectedMx, expectedMy, "M")

    @Test
    fun `N matches BoringSSL`() = assertPoint(Spake2Points.N, expectedNx, expectedNy, "N")

    @Test
    fun `both points are on the curve`() {
        assertTrue("M", Ed25519.isOnCurve(Spake2Points.M))
        assertTrue("N", Ed25519.isOnCurve(Spake2Points.N))
    }

    @Test
    fun `M and N are different points`() {
        // Same seed handling for both would be an easy mistake and would break
        // the protocol in a way that still looks like it is working.
        assertTrue(Spake2Points.M.toAffine() != Spake2Points.N.toAffine())
    }

    private fun assertPoint(
        point: Ed25519.Point,
        expectedX: ByteArray,
        expectedY: ByteArray,
        name: String,
    ) {
        val (x, y) = point.toAffine()
        assertEquals("$name.x", expectedX.toBigIntegerLittleEndian(), x)
        assertEquals("$name.y", expectedY.toBigIntegerLittleEndian(), y)
    }

    private fun String.hexToBytes(): ByteArray =
        trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
}
