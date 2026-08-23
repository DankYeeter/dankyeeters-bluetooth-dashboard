package dev.dankyeeter.btdashboard.privileged.adb.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The AES-128-GCM channel that carries the pairing payload.
 *
 * Once SPAKE2 has turned the six-digit code into shared key material, the two
 * sides use it to encrypt one message each: the peer info, which carries the
 * public key that `adbd` is being asked to trust. Everything about this class
 * has to match adb's `Aes128Gcm` byte for byte, because the far end is that
 * implementation.
 *
 * ## The two details that matter
 *
 * **The key is not the SPAKE2 output.** It is HKDF-SHA256 of that output, with
 * no salt and the info string `adb pairing_auth aes-128-gcm key`, cut to 16
 * bytes.
 *
 * **The nonce never travels.** It is a 12-byte block whose first eight bytes
 * are a little-endian counter, starting at zero and incrementing per message,
 * with separate counters for each direction. Both sides keep their own count
 * and must stay in step: nothing in the message says which nonce was used, so a
 * lost or reordered message does not decrypt and cannot be recovered from.
 *
 * That is unusual enough to be worth stating plainly - most protocols ship the
 * nonce alongside the ciphertext, and looking for it here is a good way to lose
 * an evening.
 */
internal class AdbPairingCipher(keyMaterial: ByteArray) {

    private val key = SecretKeySpec(
        Hkdf.derive(
            keyMaterial = keyMaterial,
            salt = null,
            info = INFO.toByteArray(Charsets.US_ASCII),
            length = KEY_LENGTH,
        ),
        "AES",
    )

    private var encryptSequence = 0L
    private var decryptSequence = 0L

    /** @return ciphertext with the 16-byte authentication tag appended. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonceFor(encryptSequence)))
        encryptSequence++
        return cipher.doFinal(plaintext)
    }

    /**
     * @return the plaintext, or null if the tag did not verify.
     *
     * Null rather than an exception because a failure here has exactly one
     * meaning worth acting on - the code was wrong - and callers should say so
     * rather than surface a cryptographic stack trace.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonceFor(decryptSequence)))
        decryptSequence++
        cipher.doFinal(ciphertext)
    }.getOrNull()

    private fun nonceFor(sequence: Long): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH)
        var value = sequence
        for (i in 0 until Long.SIZE_BYTES) {
            nonce[i] = (value and 0xff).toByte()
            value = value ushr 8
        }
        return nonce
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val INFO = "adb pairing_auth aes-128-gcm key"
        const val KEY_LENGTH = 16
        const val NONCE_LENGTH = 12
        const val TAG_BITS = 128
    }
}
