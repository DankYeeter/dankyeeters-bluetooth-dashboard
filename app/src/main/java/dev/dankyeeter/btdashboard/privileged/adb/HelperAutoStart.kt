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
        val endpoints = runCatching { AdbPortDiscovery(context).findAll() }
            .getOrElse { return@withContext Outcome.Broken("discovery", it.describe()) }
        if (endpoints.isEmpty()) return@withContext Outcome.NoService

        val keys = AdbKeyStore(context)
        runCatching { keys.certificate() }
            .onFailure { return@withContext Outcome.Broken("certificate", it.describe()) }
        val client = AdbTlsClient(keys)

        // Every announcement gets a turn. Stale records outlive the port they
        // describe - measured: adbd was on 35485 while mDNS still offered
        // 34797 - and a dead loopback port refuses immediately, so the cost of
        // trying is a few milliseconds each.
        var lastProblem: Outcome? = null
        for (endpoint in endpoints) {
            // Both ends have to be this phone. The connection is pinned to
            // loopback regardless; this refuses even to try when the
            // announcement came from elsewhere, so a foreign adb daemon on the
            // same Wi-Fi cannot aim the app at an arbitrary local port.
            if (!endpoint.looksLikeThisDevice()) {
                lastProblem = Outcome.Broken(
                    "identity",
                    "announcement from ${endpoint.advertisedHost} is not this device",
                )
                continue
            }

            when (val result = client.connect(endpoint)) {
                is AdbTlsClient.Result.Connected -> {
                    result.session.close()
                    return@withContext Outcome.Connected(endpoint.toString())
                }

                // Reaching the trust decision means this endpoint was the live
                // one; no later candidate can do better.
                is AdbTlsClient.Result.Untrusted ->
                    return@withContext Outcome.NeedsPairing(endpoint.toString(), result.detail)

                is AdbTlsClient.Result.Failed ->
                    lastProblem = Outcome.Broken("handshake", "${endpoint.port}: ${result.detail}")
            }
        }
        lastProblem ?: Outcome.NoService
    }

    /** Runs [attempt] and writes the verdict where a bug report can find it. */
    suspend fun attemptAndLog(): Outcome = attempt().also { outcome ->
        // Reported alongside the outcome because the next step - pairing -
        // stands or falls with it, and the answer is cheap to ask for.
        AdbPairingCapability.log()
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
