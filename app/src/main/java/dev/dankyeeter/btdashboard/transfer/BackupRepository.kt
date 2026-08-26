package dev.dankyeeter.btdashboard.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.hearing.store.AudiogramStore
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.devices.DeviceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Reads and writes backup files through the Storage Access Framework.
 *
 * SAF means the user picks the location in the system file picker and we get a
 * one-shot [Uri] — no storage permission is requested, and the app can never
 * browse the user's files on its own. That is the whole reason this goes
 * through SAF rather than a path in external storage.
 */
class BackupRepository(context: Context) {

    private val appContext = context.applicationContext

    /** Writes the current state of every store into [uri]. */
    suspend fun export(uri: Uri): BackupExportResult = withContext(Dispatchers.IO) {
        try {
            val runs = HearingGraph.audiogramStore.currentRuns()
            val document = BackupMapper.buildDocument(
                runs = runs,
                // Every run is backed up, but the stored curve is the one the
                // app actually uses - the median of the chosen runs, not of
                // all of them.
                audiogram = exportedSelection(runs)
                    .takeIf { it.isNotEmpty() }
                    ?.let { HearingGraph.aggregator.aggregate(it) },
                profiles = HearingGraph.profileStore.current(),
                eq = SystemGraph.settingsStore.current(),
                activeProfileId = activeProfileId(),
                appVersion = appVersion(),
                nowMillis = System.currentTimeMillis(),
                // Not device-scoped like everything above it: an audiogram is a
                // property of the ears, so there is exactly one and it goes into
                // every backup regardless of what is connected.
                clinical = HearingGraph.audiogramStore.currentClinicalAudiogram(),
            )
            val json = BackupCodec.encode(document)
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
            } ?: return@withContext BackupExportResult.Failure("The file could not be opened for writing.")

            BackupExportResult.Success(
                runCount = document.hearingRuns.size,
                profileCount = document.profiles.size,
            )
        } catch (e: IOException) {
            Log.w(TAG, "export failed", e)
            BackupExportResult.Failure("Writing the file failed: ${e.message ?: "I/O error"}.")
        } catch (e: SecurityException) {
            Log.w(TAG, "export denied", e)
            BackupExportResult.Failure("No permission to write to that location.")
        }
    }

    /**
     * Merges the contents of [uri] into the local stores.
     *
     * Merge, not replace: runs and profiles are inserted by id, so importing
     * the same file twice is a no-op instead of creating duplicates, and a
     * backup from the other phone adds to what is already here. The EQ curve is
     * only written when the file carries one.
     */
    suspend fun import(uri: Uri): BackupImportResult = withContext(Dispatchers.IO) {
        val raw = try {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: return@withContext BackupImportResult.Failure("The file could not be opened.")
        } catch (e: IOException) {
            Log.w(TAG, "import failed", e)
            return@withContext BackupImportResult.Failure("Reading the file failed: ${e.message ?: "I/O error"}.")
        } catch (e: SecurityException) {
            Log.w(TAG, "import denied", e)
            return@withContext BackupImportResult.Failure("No permission to read that file.")
        }

        when (val parsed = BackupCodec.decode(raw)) {
            is BackupParseResult.Failure -> BackupImportResult.Failure(parsed.message)
            is BackupParseResult.Success -> {
                val document = parsed.document
                document.hearingRuns.forEach { HearingGraph.audiogramStore.addRun(BackupMapper.toDomain(it)) }
                document.profiles.forEach { HearingGraph.profileStore.save(BackupMapper.toDomain(it)) }
                document.eq?.let { SystemGraph.settingsStore.save(BackupMapper.toDomain(it)) }
                document.activeProfileId?.let { SystemGraph.settingsStore.setActiveProfileId(it) }
                // Overwrites rather than merges, unlike the runs above. There is
                // one pair of ears and therefore one clinical audiogram; merging
                // two of them frequency by frequency would produce a document no
                // practice ever issued.
                document.clinicalAudiogram?.let {
                    HearingGraph.audiogramStore.saveClinicalAudiogram(BackupMapper.toDomain(it))
                }

                BackupImportResult.Success(
                    runCount = document.hearingRuns.size,
                    profileCount = document.profiles.size,
                    eqImported = document.eq != null,
                    warnings = parsed.warnings,
                )
            }
        }
    }

    /**
     * The runs the exported curve is built from — resolved the way the
     * equaliser resolves it, through the headphone that is connected.
     *
     * The backup should carry the curve the user actually hears. The old code
     * asked for the device-unaware selection, which is the one curve no screen
     * in the app shows: with two headphones in the history it could hand the
     * file a median of runs measured through the one that is not on the head,
     * and restoring that on a new phone would apply a correction for hardware
     * that is not there. [dev.dankyeeter.btdashboard.ui.screens.eq.EqViewModel]
     * picks the active device the same way, so the exported curve and the live
     * one are the same curve.
     *
     * With nothing connected there is no device to be aware of, and the
     * device-unaware selection is the honest answer rather than an empty
     * backup: exporting while the headphones are in the case is a perfectly
     * ordinary thing to do.
     */
    private suspend fun exportedSelection(runs: List<AudiogramRun>): List<AudiogramRun> {
        val ids = HearingGraph.audiogramStore.currentSelectedRunIds()
        // Reading the device is best-effort: a backup must not fail because the
        // Bluetooth profile is unbound or the permission was never granted.
        val devices = runCatching { MonitorGraph.codecSource.connectedDevices() }
            .getOrDefault(emptyList())
        val device = devices.firstOrNull { it.isActive } ?: devices.firstOrNull()
        return AudiogramStore.selectionOf(runs, ids, DeviceKey.fromAddress(device?.address))
    }

    private suspend fun activeProfileId(): String? =
        runCatching { SystemGraph.settingsStore.activeProfileIdOrNull() }.getOrNull()

    private fun appVersion(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    private companion object {
        const val TAG = "BackupRepository"
    }
}

sealed interface BackupExportResult {
    data class Success(val runCount: Int, val profileCount: Int) : BackupExportResult
    data class Failure(val message: String) : BackupExportResult
}

sealed interface BackupImportResult {
    data class Success(
        val runCount: Int,
        val profileCount: Int,
        val eqImported: Boolean,
        val warnings: List<String> = emptyList(),
    ) : BackupImportResult

    data class Failure(val message: String) : BackupImportResult
}
