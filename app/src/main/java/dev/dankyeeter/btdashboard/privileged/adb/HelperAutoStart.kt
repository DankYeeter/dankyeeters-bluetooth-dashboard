package dev.dankyeeter.btdashboard.privileged.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tries to start the privileged helper without a computer.
 *
 * The helper needs shell identity; an app cannot grant itself that, and the one
 * remaining door is the debugging service the user has already switched on.
 * This walks up to that door and reports exactly how far it got, because every
 * step can fail for a different reason and "it did not work" is not something
 * anyone can act on.
 *
 * Nothing here starts the helper yet. Until the app's key is paired, `adbd`
 * refuses the connection - which is the *expected* outcome and is reported as
 * [Outcome.NeedsPairing] rather than as an error.
 */
class HelperAutoStart(private val context: Context) {

    sealed interface Outcome {
        /** Wireless debugging is off, or mDNS said nothing in time. */
        data object NoService : Outcome

        /** Reached adbd; it does not know this app's key yet. Pairing is next. */
        data class NeedsPairing(val endpoint: String, val detail: String) : Outcome

        /** adbd accepted the key. Running the helper command comes next. */
        data class Connected(val endpoint: String) : Outcome

        /** Something broke before a verdict was possible. */
        data class Broken(val step: String, val detail: String) : Outcome
    }

    /**
     * Runs on IO throughout: mDNS discovery blocks, the socket blocks, and the
     * TLS handshake blocks. Enforced here rather than left to callers - the
     * natural caller is a button press, and Compose hands that a main-thread
     * scope, which turned the first real attempt into NetworkOnMainThread.
     */
    suspend fun attempt(): Outcome = withContext(Dispatchers.IO) {
        val endpoint = runCatching { AdbPortDiscovery(context).find() }
            .getOrElse { return@withContext Outcome.Broken("discovery", it.describe()) }
            ?: return@withContext Outcome.NoService

        // Both ends have to be this phone. The connection itself is pinned to
        // loopback and cannot leave the device; this refuses even to try when
        // the announcement came from somewhere else, so a foreign adb daemon on
        // the same Wi-Fi cannot aim the app at an arbitrary local port.
        if (!endpoint.looksLikeThisDevice()) {
            return@withContext Outcome.Broken(
                "identity",
                "announcement came from ${endpoint.advertisedHost}, which is not this device",
            )
        }

        val keys = AdbKeyStore(context)
        runCatching { keys.certificate() }
            .onFailure { return@withContext Outcome.Broken("certificate", it.describe()) }

        when (val result = AdbTlsClient(keys).connect(endpoint)) {
            is AdbTlsClient.Result.Connected -> {
                result.session.close()
                Outcome.Connected(endpoint.toString())
            }

            is AdbTlsClient.Result.Untrusted ->
                Outcome.NeedsPairing(endpoint.toString(), result.detail)

            is AdbTlsClient.Result.Failed ->
                Outcome.Broken("handshake", result.detail)
        }
    }

    /** Runs [attempt] and writes the verdict where a bug report can find it. */
    suspend fun attemptAndLog(): Outcome = attempt().also { outcome ->
        when (outcome) {
            is Outcome.Broken -> Log.w(TAG, "auto-start failed at ${outcome.step}: ${outcome.detail}")
            else -> Log.i(TAG, "auto-start outcome: $outcome")
        }
    }

    private fun Throwable.describe() = "${javaClass.simpleName}: $message"

    private companion object {
        const val TAG = "HelperAutoStart"
    }
}
