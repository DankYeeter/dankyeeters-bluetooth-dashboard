package dev.dankyeeter.btdashboard.privileged.adb

import android.util.Log
import org.conscrypt.Conscrypt
import javax.net.ssl.SSLSocket

/**
 * Keying material exported from a finished TLS connection (RFC 5705).
 *
 * ## Why pairing needs this
 *
 * The pairing code alone is not the SPAKE2 password. `adb` appends 64 bytes
 * exported from the TLS connection to it:
 *
 * ```c
 * std::vector<uint8_t> exportedKeyMaterial = tls_->ExportKeyingMaterial(64);
 * pswd_.insert(pswd_.end(), exportedKeyMaterial.begin(), exportedKeyMaterial.end());
 * auth_ = CreatePairingAuthPtr(role_, pswd_);
 * ```
 *
 * That binds the password to one specific TLS channel, so a relay that forwards
 * the six digits still cannot pair - its two TLS connections export different
 * bytes. It also means an implementation can get every part of SPAKE2 exactly
 * right and still derive a key the daemon does not share, which is what happened
 * here: the exchange looked perfect on the wire and adbd called it a wrong code.
 *
 * ## The label, exactly
 *
 * `adb` passes `sizeof(kExportedKeyLabel)` for a `char[]` literal, so the length
 * **includes** the terminator - ten bytes, not nine. The same idiom appears in
 * the SPAKE2 role names, and there it has already cost one wrong key.
 *
 * ## Why Conscrypt is bundled rather than borrowed
 *
 * The platform ships this exact method and refuses to hand it over: reflection
 * onto `com.android.org.conscrypt.Conscrypt#exportKeyingMaterial` is
 * `domain=core-platform, api=blocked`, and the runtime denies it outright rather
 * than warning. That was measured on device, not inferred - all three shapes of
 * the call resolve and all three are denied.
 *
 * So the app carries its own Conscrypt and, importantly, creates the pairing
 * socket with it (see [AdbTlsClient]). The exporter only works on a socket that
 * this provider made; handing it a platform socket fails.
 */
internal object TlsExporter {

    private const val TAG = "TlsExporter"

    /** `adb-label` plus the terminator that `sizeof` counted. Ten bytes. */
    private const val LABEL = "adb-label\u0000"

    /** `kExportedKeySize` in adb's pairing_connection.cpp. */
    const val LENGTH = 64

    /** The provider the pairing socket must be built with. */
    val provider: java.security.Provider by lazy { Conscrypt.newProvider() }

    /**
     * Exports [LENGTH] bytes bound to [socket]'s finished handshake, or null if
     * the socket did not come from our own Conscrypt.
     */
    fun exportKeyingMaterial(socket: SSLSocket): ByteArray? {
        if (!Conscrypt.isConscrypt(socket)) {
            // Worth naming rather than letting the call throw: it means the
            // socket was built with the platform provider, which is a wiring
            // mistake here and not a device limitation.
            Log.w(TAG, "socket is not ours: ${socket.javaClass.name}")
            return null
        }
        return runCatching { Conscrypt.exportKeyingMaterial(socket, LABEL, null, LENGTH) }
            .onFailure { Log.w(TAG, "export failed", it) }
            .getOrNull()
            ?.takeIf { it.size == LENGTH }
    }

    /**
     * Whether the bundled Conscrypt loaded at all.
     *
     * Asked before the user has opened the pairing dialog: codes expire and the
     * dialog has to be reopened by hand, so a failure discovered afterwards
     * costs an attempt every time.
     */
    fun isAvailable(): Boolean = runCatching { provider.name.isNotEmpty() }
        .onFailure { Log.w(TAG, "bundled Conscrypt did not load", it) }
        .getOrDefault(false)
        .also { Log.i(TAG, "TLS exporter available: $it") }
}
