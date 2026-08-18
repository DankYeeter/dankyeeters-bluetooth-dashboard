package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.CodecController
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatus
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatusSource
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysLinkSource
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysSnapshot
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventSource
import dev.dankyeeter.btdashboard.monitor.shell.ShellResult
import dev.dankyeeter.btdashboard.monitor.shell.ShellRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf

/** Device-free stand-ins for everything the monitor talks to. */

class FakeCodecStatusSource(
    var devices: List<BtAudioDevice> = emptyList(),
    var statuses: MutableMap<String, CodecReadResult> = mutableMapOf(),
    override var isProfileAvailable: Boolean = true,
) : CodecStatusSource {
    var codecReads = 0
        private set

    override suspend fun connectedDevices(): List<BtAudioDevice> = devices
    override fun connectedDevicesFlow(): Flow<List<BtAudioDevice>> = flowOf(devices)
    override suspend fun codecStatus(address: String): CodecReadResult {
        codecReads++
        return statuses[address] ?: CodecReadResult.Unsupported("no fake status configured")
    }

    fun withStatus(address: String, status: CodecStatus) = apply {
        statuses[address] = CodecReadResult.Available(status)
    }
}

class FakeDumpsysLinkSource(
    var result: DumpsysSnapshot = DumpsysSnapshot(),
    override var isAvailable: Boolean = true,
) : DumpsysLinkSource {
    override suspend fun snapshot(): DumpsysSnapshot = result
}

class FakeShellRunner(
    private val responses: Map<String, ShellResult> = emptyMap(),
    override val isAvailable: Boolean = true,
) : ShellRunner {
    val commands = mutableListOf<List<String>>()

    override suspend fun run(command: List<String>): ShellResult {
        commands += command
        val key = command.joinToString(" ")
        return responses.entries.firstOrNull { key.startsWith(it.key) }?.value
            ?: ShellResult(1, "", "no fake response for $key")
    }
}

class FakeEventSource(
    val flow: MutableSharedFlow<MonitorEvent> = MutableSharedFlow(extraBufferCapacity = 64),
) : MonitorEventSource {
    override fun events(): Flow<MonitorEvent> = flow
}

/** Accepts every codec it was constructed with, refuses the rest. */
class FakeCodecController(
    private val available: List<CodecFamily>,
    private val refuse: Set<CodecFamily> = emptySet(),
) : CodecController {
    override suspend fun availableCodecs(address: String) = available
    override suspend fun selectCodec(address: String, codec: CodecFamily): CodecFamily? =
        if (codec in refuse) null else codec
}

/** Deterministic clock for soak/window tests. */
class TestClock(var nowMs: Long = 0L) {
    fun now(): Long = nowMs
    fun advance(ms: Long) {
        nowMs += ms
    }
}
