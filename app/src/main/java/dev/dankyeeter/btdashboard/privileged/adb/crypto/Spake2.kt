package dev.dankyeeter.btdashboard.privileged.adb.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over Ed25519, matching BoringSSL - which is what `adbd` runs.
 *
 * Turns a short, weak secret (the six-digit pairing code) into a strong shared
 * key, without either side ever sending the code and without an eavesdropper
 * being able to guess offline. Each side blinds its public value with a
 * multiple of a fixed point - [Spake2Points.M] for Alice, `N` for Bob - and can
 * only unblind the other's if it knows the same code.
 *
 * ## The exchange
 *
 * ```
 * w         = SHA-512(code) reduced mod L        (the password scalar)
 * x         = 8 · (random mod L)                 (private key, cofactor cleared)
 * my_msg    = x·B + w·M                          (Alice; Bob uses N)
 * shared    = x · (their_msg − w·N)              (Alice; Bob subtracts M)
 * key       = SHA-512( names, messages, shared, SHA-512(code) )
 * ```
 *
 * The multiplication by 8 is not decoration. The peer's point arrives over the
 * wire and may carry a small-order component; multiplying by a scalar that is a
 * multiple of 8 annihilates it, so a hostile peer cannot steer the shared
 * secret into a tiny subgroup. That is also why [Ed25519.scalarMultiply] does
 * not reduce its scalar - see the note there.
 *
 * ## On the password scalar
 *
 * BoringSSL once had a bug here: a missing multiplication by 8 leaked three
 * bits of the password. The fix was chosen so that it does **not** change the
 * message on the wire - it adds multiples of the group order, which leaves the
 * resulting point untouched - so the plain computation below interoperates with
 * both the old and the fixed daemon.
 */
internal class Spake2(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    password: ByteArray,
    random: SecureRandom = SecureRandom(),
) {

    enum class Role { ALICE, BOB }

    /** SHA-512 of the code, kept whole: it goes into the final transcript. */
    private val passwordHash: ByteArray = sha512(password)

    /** The same hash reduced into the scalar field. */
    private val passwordScalar: BigInteger = passwordHash.toBigIntegerLittleEndian().mod(Ed25519.L)

    private val privateKey: BigInteger = run {
        val bytes = ByteArray(64).also(random::nextBytes)
        // Reduce first, then multiply by 8. The order matters: reducing
        // afterwards would put the scalar back into a range where the cofactor
        // is no longer cleared.
        bytes.toBigIntegerLittleEndian().mod(Ed25519.L).multiply(BigInteger.valueOf(8))
    }

    private val myMask: Ed25519.Point
        get() = if (role == Role.ALICE) Spake2Points.M else Spake2Points.N

    private val theirMask: Ed25519.Point
        get() = if (role == Role.ALICE) Spake2Points.N else Spake2Points.M

    /** The 32 bytes to send. Stable for the lifetime of this object. */
    val myMessage: ByteArray = run {
        val p = Ed25519.scalarMultiplyBase(privateKey)
        val mask = Ed25519.scalarMultiply(passwordScalar, myMask)
        Ed25519.add(p, mask).encode()
    }

    /**
     * Completes the exchange.
     *
     * @return the 64-byte shared key, or null if [theirMessage] is not a point.
     *   A peer that sends nonsense gets a refusal, never a key derived from
     *   something half-parsed.
     */
    fun computeKey(theirMessage: ByteArray): ByteArray? {
        if (theirMessage.size != 32) return null
        val theirPoint = Ed25519.decode(theirMessage) ?: return null

        val unmasked = Ed25519.subtract(
            theirPoint,
            Ed25519.scalarMultiply(passwordScalar, theirMask),
        )
        val shared = Ed25519.scalarMultiply(privateKey, unmasked).encode()

        val sha = MessageDigest.getInstance("SHA-512")
        // Both sides must hash the same bytes in the same order, so the order
        // is by *role*, not by "mine first" - otherwise Alice and Bob would
        // each produce a transcript the other never sees.
        if (role == Role.ALICE) {
            sha.updateWithLengthPrefix(myName)
            sha.updateWithLengthPrefix(theirName)
            sha.updateWithLengthPrefix(myMessage)
            sha.updateWithLengthPrefix(theirMessage)
        } else {
            sha.updateWithLengthPrefix(theirName)
            sha.updateWithLengthPrefix(myName)
            sha.updateWithLengthPrefix(theirMessage)
            sha.updateWithLengthPrefix(myMessage)
        }
        sha.updateWithLengthPrefix(shared)
        sha.updateWithLengthPrefix(passwordHash)
        return sha.digest()
    }

    private fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    private companion object {
        /**
         * Length-prefixed feed: eight little-endian bytes, then the data.
         *
         * The prefix is what stops two different transcripts from hashing to
         * the same bytes - without it, moving a byte from the end of one field
         * to the start of the next would go unnoticed.
         */
        fun MessageDigest.updateWithLengthPrefix(data: ByteArray) {
            val prefix = ByteArray(8)
            var length = data.size.toLong()
            for (i in 0 until 8) {
                prefix[i] = (length and 0xff).toByte()
                length = length ushr 8
            }
            update(prefix)
            update(data)
        }
    }
}
