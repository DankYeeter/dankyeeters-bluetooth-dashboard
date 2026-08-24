package dev.dankyeeter.btdashboard.privileged.adb

import android.content.Context
import android.util.Log
import dev.dankyeeter.btdashboard.privileged.PrivilegedBootstrap
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import dev.dankyeeter.btdashboard.privileged.PrivilegedContract
import dev.dankyeeter.btdashboard.privileged.adb.crypto.Spake2
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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

        /**
         * Wireless debugging cannot come up because there is no Wi-Fi.
         *
         * Distinguished from [NoService] because it is the one failure the user
         * can fix in five seconds and would otherwise never guess at: on mobile
         * data the whole activation is impossible, and every symptom of that
         * looks like a broken app rather than a missing network.
         */
        data object NoWifi : Outcome

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
        // Asked here, before anything the user has to do by hand: pairing needs
        // keying material exported from the TLS connection, and if this build
        // will not give it up, saying so now is far better than consuming a
        // pairing code to find out.
        TlsExporter.isAvailable()

        // Switch the door open before knocking on it. Wireless debugging keeps
        // closing itself - no Wi-Fi, or a USB cable plugged in - and asking the
        // user to go and re-enable it before every Activate would make "one tap
        // after a reboot" untrue.
        //
        // Whether *we* opened it is worth remembering: it decides whether we
        // are allowed to close it again at the end. Someone who switched
        // wireless debugging on themselves - for their own machine, say - would
        // not thank an equaliser for switching it back off underneath them.
        val debugging = WirelessDebuggingSwitch(context)
        val alreadyOpen = debugging.isEnabled()
        if (!debugging.enable() && debugging.canEnable()) {
            // We were allowed to write the setting and Android still refused to
            // bring wireless debugging up. In practice that means one thing:
            // there is no Wi-Fi, and adbd will not listen over mobile data.
            //
            // Worth its own answer rather than falling through to a discovery
            // that is certain to find nothing - the user would see a slow,
            // unexplained failure instead of a one-line fix.
            return@withContext Outcome.NoWifi
        }

        val keys = AdbKeyStore(context)
        runCatching { keys.certificate() }
            .onFailure { return@withContext Outcome.Broken("certificate", it.describe()) }
        val client = AdbTlsClient(keys)

        // Verdicts reached while discovery is still running. Handing each
        // endpoint to the client the moment it resolves is what keeps the wait
        // short: the usual case answers in well under a second, and the
        // discovery window only bounds how long a *hopeless* case takes.
        // Written by the discovery callbacks, read here. Those callbacks run on
        // other threads - that is the whole point of trying each endpoint the
        // moment it resolves - so a plain `var` is not merely untidy: the write
        // is not guaranteed to be visible to this thread at all. It was not.
        // The command went out, the helper came up, and the caller read null,
        // which sent the outcome down the "nothing happened" path and skipped
        // everything that was supposed to follow a successful start.
        val earlyVerdict = AtomicReference<Outcome?>(null)

        // Exactly one endpoint may start a helper. Two of them answer on this
        // device, both used to win, and the second helper's first act was to
        // retire the first - an entire runtime started and thrown away.
        val helperClaimed = AtomicBoolean(false)
        val endpoints = runCatching {
            AdbPortDiscovery(context).findAll { endpoint ->
                if (!endpoint.looksLikeThisDevice()) return@findAll false
                when (val result = client.connect(endpoint)) {
                    is AdbTlsClient.Result.Connected -> {
                        if (helperClaimed.compareAndSet(false, true)) {
                            earlyVerdict.compareAndSet(null, startHelper(endpoint, result))
                        }
                        true
                    }

                    is AdbTlsClient.Result.Untrusted -> {
                        earlyVerdict.compareAndSet(
                            null,
                            Outcome.NeedsPairing(endpoint.toString(), result.detail),
                        )
                        true
                    }

                    // A dead port is not a verdict; keep listening.
                    is AdbTlsClient.Result.Failed -> false
                }
            }
        }.getOrElse { return@withContext Outcome.Broken("discovery", it.describe()) }

        // Issuing the command is not the same thing as the helper running, and
        // treating them as one hid a real failure for an entire debugging
        // round: the command went out cleanly, the daemon killed the helper
        // moments later, and the app reported success to a user whose EQ was
        // dead. Confirmed here rather than inside the discovery callback,
        // which cannot suspend.
        earlyVerdict.get()?.let { verdict ->
            if (verdict !is Outcome.Started) return@withContext verdict
            val arrived = withTimeoutOrNull(HELPER_ARRIVAL_TIMEOUT_MS) {
                PrivilegedConnection.service.first { it != null }
            } != null
            return@withContext if (arrived) {
                // Make sure of the permission before trying to use it.
                //
                // The app also asks for it from a collector on the helper
                // connection, which is what covers every other way a helper can
                // appear. That collector is asynchronous, though, and the very
                // next line needs the permission to already be there - so it is
                // requested here too. Idempotent and cheap: when the permission
                // is held this is a local read and returns immediately.
                val granted = PrivilegedBootstrap(context).grantSecureSettings()
                Log.i(TAG, "secure settings after activation: $granted")

                // Door shut. The helper is detached and talks over Binder, so
                // it does not care that adbd is gone; what goes away is a
                // pairing port and a connect port standing open on the network
                // for the rest of the day.
                //
                // Only what this app opened, and only once it may actually
                // close it - asking without the permission would silently fail
                // and leave the port up while the log said nothing.
                if (!alreadyOpen && granted) debugging.disable()
                verdict
            } else {
                Outcome.Broken(
                    "helper",
                    "the command was accepted but the helper never connected; " +
                        "its output is in ${PrivilegedContract.HELPER_LOG_PATH}",
                )
            }
        }
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

        // One attempt, with every parameter taken from adb's own source.
        //
        // This used to try sixteen combinations, because the SPAKE2 role, the
        // password-scalar convention and the name terminators were all guesses
        // and each wrong guess looks exactly like a mistyped code. Reading
        // `pairing_auth.cpp` and BoringSSL's `spake25519.c` settled all of them
        // - client is Alice, Alice masks with M, `sizeof` includes the name
        // terminators, the scalar hack is on - so there is nothing left to
        // search over, and a loop that retries a known-correct exchange only
        // burns the user's pairing window.
        //
        // The password is *not* the six digits. adb appends 64 bytes exported
        // from the TLS connection to them, binding the exchange to this channel;
        // see [TlsExporter]. Without that the SPAKE2 side can be flawless and
        // the daemon still derives a different key.
        val socket = client.openPairingTls(endpoint)
            ?: return@withContext Outcome.Broken("pairing-tls", "could not reach $endpoint")

        val lastResult: AdbPairingClient.Result = socket.use {
            val binding = TlsExporter.exportKeyingMaterial(it)
                ?: return@withContext Outcome.Broken(
                    "tls-exporter",
                    "this Android build does not expose RFC 5705 keying material",
                )
            pairing.pair(it.inputStream, it.outputStream, code, binding)
        }
        Log.i(TAG, "pairing: $lastResult")

        // The `null` branch went with the old loop over parameter combinations,
        // where "no attempt produced a result" was a real state. There is one
        // attempt now, so a result always exists.
        when (lastResult) {
            is AdbPairingClient.Result.WrongCode -> return@withContext Outcome.WrongCode
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
        /**
         * How long to wait for the helper to hand over its binder.
         *
         * Generous on purpose: the runtime has to start, and the launching
         * shell holds on for a few seconds of that. Erring long costs a slow
         * spinner in the rare failure case; erring short would call a working
         * start broken, which is the mistake this whole check exists to undo.
         */
        private const val HELPER_ARRIVAL_TIMEOUT_MS = 12_000L

        const val TAG = "HelperAutoStart"
    }
}

