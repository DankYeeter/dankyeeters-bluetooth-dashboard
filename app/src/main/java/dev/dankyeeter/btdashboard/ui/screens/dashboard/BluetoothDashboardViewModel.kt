package dev.dankyeeter.btdashboard.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.effects.ForeignEqScanResult
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One row on the dashboard: a device plus whatever we know about its codec. */
data class DeviceCodecRow(
    val device: BtAudioDevice,
    val codecBadge: String,
    val codecKnown: Boolean,
    val codecNote: String? = null,
)

data class BluetoothDashboardState(
    val loading: Boolean = true,
    val profileAvailable: Boolean = true,
    val rows: List<DeviceCodecRow> = emptyList(),
    val dataSource: LinkDataSource = LinkDataSource.NONE,
    val foreignEq: ForeignEqScanResult? = null,
    val deepCaptureActive: Boolean = false,
)

class BluetoothDashboardViewModel : ViewModel() {

    private val _state = MutableStateFlow(BluetoothDashboardState())
    val state: StateFlow<BluetoothDashboardState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val source = MonitorGraph.codecSource
            val devices = source.connectedDevices()
            val rows = devices.map { device ->
                when (val status = source.codecStatus(device.address)) {
                    is CodecReadResult.Available -> DeviceCodecRow(
                        device = device,
                        codecBadge = status.status.badge,
                        codecKnown = true,
                    )

                    is CodecReadResult.Unsupported -> DeviceCodecRow(
                        device = device,
                        codecBadge = "Codec unknown",
                        codecKnown = false,
                        codecNote = status.reason,
                    )

                    is CodecReadResult.Failed -> DeviceCodecRow(
                        device = device,
                        codecBadge = "Codec unknown",
                        codecKnown = false,
                        codecNote = status.reason,
                    )
                }
            }
            _state.value = _state.value.copy(
                loading = false,
                profileAvailable = source.isProfileAvailable,
                rows = rows,
                dataSource = if (source.isProfileAvailable) {
                    LinkDataSource.CODEC_API
                } else {
                    LinkDataSource.NONE
                },
            )
        }
    }

    /** Foreign-EQ scan is a shell operation — kept off the refresh path. */
    fun scanForeignEq() {
        viewModelScope.launch {
            _state.value = _state.value.copy(foreignEq = MonitorGraph.foreignEqScanner.scan())
        }
    }

    /** "Watch live": jump the sampler straight into 10-second deep capture. */
    fun startWatchLive() {
        MonitorGraph.engine.startDeepCapture(SamplingPolicy.DEEP_CAPTURE_WINDOW_MS)
        MonitorGraph.ensureRunning()
        _state.value = _state.value.copy(deepCaptureActive = true)
    }
}
