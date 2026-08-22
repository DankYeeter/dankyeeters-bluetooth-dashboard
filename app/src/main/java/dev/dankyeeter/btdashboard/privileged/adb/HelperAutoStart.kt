package dev.dankyeeter.btdashboard.privileged.adb

import android.content.Context
import android.util.Log

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

    suspend fun attempt(): Outcome {
        val endpoint = runCatching { AdbPortDiscovery(context).find() }
            .getOrElse { return Outcome.Broken("discovery", it.describe()) }
            ?: return Outcome.NoService

        val keys = AdbKeyStore(context)
        runCatching { keys.certificate() }
            .onFailure { return Outcome.Broken("certificate", it.describe()) }

        return when (val result = AdbTlsClient(keys).connect(endpoint)) {
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
