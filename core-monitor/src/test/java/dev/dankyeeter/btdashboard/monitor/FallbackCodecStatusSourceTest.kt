package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadPath
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatus
import dev.dankyeeter.btdashboard.monitor.codec.FallbackCodecStatusSource
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysDevice
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The A2DP system API is behind BLUETOOTH_PRIVILEGED, so on stock Android the
 * primary source returns "unsupported" for every normal app. These tests pin
 * the behaviour that made the codec visible again: fall back to the dump, and
 * match the redacted address a real dump prints.
 */
class FallbackCodecStatusSourceTest {

    private val realAddress = "A4:D9:31:C8:35:6A"

    /** What a Pixel user build actually prints — only the last two octets survive. */
    private val redactedAddress = "XX:XX:XX:XX:35:6A"

    private fun bathysDump(address: String = redactedAddress) = DumpsysSnapshot(
        devices = listOf(
            DumpsysDevice(
                address = address,
                name = "Focal Bathys",
                isActive = true,
                isConnected = true,
                codec = CodecFamily.APTX_HD,
                sampleRateHz = 48_000,
                bitsPerSample = 24,
            ),
        ),
    )

    @Test
    fun `falls back to the dump when the system API is not permitted`() = runTest {
        val primary = FakeCodecStatusSource(
            devices = listOf(BtAudioDevice(realAddress, "Focal Bathys", isActive = true)),
            statuses = mutableMapOf(
                realAddress to CodecReadResult.Unsupported("privileged permission required"),
            ),
        )
        val source = FallbackCodecStatusSource(primary, FakeDumpsysLinkSource(bathysDump()))

        val result = source.codecStatus(realAddress)

        assertTrue(result is CodecReadResult.Available)
        val status = (result as CodecReadResult.Available).status
        assertEquals(CodecFamily.APTX_HD, status.family)
        assertEquals(48_000, status.sampleRateHz)
        assertEquals(24, status.bitsPerSample)
        assertEquals("aptX HD · 48 kHz · 24 bit", status.badge)
    }

    @Test
    fun `says where the numbers came from`() = runTest {
        val primary = FakeCodecStatusSource(
            statuses = mutableMapOf(realAddress to CodecReadResult.Unsupported("nope")),
        )
        val source = FallbackCodecStatusSource(primary, FakeDumpsysLinkSource(bathysDump()))

        val status = (source.codecStatus(realAddress) as CodecReadResult.Available).status

        assertEquals(CodecReadPath.DUMPSYS, status.readVia)
    }

    @Test
    fun `prefers the system API when it works`() = runTest {
        val primary = FakeCodecStatusSource()
            .withStatus(realAddress, CodecStatus(family = CodecFamily.LDAC, sampleRateHz = 96_000))
        val source = FallbackCodecStatusSource(primary, FakeDumpsysLinkSource(bathysDump()))

        val status = (source.codecStatus(realAddress) as CodecReadResult.Available).status

        assertEquals(CodecFamily.LDAC, status.family)
        assertEquals(CodecReadPath.SYSTEM_API, status.readVia)
    }

    @Test
    fun `keeps the primary reason when the dump has nothing to add`() = runTest {
        val primary = FakeCodecStatusSource(
            statuses = mutableMapOf(
                realAddress to CodecReadResult.Unsupported("privileged permission required"),
            ),
        )
        val source = FallbackCodecStatusSource(primary, FakeDumpsysLinkSource(DumpsysSnapshot()))

        val result = source.codecStatus(realAddress)

        assertEquals(
            CodecReadResult.Unsupported("privileged permission required"),
            result,
        )
    }

    @Test
    fun `does not fall back when the shell identity is unavailable`() = runTest {
        val primary = FakeCodecStatusSource(
            statuses = mutableMapOf(realAddress to CodecReadResult.Unsupported("no shell identity")),
        )
        val dumpsys = FakeDumpsysLinkSource(bathysDump(), isAvailable = false)
        val source = FallbackCodecStatusSource(primary, dumpsys)

        assertTrue(source.codecStatus(realAddress) is CodecReadResult.Unsupported)
    }

    @Test
    fun `an unredacted dump still matches`() = runTest {
        val primary = FakeCodecStatusSource(
            statuses = mutableMapOf(realAddress to CodecReadResult.Unsupported("nope")),
        )
        val source = FallbackCodecStatusSource(
            primary,
            FakeDumpsysLinkSource(bathysDump(address = realAddress)),
        )

        assertTrue(source.codecStatus(realAddress) is CodecReadResult.Available)
    }

    @Test
    fun `a different device in the dump is not mistaken for ours`() = runTest {
        val primary = FakeCodecStatusSource(
            statuses = mutableMapOf(realAddress to CodecReadResult.Unsupported("nope")),
        )
        val other = bathysDump(address = "XX:XX:XX:XX:37:8F")
        val source = FallbackCodecStatusSource(primary, FakeDumpsysLinkSource(other))

        assertTrue(source.codecStatus(realAddress) is CodecReadResult.Unsupported)
    }

    @Test
    fun `lists the dumped device when the A2DP proxy is unbound`() = runTest {
        val primary = FakeCodecStatusSource(devices = emptyList())
        val source = FallbackCodecStatusSource(primary, FakeDumpsysLinkSource(bathysDump()))

        val devices = source.connectedDevices()

        assertEquals(1, devices.size)
        assertEquals("Focal Bathys", devices.first().name)
        assertTrue(devices.first().isActive)
    }
}
