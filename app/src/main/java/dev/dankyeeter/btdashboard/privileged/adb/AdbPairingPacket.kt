package dev.dankyeeter.btdashboard.privileged.adb

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * One message on the pairing connection.
 *
 * A six-byte header, then the payload:
 *
 * ```
 * version  1  always 1
 * type     1  SPAKE2_MSG or PEER_INFO
 * payload  4  length, big-endian
 * ```
 *
 * The length is **big-endian**, unlike everything else in this client - the
 * ordinary ADB protocol is little-endian throughout, and adb writes this one
 * field with `htonl`. Two byte orders in one connection is the sort of detail
 * that produces a payload length in the hundreds of millions and a confusing
 * timeout, so it is worth stating twice.
 *
 * The packet is also not padded and the header is packed: six bytes, not eight.
 */
internal data class AdbPairingPacket(
    val type: Int,
    val payload: ByteArray,
) {

    fun writeTo(out: OutputStream) {
        val header = ByteArray(HEADER_SIZE)
        header[0] = VERSION.toByte()
        header[1] = type.toByte()
        val length = payload.size
        header[2] = (length ushr 24).toByte()
        header[3] = (length ushr 16).toByte()
        header[4] = (length ushr 8).toByte()
        header[5] = length.toByte()
        out.write(header)
        out.write(payload)
        out.flush()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbPairingPacket) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type + payload.contentHashCode()

    override fun toString(): String = "${typeName(type)}(${payload.size}B)"

    companion object {
        const val HEADER_SIZE = 6
        const val VERSION = 1

        /** From adb's pairing.proto. */
        const val TYPE_SPAKE2_MSG = 0
        const val TYPE_PEER_INFO = 1

        /** adb's own ceiling: kMaxPeerInfoSize * 2. */
        const val MAX_PAYLOAD = 8192 * 2

        fun readFrom(input: InputStream): AdbPairingPacket {
            val data = DataInputStream(input)
            val header = ByteArray(HEADER_SIZE).also(data::readFully)

            val version = header[0].toInt() and 0xff
            require(version == VERSION) { "unsupported pairing packet version $version" }

            val type = header[1].toInt() and 0xff
            val length = ((header[2].toInt() and 0xff) shl 24) or
                ((header[3].toInt() and 0xff) shl 16) or
                ((header[4].toInt() and 0xff) shl 8) or
                (header[5].toInt() and 0xff)

            // adb rejects both zero and oversize here, and so does this: a
            // length of zero means the peer sent nothing to read, and an
            // oversize one would have this allocate whatever a hostile - or
            // simply misaligned - sender asked for.
            require(length in 1..MAX_PAYLOAD) { "implausible pairing payload length: $length" }

            return AdbPairingPacket(type, ByteArray(length).also(data::readFully))
        }

        fun typeName(type: Int): String = when (type) {
            TYPE_SPAKE2_MSG -> "SPAKE2_MSG"
            TYPE_PEER_INFO -> "PEER_INFO"
            else -> "type$type"
        }
    }
}

/**
 * What each side tells the other about itself: the key it wants trusted.
 *
 * A fixed 8192-byte block - one byte of type, then the data, zero-padded to
 * length. Fixed rather than sized to content, because adb validates the length
 * against `sizeof(PeerInfo)` and rejects anything shorter. Sending only the
 * bytes that carry meaning is the obvious thing to do and does not work.
 */
internal object AdbPeerInfo {

    const val SIZE = 8192
    const val TYPE_RSA_PUBLIC_KEY = 0
    const val TYPE_DEVICE_GUID = 1

    /**
     * @param publicKey the key in adb's own format: base64 of the public key
     *   blob, a space, then a name. That is what lands in the device's trusted
     *   list, and the name is what the user sees when reviewing it.
     */
    fun encodeRsaPublicKey(publicKey: String): ByteArray {
        val body = publicKey.toByteArray(Charsets.UTF_8)
        require(body.size <= SIZE - 1) { "public key does not fit in a PeerInfo block" }
        return ByteArray(SIZE).also {
            it[0] = TYPE_RSA_PUBLIC_KEY.toByte()
            body.copyInto(it, 1)
        }
    }

    /** @return type and payload, with the zero padding removed. */
    fun decode(block: ByteArray): Pair<Int, String>? {
        if (block.size != SIZE) return null
        val type = block[0].toInt() and 0xff
        val end = block.indexOfFirst(1) { it == 0.toByte() }
        return type to String(block, 1, end - 1, Charsets.UTF_8)
    }

    private inline fun ByteArray.indexOfFirst(from: Int, predicate: (Byte) -> Boolean): Int {
        for (i in from until size) if (predicate(this[i])) return i
        return size
    }
}
