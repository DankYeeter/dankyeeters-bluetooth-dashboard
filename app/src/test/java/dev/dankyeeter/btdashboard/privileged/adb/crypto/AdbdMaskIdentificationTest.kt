package dev.dankyeeter.btdashboard.privileged.adb.crypto

import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Asks a real `adbd` message which mask it used.
 *
 * ## The idea
 *
 * A SPAKE2 message is `y·B + w·mask`, where `y` is a multiple of eight and `B`
 * has prime order L. So `y·B` lies in the prime-order subgroup, and subtracting
 * the **right** mask leaves a point there too - one for which `L·P` is the
 * identity. Subtracting the wrong mask leaves a point that still carries a
 * torsion component, and `L·P` is not the identity.
 *
 * That turns a guess into a measurement. Which of M and N the daemon used - and
 * therefore which role it takes - is written into the message it already sent,
 * and the same test settles the password-scalar convention at the same time.
 *
 * ## Where the numbers come from
 *
 * A captured exchange: the code Android displayed and the SPAKE2 message the
 * daemon sent in reply. Nothing here talks to a device; it re-reads bytes that
 * were already on the wire.
 */
class AdbdMaskIdentificationTest {

    /** Android's own log line `updateUIPairCode` for the run below. */
    private val pairingCode = "108462"

    /** What the daemon answered, from the trace. */
    private val daemonMessage =
        "f2a56f6e976cfc227cdd0c2695fbd1de400a14b79d505c0857d7948bb0e764f6".hexToBytes()

    @Test
    fun `which mask and scalar convention the daemon used`() {
        val point = Ed25519.decode(daemonMessage)
        if (point == null) {
            println("MASK the daemon message is not a curve point at all")
            return
        }
        println("MASK daemon message decodes cleanly")

        for (clearLowBits in listOf(true, false)) {
            val w = passwordScalar(pairingCode, clearLowBits)
            for ((maskName, mask) in listOf("M" to Spake2Points.M, "N" to Spake2Points.N)) {
                val unmasked = Ed25519.subtract(point, Ed25519.scalarMultiply(w, mask))
                val order = Ed25519.scalarMultiply(Ed25519.L, unmasked)
                val (x, y) = order.toAffine()
                val isPrimeOrder = x.signum() == 0 && y == BigInteger.ONE
                println(
                    "MASK clearLowBits=$clearLowBits mask=$maskName " +
                        "-> primeOrder=$isPrimeOrder",
                )
            }
        }
    }

    @Test
    fun `sanity - our own message subtracts back to a prime-order point`() {
        // Proves the test itself is sound: build a message the way we do, strip
        // our own mask, and the remainder must be prime-order. If this fails,
        // the check above says nothing about the daemon.
        val ours = Spake2(
            role = Spake2.Role.ALICE,
            myName = "adb pair client".toByteArray(),
            theirName = "adb pair server".toByteArray(),
            password = pairingCode.toByteArray(),
        )
        val point = Ed25519.decode(ours.myMessage)!!
        val w = passwordScalar(pairingCode, clearLowBits = true)
        val unmasked = Ed25519.subtract(point, Ed25519.scalarMultiply(w, Spake2Points.M))
        val (x, y) = Ed25519.scalarMultiply(Ed25519.L, unmasked).toAffine()
        println("MASK self-check primeOrder=${x.signum() == 0 && y == BigInteger.ONE}")
    }

    private fun passwordScalar(code: String, clearLowBits: Boolean): BigInteger {
        val hash = MessageDigest.getInstance("SHA-512").digest(code.toByteArray())
        var w = hash.toBigIntegerLittleEndian().mod(Ed25519.L)
        if (!clearLowBits) return w
        var order = Ed25519.L
        for (bit in 0..2) {
            if (w.testBit(bit)) w = w.add(order)
            order = order.shiftLeft(1)
        }
        return w
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
