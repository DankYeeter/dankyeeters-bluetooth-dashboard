package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.FallbackCodecStatusSource
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysDevice
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dashboard has no refresh button any more, so these tests are what stands
 * between "plug in a headphone and it appears" and a screen that quietly lies
 * until the user navigates away and back.
 *
 * They exercise [FallbackCodecStatusSource], which is where the list a screen
 * actually sees is assembled. The two triggers underneath it — the A2DP proxy
 * binding and the Bluetooth broadcasts — need a device and live in
 * `A2dpCodecStatusSource`; the fake stands in for both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedDevicesFlowTest {

    private val bathys = BtAudioDevice(
        address = "A4:D9:31:C8:35:6A",
        name = "Focal Bathys",
        isActive = true,
    )

    private val fives = BtAudioDevice(
        address = "24:16:1B:7A:91:81",
        name = "Klipsch The Fives",
    )

    private fun noDump() = FakeDumpsysLinkSource(isAvailable = false)

    @Test
    fun `a headphone that connects later reaches the screen on its own`() = runTest {
        val primary = FakeCodecStatusSource(devices = emptyList())
        val source = FallbackCodecStatusSource(primary, noDump())

        val seen = mutableListOf<List<BtAudioDevice>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.connectedDevicesFlow().toList(seen)
        }
        advanceUntilIdle()

        assertEquals("a cold screen starts empty", listOf(emptyList<BtAudioDevice>()), seen)

        // What a real connect looks like from here: the profile now lists it.
        primary.devices = listOf(bathys)
        primary.ticks.emit(Unit)
        advanceUntilIdle()

        assertEquals(listOf(emptyList(), listOf(bathys)), seen)
    }

    @Test
    fun `one connect described by several broadcasts is one update`() = runTest {
        val primary = FakeCodecStatusSource(devices = listOf(bathys))
        val source = FallbackCodecStatusSource(primary, noDump())

        val seen = mutableListOf<List<BtAudioDevice>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.connectedDevicesFlow().toList(seen)
        }
        advanceUntilIdle()

        // ACL_CONNECTED, then CONNECTION_STATE_CHANGED, then CODEC_CONFIG_CHANGED:
        // three broadcasts, one connect. Re-reading on each is what removes the
        // need to guess a settling delay; not re-emitting is what stops that
        // from flickering the UI three times.
        repeat(3) { primary.ticks.emit(Unit) }
        advanceUntilIdle()

        assertEquals(listOf(listOf(bathys)), seen)
    }

    @Test
    fun `disconnecting empties the list without being asked`() = runTest {
        val primary = FakeCodecStatusSource(devices = listOf(bathys, fives))
        val source = FallbackCodecStatusSource(primary, noDump())

        val seen = mutableListOf<List<BtAudioDevice>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.connectedDevicesFlow().toList(seen)
        }
        advanceUntilIdle()

        primary.devices = listOf(fives)
        primary.ticks.emit(Unit)
        primary.devices = emptyList()
        primary.ticks.emit(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(listOf(bathys, fives), listOf(fives), emptyList()),
            seen,
        )
    }

    @Test
    fun `the dump fills in what an unbound proxy cannot see`() = runTest {
        // The A2DP proxy binds asynchronously and can stay unbound entirely
        // without BLUETOOTH_CONNECT. Forwarding the primary's own list here
        // would report "nothing connected" while connectedDevices() — same
        // object, same moment — reported the Bathys.
        val primary = FakeCodecStatusSource(devices = emptyList())
        val dump = FakeDumpsysLinkSource(
            result = DumpsysSnapshot(
                devices = listOf(
                    DumpsysDevice(
                        address = "XX:XX:XX:XX:35:6A",
                        name = "Focal Bathys",
                        isActive = true,
                        isConnected = true,
                        codec = CodecFamily.APTX,
                    ),
                ),
            ),
        )
        val source = FallbackCodecStatusSource(primary, dump)

        val seen = mutableListOf<List<BtAudioDevice>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.connectedDevicesFlow().toList(seen)
        }
        advanceUntilIdle()

        assertEquals(
            listOf(
                listOf(
                    BtAudioDevice(
                        address = "XX:XX:XX:XX:35:6A",
                        name = "Focal Bathys",
                        isActive = true,
                    ),
                ),
            ),
            seen,
        )
    }
}
