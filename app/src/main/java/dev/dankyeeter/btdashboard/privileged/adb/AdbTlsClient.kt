package dev.dankyeeter.btdashboard.privileged.adb

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Talks ADB to the phone's own `adbd`, over TLS, on loopback.
 *
 * This is the piece that makes the helper startable **without a computer**. The
 * helper needs shell identity, an app cannot grant itself that, and the one
 * remaining door is the debugging service the user has already switched on.
 *
 * ## The handshake, in order
 *
 * 1. Plain TCP to the port [AdbPortDiscovery] found.
 * 2. Client sends `CNXN`.
 * 3. adbd answers `STLS` - "everything after this is encrypted". On Android 11
 *    and later, wireless debugging always answers this way.
 * 4. Client echoes `STLS` and both sides upgrade the *same socket* to TLS.
 * 5. adbd asks for a client certificate. It accepts the connection only if that
 *    key is in its trusted list, which is what pairing is for.
 *
 * Step 5 is where an unpaired app is turned away, and that is the expected
 * outcome until pairing exists - [connect] reports it as [Result.Untrusted]
 * rather than as a failure, because it is the protocol working correctly.
 *
 * ## Trust, in the other direction
 *
 * The client does **not** verify adbd's certificate, and that is not laziness:
 * adbd presents a self-signed certificate that nothing can chain, the peer is
 * reached over loopback on this very device, and pairing is what establishes
 * mutual trust. Verifying here would reject every real connection.
 */
class AdbTlsClient(
    private val keyStore: AdbKeyStore,
) {

    sealed interface Result {
        /** TLS is up and adbd accepted our key; [session] is ready for commands. */
        data class Connected(val session: AdbSession) : Result

        /**
         * The handshake completed the protocol correctly and adbd refused the
         * key. Not a bug - it means "not paired yet".
         */
        data class Untrusted(val detail: String) : Result

        /** Anything that stopped the exchange before that verdict. */
        data class Failed(val detail: String) : Result
    }

    fun connect(endpoint: AdbPortDiscovery.Endpoint, timeoutMs: Int = TIMEOUT_MS): Result {
        // Second lock on the same door. Endpoint.connectHost is already fixed to
        // loopback; this refuses to proceed if that ever stops being true -
        // a bug that would otherwise turn a local-only feature into a network
        // client without anyone noticing.
        if (endpoint.connectHost != AdbPortDiscovery.LOOPBACK) {
            return Result.Failed("refusing to connect to ${endpoint.connectHost}: loopback only")
        }
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(endpoint.connectHost, endpoint.port), timeoutMs)
            socket.soTimeout = timeoutMs
            handshake(socket, endpoint)
        } catch (t: Throwable) {
            runCatching { socket.close() }
            Result.Failed("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * A bare TLS socket to the **pairing** port.
     *
     * The pairing service speaks TLS from the first byte - no `CNXN`, no
     * `STLS`, none of the ADB preamble that [connect] performs. Sending that
     * preamble here produces a handshake failure that reads like a broken
     * connection, so the two paths stay separate rather than sharing a flag.
     *
     * Certificates are exchanged but not verified on either side; the six-digit
     * code is what establishes trust, a few messages later.
     */
    fun openPairingTls(endpoint: AdbPortDiscovery.Endpoint, timeoutMs: Int = TIMEOUT_MS): SSLSocket? {
        if (endpoint.connectHost != AdbPortDiscovery.LOOPBACK) return null
        return runCatching {
            val socket = Socket()
            socket.connect(InetSocketAddress(endpoint.connectHost, endpoint.port), timeoutMs)
            socket.soTimeout = timeoutMs
            upgrade(socket, endpoint).also { it.startHandshake() }
        }.onFailure { Log.w(TAG, "pairing TLS failed", it) }.getOrNull()
    }

    private fun handshake(socket: Socket, endpoint: AdbPortDiscovery.Endpoint): Result {
        val out: OutputStream = socket.getOutputStream()
        val input: InputStream = socket.getInputStream()

        // "host::" is the banner adb itself sends. adbd does not care what is in
        // it for a TLS connection, but it must be a valid, NUL-terminated string.
        AdbMessage(
            command = AdbMessage.A_CNXN,
            arg0 = AdbMessage.VERSION,
            arg1 = AdbMessage.MAX_PAYLOAD,
            payload = "host::\u0000".toByteArray(Charsets.UTF_8),
        ).writeTo(out)

        val reply = AdbMessage.readFrom(input)
        if (reply.command != AdbMessage.A_STLS) {
            // A_AUTH here means the device is on the pre-Android-11 RSA scheme,
            // which this client does not implement: wireless debugging, the only
            // reason it exists, always uses STLS.
            return Result.Failed("expected STLS, got $reply")
        }

        AdbMessage(AdbMessage.A_STLS, AdbMessage.STLS_VERSION, 0).writeTo(out)

        val tls = upgrade(socket, endpoint)
        return try {
            tls.startHandshake()
            // adbd sends its own CNXN once it is satisfied with the key. If it
            // is not, the socket closes here instead.
            val banner = AdbMessage.readFrom(tls.inputStream)
            if (banner.command != AdbMessage.A_CNXN) {
                return Result.Failed("expected CNXN after TLS, got $banner")
            }
            Log.i(TAG, "connected to adbd: ${String(banner.payload).trim('\u0000')}")
            Result.Connected(AdbSession(tls))
        } catch (t: Throwable) {
            runCatching { tls.close() }
            val detail = "${t.javaClass.simpleName}: ${t.message}"
            if (isTrustRefusal(t)) Result.Untrusted(detail) else Result.Failed(detail)
        }
    }

    /**
     * Did the peer refuse our identity, rather than the exchange breaking?
     *
     * Measured against the real daemon: an unpaired key comes back as
     * `SSLV3_ALERT_CERTIFICATE_UNKNOWN`, a TLS alert raised *by adbd* once it
     * has looked at the certificate and not found it in its trusted list. That
     * is not a fault - it is the protocol answering the question, and calling it
     * a failure would send the user hunting for a broken connection instead of
     * pairing.
     *
     * Matched on the alert text because the platform surfaces BoringSSL's alerts
     * as a generic [javax.net.ssl.SSLException]; there is no typed exception to
     * catch. A plain disconnect right after the handshake is treated the same
     * way, since older daemons say no by hanging up.
     */
    private fun isTrustRefusal(t: Throwable): Boolean {
        val message = generateSequence(t) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .uppercase()
        return TRUST_ALERTS.any { it in message } ||
            t is java.io.EOFException ||
            t is java.net.SocketException
    }

    private fun upgrade(socket: Socket, endpoint: AdbPortDiscovery.Endpoint): SSLSocket {
        val context = SSLContext.getInstance("TLSv1.3")
        context.init(arrayOf(keyManager()), arrayOf(AcceptAnyServer), null)
        val tls = context.socketFactory.createSocket(
            socket,
            endpoint.connectHost,
            endpoint.port,
            /* autoClose = */ true,
        ) as SSLSocket
        tls.useClientMode = true
        return tls
    }

    /**
     * Offers the app's certificate whatever the server asks for.
     *
     * The default key manager picks by issuer, and adbd's certificate request
     * names no issuers it would recognise - so the default picks nothing, the
     * handshake completes anonymously, and adbd rejects it for a reason that
     * looks like "not paired" but is not. Answering unconditionally is correct
     * here: there is exactly one identity to offer.
     */
    private fun keyManager(): X509ExtendedKeyManager {
        val pair = keyStore.keyPair()
        val chain = arrayOf(keyStore.certificate())
        return object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) =
                arrayOf(ALIAS)

            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ) = ALIAS

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ) = null

            override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
            override fun getPrivateKey(alias: String?): PrivateKey = pair.private
        }
    }

    /** See the class note on why the server side is not verified. */
    private object AcceptAnyServer : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val TAG = "AdbTlsClient"
        const val ALIAS = "btdashboard"
        const val TIMEOUT_MS = 10_000

        /** TLS alerts that mean "I do not trust this certificate", not "broken". */
        val TRUST_ALERTS = listOf(
            "CERTIFICATE_UNKNOWN",
            "BAD_CERTIFICATE",
            "CERTIFICATE_REQUIRED",
            "UNKNOWN_CA",
        )
    }
}

/** A live, authenticated ADB connection. Commands come in the next step. */
class AdbSession internal constructor(private val socket: SSLSocket) : Closeable {
    val input: InputStream get() = socket.inputStream
    val output: OutputStream get() = socket.outputStream
    override fun close() {
        runCatching { socket.close() }
    }
}
