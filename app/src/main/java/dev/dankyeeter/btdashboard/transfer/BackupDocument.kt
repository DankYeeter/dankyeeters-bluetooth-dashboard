package dev.dankyeeter.btdashboard.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * On-disk format of a profile export.
 *
 * This is a **wire format**, deliberately separate from the domain models in
 * :core-hearing and :core-audio. Those are free to change shape; this file is
 * a contract with every backup a user has ever written, so it only ever grows
 * fields, and every added field must have a default so old files keep loading.
 *
 * The file is plain, readable JSON. There is no cloud and no INTERNET
 * permission: a backup goes wherever the user points the system file picker,
 * and comes back the same way.
 */
@Serializable
data class BackupDocument(
    /**
     * Schema version of *this file*. Bump [BackupSchema.CURRENT_VERSION] when
     * the meaning of a field changes; a file from the future is refused with a
     * clear message instead of being half-read.
     */
    @SerialName("schemaVersion") val schemaVersion: Int = BackupSchema.CURRENT_VERSION,
    /** Marker so a stray JSON file is not mistaken for a backup. */
    @SerialName("format") val format: String = BackupSchema.FORMAT_ID,
    /** Informational only; never used for decisions. */
    @SerialName("appVersion") val appVersion: String = "",
    @SerialName("exportedAtMillis") val exportedAtMillis: Long = 0L,
    @SerialName("hearingRuns") val hearingRuns: List<BackupRun> = emptyList(),
    /**
     * The active median curve at export time. Re-derivable from the runs, kept
     * so a backup still says something when the runs are pruned later.
     */
    @SerialName("audiogram") val audiogram: BackupAudiogram? = null,
    @SerialName("profiles") val profiles: List<BackupProfile> = emptyList(),
    @SerialName("eq") val eq: BackupEq? = null,
    @SerialName("activeProfileId") val activeProfileId: String? = null,
)

@Serializable
data class BackupThreshold(
    @SerialName("frequencyHz") val frequencyHz: Int,
    @SerialName("thresholdDb") val thresholdDb: Double,
    @SerialName("responseCount") val responseCount: Int = 0,
    @SerialName("presentationCount") val presentationCount: Int = 0,
    @SerialName("converged") val converged: Boolean = true,
)

@Serializable
data class BackupRun(
    @SerialName("id") val id: String,
    @SerialName("timestampMillis") val timestampMillis: Long,
    @SerialName("deviceAddressHash") val deviceAddressHash: String? = null,
    @SerialName("calibrationPresetId") val calibrationPresetId: String,
    @SerialName("ancMode") val ancMode: String = "UNKNOWN",
    @SerialName("ambientNoiseDbA") val ambientNoiseDbA: Double? = null,
    @SerialName("left") val left: List<BackupThreshold> = emptyList(),
    @SerialName("right") val right: List<BackupThreshold> = emptyList(),
)

@Serializable
data class BackupAudiogram(
    @SerialName("runIds") val runIds: List<String> = emptyList(),
    @SerialName("left") val left: List<BackupThreshold> = emptyList(),
    @SerialName("right") val right: List<BackupThreshold> = emptyList(),
)

@Serializable
data class BackupEq(
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("leftGainsDb") val leftGainsDb: List<Float> = emptyList(),
    @SerialName("rightGainsDb") val rightGainsDb: List<Float> = emptyList(),
    @SerialName("preGainDb") val preGainDb: Float = 0f,
    @SerialName("limiterEnabled") val limiterEnabled: Boolean = true,
)

@Serializable
data class BackupProfile(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("createdAtMillis") val createdAtMillis: Long,
    /** Null for a hand-tuned preset: it has an EQ curve but no measurement. */
    @SerialName("audiogram") val audiogram: BackupAudiogram? = null,
    @SerialName("calibrationPresetId") val calibrationPresetId: String,
    @SerialName("ancMode") val ancMode: String = "UNKNOWN",
    @SerialName("intensity") val intensity: Float,
    @SerialName("partialFactor") val partialFactor: Float,
    @SerialName("eq") val eq: BackupEq,
)

object BackupSchema {
    /** Identifies our files; any other value is refused on import. */
    const val FORMAT_ID: String = "dankyeeters-bluetooth-dashboard-backup"

    /** Version 1: the initial Milestone 2 export. */
    const val CURRENT_VERSION: Int = 1

    /** Suggested file name for the system file picker. */
    const val MIME_TYPE: String = "application/json"

    fun defaultFileName(timestampMillis: Long): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US)
            .format(java.util.Date(timestampMillis))
        return "btdashboard-backup-$stamp.json"
    }
}
