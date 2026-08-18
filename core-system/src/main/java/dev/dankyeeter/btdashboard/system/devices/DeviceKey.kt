package dev.dankyeeter.btdashboard.system.devices

import java.security.MessageDigest
import java.util.Locale

/**
 * Stable, privacy-preserving identifier for a Bluetooth device.
 *
 * We never persist a raw MAC address. A MAC is a permanent hardware identifier;
 * once it is in a DataStore file it is also in every backup export, and the
 * export is a plain JSON file the user may hand around. Hashing keeps the one
 * property we actually need — "is this the same device as last time?" — and
 * throws away the one we do not need.
 *
 * SHA-256 over the upper-cased, colon-normalised address, truncated to 128 bits
 * of hex. A 48-bit MAC is trivially brute-forceable from a hash, so this is not
 * anonymisation and is not claimed to be: it is a deliberate choice not to keep
 * the identifier lying around in the clear.
 */
object DeviceKey {

    /** Returns null for anything that is not a plausible BT address. */
    fun fromAddress(address: String?): String? {
        val normalised = normalise(address) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Upper-case, colon-separated form. Accepts the dash- and dot-separated
     * spellings some OEM broadcasts use, so the same physical device never ends
     * up with two different keys.
     */
    fun normalise(address: String?): String? {
        if (address.isNullOrBlank()) return null
        val hex = address.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
        if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
        return hex.chunked(2).joinToString(":")
    }
}
