package dev.dankyeeter.btdashboard.privileged.adb.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import dev.dankyeeter.btdashboard.privileged.adb.AdbPeerInfo
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encryption stage against published values, not against itself.
 *
 * Everything above this has been cleared: the curve arithmetic matches
 * EdDSA-Java, M and N match BoringSSL, point decoding agrees with two other
 * implementations, HKDF matches RFC 5869, and the daemon confirms our framing
 * by reading the packet before failing on it. What remains is how the SPAKE2
 * output becomes an AES key and a nonce - and "it looks right" has already been
 * wrong twice in this feature.
 *
 * There is no published vector for adb's particular use of AES-GCM, so this
 * checks the two things that can be checked: that the platform's GCM matches
 * NIST for a known key, nonce and plaintext, and that the exact key and nonce
 * we hand it match what adb's code produces.
 */
class AdbPairingCipherReferenceTest {

    /**
     * NIST SP 800-38D, AES-128-GCM test case 3.
     *
     * Establishes that the platform's `AES/GCM/NoPadding` with a 12-byte nonce
     * and a 128-bit tag is the same primitive BoringSSL uses, and that we drive
     * it the same way - tag appended, no associated data.
     */
    @Test
    fun `the platform GCM matches NIST`() {
        val key = "feffe9928665731c6d6a8f9467308308".hexToBytes()
        val nonce = "cafebabefacedbaddecaf888".hexToBytes()
        val plaintext = (
            "d9313225f88406e5a55909c5aff5269a" +
                "86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525" +
                "b16aedf5aa0de657ba637b391aafd255"
            ).hexToBytes()
        val expected = (
            "42831ec2217774244b7221b784d0d49c" +
                "e3aa212f2c02a4e035c17e2329aca12e" +
                "21d514b25466931c7d8f6a5aac84aa05" +
                "1ba30b396a0aac973d58e091473f5985" +
                "4d5c2af327cd64a62cf35abd2ba6fab4"
            ).hexToBytes()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        assertArrayEquals(expected, cipher.doFinal(plaintext))
    }

    /**
     * The info string adb passes to HKDF, to the byte.
     *
     * `sizeof(info) - 1` in adb's C excludes the terminator, so 32 bytes. This
     * is the sibling of the bug in the SPAKE2 role names, where `sizeof()`
     * *included* it - the same idiom, the opposite answer, and either one gets
     * silently absorbed into a wrong key.
     */
    @Test
    fun `the HKDF info string is exactly what adb hashes`() {
        val info = "adb pairing_auth aes-128-gcm key"
        assertEquals("adb passes sizeof(info) - 1, i.e. no terminator", 32, info.length)
        assertArrayEquals(info.toByteArray(Charsets.US_ASCII), info.toByteArray(Charsets.UTF_8))
    }

    /**
     * The derived key, recomputed by a second path.
     *
     * [Hkdf] is already pinned to RFC 5869; this checks the *application* of it -
     * salt absent, that info string, 16 bytes out - by deriving the same key
     * from the extract/expand steps directly.
     */
    @Test
    fun `the AES key is HKDF of the key material with no salt`() {
        val keyMaterial = ByteArray(64) { (it * 7).toByte() }
        val info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)

        val direct = Hkdf.expand(Hkdf.extract(keyMaterial, null), info, 16)
        val viaDerive = Hkdf.derive(keyMaterial, null, info, 16)

        assertEquals("AES-128 needs 16 bytes", 16, direct.size)
        assertArrayEquals(direct, viaDerive)
    }

    /**
     * The first message's nonce is all zeros - in every plausible reading.
     *
     * This matters more than it looks. adb builds the nonce by copying its
     * sequence counter into a zeroed 12-byte block, and the exact width of that
     * counter is not visible from the code we could read. For the **first**
     * message the counter is zero, so the nonce is twelve zero bytes whether
     * that counter is 32-bit, 64-bit, signed or not.
     *
     * The pairing exchange sends exactly one encrypted message per side. So the
     * nonce cannot be the reason pairing fails, and the search belongs
     * elsewhere - which is worth knowing before spending another evening on it.
     */
    @Test
    fun `the first nonce is all zeros whatever the counter width`() {
        val keyMaterial = ByteArray(64) { it.toByte() }

        // Two ciphers from the same material, each encrypting once: if the
        // first nonce were anything but a fixed value, these would differ.
        val first = AdbPairingCipher(keyMaterial).encrypt("peer info".toByteArray())
        val second = AdbPairingCipher(keyMaterial).encrypt("peer info".toByteArray())
        assertArrayEquals(first, second)

        // And it really is the zero nonce: encrypting by hand with twelve zero
        // bytes has to reproduce it exactly.
        val key = Hkdf.derive(
            keyMaterial = keyMaterial,
            salt = null,
            info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII),
            length = 16,
        )
        val byHand = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteArray(12)))
        }.doFinal("peer info".toByteArray())

        assertArrayEquals("our first message is not the zero-nonce one", byHand, first)
    }

    @Test
    fun `the encrypted peer info is the size adbd reported`() {
        // The daemon logged in_len=8208 for our packet. That is 8192 plus a
        // 16-byte tag, so this pins the one number we have from the far side.
        val cipher = AdbPairingCipher(ByteArray(64))
        val block = AdbPeerInfo.encodeRsaPublicKey("AAAA name@host")
        assertEquals(8192, block.size)
        assertEquals(8208, cipher.encrypt(block).size)
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
