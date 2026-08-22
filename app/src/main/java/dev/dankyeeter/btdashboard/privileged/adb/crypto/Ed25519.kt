package dev.dankyeeter.btdashboard.privileged.adb.crypto

import java.math.BigInteger

/**
 * Point arithmetic on Ed25519, written by hand because nothing on Android
 * exposes it.
 *
 * ## Why this file has to exist
 *
 * ADB pairing turns the six-digit code into a shared secret with SPAKE2, and
 * BoringSSL - which is what `adbd` runs - implements SPAKE2 over **Ed25519**,
 * not over a NIST curve. Java's public crypto API can *sign* with Ed25519 on
 * recent versions, but it never exposes the group operations SPAKE2 needs:
 * adding two points, multiplying a point by a scalar, decompressing an encoded
 * point. So they are written out here.
 *
 * ## Why BigInteger, when every real implementation uses limb arithmetic
 *
 * Because this runs a handful of scalar multiplications, once, while a user
 * watches a spinner - and correctness is the only currency that matters. Field
 * arithmetic in packed 25.5-bit limbs is how you make Ed25519 fast and how you
 * make it constant-time; it is also where subtle carry bugs live, and a subtle
 * bug here fails as "pairing did not work" with nothing to grab hold of.
 *
 * ## On constant time
 *
 * `BigInteger` is not constant-time, so this code is not either. That is
 * acceptable *for this use and no other*: the secret is a six-digit code the
 * user is reading off their own screen, the peer is the same phone over
 * loopback, and the exchange happens once. Nothing here should be reused for
 * signing keys or long-lived secrets.
 */
internal object Ed25519 {

    /** Field prime, 2^255 - 19. */
    val P: BigInteger = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)

    /** Group order of the base point. */
    val L: BigInteger = BigInteger.TWO.pow(252) +
        BigInteger("27742317777372353535851937790883648493")

    /** Curve constant d = -121665/121666 mod p. */
    val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)

    /** sqrt(-1) mod p, needed to recover x during decompression. */
    val SQRT_M1: BigInteger = BigInteger.TWO.modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)

    /** The standard base point. */
    val B: Point by lazy {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val x = recoverX(y, 0) ?: error("base point is not on the curve; constants are wrong")
        Point.fromAffine(x, y)
    }

    val IDENTITY: Point = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /**
     * A curve point in extended coordinates (X:Y:Z:T), where x = X/Z, y = Y/Z
     * and T = XY/Z.
     *
     * The redundant fourth coordinate is what makes addition a fixed formula
     * with no inversions - inverting once per addition would be both slow and
     * a second place for mistakes to hide.
     */
    data class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger,
    ) {
        fun toAffine(): Pair<BigInteger, BigInteger> {
            val zInv = z.modInverse(P)
            return x.multiply(zInv).mod(P) to y.multiply(zInv).mod(P)
        }

        /**
         * The 32-byte encoding: little-endian y, with x's low bit in the top
         * bit of the last byte.
         */
        fun encode(): ByteArray {
            val (ax, ay) = toAffine()
            val bytes = ay.toLittleEndian(32)
            if (ax.testBit(0)) bytes[31] = (bytes[31].toInt() or 0x80).toByte()
            return bytes
        }

        companion object {
            fun fromAffine(x: BigInteger, y: BigInteger) =
                Point(x.mod(P), y.mod(P), BigInteger.ONE, x.multiply(y).mod(P))
        }
    }

    /** @return the point, or null if the bytes do not encode one. */
    fun decode(encoded: ByteArray): Point? {
        require(encoded.size == 32) { "an Ed25519 point is 32 bytes, got ${encoded.size}" }
        val copy = encoded.copyOf()
        val xSignBit = (copy[31].toInt() ushr 7) and 1
        copy[31] = (copy[31].toInt() and 0x7f).toByte()

        val y = copy.toBigIntegerLittleEndian()
        // Rejecting y >= p matters: the same point would otherwise have two
        // encodings, and a peer could use that to steer a comparison.
        if (y >= P) return null
        val x = recoverX(y, xSignBit) ?: return null
        return Point.fromAffine(x, y)
    }

    /**
     * Recovers x from y and the sign bit, or null when no such point exists.
     *
     * From the curve equation x² = (y² - 1) / (d·y² + 1). The candidate root is
     * checked rather than trusted - roughly half of all y values simply are not
     * on the curve, and a silently wrong x would produce a shared secret that
     * disagrees with the peer's for no visible reason.
     */
    fun recoverX(y: BigInteger, xSignBit: Int): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val numerator = (y2 - BigInteger.ONE).mod(P)
        val denominator = (D.multiply(y2) + BigInteger.ONE).mod(P)
        if (denominator.signum() == 0) return null

        val x2 = numerator.multiply(denominator.modInverse(P)).mod(P)
        if (x2.signum() == 0) return if (xSignBit == 1) null else BigInteger.ZERO

        // Candidate root for p ≡ 5 (mod 8).
        var x = x2.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
        if (x.multiply(x).mod(P) != x2) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).mod(P) != x2) return null

        if (x.testBit(0) != (xSignBit == 1)) x = P - x
        return x
    }

    fun add(a: Point, b: Point): Point {
        // Unified formula from RFC 8032: works for doubling and for the
        // identity without any special case, which removes the branch that
        // usually gets these implementations wrong.
        val a1 = (a.y - a.x).multiply(b.y - b.x).mod(P)
        val a2 = (a.y + a.x).multiply(b.y + b.x).mod(P)
        val a3 = a.t.multiply(BigInteger.TWO).multiply(D).multiply(b.t).mod(P)
        val a4 = a.z.multiply(BigInteger.TWO).multiply(b.z).mod(P)

        val e = (a2 - a1).mod(P)
        val f = (a4 - a3).mod(P)
        val g = (a4 + a3).mod(P)
        val h = (a2 + a1).mod(P)

        return Point(
            x = e.multiply(f).mod(P),
            y = g.multiply(h).mod(P),
            z = f.multiply(g).mod(P),
            t = e.multiply(h).mod(P),
        )
    }

    fun negate(p: Point): Point = Point((P - p.x).mod(P), p.y, p.z, (P - p.t).mod(P))

    fun subtract(a: Point, b: Point): Point = add(a, negate(b))

    /** Double-and-add. Not constant-time; see the class note on why that is allowed here. */
    fun scalarMultiply(scalar: BigInteger, point: Point): Point {
        var result = IDENTITY
        var addend = point
        var k = scalar.mod(L)
        while (k.signum() > 0) {
            if (k.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            k = k.shiftRight(1)
        }
        return result
    }

    fun scalarMultiplyBase(scalar: BigInteger): Point = scalarMultiply(scalar, B)

    /** Whether the point satisfies the curve equation. Used to reject peer input. */
    fun isOnCurve(p: Point): Boolean {
        val (x, y) = p.toAffine()
        val x2 = x.multiply(x).mod(P)
        val y2 = y.multiply(y).mod(P)
        val left = (y2 - x2).mod(P)
        val right = (BigInteger.ONE + D.multiply(x2).multiply(y2)).mod(P)
        return left == right
    }
}

/** Little-endian, fixed width, because every Ed25519 encoding is. */
internal fun BigInteger.toLittleEndian(size: Int): ByteArray {
    val out = ByteArray(size)
    var value = this
    val mask = BigInteger.valueOf(0xff)
    for (i in 0 until size) {
        out[i] = value.and(mask).toInt().toByte()
        value = value.shiftRight(8)
    }
    return out
}

internal fun ByteArray.toBigIntegerLittleEndian(): BigInteger {
    var value = BigInteger.ZERO
    for (i in indices.reversed()) {
        value = value.shiftLeft(8).or(BigInteger.valueOf((this[i].toLong() and 0xff)))
    }
    return value
}
