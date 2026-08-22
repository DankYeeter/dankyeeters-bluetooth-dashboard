package dev.dankyeeter.btdashboard.privileged.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.NetworkInterface

/**
 * Finds the port the phone's own `adbd` is listening on.
 *
 * Wireless debugging picks a **fresh random port every time it is switched on**,
 * so nothing can be hard-coded and nothing survives a reboot. Android publishes
 * it over mDNS instead, which is how `adb pair` and `adb connect` find a device
 * on the network - and the same announcement is visible to an app on the device
 * itself.
 *
 * Two service types matter, and they are not interchangeable:
 *  - [SERVICE_CONNECT] is the everyday port. Only a client whose key adbd
 *    already trusts gets past its TLS handshake.
 *  - [SERVICE_PAIRING] only exists while the user has the "Pair device with
 *    pairing code" dialog open. It is where a new key gets trusted in the first
 *    place.
 *
 * The result is deliberately not cached. A port learned before the user toggled
 * wireless debugging is worse than no port at all: connecting to it either fails
 * slowly or reaches whatever took the number over.
 */
class AdbPortDiscovery(private val context: Context) {

    /**
     * @return host and port of the running service, or null if nothing answered
     *   within [timeoutMs]. Null is the ordinary answer when wireless debugging
     *   is off, not an error.
     */
    suspend fun find(
        serviceType: String = SERVICE_CONNECT,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Endpoint? {
        val manager = context.getSystemService(NsdManager::class.java) ?: return null
        val found = CompletableDeferred<Endpoint>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit

            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                found.completeExceptionally(
                    IllegalStateException("mDNS discovery could not start ($errorCode)"),
                )
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // The announcement carries only a name; the port arrives with
                // the resolve below.
                resolve(manager, info, found)
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }

        return try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeout(timeoutMs) { found.await() }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (t: Throwable) {
            Log.w(TAG, "could not discover $serviceType", t)
            null
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    private fun resolve(
        manager: NsdManager,
        info: NsdServiceInfo,
        found: CompletableDeferred<Endpoint>,
    ) {
        @Suppress("DEPRECATION") // registerServiceInfoCallback is API 34+; minSdk here is 31.
        manager.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve failed for ${failed.serviceName} ($errorCode)")
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    // complete() rather than a check-then-set: several
                    // announcements can resolve at once, and only the first
                    // one wins. Later calls are no-ops.
                    found.complete(Endpoint(advertisedHost = host, port = resolved.port))
                }
            },
        )
    }

    /**
     * Where to reach adbd - and deliberately **not** where mDNS said it lives.
     *
     * [advertisedHost] is kept only so a refusal can name what it refused.
     * [connectHost] is always loopback, because that is the whole security
     * argument: adbd binds every interface, so the port learned from the
     * announcement is reachable at 127.0.0.1, and connecting there means the
     * packets cannot leave the phone. Not "should not" - cannot. A hostile
     * announcement on the same network can waste a connection attempt against
     * a local port; it can never make the app talk to another machine.
     */
    data class Endpoint(
        val advertisedHost: String,
        val port: Int,
    ) {
        val connectHost: String get() = LOOPBACK

        /**
         * Whether the announcement plausibly came from this device.
         *
         * Checked before connecting, so that a stray adb daemon elsewhere on
         * the network cannot steer the app at an arbitrary local port. Not a
         * security boundary on its own - [connectHost] is - but it turns "some
         * device advertised something" into a refusal with a reason instead of
         * a silent failed handshake.
         */
        fun looksLikeThisDevice(): Boolean {
            if (advertisedHost == LOOPBACK || advertisedHost == LOOPBACK_V6) return true
            return runCatching {
                NetworkInterface.getNetworkInterfaces().asSequence().any { nic ->
                    nic.inetAddresses.asSequence().any { it.hostAddress == advertisedHost }
                }
            }.getOrDefault(false)
        }

        override fun toString(): String = "$advertisedHost:$port (connecting via $LOOPBACK)"
    }

    companion object {
        private const val TAG = "AdbDiscovery"

        /** Always advertised while wireless debugging is on. */
        const val SERVICE_CONNECT = "_adb-tls-connect._tcp"

        /** Advertised only while the pairing dialog is open. */
        const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"

        /**
         * mDNS is chatty but not instant, and this runs while the user is
         * looking at a button they just pressed. Long enough for a local
         * announcement, short enough not to feel broken.
         */
        const val DEFAULT_TIMEOUT_MS = 5_000L

        /** The only address this client ever connects to. */
        const val LOOPBACK = "127.0.0.1"
        const val LOOPBACK_V6 = "::1"
    }
}
