package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatus
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysDevice
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysSnapshot
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.UnavailableQualityReportSource
import dev.dankyeeter.btdashboard.monitor.sampling.LinkSampleCollector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSampleCollectorTest {

    private val address = "AA:BB:CC:DD:EE:FF"
    private val bqr = UnavailableQualityReportSource("no privileged access in tests")

    @Test
    fun `codec api and dumpsys are merged into one sample`() = runTest {
        val codecSource = FakeCodecStatusSource(
            devices = listOf(BtAudioDevice(address, "Encore", isActive = true, isPlaying = true)),
        ).withStatus(
            address,
            CodecStatus(CodecFamily.LDAC, sampleRateHz = 96_000, bitrateKbps = 606),
        )
        val dumpsys = FakeDumpsysLinkSource(
            DumpsysSnapshot(listOf(DumpsysDevice(address, isConnected = true, rssiDbm = -62))),
        )

        val sample = LinkSampleCollector(codecSource, dumpsys, bqr) { 1_234L }.collect().single()

        assertEquals(LinkDataSource.CODEC_API, sample.source)
        assertEquals(CodecFamily.LDAC, sample.codec)
        assertEquals(606, sample.bitrateKbps)
        assertEquals(-62, sample.rssiDbm) // only dumpsys knows RSSI
        assertTrue(sample.isPlaying)
        assertEquals(1_234L, sample.timestampMs)
    }

    @Test
    fun `dumpsys alone still produces a sample`() = runTest {
        val codecSource = FakeCodecStatusSource(isProfileAvailable = false)
        val dumpsys = FakeDumpsysLinkSource(
            DumpsysSnapshot(
                listOf(DumpsysDevice(address, isConnected = true, codec = CodecFamily.SBC, isPlaying = true, rssiDbm = -70)),
            ),
        )

        val sample = LinkSampleCollector(codecSource, dumpsys, bqr).collect().single()

        assertEquals(LinkDataSource.DUMPSYS, sample.source)
        assertEquals(CodecFamily.SBC, sample.codec)
        assertNull(sample.bitrateKbps)
        assertEquals(0, codecSource.codecReads) // no device from the profile: no read
    }

    @Test
    fun `no sources at all yields no samples instead of failing`() = runTest {
        val collector = LinkSampleCollector(
            FakeCodecStatusSource(isProfileAvailable = false),
            FakeDumpsysLinkSource(isAvailable = false),
            bqr,
        )
        assertTrue(collector.collect().isEmpty())
        assertEquals(LinkDataSource.NONE, collector.activeSource())
    }

    @Test
    fun `unsupported codec status does not lose the device`() = runTest {
        val codecSource = FakeCodecStatusSource(
            devices = listOf(BtAudioDevice(address, "Encore")),
            statuses = mutableMapOf(address to CodecReadResult.Unsupported("OEM stub")),
        )
        val sample = LinkSampleCollector(codecSource, FakeDumpsysLinkSource(isAvailable = false), bqr)
            .collect().single()

        assertEquals(LinkDataSource.NONE, sample.source)
        assertNull(sample.codec)
    }
}
