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

    fun pair(input: InputStream, output: OutputStream, code: String): Result = runCatching {
        val spake2 = Spake2(
            // Alice is the initiator, and the pairing client is the side that
            // speaks first. The role decides which of M and N masks our value,
            // so getting it backwards yields a key the daemon does not share.
            role = Spake2.Role.ALICE,
            myName = CLIENT_NAME.toByteArray(Charsets.UTF_8),
            theirName = SERVER_NAME.toByteArray(Charsets.UTF_8),
            password = code.toByteArray(Charsets.UTF_8),
        )

        AdbPairingPacket(AdbPairingPacket.TYPE_SPAKE2_MSG, spake2.myMessage).writeTo(output)
        val theirSpake = AdbPairingPacket.readFrom(input)
        if (theirSpake.type != AdbPairingPacket.TYPE_SPAKE2_MSG) {
            return Result.Failed("expected SPAKE2_MSG, got $theirSpake")
        }

        val keyMaterial = spake2.computeKey(theirSpake.payload)
            ?: return Result.Failed("peer sent a SPAKE2 message that is not a curve point")
        val cipher = AdbPairingCipher(keyMaterial)

        val ourInfo = AdbPeerInfo.encodeRsaPublicKey(keyStore.adbFormatPublicKey())
        AdbPairingPacket(
            AdbPairingPacket.TYPE_PEER_INFO,
            cipher.encrypt(ourInfo),
        ).writeTo(output)

        val theirInfo = AdbPairingPacket.readFrom(input)
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

    private companion object {
        const val TAG = "AdbPairing"
        const val CLIENT_NAME = "adb pair client"
        const val SERVER_NAME = "adb pair server"
    }
}
