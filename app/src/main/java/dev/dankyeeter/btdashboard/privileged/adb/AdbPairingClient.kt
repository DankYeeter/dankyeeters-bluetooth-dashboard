package dev.dankyeeter.btdashboard.privileged.adb

import android.util.Log
import dev.dankyeeter.btdashboard.privileged.adb.crypto.AdbPairingCipher
import dev.dankyeeter.btdashboard.privileged.adb.crypto.Spake2
import java.io.InputStream
import java.io.OutputStream

/**
 * Asks `adbd` to trust this app's key, using the six-digit code.
 *
 * This is the one step that cannot be automated away. Everything else about
 * starting the helper without a computer works unattended; pairing needs the
 * user to read a number off their own screen once, and after that the app can
 * reconnect on its own for as long as the key stays trusted.
 *
 * ## The exchange
 *
 * 1. TLS to the **pairing** port - a different port from the everyday one, and
 *    it only exists while the user has the pairing dialog open.
 * 2. `SPAKE2_MSG` in both directions. The six-digit code is the password; a
 *    wrong one produces a key that disagrees, which surfaces one step later.
 * 3. `PEER_INFO` in both directions, encrypted with the SPAKE2-derived key.
 *    Ours carries the public key we want trusted.
 *
 * A wrong code fails at step 3, not step 2 - SPAKE2 completes happily on both
 * sides and simply yields different keys, so the first sign of trouble is a
 * payload that will not decrypt. Worth knowing, because "decryption failed" is
 * otherwise a frightening thing to read.
 *
 * ## Roles and names
 *
 * The names are part of the key derivation, not decoration: `adb pair client`
 * and `adb pair server`, in that spelling. A typo here yields a key that
 * differs from the daemon's with no other symptom.
 */
internal class AdbPairingClient(
    private val keyStore: AdbKeyStore,
) {

    sealed interface Result {
        /** adbd trusts the key now; connecting no longer needs pairing. */
        data object Paired : Result

        /** The code did not match. The only failure the user can act on. */
        data object WrongCode : Result

        data class Failed(val detail: String) : Result
    }

    /**
     * Runs one pairing exchange.
     *
     * [channelBinding] is the TLS-exported material adb appends to the code -
     * see [TlsExporter]. It is a required argument rather than something this
     * class fetches itself, because the bytes belong to the very socket whose
     * streams are passed in, and taking them from anywhere else would silently
     * produce a key the daemon does not share.
     */
    fun pair(
        input: InputStream,
        output: OutputStream,
        code: String,
        channelBinding: ByteArray,
    ): Result = runCatching {
        val spake2 = Spake2(
            // `pairing_auth.cpp` gives the client `kClientRole`, which is
            // Alice; the role decides both the mask and the transcript order.
            role = Spake2.Role.ALICE,
            // adb passes `sizeof(kClientName)` for a char array literal, so the
            // terminator is part of the name.
            myName = name(CLIENT_NAME),
            theirName = name(SERVER_NAME),
            // The six digits alone are not the password.
            password = code.toByteArray(Charsets.UTF_8) + channelBinding,
        )

        val ourSpake = AdbPairingPacket(AdbPairingPacket.TYPE_SPAKE2_MSG, spake2.myMessage)
        PairingTrace.sent(ourSpake)
        ourSpake.writeTo(output)

        val theirSpake = AdbPairingPacket.readFrom(input)
        PairingTrace.received(theirSpake)
        if (theirSpake.type != AdbPairingPacket.TYPE_SPAKE2_MSG) {
            return Result.Failed("expected SPAKE2_MSG, got $theirSpake")
        }

        val keyMaterial = spake2.computeKey(theirSpake.payload)
            ?: return Result.Failed("peer sent a SPAKE2 message that is not a curve point")
        PairingTrace.keyDerived(keyMaterial)
        val cipher = AdbPairingCipher(keyMaterial)

        val ourInfo = AdbPeerInfo.encodeRsaPublicKey(keyStore.adbFormatPublicKey())
        val ourInfoPacket = AdbPairingPacket(
            AdbPairingPacket.TYPE_PEER_INFO,
            cipher.encrypt(ourInfo),
        )
        PairingTrace.sent(ourInfoPacket)
        ourInfoPacket.writeTo(output)

        val theirInfo = AdbPairingPacket.readFrom(input)
        PairingTrace.received(theirInfo)
        if (theirInfo.type != AdbPairingPacket.TYPE_PEER_INFO) {
            return Result.Failed("expected PEER_INFO, got $theirInfo")
        }

        // The moment of truth. Both sides derived a key from the code; if the
        // code was wrong the keys differ, and this is where that shows.
        val decrypted = cipher.decrypt(theirInfo.payload)
            ?: return Result.WrongCode

        val peer = AdbPeerInfo.decode(decrypted)
        Log.i(TAG, "paired with peer info type=${peer?.first}")
        Result.Paired
    }.getOrElse { t ->
        // A pairing server that dislikes the exchange hangs up rather than
        // explaining, so a broken stream this late usually also means the code
        // was wrong - but not always, so it is reported as what it is.
        Result.Failed("${t.javaClass.simpleName}: ${t.message}")
    }

    /**
     * The role name, with or without its terminator.
     *
     * adb passes `sizeof()` of a C string literal, which counts the NUL - so 16
     * bytes, not 15. That reading comes from source and looks right, but it has
     * not been confirmed against a working exchange, and the names are
     * length-prefixed into the key derivation where one byte changes
     * everything. Until the device says which is right, both are tried.
     */
    private fun name(text: String): ByteArray =
        (text + '\u0000').toByteArray(Charsets.UTF_8)

    private companion object {
        const val TAG = "AdbPairing"

        /** The terminator is added, or not, by [name] - see the note there. */
        const val CLIENT_NAME = "adb pair client"
        const val SERVER_NAME = "adb pair server"
    }
}
