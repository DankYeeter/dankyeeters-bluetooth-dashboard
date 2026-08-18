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

class DeviceProfileApplierTest {

    private val address = "AC:DE:48:00:11:22"
    private val key = DeviceKey.fromAddress(address)!!

    private fun applier(
        profiles: DeviceProfileSource,
        volume: MediaVolumeController = FakeVolume(),
        compensation: CompensationApplier = FakeCompensation(setOf("p1")),
        absolute: AbsoluteVolumeController = FakeAbsoluteVolume(),
    ) = DeviceProfileApplier(profiles, volume, compensation, absolute)

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
