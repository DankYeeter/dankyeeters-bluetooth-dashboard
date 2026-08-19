package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatus
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadPath
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysDevice
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysSnapshot
import dev.dankyeeter.btdashboard.monitor.link.UnavailableQualityReportSource
import dev.dankyeeter.btdashboard.monitor.sampling.LinkSampleCollector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How many rows one poll is allowed to write.
 *
 * A real dump lists every address the phone has ever seen — a phone in daily
 * use had 196 of them — so an unfiltered collector wrote ~200 rows per poll.
 * At the normal 60-second interval that is 207 rows a minute for one pair of
 * headphones, which is both a battery cost and a timeline nobody can read.
 */
class LinkSampleCollectorScopeTest {

    private val connected = DumpsysDevice(
        address = "XX:XX:XX:XX:35:6A",
        name = "Focal Bathys",
        isActive = true,
        isConnected = true,
        codec = CodecFamily.APTX,
        sampleRateHz = 48_000,
    )

    /** Bonded-but-off headphones, plus everything the phone has ever scanned. */
    private val strangers = (1..196).map {
        DumpsysDevice(address = "XX:XX:XX:%02d:%02d:00".format(it / 100, it % 100))
    }

    private fun collector(dump: DumpsysSnapshot, devices: List<BtAudioDevice> = emptyList()) =
        LinkSampleCollector(
            codecSource = FakeCodecStatusSource(devices = devices),
            dumpsysSource = FakeDumpsysLinkSource(dump),
            qualityReportSource = UnavailableQualityReportSource("no BQR in tests"),
        )

    @Test
    fun `one poll writes one row per connected device, not per known address`() = runTest {
        val dump = DumpsysSnapshot(devices = listOf(connected) + strangers)

        val samples = collector(dump).collect()

        assertEquals(1, samples.size)
        assertEquals("XX:XX:XX:XX:35:6A", samples.single().deviceAddress)
    }

    @Test
    fun `the connected device keeps its codec`() = runTest {
        val dump = DumpsysSnapshot(devices = listOf(connected) + strangers)

        val sample = collector(dump).collect().single()

        assertEquals(CodecFamily.APTX, sample.codec)
        assertEquals(48_000, sample.sampleRateHz)
    }

    @Test
    fun `nothing connected writes nothing`() = runTest {
        val samples = collector(DumpsysSnapshot(devices = strangers)).collect()

        assertEquals(emptyList<Any>(), samples)
    }

    @Test
    fun `two connected devices are both sampled`() = runTest {
        val second = connected.copy(address = "XX:XX:XX:XX:C0:D7", isActive = false)
        val dump = DumpsysSnapshot(devices = listOf(connected, second) + strangers)

        assertEquals(2, collector(dump).collect().size)
    }
}

/**
 * One headphone must produce one row even though the two sources spell its
 * address differently: the A2DP proxy gives the real MAC, a user-build dump
 * gives `XX:XX:XX:XX:35:6A`.
 */
class RedactedAddressJoinTest {

    private val real = "A4:D9:31:C8:35:6A"
    private val redacted = "XX:XX:XX:XX:35:6A"

    @Test
    fun `the same device from both sources is one sample`() = runTest {
        val collector = LinkSampleCollector(
            codecSource = FakeCodecStatusSource(
                devices = listOf(BtAudioDevice(real, "Focal Bathys", isActive = true)),
                statuses = mutableMapOf(real to CodecReadResult.Unsupported("privileged")),
            ),
            dumpsysSource = FakeDumpsysLinkSource(
                DumpsysSnapshot(
                    listOf(
                        DumpsysDevice(
                            address = redacted,
                            isConnected = true,
                            codec = CodecFamily.APTX,
                            rssiDbm = -62,
                        ),
                    ),
                ),
            ),
            qualityReportSource = UnavailableQualityReportSource("no BQR in tests"),
        )

        val samples = collector.collect()

        assertEquals(1, samples.size)
        // The real address wins: device profiles are keyed on it.
        assertEquals(real, samples.single().deviceAddress)
        // ...and the dump's contribution still lands on that row.
        assertEquals(-62, samples.single().rssiDbm)
        assertEquals(CodecFamily.APTX, samples.single().codec)
    }
}

/** A sample must name the mechanism that actually produced it. */
class SampleProvenanceTest {

    private val address = "A4:D9:31:C8:35:6A"

    @Test
    fun `a shell-scraped reading is not credited to the system API`() = runTest {
        val collector = LinkSampleCollector(
            codecSource = FakeCodecStatusSource(
                devices = listOf(BtAudioDevice(address, "Focal Bathys")),
            ).withStatus(
                address,
                CodecStatus(family = CodecFamily.APTX, readVia = CodecReadPath.DUMPSYS),
            ),
            dumpsysSource = FakeDumpsysLinkSource(DumpsysSnapshot(), isAvailable = false),
            qualityReportSource = UnavailableQualityReportSource("no BQR in tests"),
        )

        assertEquals(LinkDataSource.DUMPSYS, collector.collect().single().source)
    }

    @Test
    fun `a real system-API reading is reported as such`() = runTest {
        val collector = LinkSampleCollector(
            codecSource = FakeCodecStatusSource(
                devices = listOf(BtAudioDevice(address, "Focal Bathys")),
            ).withStatus(
                address,
                CodecStatus(family = CodecFamily.LDAC, readVia = CodecReadPath.SYSTEM_API),
            ),
            dumpsysSource = FakeDumpsysLinkSource(DumpsysSnapshot(), isAvailable = false),
            qualityReportSource = UnavailableQualityReportSource("no BQR in tests"),
        )

        assertEquals(LinkDataSource.CODEC_API, collector.collect().single().source)
    }
}
