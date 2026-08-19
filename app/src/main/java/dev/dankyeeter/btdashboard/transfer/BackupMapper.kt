package dev.dankyeeter.btdashboard.transfer

import dev.dankyeeter.btdashboard.audio.eq.EqBands
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.CompensationProfile
import dev.dankyeeter.btdashboard.hearing.ThresholdPoint

/**
 * Translation between the domain models and the backup wire format.
 *
 * Deliberately boring and total: every mapping in this file is pure, so the
 * round trip is testable without Android. Unknown enum names degrade to
 * [AncMode.UNKNOWN] and EQ curves come back through [EqSettings.sanitized] so
 * an edited file cannot inject out-of-range gains into the audio chain.
 */
object BackupMapper {

    // ---- domain -> file -------------------------------------------------------

    fun toBackup(point: ThresholdPoint): BackupThreshold = BackupThreshold(
        frequencyHz = point.frequencyHz,
        thresholdDb = point.thresholdDb,
        responseCount = point.responseCount,
        presentationCount = point.presentationCount,
        converged = point.converged,
    )

    fun toBackup(run: AudiogramRun): BackupRun = BackupRun(
        id = run.id,
        timestampMillis = run.timestampMillis,
        deviceAddressHash = run.deviceAddressHash,
        calibrationPresetId = run.calibrationPresetId,
        ancMode = run.ancMode.name,
        ambientNoiseDbA = run.ambientNoiseDbA,
        left = run.left.map(::toBackup),
        right = run.right.map(::toBackup),
    )

    fun toBackup(audiogram: Audiogram): BackupAudiogram = BackupAudiogram(
        runIds = audiogram.runIds,
        left = audiogram.left.map(::toBackup),
        right = audiogram.right.map(::toBackup),
    )

    fun toBackup(eq: EqSettings): BackupEq = BackupEq(
        enabled = eq.enabled,
        leftGainsDb = eq.leftGainsDb,
        rightGainsDb = eq.rightGainsDb,
        preGainDb = eq.preGainDb,
        limiterEnabled = eq.limiterEnabled,
    )

    fun toBackup(profile: CompensationProfile): BackupProfile = BackupProfile(
        id = profile.id,
        name = profile.name,
        createdAtMillis = profile.createdAtMillis,
        audiogram = profile.audiogram?.let(::toBackup),
        calibrationPresetId = profile.calibrationPresetId,
        ancMode = profile.ancMode.name,
        intensity = profile.intensity,
        partialFactor = profile.partialFactor,
        eq = toBackup(profile.eq),
    )

    /** Assembles a complete export. */
    fun buildDocument(
        runs: List<AudiogramRun>,
        audiogram: Audiogram?,
        profiles: List<CompensationProfile>,
        eq: EqSettings,
        activeProfileId: String?,
        appVersion: String,
        nowMillis: Long,
    ): BackupDocument = BackupDocument(
        appVersion = appVersion,
        exportedAtMillis = nowMillis,
        hearingRuns = runs.map(::toBackup),
        audiogram = audiogram?.let(::toBackup),
        profiles = profiles.map(::toBackup),
        eq = toBackup(eq),
        activeProfileId = activeProfileId,
    )

    // ---- file -> domain -------------------------------------------------------

    fun toDomain(point: BackupThreshold): ThresholdPoint = ThresholdPoint(
        frequencyHz = point.frequencyHz,
        thresholdDb = point.thresholdDb,
        responseCount = point.responseCount,
        presentationCount = point.presentationCount,
        converged = point.converged,
    )

    fun toDomain(run: BackupRun): AudiogramRun = AudiogramRun(
        id = run.id,
        timestampMillis = run.timestampMillis,
        deviceAddressHash = run.deviceAddressHash,
        calibrationPresetId = run.calibrationPresetId,
        ancMode = ancMode(run.ancMode),
        ambientNoiseDbA = run.ambientNoiseDbA,
        left = run.left.map(::toDomain),
        right = run.right.map(::toDomain),
    )

    fun toDomain(audiogram: BackupAudiogram): Audiogram = Audiogram(
        runIds = audiogram.runIds,
        left = audiogram.left.map(::toDomain),
        right = audiogram.right.map(::toDomain),
    )

    /** Always sanitised: an imported curve must obey the app's gain limits. */
    fun toDomain(eq: BackupEq): EqSettings = EqSettings(
        enabled = eq.enabled,
        leftGainsDb = eq.leftGainsDb.padded(),
        rightGainsDb = eq.rightGainsDb.padded(),
        preGainDb = eq.preGainDb,
        limiterEnabled = eq.limiterEnabled,
    ).sanitized()

    fun toDomain(profile: BackupProfile): CompensationProfile = CompensationProfile(
        id = profile.id,
        name = profile.name,
        createdAtMillis = profile.createdAtMillis,
        audiogram = profile.audiogram?.let(::toDomain),
        calibrationPresetId = profile.calibrationPresetId,
        ancMode = ancMode(profile.ancMode),
        intensity = profile.intensity.coerceIn(0f, 1f),
        partialFactor = profile.partialFactor.coerceIn(0f, 1f),
        eq = toDomain(profile.eq),
    )

    private fun ancMode(name: String): AncMode =
        runCatching { AncMode.valueOf(name) }.getOrDefault(AncMode.UNKNOWN)

    /**
     * The codec already rejects wrong-sized curves, so this only guards the
     * `EqSettings` constructor against a caller that skipped validation.
     */
    private fun List<Float>.padded(): List<Float> =
        if (size == EqBands.COUNT) this else List(EqBands.COUNT) { getOrElse(it) { 0f } }
}
