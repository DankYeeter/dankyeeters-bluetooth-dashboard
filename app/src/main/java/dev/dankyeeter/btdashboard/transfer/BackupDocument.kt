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
    /**
     * The clinical audiogram, when one has been entered.
     *
     * Worth more in a backup than anything else in this file: the runs can be
     * measured again in twenty minutes, and this cannot be re-obtained at all
     * without another appointment. Null in every file written before the field
     * existed, which is why it is nullable and defaulted — no version bump, as
     * nothing that already existed changed meaning.
     */
    @SerialName("clinicalAudiogram") val clinicalAudiogram: BackupClinicalAudiogram? = null,
)

/**
 * A clinical audiogram on disk.
 *
 * Thresholds are keyed by frequency as a *string*, because JSON object keys
 * are strings and a sparse map is the honest shape: a frequency the practice
 * did not test has no entry, and must never come back as 0 dB HL.
 *
 * The unit is dB HL and it is not negotiable — unlike [BackupThreshold.thresholdDb],
 * which is this app's internal dBFS. The two must not be mixed up on import,
 * which is the reason this is its own type rather than another
 * [BackupAudiogram].
 */
@Serializable
data class BackupClinicalAudiogram(
    @SerialName("leftDbHl") val leftDbHl: Map<String, Double> = emptyMap(),
    @SerialName("rightDbHl") val rightDbHl: Map<String, Double> = emptyMap(),
    @SerialName("measuredOn") val measuredOn: String = "",
    @SerialName("source") val source: String = "",
    @SerialName("savedAtMillis") val savedAtMillis: Long = 0L,
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
    @SerialName("deviceName") val deviceName: String? = null,
    @SerialName("calibrationPresetId") val calibrationPresetId: String,
    @SerialName("ancMode") val ancMode: String = "UNKNOWN",
    @SerialName("ambientNoiseDbA") val ambientNoiseDbA: Double? = null,
    @SerialName("volumeFraction") val volumeFraction: Double = 0.7,
    @SerialName("left") val left: List<BackupThreshold> = emptyList(),
    @SerialName("right") val right: List<BackupThreshold> = emptyList(),
)

@Serializable
data class BackupAudiogram(
    @SerialName("runIds") val runIds: List<String> = emptyList(),
    @SerialName("left") val left: List<BackupThreshold> = emptyList(),
    @SerialName("right") val right: List<BackupThreshold> = emptyList(),
)

/**
 * A stored EQ curve.
 *
 * [layout] was added after the first release, when the EQ stopped being fixed
 * at ten bands. It is nullable on purpose: a file written before the field
 * existed has no layout, and per this file's contract an added field must not
 * make such a file unreadable. Adding it is therefore *not* a
 * [BackupSchema.CURRENT_VERSION] bump — the version marks a change in what an
 * existing field *means*, and nothing here changed meaning.
 *
 * Without it, importing threw away everything past the tenth gain and read the
 * remaining ten as octave bands, so a 20- or 31-band curve came back as a
 * different, silently wrong curve. The band count carries the same information
 * for old files, which is why the importer can infer the layout from the list
 * length when the field is absent.
 */
@Serializable
data class BackupEq(
    @SerialName("enabled") val enabled: Boolean = false,
    /** [EqBandLayout.id]; null in files written before layouts existed. */
    @SerialName("layout") val layout: String? = null,
    @SerialName("leftGainsDb") val leftGainsDb: List<Float> = emptyList(),
    @SerialName("rightGainsDb") val rightGainsDb: List<Float> = emptyList(),
    @SerialName("preGainDb") val preGainDb: Float = 0f,
    @SerialName("limiterEnabled") val limiterEnabled: Boolean = true,
    @SerialName("autoHeadroom") val autoHeadroom: Boolean = true,
    @SerialName("loudnessRestoration") val loudnessRestoration: Boolean = false,
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
