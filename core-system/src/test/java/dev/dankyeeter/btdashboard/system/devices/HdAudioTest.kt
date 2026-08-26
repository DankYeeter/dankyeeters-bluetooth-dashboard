package dev.dankyeeter.btdashboard.system.devices

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ---- fakes ------------------------------------------------------------------

private class HdProfiles(private val profile: DeviceProfile) : DeviceProfileSource {
    override suspend fun profileFor(deviceKey: String): DeviceProfile? =
        profile.takeIf { it.deviceKey == deviceKey }
}

private class InertVolume : MediaVolumeController {
    override fun currentPercent(): Int? = null
    override fun setPercent(percent: Int): Boolean = true
}

private class InertCompensation : CompensationApplier {
    override suspend fun apply(compensationProfileId: String): Boolean = true
}

private class InertAbsoluteVolume : AbsoluteVolumeController {
    override fun isWritable(): Boolean = true
    override fun isEnabled(): Boolean = true
    override fun setEnabled(enabled: Boolean): Boolean = true
    override fun clear(): Boolean = true
}

private class InertSettings : SecureSettingsController {
    override fun isWritable(): Boolean = true
    override fun read(key: String): String? = null
    override fun write(key: String, value: String): Boolean = true
    override fun clear(key: String): Boolean = true
}

/**
 * Stands in for the privileged helper's HD-audio calls.
 *
 * [state] is what a read reports and [outcome] is what a write reports, kept
 * apart on purpose: the applier reads first to avoid a needless renegotiation,
 * and a fake that derived one from the other could not exercise the case where
 * they disagree — which is the case the read-back exists for.
 */
private class FakeHdAudio(
    private val available: Boolean = true,
    private val state: HdAudioState = HdAudioState.Known(supported = true, enabled = null),
    private val outcome: HdAudioOutcome = HdAudioOutcome.Applied(enabled = true),
) : HdAudioController {

    val reads = mutableListOf<String>()
    val writes = mutableListOf<Pair<String, HdAudioPreference>>()

    override fun isAvailable(): Boolean = available

    override suspend fun read(address: String): HdAudioState {
        reads += address
        return state
    }

    override suspend fun apply(address: String, preference: HdAudioPreference): HdAudioOutcome {
        writes += address to preference
        return outcome
    }
}

/** Records codec requests, so the ordering test can see which ran first. */
private class RecordingCodec : CodecPreferenceController {
    val requests = mutableListOf<CodecPreference>()

    override fun isAvailable(): Boolean = true

    override suspend fun apply(address: String, preference: CodecPreference): CodecApplyOutcome {
        requests += preference
        return CodecApplyOutcome.Applied("LDAC")
    }
}

// ---- tests ------------------------------------------------------------------

class HdAudioPreferenceTest {

    @Test
    fun `the three preferences map onto the three states the stack has`() {
        // Not two-plus-a-synonym: AOSP's OPTIONAL_CODECS_PREF_UNKNOWN is a real
        // value meaning "nobody has chosen", and it is how a choice made here is
        // withdrawn rather than merely replaced.
        assertEquals(true, HdAudioPreference.ENABLE.asEnabled())
        assertEquals(false, HdAudioPreference.DISABLE.asEnabled())
        assertNull(HdAudioPreference.SYSTEM_DEFAULT.asEnabled())
    }

    @Test
    fun `a profile with no wish survives sanitising untouched`() {
        val profile = DeviceProfile("k", "Bathys").sanitized()
        assertNull("null means leave alone, and must not become a default", profile.hdAudio)
    }

    @Test
    fun `a stored wish survives sanitising`() {
        // Unlike developerOptions there is nothing to filter - the type is a
        // closed enum, so restored data cannot carry a value the registry has
        // since dropped. This pins that it is not filtered anyway.
        val profile = DeviceProfile("k", "Bathys", hdAudio = HdAudioPreference.DISABLE).sanitized()
        assertEquals(HdAudioPreference.DISABLE, profile.hdAudio)
    }
}

class HdAudioApplierTest {

    private val address = "AC:DE:48:00:11:22"
    private val key = DeviceKey.fromAddress(address)!!

    private fun applier(
        profile: DeviceProfile,
        hdAudio: HdAudioController = FakeHdAudio(),
        codec: CodecPreferenceController = UnavailableCodecPreferenceController,
    ) = DeviceProfileApplier(
        profiles = HdProfiles(profile),
        volume = InertVolume(),
        compensation = InertCompensation(),
        absoluteVolume = InertAbsoluteVolume(),
        secureSettings = InertSettings(),
        codec = codec,
        hdAudio = hdAudio,
    )

    private fun profile(wish: HdAudioPreference?) =
        DeviceProfile(deviceKey = key, name = "Bathys", hdAudio = wish)

    @Test
    fun `a wish is written and reported as read back`() = runTest {
        val hd = FakeHdAudio(
            state = HdAudioState.Known(supported = true, enabled = false),
            outcome = HdAudioOutcome.Applied(enabled = true),
        )
        val profile = profile(HdAudioPreference.ENABLE)

        val actions = applier(profile, hd).applyNow(profile, address)

        assertEquals(listOf(address to HdAudioPreference.ENABLE), hd.writes)
        val set = actions.filterIsInstance<ProfileAction.HdAudioSet>().single()
        assertEquals(true, set.enabled)
        assertFalse(set.alreadySet)
    }

    @Test
    fun `a value already in place is not written again`() = runTest {
        // Not an optimisation for its own sake: changing this drops the A2DP
        // link, so re-asserting a value that is already right would be an
        // audible hiccup on every single connect.
        val hd = FakeHdAudio(state = HdAudioState.Known(supported = true, enabled = true))
        val profile = profile(HdAudioPreference.ENABLE)

        val actions = applier(profile, hd).applyNow(profile, address)

        assertTrue("no write was needed", hd.writes.isEmpty())
        val set = actions.filterIsInstance<ProfileAction.HdAudioSet>().single()
        assertTrue(set.alreadySet)
    }

    @Test
    fun `system default is not the same as on`() = runTest {
        // The stack reports "nobody has chosen" as unknown, so a profile asking
        // for the system default is already satisfied by an unknown read - and
        // must not be satisfied by a read of "on".
        val unset = FakeHdAudio(state = HdAudioState.Known(supported = true, enabled = null))
        val on = FakeHdAudio(state = HdAudioState.Known(supported = true, enabled = true))
        val profile = profile(HdAudioPreference.SYSTEM_DEFAULT)

        applier(profile, unset).applyNow(profile, address)
        applier(profile, on).applyNow(profile, address)

        assertTrue("an unset value already is the system default", unset.writes.isEmpty())
        assertEquals(
            "an explicit 'on' has to be cleared",
            listOf(address to HdAudioPreference.SYSTEM_DEFAULT),
            on.writes,
        )
    }

    @Test
    fun `a write that is not observed is never reported as applied`() = runTest {
        val hd = FakeHdAudio(
            state = HdAudioState.Known(supported = true, enabled = false),
            outcome = HdAudioOutcome.NotObserved("HD audio still reads off"),
        )
        val profile = profile(HdAudioPreference.ENABLE)

        val actions = applier(profile, hd).applyNow(profile, address)

        assertTrue(actions.filterIsInstance<ProfileAction.HdAudioSet>().isEmpty())
        val missed = actions.filterIsInstance<ProfileAction.HdAudioNotObserved>().single()
        assertTrue(missed.detail.contains("still reads off"))
    }

    @Test
    fun `an unreadable state does not stop the write`() = runTest {
        // "Cannot check" must not become "leave it alone". The user asked for a
        // value; failing to read the old one is no reason to withhold the write.
        val hd = FakeHdAudio(state = HdAudioState.Unreadable("the A2DP proxy did not bind"))
        val profile = profile(HdAudioPreference.DISABLE)

        applier(profile, hd).applyNow(profile, address)

        assertEquals(listOf(address to HdAudioPreference.DISABLE), hd.writes)
    }

    @Test
    fun `without a helper nothing is attempted and the reason is named`() = runTest {
        val hd = FakeHdAudio(available = false)
        val profile = profile(HdAudioPreference.ENABLE)

        val actions = applier(profile, hd).applyNow(profile, address)

        assertTrue("must not even try", hd.writes.isEmpty())
        assertTrue(hd.reads.isEmpty())
        val skipped = actions.filterIsInstance<ProfileAction.Skipped>().single()
        assertTrue(skipped.reason.contains("privileged helper"))
    }

    @Test
    fun `without an address the step is skipped honestly rather than silently`() = runTest {
        val hd = FakeHdAudio()
        val profile = profile(HdAudioPreference.ENABLE)

        val actions = applier(profile, hd).applyNow(profile, address = null)

        assertTrue(hd.writes.isEmpty())
        val skipped = actions.filterIsInstance<ProfileAction.Skipped>().single()
        assertTrue(skipped.reason.contains("address"))
    }

    @Test
    fun `no wish means the controller is never touched`() = runTest {
        val hd = FakeHdAudio()
        val profile = profile(null)

        val actions = applier(profile, hd).applyNow(profile, address)

        assertTrue(hd.reads.isEmpty())
        assertTrue(hd.writes.isEmpty())
        assertTrue(actions.none { it is ProfileAction.HdAudioSet })
    }

    @Test
    fun `HD audio is applied before the codec`() = runTest {
        // The ordering is load-bearing, not cosmetic. HD audio is the gate in
        // front of codec negotiation: asking for LDAC first, while HD audio is
        // still off, comes back as SBC and gets reported as "did not stick" -
        // true, and completely misleading about the cause.
        val hd = FakeHdAudio(
            state = HdAudioState.Known(supported = true, enabled = false),
            outcome = HdAudioOutcome.Applied(enabled = true),
        )
        val codec = RecordingCodec()
        val profile = DeviceProfile(
            deviceKey = key,
            name = "Bathys",
            codecPreference = CodecPreference("LDAC"),
            hdAudio = HdAudioPreference.ENABLE,
        )

        val actions = applier(profile, hd, codec).applyNow(profile, address)

        assertEquals(listOf(CodecPreference("LDAC")), codec.requests)
        val hdIndex = actions.indexOfFirst { it is ProfileAction.HdAudioSet }
        val codecIndex = actions.indexOfFirst { it is ProfileAction.CodecSet }
        assertTrue("both steps ran", hdIndex >= 0 && codecIndex >= 0)
        assertTrue("HD audio must come first", hdIndex < codecIndex)
    }

    @Test
    fun `the default applier has no HD-audio controller and says so`() = runTest {
        // Every caller that predates HD audio keeps compiling, and gets an
        // honest "not attempted" rather than a silent skip.
        val profile = profile(HdAudioPreference.ENABLE)
        val applier = DeviceProfileApplier(
            profiles = HdProfiles(profile),
            volume = InertVolume(),
            compensation = InertCompensation(),
            absoluteVolume = InertAbsoluteVolume(),
            secureSettings = InertSettings(),
        )

        val actions = applier.applyNow(profile, address)

        val skipped = actions.filterIsInstance<ProfileAction.Skipped>().single()
        assertEquals("HD audio", skipped.what)
    }
}

class BluetoothSystemControlsTest {

    @Test
    fun `the system panel offers exactly the keys the profiles do`() {
        // Two lists that could drift apart would eventually offer an option in
        // one place and not the other, with no reason a reader could find.
        assertEquals(
            BluetoothDeveloperOptions.all.map { it.key },
            BluetoothSystemControls.writableGlobals.map { it.key },
        )
    }

    @Test
    fun `every read-only setting names a property and a reason`() {
        // The whole point of showing them is the explanation. A row that said
        // "cannot be changed" without saying why would be worse than absent.
        BluetoothReadOnlySettings.all.forEach { setting ->
            assertTrue(
                "${setting.label} must name the property it stands for",
                setting.liveValueKey.startsWith("persist."),
            )
            assertTrue("${setting.label} needs a reason", setting.whyReadOnly.isNotBlank())
            assertTrue("${setting.label} needs an explanation", setting.explanation.isNotBlank())
        }
    }

    @Test
    fun `no read-only setting is also offered as writable`() {
        // The trap this guards: Settings.Global will happily accept a key named
        // after a system property, write it, and read it back - a green
        // checkmark on an option that was never connected to anything.
        val writable = BluetoothSystemControls.writableGlobals.map { it.key }.toSet()
        BluetoothReadOnlySettings.all.forEach {
            assertFalse(it.liveValueKey in writable)
        }
    }

    @Test
    fun `the stub controllers never claim something happened`() = runTest {
        assertFalse(UnavailableHdAudioController.isAvailable())
        assertTrue(UnavailableHdAudioController.read("AC:DE:48:00:11:22") is HdAudioState.Unreadable)
        assertTrue(
            UnavailableHdAudioController.apply("AC:DE:48:00:11:22", HdAudioPreference.ENABLE)
                is HdAudioOutcome.Unavailable,
        )

        assertFalse(UnavailableBluetoothRestartController.isAvailable())
        assertTrue(
            UnavailableBluetoothRestartController.restart() is BluetoothRestartOutcome.Unavailable,
        )

        assertNull(NoSystemPropertyReader.read("persist.bluetooth.a2dp_offload.disabled"))
    }
}
