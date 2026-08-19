package dev.dankyeeter.btdashboard.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.effects.EqCandidateScan
import dev.dankyeeter.btdashboard.monitor.effects.ForeignEqScanResult
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingPolicy
import dev.dankyeeter.btdashboard.ui.DetectedDeviceRepository
import dev.dankyeeter.btdashboard.hearing.PresetMatching
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.airpods.AirPodsBeacon
import dev.dankyeeter.btdashboard.system.airpods.AirPodsScanState
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
    /**
     * Battery and wear state, when the connected device happens to broadcast an
     * Apple proximity beacon. It belongs on the device's own row: a pair of
     * AirPods is a connected Bluetooth device like any other, not a separate
     * kind of thing that deserves its own card.
     */
    val beacon: AirPodsBeacon? = null,
)

data class BluetoothDashboardState(
    val loading: Boolean = true,
    val profileAvailable: Boolean = true,
    val rows: List<DeviceCodecRow> = emptyList(),
    val dataSource: LinkDataSource = LinkDataSource.NONE,
    val foreignEq: ForeignEqScanResult? = null,
    /**
     * "These apps could have their own EQ." Separate from [foreignEq] because
     * it answers a different question and, unlike the audio_flinger scan, needs
     * no Shizuku at all — it must still work when the shell identity does not.
     */
    val eqCandidates: EqCandidateScan? = null,
    val candidatesScanning: Boolean = false,
    val deepCaptureActive: Boolean = false,
)

class BluetoothDashboardViewModel : ViewModel() {

    private val airPods = SystemGraph.airPodsScanner

    private val _state = MutableStateFlow(BluetoothDashboardState())
    val state: StateFlow<BluetoothDashboardState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val source = MonitorGraph.codecSource
            val devices = source.connectedDevices()
            val beacon = (airPods.state.value as? AirPodsScanState.Found)?.beacon

            // The supported headphones are never listed in the UI, so the
            // connected device's own name is what selects its correction curve.
            devices.firstOrNull { it.isActive }?.let { active ->
                PresetMatching.presetIdFor(active.name)?.let(DetectedDeviceRepository::suggest)
            }
            val rows = devices.map { device ->
                when (val status = source.codecStatus(device.address)) {
                    is CodecReadResult.Available -> DeviceCodecRow(
                        device = device,
                        codecBadge = status.status.badge,
                        codecKnown = true,
                        beacon = beacon.takeIf { device.isAppleAudio() },
                    )

                    is CodecReadResult.Unsupported -> DeviceCodecRow(
                        device = device,
                        codecBadge = "Codec unknown",
                        codecKnown = false,
                        codecNote = status.reason,
                        beacon = beacon.takeIf { device.isAppleAudio() },
                    )

                    is CodecReadResult.Failed -> DeviceCodecRow(
                        device = device,
                        codecBadge = "Codec unknown",
                        codecKnown = false,
                        codecNote = status.reason,
                        beacon = beacon.takeIf { device.isAppleAudio() },
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

    fun startBeaconScan() = airPods.start()

    fun stopBeaconScan() = airPods.stop()

    /**
     * The beacon is BLE and advertises under a different address than the
     * classic A2DP link, so the two cannot be joined on address. The name is
     * what is left — good enough to decide which row shows battery.
     */
    private fun BtAudioDevice.isAppleAudio(): Boolean =
        name.contains("airpod", ignoreCase = true) || name.contains("beats", ignoreCase = true)

    /** Foreign-EQ scan is a shell operation — kept off the refresh path. */
    fun scanForeignEq() {
        viewModelScope.launch {
            _state.value = _state.value.copy(foreignEq = MonitorGraph.foreignEqScanner.scan())
        }
    }

    /**
     * Tier every installed app by how likely it is to carry its own EQ.
     *
     * Called when the section becomes visible and when the user taps "check
     * again" — never on a timer and never from [refresh]. The first call pays
     * for one pass over the package list (a few hundred milliseconds on a
     * background dispatcher, measured and shown in the UI); every later call
     * reuses the cached pass and only re-reads what is currently playing, which
     * is a single cheap query.
     *
     * @param refresh drop the cached package pass and look again.
     */
    fun scanEqCandidates(refresh: Boolean = false) {
        // Opening the section twice must not queue two package passes.
        if (_state.value.candidatesScanning) return
        val scanner = MonitorGraph.eqCandidateScanner

        viewModelScope.launch {
            _state.value = _state.value.copy(candidatesScanning = true)
            val scan = scanner.scan(refresh = refresh)
            _state.value = _state.value.copy(eqCandidates = scan, candidatesScanning = false)
        }
    }

    /**
     * Event-driven playing-now updates, bound to the screen being visible.
     * The callback re-runs the cheap overlay only; the package pass stays
     * cached, so nothing expensive happens when a track starts.
     */
    fun startPlaybackWatch() {
        MonitorGraph.playbackWatcher.start {
            if (_state.value.eqCandidates != null) scanEqCandidates(refresh = false)
        }
    }

    fun stopPlaybackWatch() = MonitorGraph.playbackWatcher.stop()

    /** "Watch live": jump the sampler straight into 10-second deep capture. */
    fun startWatchLive() {
        MonitorGraph.engine.startDeepCapture(SamplingPolicy.DEEP_CAPTURE_WINDOW_MS)
        MonitorGraph.ensureRunning()
        _state.value = _state.value.copy(deepCaptureActive = true)
    }
}
