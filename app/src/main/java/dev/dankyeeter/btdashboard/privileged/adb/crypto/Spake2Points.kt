package dev.dankyeeter.btdashboard.privileged.adb.crypto

import java.security.MessageDigest

/**
 * The two fixed points SPAKE2 needs, M and N.
 *
 * SPAKE2 blinds each side's public value with a multiple of a fixed group
 * element - M for one role, N for the other. The security argument requires
 * that **nobody knows their discrete logarithm**, so they cannot simply be
 * picked. BoringSSL derives them by hashing a seed string and trying to read
 * the digest as a point, hashing again whenever that fails:
 *
 * ```python
 * def genpoint(seed):
 *   v = hashlib.sha256(seed).digest()
 *   while True:
 *     try:
 *       x, y = E.decodepoint(v)
 *     except Exception:
 *       v = hashlib.sha256(v).digest()
 *       continue
 *     return (x, y)
 * ```
 *
 * That is reproduced here rather than pasting two magic constants, because the
 * derivation is the argument: anyone reading this can see where the points come
 * from and that no one chose them. The constants are still checked against
 * BoringSSL's own table in the tests - a derivation that quietly produces
 * *different* points would be far worse than a hard-coded one.
 *
 * Roughly half of all 32-byte strings are not valid points, so the loop
 * normally ends after a couple of rounds.
 */
internal object Spake2Points {

    const val SEED_M = "edwards25519 point generation seed (M)"
    const val SEED_N = "edwards25519 point generation seed (N)"

    val M: Ed25519.Point by lazy { generate(SEED_M) }
    val N: Ed25519.Point by lazy { generate(SEED_N) }

    fun generate(seed: String): Ed25519.Point {
        val sha256 = MessageDigest.getInstance("SHA-256")
        var candidate = sha256.digest(seed.toByteArray(Charsets.US_ASCII))
        repeat(MAX_ROUNDS) {
            Ed25519.decode(candidate)?.let { return it }
            candidate = sha256.digest(candidate)
        }
        // Unreachable in practice; a bound beats an endless loop inside a
        // lazy initialiser, where a hang would look like the app freezing.
        error("no valid point for seed after $MAX_ROUNDS rounds")
    }

    private const val MAX_ROUNDS = 1_000
}
