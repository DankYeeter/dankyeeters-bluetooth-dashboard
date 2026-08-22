package dev.dankyeeter.btdashboard.system.devices

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ---- fakes ------------------------------------------------------------------

private class OneProfile(private val profile: DeviceProfile) : DeviceProfileSource {
    override suspend fun profileFor(deviceKey: String): DeviceProfile? =
        profile.takeIf { it.deviceKey == deviceKey }
}

private class NoVolume : MediaVolumeController {
    override fun currentPercent(): Int? = null
    override fun setPercent(percent: Int): Boolean = true
}

private class NoCompensation : CompensationApplier {
    override suspend fun apply(compensationProfileId: String): Boolean = true
}

private class NoAbsoluteVolume : AbsoluteVolumeController {
    override fun isWritable(): Boolean = true
    override fun isEnabled(): Boolean = true
    override fun setEnabled(enabled: Boolean): Boolean = true
    override fun clear(): Boolean = true
}

private class NoSettings : SecureSettingsController {
    override fun isWritable(): Boolean = true
    override fun read(key: String): String? = null
    override fun write(key: String, value: String): Boolean = true
    override fun clear(key: String): Boolean = true
}

/**
 * Stands in for the privileged helper.
 *
 * [outcome] is what the helper *observed*, which is the whole reason this is
 * not a boolean: "requested and read back" and "requested, still reads
 * something else" are different answers and the applier must keep them apart.
 */
private class FakeCodec(
    private val available: Boolean = true,
    private val outcome: CodecApplyOutcome = CodecApplyOutcome.Applied("LDAC · 96000 Hz · 24 bit"),
) : CodecPreferenceController {

    val requests = mutableListOf<Pair<String, CodecPreference>>()

    override fun isAvailable(): Boolean = available

    override suspend fun apply(address: String, preference: CodecPreference): CodecApplyOutcome {
        requests += address to preference
        return outcome
    }
}

// ---- tests ------------------------------------------------------------------

class CodecPreferenceRegistryTest {

    @Test
    fun `a preference the registry recognises is valid`() {
        assertTrue(CodecPreference("LDAC").isValid)
        assertTrue(CodecPreference("LDAC", 96_000, 24, 2, 1003L).isValid)
        assertTrue("zero means leave alone", CodecPreference("AAC", 0, 0, 0, 0L).isValid)
    }

    @Test
    fun `a codec this app cannot request is not valid`() {
        // aptX Adaptive is readable but not writable: its id is a vendor value
        // that has moved between Android versions.
        assertFalse(CodecPreference("APTX_ADAPTIVE").isValid)
        assertFalse(CodecPreference("UNKNOWN").isValid)
        assertFalse(CodecPreference("").isValid)
    }

    @Test
    fun `an LDAC quality outside LDAC is not valid`() {
        assertFalse(CodecPreference("SBC", ldacQuality = 1000L).isValid)
        assertTrue(CodecPreference("LDAC", ldacQuality = 1000L).isValid)
    }

    @Test
    fun `sanitizing drops a stored preference the registry no longer recognises`() {
        // Restored data outlives the registry, and unlike a developer option
        // this one is a *write* to the Bluetooth stack: a stale value is not a
        // wrong label, it is a renegotiation nobody asked for.
        val stale = DeviceProfile(
            deviceKey = "k",
            name = "Bathys",
            codecPreference = CodecPreference("APTX_ADAPTIVE"),
        ).sanitized()
        assertNull(stale.codecPreference)

        val kept = DeviceProfile(
            deviceKey = "k",
            name = "Bathys",
            codecPreference = CodecPreference("LDAC", 96_000),
        ).sanitized()
        assertEquals(CodecPreference("LDAC", 96_000), kept.codecPreference)
    }

    @Test
    fun `the unavailable controller says cannot check, never no`() {
        val outcome = kotlinx.coroutines.runBlocking {
            UnavailableCodecPreferenceController.apply("AC:DE:48:00:11:22", CodecPreference("LDAC"))
        }
        assertTrue(outcome is CodecApplyOutcome.Unavailable)
        assertFalse(UnavailableCodecPreferenceController.isAvailable())
    }
}

class CodecApplierTest {

    private val address = "AC:DE:48:00:11:22"
    private val key = DeviceKey.fromAddress(address)!!

    private fun profile(preference: CodecPreference? = CodecPreference("LDAC", 96_000, 24, 2, 1000L)) =
        DeviceProfile(deviceKey = key, name = "Bathys", codecPreference = preference)

    private fun applier(profile: DeviceProfile, codec: CodecPreferenceController) =
        DeviceProfileApplier(
            profiles = OneProfile(profile),
            volume = NoVolume(),
            compensation = NoCompensation(),
            absoluteVolume = NoAbsoluteVolume(),
            secureSettings = NoSettings(),
            codec = codec,
        )

    @Test
    fun `a connect applies the codec and reports what was read back`() = runTest {
        val codec = FakeCodec(outcome = CodecApplyOutcome.Applied("LDAC · 96000 Hz · 24 bit"))
        val profile = profile()

        val applied = applier(profile, codec).onDeviceConnected(address) as ApplyResult.Applied

        assertEquals(listOf(address to profile.codecPreference!!), codec.requests)
        val set = applied.actions.filterIsInstance<ProfileAction.CodecSet>().single()
        assertEquals("LDAC · 96000 Hz · 24 bit", set.observed)
    }

    @Test
    fun `a request that was not observed is its own outcome, not a success`() = runTest {
        val codec = FakeCodec(
            outcome = CodecApplyOutcome.NotObserved("aptX", "after 2500 ms it still reads aptX"),
        )

        val applied = applier(profile(), codec).onDeviceConnected(address) as ApplyResult.Applied

        assertTrue(applied.actions.filterIsInstance<ProfileAction.CodecSet>().isEmpty())
        val notObserved = applied.actions
            .filterIsInstance<ProfileAction.CodecNotObserved>()
            .single()
        assertEquals("aptX", notObserved.observed)
        assertTrue(notObserved.detail.contains("2500 ms"))
    }

    @Test
    fun `without the helper nothing is attempted and the reason says so`() = runTest {
        val codec = FakeCodec(available = false)

        val applied = applier(profile(), codec).onDeviceConnected(address) as ApplyResult.Applied

        assertTrue("must not even try", codec.requests.isEmpty())
        val skipped = applied.actions.filterIsInstance<ProfileAction.Skipped>().single()
        assertTrue(skipped.reason.contains("privileged helper"))
        // "Cannot check" and "not applied" must not read the same.
        assertTrue(skipped.reason.contains("neither"))
    }

    @Test
    fun `a manual apply without an address cannot set a codec and says so`() = runTest {
        // Every other field is a global setting and needs no device; a codec is
        // set on a BluetoothDevice. Silently skipping would look like success.
        val codec = FakeCodec()
        val profile = profile()

        val actions = applier(profile, codec).applyNow(profile)

        assertTrue(codec.requests.isEmpty())
        val skipped = actions.filterIsInstance<ProfileAction.Skipped>().single()
        assertTrue(skipped.reason.contains("address"))
    }

    @Test
    fun `no codec preference means the helper is never called`() = runTest {
        val codec = FakeCodec()
        val profile = profile(preference = null)

        val applied = applier(profile, codec).onDeviceConnected(address) as ApplyResult.Applied

        assertTrue(codec.requests.isEmpty())
        assertTrue(applied.actions.isEmpty())
    }

    @Test
    fun `the codec step runs last, after everything that does not interrupt playback`() = runTest {
        // Renegotiating the codec cuts the stream for a moment. Everything that
        // can be done without doing that is done first.
        val codec = FakeCodec()
        val profile = DeviceProfile(
            deviceKey = key,
            name = "Bathys",
            compensationProfileId = "p1",
            mediaVolumePercent = 70,
            absoluteVolumeEnabled = true,
            codecPreference = CodecPreference("LDAC"),
        )

        val applied = applier(profile, codec).onDeviceConnected(address) as ApplyResult.Applied

        assertTrue(applied.actions.last() is ProfileAction.CodecSet)
        assertEquals(4, applied.actions.size)
    }

    @Test
    fun `a codec the controller refuses is skipped with the controller's own reason`() = runTest {
        val codec = FakeCodec(
            outcome = CodecApplyOutcome.Unavailable("LC3 cannot be requested on this build"),
        )

        val applied = applier(profile(), codec).onDeviceConnected(address) as ApplyResult.Applied

        val skipped = applied.actions.filterIsInstance<ProfileAction.Skipped>().single()
        assertEquals("LC3 cannot be requested on this build", skipped.reason)
    }

    @Test
    fun `the default applier has no codec controller and reports that honestly`() = runTest {
        // The four call sites that predate codec support keep compiling; what
        // they must not do is silently drop a stored codec wish.
        val profile = profile()
        val actions = DeviceProfileApplier(
            profiles = OneProfile(profile),
            volume = NoVolume(),
            compensation = NoCompensation(),
            absoluteVolume = NoAbsoluteVolume(),
            secureSettings = NoSettings(),
        ).applyNow(profile, address)

        val skipped = actions.filterIsInstance<ProfileAction.Skipped>().single()
        assertTrue(skipped.reason.contains("privileged helper"))
    }
}
