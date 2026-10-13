package dev.dankyeeter.btdashboard.privileged.adb.crypto

import java.math.BigInteger

/**
 * Whether the point satisfies the curve equation -x^2 + y^2 = 1 + d*x^2*y^2.
 *
 * Test-only: production never needs it, because [Ed25519.decode] already
 * returns null for anything that is not on the curve.
 */
internal fun Ed25519.isOnCurve(p: Ed25519.Point): Boolean {
    val (x, y) = p.toAffine()
    val x2 = x.multiply(x).mod(P)
    val y2 = y.multiply(y).mod(P)
    val left = (y2 - x2).mod(P)
    val right = (BigInteger.ONE + D.multiply(x2).multiply(y2)).mod(P)
    return left == right
}
