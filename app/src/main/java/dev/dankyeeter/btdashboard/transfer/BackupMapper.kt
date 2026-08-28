package dev.dankyeeter.btdashboard.transfer

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.AgeReference
import dev.dankyeeter.btdashboard.hearing.AncMode
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.ClinicalAudiogram
import dev.dankyeeter.btdashboard.hearing.CompensationProfile
import dev.dankyeeter.btdashboard.hearing.DerivedCalibration
import dev.dankyeeter.btdashboard.hearing.Iso7029Sex
import dev.dankyeeter.btdashboard.hearing.TEST_FREQUENCIES_HZ
import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
import dev.dankyeeter.btdashboard.hearing.preference.FinalCheck
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceCandidate
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceLabelSource
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceProfile
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceRun

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
        deviceName = run.deviceName,
        calibrationPresetId = run.calibrationPresetId,
        ancMode = run.ancMode.name,
        ambientNoiseDbA = run.ambientNoiseDbA,
        volumeFraction = run.volumeFraction,
        left = run.left.map(::toBackup),
        right = run.right.map(::toBackup),
    )

    fun toBackup(audiogram: Audiogram): BackupAudiogram = BackupAudiogram(
        runIds = audiogram.runIds,
        left = audiogram.left.map(::toBackup),
        right = audiogram.right.map(::toBackup),
    )

    fun toBackup(clinical: ClinicalAudiogram): BackupClinicalAudiogram = BackupClinicalAudiogram(
        leftDbHl = clinical.leftDbHl.mapKeys { it.key.toString() },
        rightDbHl = clinical.rightDbHl.mapKeys { it.key.toString() },
        measuredOn = clinical.measuredOn,
        source = clinical.source,
        savedAtMillis = clinical.savedAtMillis,
    )

    fun toBackup(calibration: DerivedCalibration): BackupDerivedCalibration = BackupDerivedCalibration(
        deviceKey = calibration.deviceKey,
        deviceName = calibration.deviceName,
        responseDeviationDb = calibration.responseDeviationDb,
        earSpreadDb = calibration.earSpreadDb,
        warnings = calibration.warnings,
        createdAtMillis = calibration.createdAtMillis,
        sourceRunIds = calibration.sourceRunIds,
    )

    fun toBackup(profile: PreferenceProfile): BackupPreferenceProfile = BackupPreferenceProfile(
        deviceKey = profile.deviceKey,
        deviceName = profile.deviceName,
        layout = profile.layout.id,
        baseLeftDb = profile.baseLeftDb,
        baseRightDb = profile.baseRightDb,
        manualBassDb = profile.manualBassDb,
        manualTrebleDb = profile.manualTrebleDb,
        finalCheck = profile.finalCheck.name,
        createdAtMillis = profile.createdAtMillis,
        updatedAtMillis = profile.updatedAtMillis,
        runs = profile.runs.map(::toBackup),
    )

    fun toBackup(run: PreferenceRun): BackupPreferenceRun = BackupPreferenceRun(
        id = run.id,
        label = run.label,
        labelSource = run.labelSource.name,
        createdAtMillis = run.createdAtMillis,
        bassDb = run.candidate.bassDb,
        trebleDb = run.candidate.trebleDb,
        consistency = run.consistency,
    )

    fun toBackup(age: AgeReference): BackupAgeReference = BackupAgeReference(
        birthYear = age.birthYear,
        sex = age.sex.name,
    )

    fun toBackup(eq: EqSettings): BackupEq = BackupEq(
        enabled = eq.enabled,
        // Without this the gain list is just a bare row of numbers, and the
        // importer has to guess which frequencies they sit on.
        layout = eq.layout.id,
        leftGainsDb = eq.leftGainsDb,
        rightGainsDb = eq.rightGainsDb,
        preGainDb = eq.preGainDb,
        limiterEnabled = eq.limiterEnabled,
        autoHeadroom = eq.autoHeadroom,
        loudnessRestoration = eq.loudnessRestoration,
        volumeAwareTilt = eq.volumeAwareTilt,
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
        clinical: ClinicalAudiogram? = null,
        derivedCalibrations: List<DerivedCalibration> = emptyList(),
        ageReference: AgeReference? = null,
        preferenceProfiles: List<PreferenceProfile> = emptyList(),
    ): BackupDocument = BackupDocument(
        appVersion = appVersion,
        exportedAtMillis = nowMillis,
        hearingRuns = runs.map(::toBackup),
        audiogram = audiogram?.let(::toBackup),
        profiles = profiles.map(::toBackup),
        eq = toBackup(eq),
        activeProfileId = activeProfileId,
        clinicalAudiogram = clinical?.takeUnless { it.isEmpty }?.let(::toBackup),
        derivedCalibrations = derivedCalibrations.map(::toBackup),
        ageReference = ageReference?.let(::toBackup),
        preferenceProfiles = preferenceProfiles.map(::toBackup),
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
        deviceName = run.deviceName,
        calibrationPresetId = run.calibrationPresetId,
        ancMode = ancMode(run.ancMode),
        ambientNoiseDbA = run.ambientNoiseDbA,
        volumeFraction = run.volumeFraction,
        left = run.left.map(::toDomain),
        right = run.right.map(::toDomain),
    )

    fun toDomain(audiogram: BackupAudiogram): Audiogram = Audiogram(
        runIds = audiogram.runIds,
        left = audiogram.left.map(::toDomain),
        right = audiogram.right.map(::toDomain),
    )

    /**
     * A frequency key that is not a number is dropped on its own rather than
     * costing the whole record — the same rule the DataStore parser follows,
     * because a hand-edited backup is exactly as likely to be malformed as a
     * hand-edited preference.
     */
    fun toDomain(clinical: BackupClinicalAudiogram): ClinicalAudiogram = ClinicalAudiogram(
        leftDbHl = clinical.leftDbHl.toFrequencyMap(),
        rightDbHl = clinical.rightDbHl.toFrequencyMap(),
        measuredOn = clinical.measuredOn,
        source = clinical.source,
        savedAtMillis = clinical.savedAtMillis,
    )

    /**
     * A record whose deviation list does not match [TEST_FREQUENCIES_HZ] is
     * dropped rather than padded: `CalibrationPreset` requires the alignment in
     * its constructor, and a padded list would be a device response the app
     * invented at the frequencies it filled in.
     */
    fun toDomain(calibration: BackupDerivedCalibration): DerivedCalibration? {
        if (calibration.deviceKey.isBlank()) return null
        if (calibration.responseDeviationDb.size != TEST_FREQUENCIES_HZ.size) return null
        return DerivedCalibration(
            deviceKey = calibration.deviceKey,
            deviceName = calibration.deviceName,
            responseDeviationDb = calibration.responseDeviationDb,
            earSpreadDb = calibration.earSpreadDb,
            warnings = calibration.warnings,
            createdAtMillis = calibration.createdAtMillis,
            sourceRunIds = calibration.sourceRunIds,
        )
    }

    /**
     * A birth year of zero or less is not a year — it is the default this field
     * carries when a hand-edited file leaves it out — so it comes back as "no
     * age reference" rather than as a person born in year 0. An unknown sex
     * name degrades to `UNSPECIFIED`, the same rule [ancMode] follows.
     */
    fun toDomain(age: BackupAgeReference): AgeReference? {
        if (age.birthYear <= 0) return null
        return AgeReference(
            birthYear = age.birthYear,
            sex = runCatching { Iso7029Sex.valueOf(age.sex) }.getOrDefault(Iso7029Sex.UNSPECIFIED),
        )
    }

    /**
     * A preference curve back off disk.
     *
     * Dropped rather than repaired when it names no device: the record is only
     * ever applied to the headphone it belongs to, so one with no headphone has
     * nothing it could ever do except sit there.
     *
     * The base curve is fitted to the layout the file names, by the same
     * resample-rather-than-truncate rule [toDomain] applies to an EQ curve — a
     * backup written on a phone whose EQ was on a different grid is still the
     * same curve.
     */
    fun toDomain(profile: BackupPreferenceProfile): PreferenceProfile? {
        if (profile.deviceKey.isBlank()) return null
        val layout = EqBandLayout.fromId(profile.layout)
        return PreferenceProfile(
            deviceKey = profile.deviceKey,
            deviceName = profile.deviceName,
            runs = profile.runs.mapNotNull(::toDomain),
            layout = layout,
            baseLeftDb = profile.baseLeftDb.fittedTo(layout),
            baseRightDb = profile.baseRightDb.fittedTo(layout),
            manualBassDb = profile.manualBassDb,
            manualTrebleDb = profile.manualTrebleDb,
            finalCheck = runCatching { FinalCheck.valueOf(profile.finalCheck) }
                .getOrDefault(FinalCheck.NOT_RUN),
            createdAtMillis = profile.createdAtMillis,
            updatedAtMillis = profile.updatedAtMillis,
        )
    }

    /** A run with no id cannot be replaced or removed later, so it is dropped. */
    fun toDomain(run: BackupPreferenceRun): PreferenceRun? {
        if (run.id.isBlank()) return null
        return PreferenceRun(
            id = run.id,
            label = run.label,
            labelSource = runCatching { PreferenceLabelSource.valueOf(run.labelSource) }
                .getOrDefault(PreferenceLabelSource.NONE),
            createdAtMillis = run.createdAtMillis,
            candidate = PreferenceCandidate(run.bassDb, run.trebleDb).clamped(),
            consistency = run.consistency.coerceIn(0.0, 1.0),
            // Not carried by the file; see [BackupPreferenceRun].
            trials = emptyList(),
        )
    }

    private fun Map<String, Double>.toFrequencyMap(): Map<Int, Double> =
        mapNotNull { (hz, db) -> hz.toIntOrNull()?.let { it to db } }.toMap()

    /**
     * Always sanitised: an imported curve must obey the app's gain limits.
     *
     * The curve is restored at the resolution it was saved at. This used to pad
     * or truncate every list to the ten-band default, which meant a 20- or
     * 31-band export came back as its first ten gains reinterpreted as octave
     * bands — the user's curve destroyed, quietly, with the import reporting
     * success.
     */
    fun toDomain(eq: BackupEq): EqSettings {
        val layout = layoutOf(eq)
        return EqSettings(
            enabled = eq.enabled,
            layout = layout,
            leftGainsDb = eq.leftGainsDb.fittedTo(layout),
            rightGainsDb = eq.rightGainsDb.fittedTo(layout),
            preGainDb = eq.preGainDb,
            limiterEnabled = eq.limiterEnabled,
            autoHeadroom = eq.autoHeadroom,
            loudnessRestoration = eq.loudnessRestoration,
            volumeAwareTilt = eq.volumeAwareTilt,
            // No tilt gains: the layer is derived on the way to the effect from
            // the volume that is set now. Importing zeros is not a loss.
        ).sanitized()
    }

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
     * Which layout an imported curve belongs to.
     *
     * A file written before [BackupEq.layout] existed has no id, but its band
     * count still says which layout it was: the three layouts have distinct
     * counts, so the length is an unambiguous fallback. Left is asked first and
     * right only if left is unusable, because a hand-edited file can disagree
     * with itself and one of the two has to win.
     */
    private fun layoutOf(eq: BackupEq): EqBandLayout {
        eq.layout?.let { return EqBandLayout.fromId(it) }
        return layoutWithBandCount(eq.leftGainsDb.size)
            ?: layoutWithBandCount(eq.rightGainsDb.size)
            ?: EqBandLayout.DEFAULT
    }

    private fun layoutWithBandCount(count: Int): EqBandLayout? =
        EqBandLayout.entries.firstOrNull { it.bandCount == count }

    /**
     * Makes a gain list fit [layout]. Same rule as
     * `EqSettingsStore.parseGains`: a list whose length belongs to a *different*
     * layout is the user's curve at another resolution, so it is resampled
     * rather than discarded; a length that matches no layout is not a curve at
     * all and degrades to flat. Never truncated — that was the data loss.
     */
    private fun List<Float>.fittedTo(layout: EqBandLayout): List<Float> {
        if (size == layout.bandCount) return this
        val source = layoutWithBandCount(size) ?: return List(layout.bandCount) { 0f }
        return EqBandLayout.resample(this, source, layout)
    }
}
