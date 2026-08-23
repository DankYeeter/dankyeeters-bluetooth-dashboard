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
     * Every endpoint announced within [timeoutMs], not just the first.
     *
     * The first answer is not good enough, and that was measured rather than
     * feared: with adbd actually listening on port 35485, discovery returned
     * 34797 - a leftover announcement from an earlier session with nothing
     * behind it. mDNS caches, devices reconnect, ports change, and nothing
     * removes the old record promptly.
     *
     * So the whole window is collected and the caller tries each in turn. On
     * loopback a dead port refuses instantly, which makes working through a
     * short list far cheaper than getting it wrong once.
     *
     * @return endpoints in the order they resolved; empty when wireless
     *   debugging is off, which is an ordinary answer and not an error.
     */
    suspend fun findAll(
        serviceType: String = SERVICE_CONNECT,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        /**
         * Called for each endpoint the moment it resolves. Return true to stop
         * looking - the caller has what it needs and nobody should watch a
         * spinner for the rest of the window.
         */
        onEach: (Endpoint) -> Boolean = { false },
    ): List<Endpoint> {
        val manager = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val collected = LinkedHashSet<Endpoint>()
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
                resolve(manager, info) { endpoint ->
                    val isNew = synchronized(collected) { collected.add(endpoint) }
                    // Try it straight away rather than at the end of the window.
                    // A dead loopback port refuses in milliseconds, so the cost
                    // of an early attempt is nothing and the saving is the whole
                    // remaining wait.
                    if (isNew && onEach(endpoint)) found.complete(endpoint)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }

        try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            // Always waits the full window: a live announcement often arrives
            // after a stale one, and returning early would keep picking the
            // wrong port forever.
            withTimeout(timeoutMs) { found.await() }
        } catch (_: TimeoutCancellationException) {
            // Expected. Nothing ever completes `found`; the window simply ends.
        } catch (t: Throwable) {
            Log.w(TAG, "could not discover $serviceType", t)
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        val endpoints = synchronized(collected) { collected.toList() }
        Log.i(TAG, "discovered ${endpoints.size} endpoint(s): $endpoints")
        return endpoints
    }

    private fun resolve(
        manager: NsdManager,
        info: NsdServiceInfo,
        onResolved: (Endpoint) -> Unit,
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
                    onResolved(Endpoint(advertisedHost = host, port = resolved.port))
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
        /**
         * Lowered from 5 s, and the reason it was ever 5 s is worth keeping in
         * mind: a stale announcement can arrive before the live one, so the
         * first answer is not necessarily the right one.
         *
         * Waiting out the full window solved that by making everyone wait, and
         * five seconds of a spinner after pressing a button is a long time. The
         * caller now tries candidates as they arrive and stops at the first
         * that reaches a verdict, so the window only bounds the *worst* case
         * instead of setting the price for every case.
         */
        const val DEFAULT_TIMEOUT_MS = 2_500L

        /** The only address this client ever connects to. */
        const val LOOPBACK = "127.0.0.1"
        const val LOOPBACK_V6 = "::1"
    }
}
