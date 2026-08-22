package dev.dankyeeter.btdashboard.privileged.adb

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One ADB wire message.
 *
 * The app speaks ADB to the phone's *own* `adbd` over loopback, because that is
 * the only way to start a shell-uid helper without a computer attached. This is
 * the bottom of that stack: a 24-byte little-endian header followed by an
 * optional payload.
 *
 * ```
 * command      4  the constant below
 * arg0         4
 * arg1         4
 * data_length  4  payload bytes that follow
 * data_crc32   4  plain sum of payload bytes, not a real CRC
 * magic        4  command xor 0xffffffff
 * ```
 *
 * The `magic` field is the protocol's own sanity check, and the "crc" is a byte
 * sum despite the name - both are copied from AOSP's `adb_protocol` rather than
 * invented here, because a wrong guess would look exactly like a network fault.
 */
internal data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = EMPTY,
) {

    fun writeTo(out: OutputStream) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        header.putInt(payloadChecksum(payload))
        header.putInt(command.inv())
        out.write(header.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    /** Data class with an array field; equals/hashCode must compare contents. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbMessage) return false
        return command == other.command &&
            arg0 == other.arg0 &&
            arg1 == other.arg1 &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String = "${commandName(command)}(arg0=$arg0, arg1=$arg1, ${payload.size}B)"

    companion object {
        const val HEADER_SIZE = 24

        // ASCII, little-endian, exactly as AOSP spells them.
        const val A_CNXN = 0x4e584e43 // "CNXN"
        const val A_STLS = 0x534c5453 // "STLS"
        const val A_AUTH = 0x48545541 // "AUTH"
        const val A_OPEN = 0x4e45504f // "OPEN"
        const val A_OKAY = 0x59414b4f // "OKAY"
        const val A_CLSE = 0x45534c43 // "CLSE"
        const val A_WRTE = 0x45545257 // "WRTE"

        /** Protocol version the client claims. */
        const val VERSION = 0x0100_0000

        /** STLS handshake version; unrelated to [VERSION] despite looking alike. */
        const val STLS_VERSION = 0x0100_0000

        /** Largest payload we accept, matching what modern adbd offers. */
        const val MAX_PAYLOAD = 1024 * 1024

        private val EMPTY = ByteArray(0)

        fun readFrom(input: InputStream): AdbMessage {
            val data = DataInputStream(input)
            val header = ByteArray(HEADER_SIZE)
            data.readFully(header)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val length = buffer.int
            val checksum = buffer.int
            val magic = buffer.int

            require(magic == command.inv()) {
                "not an ADB message: magic ${Integer.toHexString(magic)} does not match " +
                    "command ${Integer.toHexString(command)}"
            }
            require(length in 0..MAX_PAYLOAD) { "implausible payload length: $length" }

            val payload = if (length == 0) EMPTY else ByteArray(length).also(data::readFully)
            // adbd stopped filling this in for some commands; a mismatch is only
            // worth knowing about, never worth dropping a valid message over.
            require(checksum == 0 || checksum == payloadChecksum(payload)) {
                "payload checksum mismatch on ${commandName(command)}"
            }
            return AdbMessage(command, arg0, arg1, payload)
        }

        /** Not a CRC at all: AOSP sums the unsigned bytes. */
        private fun payloadChecksum(payload: ByteArray): Int {
            var sum = 0
            for (byte in payload) sum += byte.toInt() and 0xff
            return sum
        }

        fun commandName(command: Int): String = when (command) {
            A_CNXN -> "CNXN"
            A_STLS -> "STLS"
            A_AUTH -> "AUTH"
            A_OPEN -> "OPEN"
            A_OKAY -> "OKAY"
            A_CLSE -> "CLSE"
            A_WRTE -> "WRTE"
            else -> "0x${Integer.toHexString(command)}"
        }
    }
}
