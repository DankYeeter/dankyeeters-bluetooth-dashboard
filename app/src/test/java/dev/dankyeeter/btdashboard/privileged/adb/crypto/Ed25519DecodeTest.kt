package dev.dankyeeter.btdashboard.privileged.adb.crypto

import io.github.muntashirakon.crypto.spake2.Spake2Context
import io.github.muntashirakon.crypto.spake2.Spake2Role
import net.i2p.crypto.eddsa.math.GroupElement
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Can we read a point that another implementation wrote?
 *
 * The full SPAKE2 comparison fails with "our side refused their message" -
 * [Ed25519.decode] returns null for a message the reference just produced. That
 * is a decoding bug, not a key-derivation one, and it is worth its own test
 * because the two look identical from the outside: both end as "pairing
 * failed".
 *
 * Many messages are tried rather than one. The private key inside SPAKE2 is
 * random, so a decoder that rejects, say, one point in eight would pass a
 * single-shot test most of the time and fail in the field - which is exactly
 * the kind of intermittent failure that costs days.
 */
class Ed25519DecodeTest {

    private val spec = EdDSANamedCurveTable.getByName("Ed25519")

    @Test
    fun `every message the reference produces decodes`() {
        val refused = mutableListOf<String>()
        val disagreed = mutableListOf<String>()

        repeat(ROUNDS) {
            val bob = Spake2Context(
                Spake2Role.Bob,
                "adb pair server".toByteArray(),
                "adb pair client".toByteArray(),
            )
            val message = bob.generateMessage("795583".toByteArray())

            val ours = Ed25519.decode(message)
            if (ours == null) {
                refused += message.toHex()
                return@repeat
            }
            // Decoding is only right if it round-trips to the same bytes; a
            // decoder can accept a point and still land on the wrong one.
            if (!ours.encode().contentEquals(message)) disagreed += message.toHex()
        }

        assertTrue(
            "our decoder refused ${refused.size} of $ROUNDS reference messages, " +
                "first: ${refused.firstOrNull()}",
            refused.isEmpty(),
        )
        assertTrue(
            "our decoder accepted but re-encoded differently for ${disagreed.size} " +
                "messages, first: ${disagreed.firstOrNull()}",
            disagreed.isEmpty(),
        )
    }

    @Test
    fun `the reference messages are points EdDSA-Java also accepts`() {
        // Guards against blaming ourselves for a message that is genuinely not
        // a point: if a third implementation reads it, ours should too.
        val bob = Spake2Context(
            Spake2Role.Bob,
            "adb pair server".toByteArray(),
            "adb pair client".toByteArray(),
        )
        val message = bob.generateMessage("795583".toByteArray())
        val referencePoint = GroupElement(spec.curve, message)
        assertNotNull(referencePoint)
        assertNotNull("ours refused what EdDSA-Java accepted", Ed25519.decode(message))
    }

    @Test
    fun `points with a high y are handled the same way`() {
        // The one place our decoder is deliberately stricter than the classic
        // reference code: it rejects y >= p, because otherwise a point would
        // have two encodings. Worth pinning so the choice is visible if it ever
        // turns out to matter on the wire.
        val encoded = Ed25519.P.toLittleEndian(32)
        assertTrue("y = p must be refused", Ed25519.decode(encoded) == null)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        /** Enough rounds that a one-in-eight rejection cannot pass by luck. */
        const val ROUNDS = 40
    }
}

