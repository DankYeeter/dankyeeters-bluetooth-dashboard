package dev.dankyeeter.btdashboard.privileged.adb

import android.content.Context
import android.util.Log
import dev.dankyeeter.btdashboard.privileged.PrivilegedBootstrap
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

        /** adbd accepted the key and the helper command ran. */
        data class Started(val endpoint: String, val output: String) : Outcome

        /** The six-digit code did not match. The one failure a user can fix. */
        data object WrongCode : Outcome

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
        // Switch the door open before knocking on it. Wireless debugging keeps
        // closing itself - no Wi-Fi, or a USB cable plugged in - and asking the
        // user to go and re-enable it before every Activate would make "one tap
        // after a reboot" untrue.
        WirelessDebuggingSwitch(context).enable()

        val keys = AdbKeyStore(context)
        runCatching { keys.certificate() }
            .onFailure { return@withContext Outcome.Broken("certificate", it.describe()) }
        val client = AdbTlsClient(keys)

        // Verdicts reached while discovery is still running. Handing each
        // endpoint to the client the moment it resolves is what keeps the wait
        // short: the usual case answers in well under a second, and the
        // discovery window only bounds how long a *hopeless* case takes.
        var earlyVerdict: Outcome? = null
        val endpoints = runCatching {
            AdbPortDiscovery(context).findAll { endpoint ->
                if (!endpoint.looksLikeThisDevice()) return@findAll false
                when (val result = client.connect(endpoint)) {
                    is AdbTlsClient.Result.Connected -> {
                        earlyVerdict = startHelper(endpoint, result)
                        true
                    }

                    is AdbTlsClient.Result.Untrusted -> {
                        earlyVerdict = Outcome.NeedsPairing(endpoint.toString(), result.detail)
                        true
                    }

                    // A dead port is not a verdict; keep listening.
                    is AdbTlsClient.Result.Failed -> false
                }
            }
        }.getOrElse { return@withContext Outcome.Broken("discovery", it.describe()) }

        earlyVerdict?.let { return@withContext it }
        if (endpoints.isEmpty()) return@withContext Outcome.NoService

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
                is AdbTlsClient.Result.Connected ->
                    return@withContext startHelper(endpoint, result)

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

    /**
     * Pairs with the six-digit code, then starts the helper.
     *
     * The pairing service is only advertised while the user has Android's
     * pairing dialog open, so [Outcome.NoService] here usually means the dialog
     * was closed rather than anything being wrong - worth saying differently in
     * the UI than a genuine failure.
     */
    suspend fun pairThenStart(code: String): Outcome = withContext(Dispatchers.IO) {
        val endpoints = runCatching {
            AdbPortDiscovery(context).findAll(AdbPortDiscovery.SERVICE_PAIRING)
        }.getOrElse { return@withContext Outcome.Broken("discovery", it.describe()) }

        val endpoint = endpoints.firstOrNull { it.looksLikeThisDevice() }
            ?: return@withContext Outcome.NoService

        val keys = AdbKeyStore(context)
        val client = AdbTlsClient(keys)
        val pairing = AdbPairingClient(keys)

        // Both password-scalar conventions, in turn.
        //
        // BoringSSL changed how it derives this scalar - see Spake2 - and which
        // convention a given adbd follows cannot be read off the device. Both
        // produce a valid-looking exchange and a key the other side does not
        // share, so the failure is indistinguishable from a mistyped code.
        // Asking the daemon twice costs a second and answers the question the
        // only way it can be answered.
        var lastResult: AdbPairingClient.Result? = null
        for (clearLowBits in listOf(true, false)) {
            // A fresh connection each time: the pairing server closes the
            // stream once it has rejected an exchange.
            val socket = client.openPairingTls(endpoint)
                ?: return@withContext Outcome.Broken("pairing-tls", "could not reach $endpoint")

            val result = socket.use {
                pairing.pair(it.inputStream, it.outputStream, code, clearLowBits)
            }
            Log.i(TAG, "pairing with clearLowBits=$clearLowBits: $result")
            lastResult = result
            if (result is AdbPairingClient.Result.Paired) break
        }

        when (lastResult) {
            is AdbPairingClient.Result.WrongCode, null -> return@withContext Outcome.WrongCode
            is AdbPairingClient.Result.Failed ->
                return@withContext Outcome.Broken("pairing", lastResult.detail)

            AdbPairingClient.Result.Paired -> Unit
        }

        // Trusted now, so the ordinary path can do the rest. Deliberately a
        // fresh attempt rather than reusing the pairing socket: pairing and
        // command execution are different services on different ports.
        attempt()
    }

    /**
     * The payoff: adbd trusts us, so the helper starts from the phone itself.
     */
    private fun startHelper(
        endpoint: AdbPortDiscovery.Endpoint,
        connected: AdbTlsClient.Result.Connected,
    ): Outcome = connected.session.use { session ->
        val output = AdbShell.execute(session.input, session.output, helperCommand())
            ?: return Outcome.Broken("shell", "stream did not open")
        Outcome.Started(endpoint.toString(), output)
    }

    /**
     * The command that brings the helper up, minted fresh.
     *
     * Asking [PrivilegedBootstrap] rather than assembling it here: the token
     * rotates, and a copy of the command would keep working right up until it
     * quietly did not.
     */
    private fun helperCommand(): String = PrivilegedBootstrap(context).deviceShellCommand()

    private fun Throwable.describe() = "${javaClass.simpleName}: $message"

    private companion object {
        const val TAG = "HelperAutoStart"
    }
}
