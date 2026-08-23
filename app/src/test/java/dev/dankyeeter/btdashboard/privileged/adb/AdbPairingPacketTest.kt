package dev.dankyeeter.btdashboard.privileged.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The wire format, pinned byte by byte.
 *
 * Framing bugs are the cheapest kind to write and the most expensive to find:
 * the daemon simply stops answering, and nothing distinguishes "wrong length
 * field" from "the network went away". These tests assert the actual bytes, so
 * a mistake shows up here rather than as a timeout against a real device.
 */
class AdbPairingPacketTest {

    @Test
    fun `header is six bytes with a big-endian length`() {
        val out = ByteArrayOutputStream()
        AdbPairingPacket(AdbPairingPacket.TYPE_SPAKE2_MSG, ByteArray(0x0102)).writeTo(out)
        val bytes = out.toByteArray()

        assertEquals("header plus payload", 6 + 0x0102, bytes.size)
        assertEquals("version", 1, bytes[0].toInt())
        assertEquals("type", 0, bytes[1].toInt())
        // Big-endian here, little-endian in AdbMessage. Two byte orders in one
        // connection is exactly why this is asserted rather than assumed.
        assertArrayEquals(
            "length must be big-endian",
            byteArrayOf(0x00, 0x00, 0x01, 0x02),
            bytes.copyOfRange(2, 6),
        )
    }

    @Test
    fun `a packet survives the round trip`() {
        val original = AdbPairingPacket(AdbPairingPacket.TYPE_PEER_INFO, "payload".toByteArray())
        val out = ByteArrayOutputStream()
        original.writeTo(out)

        assertEquals(original, AdbPairingPacket.readFrom(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `refuses an unknown version`() {
        val bytes = byteArrayOf(9, 0, 0, 0, 0, 1, 42)
        assertThrows(IllegalArgumentException::class.java) {
            AdbPairingPacket.readFrom(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `refuses an empty or oversized payload`() {
        // Zero is refused by adb too: there would be nothing to read.
        val empty = byteArrayOf(1, 0, 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) {
            AdbPairingPacket.readFrom(ByteArrayInputStream(empty))
        }

        // Without the ceiling, a bad length would have the reader allocate
        // whatever the sender claimed.
        val huge = byteArrayOf(1, 0, 0x7f, -1, -1, -1)
        assertThrows(IllegalArgumentException::class.java) {
            AdbPairingPacket.readFrom(ByteArrayInputStream(huge))
        }
    }

    @Test
    fun `peer info is a fixed eight kilobyte block`() {
        // Sized to content is the obvious thing to do and does not work: adb
        // validates the length against sizeof(PeerInfo) and rejects anything
        // shorter.
        val block = AdbPeerInfo.encodeRsaPublicKey("AAAA btdashboard@android")
        assertEquals(AdbPeerInfo.SIZE, block.size)
        assertEquals(AdbPeerInfo.TYPE_RSA_PUBLIC_KEY, block[0].toInt())
        assertTrue("must be zero-padded", block.drop(25).all { it == 0.toByte() })
    }

    @Test
    fun `peer info decodes back to the key`() {
        val key = "AAAAB3NzaC1 btdashboard@android"
        val (type, decoded) = AdbPeerInfo.decode(AdbPeerInfo.encodeRsaPublicKey(key))!!
        assertEquals(AdbPeerInfo.TYPE_RSA_PUBLIC_KEY, type)
        assertEquals(key, decoded)
    }

    @Test
    fun `peer info rejects a block of the wrong size`() {
        assertNull(AdbPeerInfo.decode(ByteArray(100)))
    }
}
