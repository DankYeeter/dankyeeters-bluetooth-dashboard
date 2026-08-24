package dev.dankyeeter.btdashboard.privileged.adb

import android.util.Log
import javax.net.ssl.SSLContext

/**
 * Can this platform do what ADB pairing requires?
 *
 * Pairing is the last missing piece, and it is not one problem but two. SPAKE2
 * turns the six-digit code into a shared secret - that part is arithmetic and
 * can be written. The shared secret is then used as a **TLS pre-shared key**,
 * and that part cannot be written: it needs the TLS stack to support PSK, and
 * `javax.net.ssl` has no public API for it.
 *
 * Android's Conscrypt once carried `PSKKeyManager` for the TLS 1.2 PSK cipher
 * suites, hidden but reachable by reflection. If it is gone - or if adbd wants
 * TLS 1.3 session-based PSK, which has no Java equivalent at all - then a
 * pure-Kotlin pairing implementation is impossible and the feature needs a
 * native component instead.
 *
 * That is an expensive thing to discover late, so this asks first. It changes
 * nothing and connects to nothing; it only reports what the platform offers.
 */
object AdbPairingCapability {

    fun report(): String = buildString {
        // First, because without it nothing else here matters: pairing derives
        // its password from TLS-exported keying material, and the platform
        // blocks that call outright. See [TlsExporter].
        append("tlsExporter=")
        append(if (TlsExporter.isAvailable()) "yes" else "NO")
        append(" pskKeyManager=")
        append(
            runCatching { Class.forName("com.android.org.conscrypt.PSKKeyManager").simpleName }
                .getOrElse { "absent" },
        )

        val suites = runCatching {
            val context = SSLContext.getInstance("TLS").apply { init(null, null, null) }
            context.supportedSSLParameters.cipherSuites.filter { "PSK" in it }
        }.getOrDefault(emptyList())

        append(" pskCipherSuites=")
        append(if (suites.isEmpty()) "none" else suites.joinToString(","))

        append(" protocols=")
        append(
            runCatching {
                val context = SSLContext.getInstance("TLS").apply { init(null, null, null) }
                context.supportedSSLParameters.protocols.joinToString(",")
            }.getOrDefault("unknown"),
        )
    }

    fun log() = Log.i(TAG, report())

    private const val TAG = "AdbPairingCap"
}
