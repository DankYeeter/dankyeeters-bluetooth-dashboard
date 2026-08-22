package dev.dankyeeter.btdashboard.system.devices

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ---- fakes ------------------------------------------------------------------

private class FakeProfiles(vararg profiles: DeviceProfile) : DeviceProfileSource {
    private val byKey = profiles.associateBy { it.deviceKey }
    override suspend fun profileFor(deviceKey: String): DeviceProfile? = byKey[deviceKey]
}

private class FakeVolume(
    private var percent: Int? = 50,
    private val accept: Boolean = true,
) : MediaVolumeController {
    var lastWrite: Int? = null
        private set

    override fun currentPercent(): Int? = percent

    override fun setPercent(percent: Int): Boolean {
        if (!accept) return false
        lastWrite = percent
        this.percent = percent
        return true
    }
}

private class FakeCompensation(private val known: Set<String>) : CompensationApplier {
    var applied: String? = null
        private set

    override suspend fun apply(compensationProfileId: String): Boolean {
        if (compensationProfileId !in known) return false
        applied = compensationProfileId
        return true
    }
}

private class FakeAbsoluteVolume(
    private val writable: Boolean = true,
    private var enabled: Boolean? = true,
    private val acceptWrites: Boolean = true,
) : AbsoluteVolumeController {
    var writes = 0
        private set

    override fun isWritable(): Boolean = writable
    override fun isEnabled(): Boolean? = enabled

    override fun setEnabled(enabled: Boolean): Boolean {
        writes++
        if (!acceptWrites) return false
        this.enabled = enabled
        return true
    }

    var clears = 0
        private set

    override fun clear(): Boolean {
        clears++
        if (!acceptWrites) return false
        // An absent key reads as "enabled" — that is what Android's default is.
        enabled = true
        return true
    }
}

// ---- tests ------------------------------------------------------------------

class DeviceKeyTest {

    @Test
    fun `normalises separator spellings to one form`() {
        val colon = DeviceKey.fromAddress("AC:DE:48:00:11:22")
        assertEquals(colon, DeviceKey.fromAddress("ac-de-48-00-11-22"))
        assertEquals(colon, DeviceKey.fromAddress("acde48001122"))
    }

    @Test
    fun `different devices get different keys`() {
        assertTrue(DeviceKey.fromAddress("AC:DE:48:00:11:22") != DeviceKey.fromAddress("AC:DE:48:00:11:23"))
    }

    @Test
    fun `the raw address never appears in the key`() {
        val key = DeviceKey.fromAddress("AC:DE:48:00:11:22")!!
        assertFalse(key.contains("acde48", ignoreCase = true))
        assertEquals(32, key.length)
    }

    @Test
    fun `garbage input yields null instead of a bogus key`() {
        assertNull(DeviceKey.fromAddress(null))
        assertNull(DeviceKey.fromAddress(""))
        assertNull(DeviceKey.fromAddress("not-a-mac"))
        assertNull(DeviceKey.fromAddress("AC:DE:48:00:11"))
        assertNull(DeviceKey.fromAddress("ZZ:DE:48:00:11:22"))
    }
}

/**
 * Records writes and can refuse them.
 *
 * The refusal path is the point: no Android API answers "does this build
 * support this option", so the applier writes and reads back, and this fake is
 * how that branch gets exercised without a phone that ignores a key.
 */
class FakeSecureSettings(
    private val writable: Boolean = true,
    /** Keys whose writes never land, standing in for an unsupported option. */
    private val rejects: Set<String> = emptySet(),
    initial: Map<String, String> = emptyMap(),
) : SecureSettingsController {

    val values = initial.toMutableMap()
    val writes = mutableListOf<Pair<String, String>>()

    override fun isWritable(): Boolean = writable

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String): Boolean {
        writes += key to value
        if (key in rejects) return false
        values[key] = value
        return true
    }

    val clears = mutableListOf<String>()

    override fun clear(key: String): Boolean {
        clears += key
        if (key in rejects) return false
        values.remove(key)
        return true
    }
}

class DeviceProfileApplierTest {

    private val address = "AC:DE:48:00:11:22"
    private val key = DeviceKey.fromAddress(address)!!

    private fun applier(
        profiles: DeviceProfileSource,
        volume: MediaVolumeController = FakeVolume(),
        compensation: CompensationApplier = FakeCompensation(setOf("p1")),
        absolute: AbsoluteVolumeController = FakeAbsoluteVolume(),
        settings: SecureSettingsController = FakeSecureSettings(),
    ) = DeviceProfileApplier(profiles, volume, compensation, absolute, settings)

    private val avrcp = BluetoothDeveloperOptions.avrcpVersion.key

    private fun withOption(value: String) = DeviceProfile(
        deviceKey = key,
        name = "Bathys",
        developerOptions = mapOf(avrcp to value),
    )

    @Test
    fun `a developer option is written and reported`() = runTest {
        val settings = FakeSecureSettings()
        val profile = withOption("avrcp15")
        val actions = applier(FakeProfiles(profile), settings = settings)
            .applyNow(profile)

        assertEquals(listOf(avrcp to "avrcp15"), settings.writes)
        val set = actions.filterIsInstance<ProfileAction.DeveloperOptionSet>().single()
        assertEquals("avrcp15", set.value)
        assertFalse(set.alreadySet)
        assertTrue("the stack reads these at startup", set.needsBluetoothRestart)
    }

    /**
     * "Use System Default" is a delete, not a write: it must remove the key so
     * the stack falls back to its own default — including values some earlier
     * writer left behind.
     */
    /**
     * "Use System Default" for absolute volume must delete the key, not write
     * the value that happens to look the same.
     *
     * Writing "enabled" pins a value this app chose; an absent key is the state
     * the phone shipped in, and only deleting undoes what another writer left.
     * Without this, a stored wish could be replaced but never withdrawn.
     */
    @Test
    fun `absolute volume system default deletes the key`() = runTest {
        val absolute = FakeAbsoluteVolume(enabled = false)
        val profile = DeviceProfile(key, "Encore", absoluteVolumeSystemDefault = true)
        val actions = applier(FakeProfiles(profile), absolute = absolute).applyNow(profile)

        assertEquals(1, absolute.clears)
        assertEquals("no value may be written", 0, absolute.writes)
        assertTrue(actions.contains(ProfileAction.AbsoluteVolumeReset))
    }

    @Test
    fun `system default outranks a stale on-off wish beside it`() = runTest {
        // The editor never produces both, but restored data might, and a reset
        // is a decision about the setting as a whole.
        val absolute = FakeAbsoluteVolume(enabled = true)
        val profile = DeviceProfile(
            key,
            "Encore",
            absoluteVolumeEnabled = false,
            absoluteVolumeSystemDefault = true,
        )
        val actions = applier(FakeProfiles(profile), absolute = absolute).applyNow(profile)

        assertEquals(1, absolute.clears)
        assertEquals(0, absolute.writes)
        assertTrue(actions.contains(ProfileAction.AbsoluteVolumeReset))
    }

    @Test
    fun `use system default clears the key`() = runTest {
        val settings = FakeSecureSettings(initial = mapOf(avrcp to "avrcp15"))
        val profile = withOption(BluetoothDeveloperOptions.USE_SYSTEM_DEFAULT)
        val actions = applier(FakeProfiles(profile), settings = settings)
            .applyNow(profile)

        assertEquals(listOf(avrcp), settings.clears)
        assertTrue("no value write may happen", settings.writes.isEmpty())
        assertEquals(null, settings.values[avrcp])
        val set = actions.filterIsInstance<ProfileAction.DeveloperOptionSet>().single()
        assertFalse(set.alreadySet)
    }

    @Test
    fun `an already unset key is reported without touching it`() = runTest {
        val settings = FakeSecureSettings()
        val profile = withOption(BluetoothDeveloperOptions.USE_SYSTEM_DEFAULT)
        val actions = applier(FakeProfiles(profile), settings = settings)
            .applyNow(profile)

        assertTrue(settings.clears.isEmpty())
        val set = actions.filterIsInstance<ProfileAction.DeveloperOptionSet>().single()
        assertTrue(set.alreadySet)
    }

    @Test
    fun `a value that is already in place is not written again`() = runTest {
        val settings = FakeSecureSettings(initial = mapOf(avrcp to "avrcp16"))
        val profile = withOption("avrcp16")
        val actions = applier(FakeProfiles(profile), settings = settings)
            .applyNow(profile)

        assertTrue("no write was needed", settings.writes.isEmpty())
        val set = actions.filterIsInstance<ProfileAction.DeveloperOptionSet>().single()
        assertTrue(set.alreadySet)
        // Nothing changed, so nothing needs a Bluetooth restart to take effect.
        assertFalse(set.needsBluetoothRestart)
    }

    @Test
    fun `a value that does not stick is reported as skipped, never as applied`() = runTest {
        // The whole reason the applier reads back: an unsupported key accepts
        // the write and keeps its old value. Claiming success there would be a
        // green checkmark on a phone that ignores the option.
        val settings = FakeSecureSettings(rejects = setOf(avrcp))
        val profile = withOption("avrcp13")
        val actions = applier(FakeProfiles(profile), settings = settings)
            .applyNow(profile)

        assertTrue(actions.filterIsInstance<ProfileAction.DeveloperOptionSet>().isEmpty())
        val skipped = actions.filterIsInstance<ProfileAction.Skipped>().single()
        assertTrue(skipped.reason.contains("did not stick"))
    }

    @Test
    fun `without the permission nothing is attempted`() = runTest {
        val settings = FakeSecureSettings(writable = false)
        val profile = withOption("avrcp15")
        val actions = applier(FakeProfiles(profile), settings = settings)
            .applyNow(profile)

        assertTrue("must not even try", settings.writes.isEmpty())
        val skipped = actions.filterIsInstance<ProfileAction.Skipped>().single()
        assertTrue(skipped.reason.contains("WRITE_SECURE_SETTINGS"))
    }

    @Test
    fun `an option this build no longer offers is dropped on load`() {
        // Restored data outlives the registry. Writing an unrecognised value
        // into a system setting is not a risk worth carrying for a stale key.
        val profile = DeviceProfile(
            deviceKey = key,
            name = "Bathys",
            developerOptions = mapOf(
                avrcp to "avrcp15",
                "bluetooth_something_removed" to "1",
                avrcp + "_typo" to "avrcp15",
            ),
        ).sanitized()

        assertEquals(mapOf(avrcp to "avrcp15"), profile.developerOptions)
    }

    @Test
    fun `a value outside the option's own list is dropped too`() {
        val profile = DeviceProfile(
            deviceKey = key,
            name = "Bathys",
            developerOptions = mapOf(avrcp to "avrcp99"),
        ).sanitized()

        assertTrue(profile.developerOptions.isEmpty())
    }

    @Test
    fun `matches a stored device by hashed address and applies everything`() = runTest {
        val volume = FakeVolume()
        val compensation = FakeCompensation(setOf("p1"))
        val absolute = FakeAbsoluteVolume(enabled = true)
        val profile = DeviceProfile(
            deviceKey = key,
            name = "Encore",
            compensationProfileId = "p1",
            mediaVolumePercent = 70,
            absoluteVolumeEnabled = false,
        )

        val result = applier(FakeProfiles(profile), volume, compensation, absolute)
            .onDeviceConnected(address)

        val applied = result as ApplyResult.Applied
        assertEquals("Encore", applied.profile.name)
        assertEquals(70, volume.lastWrite)
        assertEquals("p1", compensation.applied)
        assertEquals(false, absolute.isEnabled())
        assertEquals(
            listOf(
                ProfileAction.CompensationApplied("p1"),
                ProfileAction.VolumeSet(70),
                ProfileAction.AbsoluteVolumeSet(false),
            ),
            applied.actions,
        )
    }

    @Test
    fun `an unknown device is reported, not guessed at`() = runTest {
        val result = applier(FakeProfiles()).onDeviceConnected(address)
        assertEquals(ApplyResult.NoProfile(key), result)
    }

    @Test
    fun `an unusable address does not reach the store`() = runTest {
        assertEquals(ApplyResult.UnknownAddress, applier(FakeProfiles()).onDeviceConnected("nonsense"))
        assertEquals(ApplyResult.UnknownAddress, applier(FakeProfiles()).onDeviceConnected(null))
    }

    @Test
    fun `auto-apply off means nothing is touched`() = runTest {
        val volume = FakeVolume()
        val profile = DeviceProfile(key, "Encore", mediaVolumePercent = 20, autoApply = false)

        val result = applier(FakeProfiles(profile), volume).onDeviceConnected(address)

        assertTrue(result is ApplyResult.AutoApplyDisabled)
        assertNull(volume.lastWrite)
    }

    @Test
    fun `null fields mean leave it alone`() = runTest {
        val volume = FakeVolume()
        val absolute = FakeAbsoluteVolume()
        val profile = DeviceProfile(key, "Bathys", compensationProfileId = "p1")

        val applied = applier(FakeProfiles(profile), volume, absolute = absolute)
            .onDeviceConnected(address) as ApplyResult.Applied

        assertNull(volume.lastWrite)
        assertEquals(0, absolute.writes)
        assertEquals(listOf(ProfileAction.CompensationApplied("p1")), applied.actions)
    }

    @Test
    fun `a failed step does not stop the remaining steps`() = runTest {
        val volume = FakeVolume(accept = false)
        val compensation = FakeCompensation(emptySet())
        val absolute = FakeAbsoluteVolume(enabled = true)
        val profile = DeviceProfile(
            deviceKey = key,
            name = "Encore",
            compensationProfileId = "gone",
            mediaVolumePercent = 70,
            absoluteVolumeEnabled = false,
        )

        val applied = applier(FakeProfiles(profile), volume, compensation, absolute)
            .onDeviceConnected(address) as ApplyResult.Applied

        assertEquals(3, applied.actions.size)
        assertTrue(applied.actions[0] is ProfileAction.Skipped)
        assertTrue(applied.actions[1] is ProfileAction.Skipped)
        assertEquals(ProfileAction.AbsoluteVolumeSet(false), applied.actions[2])
    }

    @Test
    fun `absolute volume without the permission is skipped honestly`() = runTest {
        val absolute = FakeAbsoluteVolume(writable = false)
        val profile = DeviceProfile(key, "Encore", absoluteVolumeEnabled = false)

        val applied = applier(FakeProfiles(profile), absolute = absolute)
            .onDeviceConnected(address) as ApplyResult.Applied

        assertEquals(0, absolute.writes)
        val skipped = applied.actions.single() as ProfileAction.Skipped
        assertTrue(skipped.reason.contains("WRITE_SECURE_SETTINGS"))
    }

    @Test
    fun `an already-correct absolute-volume setting is not rewritten`() = runTest {
        val absolute = FakeAbsoluteVolume(enabled = false)
        val profile = DeviceProfile(key, "Encore", absoluteVolumeEnabled = false)

        val applied = applier(FakeProfiles(profile), absolute = absolute)
            .onDeviceConnected(address) as ApplyResult.Applied

        assertEquals(0, absolute.writes)
        assertEquals(listOf(ProfileAction.AbsoluteVolumeSet(false)), applied.actions)
    }

    @Test
    fun `out-of-range volumes are clamped rather than rejected`() = runTest {
        val volume = FakeVolume()
        val profile = DeviceProfile(key, "Encore").copy(mediaVolumePercent = 400)

        applier(FakeProfiles(profile), volume).onDeviceConnected(address)

        assertEquals(100, volume.lastWrite)
    }
}
