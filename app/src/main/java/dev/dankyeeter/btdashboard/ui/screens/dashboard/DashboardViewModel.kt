package dev.dankyeeter.btdashboard.ui.screens.dashboard

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.hearing.CalibrationPreset
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.nowplaying.CodecSummary
import dev.dankyeeter.btdashboard.nowplaying.NowPlaying
import dev.dankyeeter.btdashboard.nowplaying.NowPlayingCodecRegistry
import dev.dankyeeter.btdashboard.nowplaying.NowPlayingRepository
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.airpods.AirPodsScanState
import dev.dankyeeter.btdashboard.transfer.BackupExportResult
import dev.dankyeeter.btdashboard.transfer.BackupImportResult
import dev.dankyeeter.btdashboard.transfer.BackupRepository
import dev.dankyeeter.btdashboard.system.setup.SetupStatus
import dev.dankyeeter.btdashboard.ui.DetectedDeviceRepository
import dev.dankyeeter.btdashboard.ui.screens.wizard.AndroidSetupEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Dashboard state: what is playing, through which codec, on which device.
 *
 * An [AndroidViewModel] because the backup flow needs a context for the
 * Storage Access Framework URIs; everything else comes from the graphs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = SystemGraph.airPodsScanner
    private val backups = BackupRepository(application)

    val nowPlaying: StateFlow<NowPlaying?> = NowPlayingRepository.current
    val listenerConnected: StateFlow<Boolean> = NowPlayingRepository.listenerConnected

    /**
     * Codec of the active link, once the monitor registers a source. Until
     * then this stays null and the now-playing line simply omits the codec
     * clause instead of inventing one.
     */
    val codec: StateFlow<CodecSummary?> = NowPlayingCodecRegistry.source
        .flatMapLatest { it?.codec ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val airPods: StateFlow<AirPodsScanState> = scanner.state

    val presets: List<CalibrationPreset> = HearingGraph.presets.all()

    private val _backupMessage = MutableStateFlow<BackupMessage?>(null)
    val backupMessage: StateFlow<BackupMessage?> = _backupMessage.asStateFlow()

    // ---- setup status ---------------------------------------------------------

    private val setupEnvironment = AndroidSetupEnvironment(application)
    private val setupTick = MutableStateFlow(0)

    /**
     * "Setup incomplete: N steps left", or null once nothing is outstanding.
     * Recomputed from [setupTick] on every resume, so granting a permission
     * elsewhere makes the card disappear without a restart.
     */
    val setupSummary: StateFlow<String?> =
        combine(SystemGraph.setupStore.skippedStepIds, setupTick) { skipped, _ ->
            SetupStatus.summary(SetupStatus.evaluate(setupEnvironment, skipped))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refreshSetupStatus() {
        setupTick.value += 1
    }

    init {
        // Model detection drives preset selection: the EQ screen adopts the
        // suggestion only while it is still on the generic preset, so a
        // detected device can never overwrite a deliberate choice.
        viewModelScope.launch {
            scanner.state.collect { state ->
                val presetId = (state as? AirPodsScanState.Found)
                    ?.beacon?.model?.calibrationPresetId
                if (presetId != null) DetectedDeviceRepository.suggest(presetId)
            }
        }
    }

    fun startScan() = scanner.start()

    fun stopScan() = scanner.stop()

    fun hasScanPermission(): Boolean = scanner.hasScanPermission()

    // ---- backup ---------------------------------------------------------------

    fun export(uri: Uri) {
        viewModelScope.launch {
            _backupMessage.value = when (val result = backups.export(uri)) {
                is BackupExportResult.Success -> BackupMessage(
                    text = "Exported ${result.runCount} hearing run(s) and " +
                        "${result.profileCount} profile(s).",
                    isError = false,
                )
                is BackupExportResult.Failure -> BackupMessage(result.message, isError = true)
            }
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            _backupMessage.value = when (val result = backups.import(uri)) {
                is BackupImportResult.Success -> BackupMessage(
                    text = buildString {
                        append("Imported ${result.runCount} hearing run(s) and ")
                        append("${result.profileCount} profile(s)")
                        append(if (result.eqImported) ", including the EQ curve." else ".")
                        result.warnings.forEach { append("\n• $it") }
                    },
                    isError = false,
                )
                is BackupImportResult.Failure -> BackupMessage(result.message, isError = true)
            }
        }
    }

    fun dismissBackupMessage() {
        _backupMessage.value = null
    }
}

data class BackupMessage(val text: String, val isError: Boolean)
