package dev.dankyeeter.btdashboard.monitorbridge

import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.nowplaying.CodecSummary
import dev.dankyeeter.btdashboard.nowplaying.NowPlayingCodecRegistry
import dev.dankyeeter.btdashboard.nowplaying.NowPlayingCodecSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fills the codec seam the "now playing" card exposes.
 *
 * Deliberately the only place where :app's monitor code touches the dashboard's
 * now-playing feature: the monitor owns the codec data, the card owns the
 * sentence, and [NowPlayingCodecRegistry] keeps the two from depending on each
 * other. Unknown stays unknown — this never invents a codec name.
 */
class MonitorCodecSource(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val refreshIntervalMs: Long = 30_000L,
) : NowPlayingCodecSource {

    private val _codec = MutableStateFlow<CodecSummary?>(null)
    override val codec: StateFlow<CodecSummary?> = _codec.asStateFlow()

    fun start() {
        scope.launch {
            while (true) {
                _codec.value = readActiveCodec()
                delay(refreshIntervalMs)
            }
        }
    }

    private suspend fun readActiveCodec(): CodecSummary? {
        val source = MonitorGraph.codecSource
        val device = source.connectedDevices().firstOrNull { it.isActive }
            ?: source.connectedDevices().firstOrNull()
            ?: return null
        val status = source.codecStatus(device.address) as? CodecReadResult.Available
            ?: return null
        return CodecSummary(
            name = status.status.family.displayName,
            bitrateKbps = status.status.bitrateKbps,
            sampleRateHz = status.status.sampleRateHz,
        )
    }

    companion object {
        /** Called once from Application; safe if the monitor is unavailable. */
        fun install() {
            runCatching {
                val source = MonitorCodecSource()
                NowPlayingCodecRegistry.register(source)
                source.start()
            }
        }
    }
}
