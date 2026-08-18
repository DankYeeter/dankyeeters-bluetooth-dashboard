package dev.dankyeeter.btdashboard.system.devices

/** Media-stream volume, expressed in percent so the UI is device-independent. */
interface MediaVolumeController {
    /** Null when the audio service is unreachable. */
    fun currentPercent(): Int?

    /** Returns false if the write was refused (e.g. DND / fixed-volume device). */
    fun setPercent(percent: Int): Boolean
}

/** Activates a stored compensation profile on the system EQ. */
interface CompensationApplier {
    /** False when the profile id no longer exists. */
    suspend fun apply(compensationProfileId: String): Boolean
}

/** Android's global absolute-volume switch (see [AbsoluteVolumeGate]). */
interface AbsoluteVolumeController {
    fun isWritable(): Boolean

    /** Null when the value cannot be read. */
    fun isEnabled(): Boolean?

    fun setEnabled(enabled: Boolean): Boolean
}

/**
 * The core of spec function 2: a known headphone connects, its stored profile
 * is applied.
 *
 * Deliberately free of Android types so it can be tested with plain fakes. The
 * caller hands in a raw address; hashing to a [DeviceKey] happens here so that
 * every entry point agrees on one key derivation.
 *
 * Each step is independent: a failed volume write must not stop the EQ profile
 * from being applied. Anything that could not be done is reported as
 * [ProfileAction.Skipped] with a reason, because a profile that silently did
 * half its job is exactly the kind of dishonest UI this project avoids.
 */
class DeviceProfileApplier(
    private val profiles: DeviceProfileSource,
    private val volume: MediaVolumeController,
    private val compensation: CompensationApplier,
    private val absoluteVolume: AbsoluteVolumeController,
) {

    suspend fun onDeviceConnected(address: String?): ApplyResult {
        val key = DeviceKey.fromAddress(address) ?: return ApplyResult.UnknownAddress
        val profile = profiles.profileFor(key) ?: return ApplyResult.NoProfile(key)
        if (!profile.autoApply) return ApplyResult.AutoApplyDisabled(profile)
        return ApplyResult.Applied(profile, applyNow(profile))
    }

    /** Applies a profile regardless of [DeviceProfile.autoApply] (manual "Apply now"). */
    suspend fun applyNow(profile: DeviceProfile): List<ProfileAction> = buildList {
        profile.compensationProfileId?.let { id ->
            if (compensation.apply(id)) {
                add(ProfileAction.CompensationApplied(id))
            } else {
                add(ProfileAction.Skipped("compensation profile", "the stored profile no longer exists"))
            }
        }

        profile.mediaVolumePercent?.let { percent ->
            val target = percent.coerceIn(0, 100)
            if (volume.setPercent(target)) {
                add(ProfileAction.VolumeSet(target))
            } else {
                add(ProfileAction.Skipped("media volume", "the system refused the volume change"))
            }
        }

        profile.absoluteVolumeEnabled?.let { enabled ->
            when {
                !absoluteVolume.isWritable() ->
                    add(ProfileAction.Skipped("absolute volume", "WRITE_SECURE_SETTINGS is not granted"))

                absoluteVolume.isEnabled() == enabled ->
                    add(ProfileAction.AbsoluteVolumeSet(enabled))

                absoluteVolume.setEnabled(enabled) ->
                    add(ProfileAction.AbsoluteVolumeSet(enabled))

                else ->
                    add(ProfileAction.Skipped("absolute volume", "the setting could not be written"))
            }
        }
    }
}
