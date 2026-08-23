package dev.dankyeeter.btdashboard.privileged.adb.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checked against RFC 5869, for the same reason the curve arithmetic is checked
 * against RFC 8032: a derivation that is subtly wrong still produces
 * plausible-looking key bytes, and the only symptom is that the peer's
 * decryption fails with no explanation.
 *
 * Vectors are the SHA-256 cases from RFC 5869 appendix A. Case 3 is the
 * important one here - no salt and no info - because that is the shape ADB
 * pairing uses.
 */
class HkdfTest {

    @Test
    fun `RFC 5869 case 1 - basic with salt and info`() {
        val prk = Hkdf.extract(
            keyMaterial = "0b".repeat(22).hexToBytes(),
            salt = "000102030405060708090a0b0c".hexToBytes(),
        )
        assertArrayEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5".hexToBytes(),
            prk,
        )
        assertArrayEquals(
            ("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865").hexToBytes(),
            Hkdf.expand(prk, "f0f1f2f3f4f5f6f7f8f9".hexToBytes(), 42),
        )
    }

    @Test
    fun `RFC 5869 case 3 - no salt, no info, which is how ADB uses it`() {
        val prk = Hkdf.extract(keyMaterial = "0b".repeat(22).hexToBytes(), salt = null)
        assertArrayEquals(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04".hexToBytes(),
            prk,
        )
        assertArrayEquals(
            ("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8").hexToBytes(),
            Hkdf.expand(prk, ByteArray(0), 42),
        )
    }

    @Test
    fun `derive is extract then expand`() {
        val ikm = "0b".repeat(22).hexToBytes()
        val info = "f0f1f2f3".hexToBytes()
        assertArrayEquals(
            Hkdf.expand(Hkdf.extract(ikm, null), info, 16),
            Hkdf.derive(ikm, null, info, 16),
        )
    }

    @Test
    fun `expands past one hash block`() {
        // 42 bytes needs two rounds; a counter that starts at 0 instead of 1,
        // or that is appended rather than fed into the HMAC, gets the first
        // block right and the second wrong.
        val prk = Hkdf.extract("00".repeat(22).hexToBytes(), null)
        val long = Hkdf.expand(prk, ByteArray(0), 64)
        val short = Hkdf.expand(prk, ByteArray(0), 32)
        assertEquals(64, long.size)
        assertArrayEquals(short, long.copyOfRange(0, 32))
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
