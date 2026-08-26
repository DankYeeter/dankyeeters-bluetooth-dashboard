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

    /**
     * Deletes the key so Android's own default applies again.
     *
     * Not the same as `setEnabled(true)`: writing the "enabled" value pins a
     * value the app chose, while an absent key is the state the phone shipped
     * in. It also clears a value some other writer left behind, which is the
     * only way out once anything has touched the setting.
     */
    fun clear(): Boolean
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
    private val secureSettings: SecureSettingsController,
    /**
     * Defaulted so the four callers that predate codec support — and every
     * existing test — keep compiling and keep behaving identically: without a
     * helper installed, a codec wish reports that it could not be attempted.
     */
    private val codec: CodecPreferenceController = UnavailableCodecPreferenceController,
    /**
     * Defaulted for the same reason [codec] is: every existing caller and test
     * predates HD audio, and without a helper the step reports that it could
     * not be attempted rather than that it was left alone.
     */
    private val hdAudio: HdAudioController = UnavailableHdAudioController,
) {

    suspend fun onDeviceConnected(address: String?): ApplyResult {
        val key = DeviceKey.fromAddress(address) ?: return ApplyResult.UnknownAddress
        val profile = profiles.profileFor(key) ?: return ApplyResult.NoProfile(key)
        if (!profile.autoApply) return ApplyResult.AutoApplyDisabled(profile)
        return ApplyResult.Applied(profile, applyNow(profile, address))
    }

    /**
     * Applies a profile regardless of [DeviceProfile.autoApply] (manual "Apply now").
     *
     * @param address the raw address, when the caller has one. Everything else
     *   in a profile is global and needs no device, but a codec is set *on* a
     *   `BluetoothDevice`, so without an address that one step cannot be
     *   attempted — and says so instead of being skipped silently. This class
     *   otherwise never sees a raw address (it hashes to a [DeviceKey]
     *   immediately), which is why the address is a parameter of this call
     *   rather than a field.
     */
    suspend fun applyNow(
        profile: DeviceProfile,
        address: String? = null,
    ): List<ProfileAction> = buildList {
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

        when {
            // Checked before the on/off wish: "hand it back" is a decision
            // about the setting as a whole and outranks a stale value beside it.
            profile.absoluteVolumeSystemDefault -> when {
                !absoluteVolume.isWritable() ->
                    add(ProfileAction.Skipped("absolute volume", "WRITE_SECURE_SETTINGS is not granted"))

                absoluteVolume.clear() ->
                    add(ProfileAction.AbsoluteVolumeReset)

                else ->
                    add(ProfileAction.Skipped("absolute volume", "the setting could not be reset"))
            }

            else -> profile.absoluteVolumeEnabled?.let { enabled ->
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

        profile.developerOptions.forEach { (key, value) ->
            val option = BluetoothDeveloperOptions.byKey(key)
            when {
                option == null ->
                    add(ProfileAction.Skipped(key, "this app no longer offers that option"))

                !secureSettings.isWritable() ->
                    add(ProfileAction.Skipped(option.label, "WRITE_SECURE_SETTINGS is not granted"))

                // "Use System Default" is a delete, not a write: an unset key
                // is the state a fresh phone is in, and the stack falls back to
                // its own default. It also undoes values other writers left.
                value == BluetoothDeveloperOptions.USE_SYSTEM_DEFAULT -> when {
                    secureSettings.read(key) == null -> add(
                        ProfileAction.DeveloperOptionSet(
                            key = key,
                            value = value,
                            needsBluetoothRestart = false,
                            alreadySet = true,
                        ),
                    )

                    secureSettings.clear(key) -> add(
                        ProfileAction.DeveloperOptionSet(
                            key = key,
                            value = value,
                            needsBluetoothRestart = option.needsBluetoothRestart,
                            alreadySet = false,
                        ),
                    )

                    else -> add(
                        ProfileAction.Skipped(
                            option.label,
                            "the key could not be cleared — it still holds a value",
                        ),
                    )
                }

                secureSettings.read(key) == value -> add(
                    ProfileAction.DeveloperOptionSet(
                        key = key,
                        value = value,
                        needsBluetoothRestart = false,
                        alreadySet = true,
                    ),
                )

                secureSettings.write(key, value) -> add(
                    ProfileAction.DeveloperOptionSet(
                        key = key,
                        value = value,
                        needsBluetoothRestart = option.needsBluetoothRestart,
                        alreadySet = false,
                    ),
                )

                // The only evidence available that a build ignores a key. Said
                // plainly rather than reported as success.
                else -> add(
                    ProfileAction.Skipped(
                        option.label,
                        "the value did not stick \u2014 this Android build may not support it",
                    ),
                )
            }
        }

        // Before the codec, and that order is load-bearing rather than tidy.
        // HD audio is the gate in front of the codec picker: with it off the
        // stack will not negotiate anything but SBC, so a codec request made
        // first would come back as SBC and be reported as "did not stick" —
        // technically true and completely misleading, because the cause was a
        // switch this same profile was about to turn on one line later.
        profile.hdAudio?.let { wish ->
            when {
                address == null -> add(
                    ProfileAction.Skipped(
                        "HD audio",
                        "the device address is not known here — connect the device and try again",
                    ),
                )

                !hdAudio.isAvailable() -> add(
                    ProfileAction.Skipped(
                        "HD audio",
                        "the privileged helper is not running, so HD audio can be neither " +
                            "set nor checked",
                    ),
                )

                else -> {
                    // Read first so an unchanged value costs nothing. Writing it
                    // anyway would be harmless in itself, but the stack drops the
                    // A2DP link to re-negotiate when this changes, and doing that
                    // on every connect to re-assert a value that was already
                    // right is an audible hiccup for no gain.
                    val before = hdAudio.read(address)
                    val alreadyRight = (before as? HdAudioState.Known)
                        ?.let { it.enabled == wish.asEnabled() } == true

                    if (alreadyRight) {
                        add(ProfileAction.HdAudioSet(wish.asEnabled(), alreadySet = true))
                    } else {
                        when (val outcome = hdAudio.apply(address, wish)) {
                            is HdAudioOutcome.Applied ->
                                add(ProfileAction.HdAudioSet(outcome.enabled, alreadySet = false))

                            is HdAudioOutcome.NotObserved ->
                                add(ProfileAction.HdAudioNotObserved(outcome.detail))

                            is HdAudioOutcome.Unavailable ->
                                add(ProfileAction.Skipped("HD audio", outcome.reason))
                        }
                    }
                }
            }
        }

        // Last on purpose. It is the only privileged *write* in this list, and
        // renegotiating the codec briefly interrupts the stream - so everything
        // that can be done without disturbing playback is done first.
        profile.codecPreference?.let { preference ->
            when {
                address == null -> add(
                    ProfileAction.Skipped(
                        "codec",
                        "the device address is not known here, so the codec cannot be set \u2014 " +
                            "connect the device and try again",
                    ),
                )

                !codec.isAvailable() -> add(
                    ProfileAction.Skipped(
                        "codec",
                        "the privileged helper is not running, so the codec can be neither " +
                            "set nor checked",
                    ),
                )

                else -> when (val outcome = codec.apply(address, preference)) {
                    is CodecApplyOutcome.Applied -> add(ProfileAction.CodecSet(outcome.observed))

                    is CodecApplyOutcome.NotObserved ->
                        add(ProfileAction.CodecNotObserved(outcome.observed, outcome.detail))

                    is CodecApplyOutcome.Unavailable ->
                        add(ProfileAction.Skipped("codec", outcome.reason))
                }
            }
        }
    }
}
