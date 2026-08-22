package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.FallbackCodecStatusSource
import dev.dankyeeter.btdashboard.monitor.dumpsys.CachedDumpsysLinkSource
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysDevice
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysLinkSource
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysSnapshot
import dev.dankyeeter.btdashboard.monitor.link.UnavailableQualityReportSource
import dev.dankyeeter.btdashboard.monitor.sampling.LinkSampleCollector
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What one sample run is allowed to cost in `dumpsys bluetooth_manager`.
 *
 * The dump is the most expensive recurring thing this app does — a
 * `ProcessBuilder` exec inside the privileged helper, the whole output Base64'd
 * back over a Binder call, then a 239-line regex parser. A run used to pay for
 * it two to N times: once for the device list, then once more inside
 * `FallbackCodecStatusSource.codecStatus()` *per device*, because
 * `BluetoothA2dp.getCodecStatus()` throws `SecurityException` on stock Android
 * and the fallback therefore fires every single time.
 */
class DumpsysSnapshotCacheTest {

    /** Counts what actually reaches the shell. */
    private class CountingDumpsys(
        private val snapshot: DumpsysSnapshot,
        override val isAvailable: Boolean = true,
    ) : DumpsysLinkSource {
        var calls = 0
            private set

        override suspend fun snapshot(): DumpsysSnapshot {
            calls++
            return snapshot
        }
    }

    private val bathys = DumpsysDevice(
        address = "XX:XX:XX:XX:35:6A",
        name = "Focal Bathys",
        isActive = true,
        isConnected = true,
        codec = CodecFamily.APTX,
        sampleRateHz = 48_000,
        rssiDbm = -62,
    )
    private val buds = bathys.copy(address = "XX:XX:XX:XX:C0:D7", name = "Buds", isActive = false)

    private fun dump(vararg devices: DumpsysDevice) = DumpsysSnapshot(devices = devices.toList())

    @Test
    fun `one full sample run costs exactly one dumpsys`() = runTest {
        val shell = CountingDumpsys(dump(bathys, buds))
        val cache = CachedDumpsysLinkSource(shell, clock = { 1_000L })
        // The A2DP profile refuses on stock Android, so every level falls
        // through to the dump — the expensive case, on purpose.
        val codecSource = FallbackCodecStatusSource(
            primary = FakeCodecStatusSource(isProfileAvailable = false),
            dumpsys = cache,
        )

        val samples = LinkSampleCollector(
            codecSource = codecSource,
            dumpsysSource = cache,
            qualityReportSource = UnavailableQualityReportSource("no BQR in tests"),
        ).collect()

        assertEquals(2, samples.size)
        assertEquals("one run must cost one dumpsys", 1, shell.calls)
    }

    @Test
    fun `two runs inside the TTL still share one dumpsys, a later one does not`() = runTest {
        val shell = CountingDumpsys(dump(bathys))
        var now = 0L
        val cache = CachedDumpsysLinkSource(shell, ttlMs = 5_000L, clock = { now })

        cache.snapshot()
        now = 4_999L
        cache.snapshot()
        assertEquals(1, shell.calls)

        now = 5_000L
        cache.snapshot()
        assertEquals(2, shell.calls)
    }

    /**
     * The collector and the codec source ask microseconds apart, and on the
     * default dispatcher that can genuinely be in parallel. Two execs of the
     * same command would be the worst version of the bug, not a lesser one.
     */
    @Test
    fun `concurrent callers do not each start their own dump`() = runTest {
        val shell = CountingDumpsys(dump(bathys))
        val cache = CachedDumpsysLinkSource(shell, clock = { 1_000L })

        (1..8).map { async { cache.snapshot() } }.awaitAll()

        assertEquals(1, shell.calls)
    }

    @Test
    fun `invalidate forces the next read back to the shell`() = runTest {
        val shell = CountingDumpsys(dump(bathys))
        val cache = CachedDumpsysLinkSource(shell, clock = { 1_000L })

        cache.snapshot()
        cache.invalidate()
        cache.snapshot()

        assertEquals(2, shell.calls)
    }

    /** A dead shell must not be asked at all, cache or no cache. */
    @Test
    fun `an unavailable source is never dumped`() = runTest {
        val shell = CountingDumpsys(dump(bathys), isAvailable = false)
        val cache = CachedDumpsysLinkSource(shell, clock = { 1_000L })

        val samples = LinkSampleCollector(
            codecSource = FakeCodecStatusSource(isProfileAvailable = false),
            dumpsysSource = cache,
            qualityReportSource = UnavailableQualityReportSource("no BQR in tests"),
        ).collect()

        assertEquals(emptyList<Any>(), samples)
        assertEquals(0, shell.calls)
    }
}
