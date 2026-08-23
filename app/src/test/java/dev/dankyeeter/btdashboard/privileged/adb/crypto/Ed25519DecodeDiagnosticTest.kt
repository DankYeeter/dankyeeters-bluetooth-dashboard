package dev.dankyeeter.btdashboard.privileged.adb.crypto

import net.i2p.crypto.eddsa.math.GroupElement
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.junit.Test
import java.math.BigInteger

/**
 * Takes one refused point apart, step by step.
 *
 * Our decoder rejects roughly two messages in five that another implementation
 * produced and a third accepts. "Roughly half" points at the square-root
 * branch, so this prints each intermediate value for a message known to fail -
 * cheaper than reasoning about modular arithmetic in the abstract, and it says
 * plainly which step goes wrong.
 */
class Ed25519DecodeDiagnosticTest {

    private val spec = EdDSANamedCurveTable.getByName("Ed25519")

    /** One of the messages our decoder refused. */
    private val refused =
        "9a088ea27b3106155bf4a4e7a9297d24921240bfd5107612de311e7f4aa6c8a1".hexToBytes()

    @Test
    fun `dissect a refused point`() {
        println("DIAG encoded = ${refused.toHex()}")

        val copy = refused.copyOf()
        val signBit = (copy[31].toInt() ushr 7) and 1
        copy[31] = (copy[31].toInt() and 0x7f).toByte()
        val y = copy.toBigIntegerLittleEndian()

        println("DIAG signBit = $signBit")
        println("DIAG y < p   = ${y < Ed25519.P}")

        val y2 = y.multiply(y).mod(Ed25519.P)
        val numerator = (y2 - BigInteger.ONE).mod(Ed25519.P)
        val denominator = (Ed25519.D.multiply(y2) + BigInteger.ONE).mod(Ed25519.P)
        val x2 = numerator.multiply(denominator.modInverse(Ed25519.P)).mod(Ed25519.P)

        // Euler's criterion: x2 is a square exactly when this is 1.
        val legendre = x2.modPow((Ed25519.P - BigInteger.ONE) / BigInteger.TWO, Ed25519.P)
        println("DIAG x2 is a square = ${legendre == BigInteger.ONE} (legendre=$legendre)")

        var candidate = x2.modPow(
            (Ed25519.P + BigInteger.valueOf(3)) / BigInteger.valueOf(8),
            Ed25519.P,
        )
        println("DIAG first candidate squares back = ${candidate.multiply(candidate).mod(Ed25519.P) == x2}")
        candidate = candidate.multiply(Ed25519.SQRT_M1).mod(Ed25519.P)
        println("DIAG after sqrt(-1) squares back  = ${candidate.multiply(candidate).mod(Ed25519.P) == x2}")

        println("DIAG our decode  = ${Ed25519.decode(refused)?.encode()?.toHex() ?: "REFUSED"}")
        val theirs = runCatching { GroupElement(spec.curve, refused).toByteArray().toHex() }
        println("DIAG their decode = ${theirs.getOrElse { "REFUSED: ${it.javaClass.simpleName}" }}")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
