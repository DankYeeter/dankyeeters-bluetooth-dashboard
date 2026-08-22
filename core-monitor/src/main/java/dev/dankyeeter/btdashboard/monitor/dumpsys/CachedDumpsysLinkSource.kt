package dev.dankyeeter.btdashboard.monitor.dumpsys

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A short-lived hold on the last `dumpsys bluetooth_manager`.
 *
 * `dumpsys bluetooth_manager` is by a wide margin the most expensive recurring
 * thing this app does: a `ProcessBuilder` exec inside the privileged helper,
 * the whole dump Base64-encoded back over a Binder call, and a 239-line regex
 * parser on top. One sample run used to pay for it two to N times — once for
 * the device list, then once more per device inside
 * `FallbackCodecStatusSource.codecStatus()`, because the A2DP system API throws
 * `SecurityException` on stock Android and the fallback fires every time.
 *
 * With one of these shared between the collector and the codec source, a run
 * costs exactly one dump.
 *
 * **Why a TTL and not an explicit invalidation:** the window is deliberately
 * shorter than any polling interval the sampler uses (the fastest is DEEP at
 * 10 s), so the cache can only ever collapse the reads *within* one run and
 * never carries a reading from one run into the next. It also cannot hide a
 * connection change from the UI, because the UI's device list is driven by
 * `connectedDevicesFlow()` off the Bluetooth broadcasts, not by polling this.
 * [invalidate] exists for the caller that does know better.
 */
class CachedDumpsysLinkSource(
    private val delegate: DumpsysLinkSource,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : DumpsysLinkSource {

    private val mutex = Mutex()

    @Volatile
    private var cached: DumpsysSnapshot? = null

    @Volatile
    private var cachedAtMs = 0L

    override val isAvailable: Boolean get() = delegate.isAvailable

    /**
     * The lock is not only for the fields: it also serialises two callers that
     * arrive together, so the second one waits for the first one's dump rather
     * than starting a second exec of its own. That is the common case here —
     * the collector and the codec source ask microseconds apart.
     */
    override suspend fun snapshot(): DumpsysSnapshot = mutex.withLock {
        val now = clock()
        val hit = cached
        if (hit != null && now - cachedAtMs < ttlMs) return@withLock hit
        delegate.snapshot().also {
            cached = it
            cachedAtMs = now
        }
    }

    /** Drops the held dump; the next [snapshot] goes to the shell again. */
    fun invalidate() {
        cached = null
        cachedAtMs = 0L
    }

    companion object {
        /**
         * Shorter than the fastest sampling interval (DEEP, 10 s) on purpose:
         * see the class KDoc. Long enough that one run's two-to-N reads
         * collapse into one.
         */
        const val DEFAULT_TTL_MS = 5_000L
    }
}
