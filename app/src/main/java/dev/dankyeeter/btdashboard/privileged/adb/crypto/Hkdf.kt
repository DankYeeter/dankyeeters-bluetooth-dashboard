package dev.dankyeeter.btdashboard.privileged.adb.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (RFC 5869), because Android does not offer one.
 *
 * ADB pairing does not use the SPAKE2 output as an AES key directly - it runs
 * it through HKDF-SHA256 first, with a fixed info string and no salt. Both
 * sides must derive the identical key, so this has to match the standard
 * exactly rather than approximately.
 *
 * `javax.crypto` has HMAC but no HKDF, and the platform's own implementation is
 * internal. It is twenty lines: extract, then expand.
 */
internal object Hkdf {

    private const val ALGORITHM = "HmacSHA256"
    private const val HASH_LENGTH = 32

    /**
     * @param salt null or empty means a string of zero bytes, per RFC 5869 §2.2.
     *   ADB passes none, which is what that clause is for.
     */
    fun derive(
        keyMaterial: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        length: Int,
    ): ByteArray = expand(extract(keyMaterial, salt), info, length)

    /** PRK = HMAC(salt, keyMaterial). Note the salt is the *key* here, not the data. */
    fun extract(keyMaterial: ByteArray, salt: ByteArray?): ByteArray {
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LENGTH) else salt
        return Mac.getInstance(ALGORITHM).run {
            init(SecretKeySpec(actualSalt, ALGORITHM))
            doFinal(keyMaterial)
        }
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * HASH_LENGTH) { "HKDF cannot expand to $length bytes" }
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(prk, ALGORITHM)) }

        val output = ByteArray(length)
        var previous = ByteArray(0)
        var position = 0
        var counter = 1
        while (position < length) {
            // T(n) = HMAC(PRK, T(n-1) | info | n), with n as a single byte
            // starting at 1 - the counter is part of the input, not a suffix
            // on the output, which is the detail people get wrong.
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()

            val take = minOf(previous.size, length - position)
            previous.copyInto(output, position, 0, take)
            position += take
            counter++
        }
        return output
    }
}
