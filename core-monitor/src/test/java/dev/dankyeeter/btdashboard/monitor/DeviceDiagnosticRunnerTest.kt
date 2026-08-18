package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatus
import dev.dankyeeter.btdashboard.monitor.codec.NoOpCodecController
import dev.dankyeeter.btdashboard.monitor.diagnostic.DeviceDiagnosticRunner
import dev.dankyeeter.btdashboard.monitor.diagnostic.DiagnosticAnalysis
import dev.dankyeeter.btdashboard.monitor.diagnostic.DiagnosticStep
import dev.dankyeeter.btdashboard.monitor.diagnostic.StepOutcome
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysDevice
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysSnapshot
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.UnavailableQualityReportSource
import dev.dankyeeter.btdashboard.monitor.sampling.LinkSampleCollector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDiagnosticRunnerTest {

    private val address = "AA:BB:CC:DD:EE:FF"
    private val bqr = UnavailableQualityReportSource("not available in tests")

    private fun runner(
        clock: TestClock,
        codecSource: FakeCodecStatusSource,
        dumpsys: FakeDumpsysLinkSource,
        controller: dev.dankyeeter.btdashboard.monitor.codec.CodecController = NoOpCodecController,
    ) = DeviceDiagnosticRunner(
        codecSource = codecSource,
        collector = LinkSampleCollector(codecSource, dumpsys, bqr, clock::now),
        codecController = controller,
        clock = clock::now,
        // "Sleeping" only moves the virtual clock — the soak runs instantly.
        sleep = { clock.advance(it) },
    )

    private fun connectedSource() = FakeCodecStatusSource(
        devices = listOf(BtAudioDevice(address, "Encore", isActive = true, isPlaying = true)),
    ).withStatus(address, CodecStatus(CodecFamily.LDAC, sampleRateHz = 96_000, bitrateKbps = 909))

    @Test
    fun `a disconnected device fails at the first step`() = runTest {
        val clock = TestClock()
        val report = runner(clock, FakeCodecStatusSource(), FakeDumpsysLinkSource()).run(address)

        assertEquals(1, report.steps.size)
        assertEquals(DiagnosticStep.CONNECTION_CHECK, report.steps.single().step)
        assertTrue(report.steps.single().outcome is StepOutcome.Failed)
    }

    @Test
    fun `a full run walks every step and summarises`() = runTest {
        val clock = TestClock()
        val dumpsys = FakeDumpsysLinkSource(
            DumpsysSnapshot(listOf(DumpsysDevice(address, rssiDbm = -60, isPlaying = true))),
        )
        val progress = mutableListOf<DiagnosticStep>()

        val report = runner(clock, connectedSource(), dumpsys)
            .run(address, soakDurationMs = 60_000, soakIntervalMs = 10_000) {
                progress += it.step
            }

        assertEquals(DiagnosticStep.entries.toList(), progress.toList())
        assertEquals(6, report.sampleCount) // 60 s soak at 10 s resolution
        assertEquals(CodecFamily.LDAC, report.bestStableCodec)
        assertEquals(0, report.dropCount)
        assertEquals(-60..-60, report.rssiRangeDbm)
        assertTrue(report.verdict.contains("Most stable codec: LDAC"))
    }

    @Test
    fun `codec cycling is skipped honestly when we lack the privilege`() = runTest {
        val clock = TestClock()
        val report = runner(clock, connectedSource(), FakeDumpsysLinkSource())
            .run(address, soakDurationMs = 10_000, soakIntervalMs = 10_000)

        val cycling = report.steps.first { it.step == DiagnosticStep.CODEC_CYCLING }.outcome
        assertTrue(cycling is StepOutcome.Skipped)
        assertTrue(cycling.detail.contains("privileged"))
    }

    @Test
    fun `codec cycling reports which codecs actually held`() = runTest {
        val clock = TestClock()
        val controller = FakeCodecController(
            available = listOf(CodecFamily.SBC, CodecFamily.AAC, CodecFamily.LDAC),
            refuse = setOf(CodecFamily.LDAC),
        )
        val report = runner(clock, connectedSource(), FakeDumpsysLinkSource(), controller)
            .run(address, soakDurationMs = 10_000, soakIntervalMs = 10_000)

        val cycling = report.steps.first { it.step == DiagnosticStep.CODEC_CYCLING }.outcome
        assertTrue(cycling is StepOutcome.Passed)
        assertEquals("Applied: SBC, AAC", cycling.detail)
    }

    @Test
    fun `unreadable codec status is skipped, not failed`() = runTest {
        val clock = TestClock()
        val source = FakeCodecStatusSource(
            devices = listOf(BtAudioDevice(address, "Encore")),
            statuses = mutableMapOf(address to CodecReadResult.Unsupported("OEM stub")),
        )
        val report = runner(clock, source, FakeDumpsysLinkSource())
            .run(address, soakDurationMs = 10_000, soakIntervalMs = 10_000)

        val negotiation = report.steps.first { it.step == DiagnosticStep.CODEC_NEGOTIATION }.outcome
        assertTrue(negotiation is StepOutcome.Skipped)
    }
}

class DiagnosticAnalysisTest {

    private fun sample(
        at: Long,
        codec: CodecFamily?,
        playing: Boolean = true,
        rssi: Int? = null,
    ) = LinkQualitySample(
        timestampMs = at,
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        source = LinkDataSource.CODEC_API,
        rssiDbm = rssi,
        codec = codec,
        isPlaying = playing,
    )

    @Test
    fun `the codec that held longest wins, not the most frequent`() {
        val result = DiagnosticAnalysis.analyse(
            listOf(
                sample(0, CodecFamily.SBC),
                sample(1_000, CodecFamily.SBC),
                sample(2_000, CodecFamily.LDAC),
                sample(120_000, CodecFamily.LDAC),
            ),
        )
        assertEquals(CodecFamily.LDAC, result.bestStableCodec)
        assertEquals(1, result.codecChanges)
    }

    @Test
    fun `playback stopping mid-soak counts as a drop and rssi range is tracked`() {
        val result = DiagnosticAnalysis.analyse(
            listOf(
                sample(0, CodecFamily.LDAC, rssi = -55),
                sample(10_000, CodecFamily.LDAC, playing = false, rssi = -78),
                sample(20_000, CodecFamily.LDAC, rssi = -60),
            ),
        )
        assertEquals(1, result.dropCount)
        assertEquals(-78..-55, result.rssiRange)
    }

    @Test
    fun `an empty soak analyses to an empty result`() {
        val result = DiagnosticAnalysis.analyse(emptyList())
        assertEquals(0, result.dropCount)
        assertEquals(null, result.bestStableCodec)
        assertEquals(null, result.rssiRange)
    }
}
