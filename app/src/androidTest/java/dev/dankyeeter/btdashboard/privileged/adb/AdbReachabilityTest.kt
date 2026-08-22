package dev.dankyeeter.btdashboard.privileged.adb

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Can the app reach the phone's own `adbd`?
 *
 * The first half of "start the helper without a computer". Everything else -
 * pairing, running a command - depends on two answers this test provides and
 * nothing else can: does mDNS hand us the port, and does adbd carry the TLS
 * handshake far enough to ask for a client certificate.
 *
 * **A refusal here is a pass, not a failure.** Until pairing exists the app's
 * key is unknown to adbd, so the expected outcome is `Untrusted`: the protocol
 * ran correctly all the way to the point where trust is decided. That is
 * precisely what needed proving before writing the pairing crypto, which is the
 * expensive and risky part.
 *
 * Needs wireless debugging switched on. With it off, discovery finds nothing -
 * also a meaningful result, just a different one.
 */
@RunWith(JUnit4::class)
class AdbReachabilityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun how_far_does_the_app_get_towards_adbd() = runBlocking {
        val discovery = AdbPortDiscovery(context)

        val connect = discovery.find(AdbPortDiscovery.SERVICE_CONNECT)
        println("ADBPROBE connect service: ${connect ?: "not advertised"}")

        val pairing = discovery.find(AdbPortDiscovery.SERVICE_PAIRING, timeoutMs = 2_000)
        println("ADBPROBE pairing service: ${pairing ?: "not advertised (dialog closed)"}")

        if (connect == null) {
            println("ADBPROBE verdict: wireless debugging appears to be off")
            return@runBlocking
        }

        val keys = AdbKeyStore(context)
        println("ADBPROBE key existed before: ${keys.exists}")
        // Generating the certificate is itself a checkpoint: it goes through a
        // hidden platform class, and if that has moved on this Android version
        // it fails here rather than halfway through a handshake.
        val certificate = runCatching { keys.certificate() }
        println(
            "ADBPROBE certificate: " +
                (certificate.getOrNull()?.subjectX500Principal?.name ?: "FAILED ${certificate.exceptionOrNull()}"),
        )
        if (certificate.isFailure) return@runBlocking

        when (val result = AdbTlsClient(keys).connect(connect)) {
            is AdbTlsClient.Result.Connected -> {
                println("ADBPROBE verdict: CONNECTED - adbd already trusts this key")
                result.session.close()
            }

            is AdbTlsClient.Result.Untrusted ->
                println("ADBPROBE verdict: UNTRUSTED (expected before pairing) - ${result.detail}")

            is AdbTlsClient.Result.Failed ->
                println("ADBPROBE verdict: FAILED - ${result.detail}")
        }
    }
}
