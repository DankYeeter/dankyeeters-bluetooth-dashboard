package dev.dankyeeter.btdashboard.ui.screens.dashboard

import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatusSource
import dev.dankyeeter.btdashboard.monitor.codec.FallbackCodecStatusSource
import dev.dankyeeter.btdashboard.monitor.dumpsys.CachedDumpsysLinkSource
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysDevice
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysLinkSource
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Bluetooth tab showed "Unknown" after Bluetooth off/on until the app was
 * restarted: the codec was read once, when the reconnected device appeared,
 * and the codec config broadcast that followed changed nothing in the device
 * list, so nothing read it again.
 *
 * Real [FallbackCodecStatusSource] and [CachedDumpsysLinkSource]; the A2DP
 * proxy is a fake whose list only emits on change, like the real one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardCodecRereadTest {

    private val focus = BtAudioDevice(address = "A4:D9:31:C8:35:6A", name = "Noble FoKus")

    private class ProxyFake : CodecStatusSource {
        val devices = MutableStateFlow<List<BtAudioDevice>>(emptyList())
        override suspend fun connectedDevices() = devices.value
        override fun connectedDevicesFlow(): Flow<List<BtAudioDevice>> = devices
        // What stock Android answers a normal app.
        override suspend fun codecStatus(address: String): CodecReadResult =
            CodecReadResult.Unsupported("privileged permission required")
        override val isProfileAvailable = true
    }

    private class DumpFake : DumpsysLinkSource {
        var current = DumpsysSnapshot()
        override suspend fun snapshot() = current
        override val isAvailable = true
    }

    private fun dumpWith(codec: CodecFamily?) = DumpsysSnapshot(
        devices = listOf(
            DumpsysDevice(address = "XX:XX:XX:XX:35:6A", isConnected = true, codec = codec),
        ),
    )

    @Test
    fun `after Bluetooth off and on the next read finds the codec again`() = runTest {
        val proxy = ProxyFake()
        val dump = DumpFake()
        var nowMs = 0L
        val heldDump = CachedDumpsysLinkSource(dump, clock = { nowMs })
        val source = FallbackCodecStatusSource(proxy, heldDump)
        val broadcasts = MutableSharedFlow<Unit>()
        proxy.devices.value = listOf(focus)
        dump.current = dumpWith(codec = CodecFamily.LDAC)

        val readings = mutableListOf<CodecReadResult?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            devicesToRead(source, broadcasts, heldDump::invalidate).collect { devices ->
                readings += devices.firstOrNull()?.let { source.codecStatus(it.address) }
            }
        }

        assertEquals(CodecFamily.LDAC, (readings.last() as? CodecReadResult.Available)?.status?.family)

        // Bluetooth off: the proxy loses the device, the dump is empty.
        nowMs = 10_000L
        dump.current = DumpsysSnapshot()
        proxy.devices.value = emptyList()
        broadcasts.emit(Unit)
        // Bluetooth on, headphone reconnects: A2DP is up, the codec not yet.
        nowMs = 20_000L
        dump.current = dumpWith(codec = null)
        proxy.devices.value = listOf(focus)
        // The codec is negotiated and announced, well inside the dump's hold time.
        nowMs = 20_500L
        dump.current = dumpWith(codec = CodecFamily.LDAC)
        broadcasts.emit(Unit)
        advanceUntilIdle()

        assertEquals(CodecFamily.LDAC, (readings.last() as? CodecReadResult.Available)?.status?.family)
    }
}
