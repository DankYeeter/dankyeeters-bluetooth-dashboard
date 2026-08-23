package dev.dankyeeter.btdashboard.privileged.adb

import android.util.Log

/**
 * Writes the pairing exchange to the log, byte for byte.
 *
 * ## Why this is here
 *
 * Every layer of the pairing has now been checked against an outside
 * reference - curve arithmetic, the SPAKE2 points, HKDF, AES-GCM, the packet
 * framing - and each one is correct. The daemon still derives a different key.
 * That contradiction says the mistake is not in a value but in the *sequence*:
 * something about the exchange is not what the code assumes, and no amount of
 * checking individual functions will show it.
 *
 * So this prints what actually crosses the wire, rather than what the code
 * believes crosses it. Headers included: a surprise in the type or version
 * field would explain everything and is invisible from the outside.
 *
 * ## What it prints, and why that is acceptable here
 *
 * The SPAKE2 messages, the packet headers, and a fingerprint of the derived
 * key. **Not** the private key or the key material itself - a log is the wrong
 * place for those even in a debug build, and the fingerprint is enough to tell
 * two keys apart, which is the only question being asked.
 *
 * The pairing code is not printed either. It is on the user's screen while this
 * runs; it does not need to be in a log as well.
 */
internal object PairingTrace {

    private const val TAG = "PairingTrace"

    /** Turned on only while the exchange is being investigated. */
    var enabled: Boolean = true

    fun sent(packet: AdbPairingPacket) {
        if (!enabled) return
        Log.i(TAG, "-> ${AdbPairingPacket.typeName(packet.type)} " +
            "len=${packet.payload.size} ${packet.payload.preview()}")
    }

    fun received(packet: AdbPairingPacket) {
        if (!enabled) return
        Log.i(TAG, "<- ${AdbPairingPacket.typeName(packet.type)} " +
            "len=${packet.payload.size} ${packet.payload.preview()}")
    }

    fun keyDerived(role: String, clearLowBits: Boolean, nulNames: Boolean, key: ByteArray) {
        if (!enabled) return
        // A fingerprint, not the key: enough to see whether two runs agree,
        // useless to anyone reading the log afterwards.
        Log.i(TAG, "key $role clearLowBits=$clearLowBits nulNames=$nulNames " +
            "fp=${key.fingerprint()}")
    }

    fun note(message: String) {
        if (!enabled) return
        Log.i(TAG, message)
    }

    /**
     * First and last eight bytes. A 32-byte SPAKE2 message prints whole; the
     * 8 KB peer info does not need to, and a log line that long gets truncated
     * by the system anyway.
     */
    private fun ByteArray.preview(): String = if (size <= 32) {
        toHex()
    } else {
        "${copyOfRange(0, 8).toHex()}…${copyOfRange(size - 8, size).toHex()}"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun ByteArray.fingerprint(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(this)
            .copyOfRange(0, 6)
            .toHex()
}
