package dev.dankyeeter.btdashboard.ui.screens.dashboard

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.hearing.CalibrationPreset
import dev.dankyeeter.btdashboard.hearing.HearingGraph
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
 * Dashboard state: which device is connected, and what setup is still missing.
 *
 * An [AndroidViewModel] because the backup flow needs a context for the
 * Storage Access Framework URIs; everything else comes from the graphs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = SystemGraph.airPodsScanner
    private val backups = BackupRepository(application)

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

    fun stopScan() = scanner.stop()

    fun hasScanPermission(): Boolean = scanner.hasScanPermission()

    // ---- backup ---------------------------------------------------------------

    fun export(uri: Uri) {
        viewModelScope.launch {
            _backupMessage.value = when (val result = backups.export(uri)) {
                is BackupExportResult.Success -> BackupMessage(
                    text = buildString {
                        append("Exported ${plural(result.runCount, "hearing run")}")
                        // "and 0 profiles" reads as a failure of the export. It
                        // is not: there were none to write, and saying so is
                        // the only thing that tells the user whether the file
                        // they are about to keep is missing anything.
                        if (result.profileCount > 0) {
                            append(" and ${plural(result.profileCount, "profile")}.")
                        } else {
                            append(". No profiles were saved yet.")
                        }
                    },
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
                        append("Imported ${plural(result.runCount, "hearing run")} and ")
                        append(plural(result.profileCount, "profile"))
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

/**
 * "1 hearing run" / "2 hearing runs".
 *
 * The "(s)" form is what a program writes when it has not decided what it is
 * saying; every count here is known at the moment the sentence is built.
 */
private fun plural(count: Int, singular: String): String =
    if (count == 1) "$count $singular" else "$count ${singular}s"
